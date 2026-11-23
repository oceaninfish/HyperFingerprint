package io.fingerprintfiveseconds.hyperos;

import android.app.Application;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;

public final class SettingsApplication extends Application
        implements XposedServiceHelper.OnServiceListener {
    enum SyncStatus { WAITING, SAVING, SYNCED, FAILED }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService writer = Executors.newSingleThreadExecutor();
    private final Set<Runnable> listeners = new LinkedHashSet<>();

    private SharedPreferences local;
    private XposedService service;
    private int duration;
    private long revision;
    private SyncStatus status = SyncStatus.WAITING;

    @Override
    public void onCreate() {
        super.onCreate();
        local = createDeviceProtectedStorageContext().getSharedPreferences(DurationSetting.GROUP, MODE_PRIVATE);
        duration = DurationSetting.clamp(local.getInt(DurationSetting.KEY, DurationSetting.DEFAULT));
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

    int duration() {
        return duration;
    }

    SyncStatus status() {
        return status;
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
        int next = DurationSetting.clamp(seconds);
        if (next != duration) {
            duration = next;
            local.edit().putInt(DurationSetting.KEY, next).apply();
            sync();
        }
    }

    private void sync() {
        long request = ++revision;
        XposedService target = service;
        int seconds = duration;
        if (target == null) {
            publish(SyncStatus.WAITING);
            return;
        }
        publish(SyncStatus.SAVING);
        writer.execute(() -> {
            boolean saved = writeDuration(target, seconds);
            main.post(() -> {
                if (revision == request) {
                    publish(saved ? SyncStatus.SYNCED : SyncStatus.FAILED);
                }
            });
        });
    }

    private static boolean writeDuration(XposedService target, int seconds) {
        try {
            return target.getRemotePreferences(DurationSetting.GROUP).edit()
                    .putInt(DurationSetting.KEY, seconds).commit();
        } catch (RuntimeException error) {
            Log.w(FingerprintModule.TAG, "Could not sync the icon duration", error);
            return false;
        }
    }

    private void publish(SyncStatus newStatus) {
        status = newStatus;
        for (Runnable listener : listeners) {
            listener.run();
        }
    }
}
