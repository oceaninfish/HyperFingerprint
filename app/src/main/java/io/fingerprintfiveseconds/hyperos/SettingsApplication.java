package io.fingerprintfiveseconds.hyperos;

import android.app.Application;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.util.AtomicFile;
import android.util.Log;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;

public final class SettingsApplication extends Application
        implements XposedServiceHelper.OnServiceListener {
    enum SyncStatus { WAITING, SAVING, SYNCED, FAILED }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService writer = Executors.newSingleThreadExecutor();
    private final ExecutorService restartWorker = Executors.newSingleThreadExecutor();
    private final Set<Runnable> listeners = new LinkedHashSet<>();

    private SharedPreferences local;
    private File iconDirectory;
    private XposedService lastIconService;
    private String uploadedIcon = "";
    private XposedService service;
    private IconSettings current;
    private long revision;
    private SyncStatus status = SyncStatus.WAITING;
    private boolean restartingScopes;

    @Override
    public void onCreate() {
        super.onCreate();
        var storage = createDeviceProtectedStorageContext();
        local = storage.getSharedPreferences(DurationSetting.GROUP, MODE_PRIVATE);
        iconDirectory = storage.getDir("fingerprint_icons", MODE_PRIVATE);
        current = IconSettings.read(local);
        XposedServiceHelper.registerListener(this);
    }

    @Override
    public void onServiceBind(XposedService bound) {
        main.post(() -> {
            service = bound;
            sync();
        });
    }

    @Override
    public void onServiceDied(XposedService dead) {
        main.post(() -> {
            if (service == dead) {
                service = null;
                sync();
            }
        });
    }

    IconSettings current() {
        return current;
    }

    SyncStatus status() {
        return status;
    }

    boolean isRestartingScopes() {
        return restartingScopes;
    }

    void restartScopes() {
        if (restartingScopes) {
            return;
        }
        restartingScopes = true;
        if (status == SyncStatus.FAILED && service != null) {
            sync();
        } else {
            notifyListeners();
        }
        // Let any settings already queued for LSPosed finish writing before restarting SystemUI.
        writer.execute(() -> restartWorker.execute(() -> {
            ScopeRestart.Result result = ScopeRestart.restart();
            main.post(() -> {
                restartingScopes = false;
                notifyListeners();
                int text = switch (result) {
                    case RESTARTED -> R.string.scopes_restarted;
                    case ROOT_REQUIRED -> R.string.restart_root_required;
                    case FAILED -> R.string.restart_failed;
                    case TIMED_OUT -> R.string.restart_timed_out;
                };
                Toast.makeText(this, text, Toast.LENGTH_LONG).show();
            });
        }));
    }

    void addListener(Runnable listener) {
        listeners.add(listener);
        if (status == SyncStatus.FAILED) {
            sync();
        } else {
            listener.run();
        }
    }

    void removeListener(Runnable listener) {
        listeners.remove(listener);
    }

    void saveDuration(int seconds) {
        save(new IconSettings(DurationSetting.clamp(seconds), current.pickup(), current.wake(),
                current.tap(), current.touch(), current.iconFile(), current.opacity(), current.sizeTenths()));
    }

    void saveTriggers(boolean pickup, boolean wake, boolean tap, boolean touch) {
        save(new IconSettings(current.duration(), pickup, wake, tap, touch, current.iconFile(),
                current.opacity(), current.sizeTenths()));
    }

    void saveOpacity(int opacity) {
        save(new IconSettings(current.duration(), current.pickup(), current.wake(), current.tap(),
                current.touch(), current.iconFile(), opacity, current.sizeTenths()));
    }

    void saveSize(int sizeTenths) {
        save(new IconSettings(current.duration(), current.pickup(), current.wake(), current.tap(),
                current.touch(), current.iconFile(), current.opacity(), sizeTenths));
    }

    void restoreSystemIcon() {
        selectIcon("");
    }

    Bitmap iconPreview(String name) {
        return IconSettings.isIconFile(name) ? BitmapFactory.decodeFile(iconPath(name).getPath()) : null;
    }

    void saveCroppedIcon(Bitmap image, Consumer<Boolean> complete) {
        String name = "icon_" + UUID.randomUUID().toString().replace("-", "") + ".png";
        writer.execute(() -> {
            boolean saved = saveIconFile(name, image);
            main.post(() -> {
                if (saved) {
                    selectIcon(name);
                }
                complete.accept(saved);
            });
        });
    }

    private File iconPath(String name) {
        return new File(iconDirectory, name);
    }

    private boolean saveIconFile(String name, Bitmap image) {
        AtomicFile file = new AtomicFile(iconPath(name));
        FileOutputStream stream = null;
        try {
            stream = file.startWrite();
            if (!image.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
                throw new IOException("PNG encoding failed");
            }
            file.finishWrite(stream);
            return true;
        } catch (IOException | RuntimeException error) {
            file.failWrite(stream);
            Log.w(FingerprintModule.TAG, "Could not save the cropped icon", error);
            return false;
        }
    }

    private void selectIcon(String name) {
        String previous = current.iconFile();
        if (!previous.equals(name)) {
            save(new IconSettings(current.duration(), current.pickup(), current.wake(), current.tap(),
                    current.touch(), name, current.opacity(), current.sizeTenths()));
            if (!previous.isEmpty()) {
                writer.execute(() -> {
                    if (local.edit().commit()) {
                        new AtomicFile(iconPath(previous)).delete();
                    }
                });
            }
        }
    }

    private void save(IconSettings next) {
        if (!next.equals(current)) {
            current = next;
            next.writeTo(local).apply();
            sync();
        }
    }

    private void sync() {
        long request = ++revision;
        XposedService target = service;
        IconSettings snapshot = current;
        if (target == null) {
            publish(SyncStatus.WAITING);
            return;
        }
        publish(SyncStatus.SAVING);
        writer.execute(() -> {
            boolean saved = writeSettings(target, snapshot);
            main.post(() -> {
                if (revision == request) {
                    publish(saved ? SyncStatus.SYNCED : SyncStatus.FAILED);
                }
            });
        });
    }

    private boolean writeSettings(XposedService target, IconSettings snapshot) {
        try {
            SharedPreferences remote = target.getRemotePreferences(DurationSetting.GROUP);
            String previous = IconSettings.readIconFile(remote);
            String name = snapshot.iconFile();
            if (!name.isEmpty() && (target != lastIconService || !name.equals(uploadedIcon))) {
                try (var output = new ParcelFileDescriptor.AutoCloseOutputStream(target.openRemoteFile(name))) {
                    output.getChannel().truncate(0);
                    Files.copy(iconPath(name).toPath(), output);
                }
                lastIconService = target;
                uploadedIcon = name;
            }
            if (!snapshot.writeTo(remote).commit()) {
                return false;
            }
            if (!previous.isEmpty() && !previous.equals(name)) {
                try {
                    target.deleteRemoteFile(previous);
                } catch (RuntimeException error) {
                    Log.w(FingerprintModule.TAG, "Could not remove the previous shared icon", error);
                }
            }
            return true;
        } catch (IOException | RuntimeException error) {
            Log.w(FingerprintModule.TAG, "Could not sync the icon settings", error);
            return false;
        }
    }

    private void publish(SyncStatus newStatus) {
        status = newStatus;
        notifyListeners();
    }

    private void notifyListeners() {
        for (Runnable listener : listeners) {
            listener.run();
        }
    }
}
