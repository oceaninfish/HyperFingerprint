package io.fingerprintfiveseconds.hyperos;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import io.github.libxposed.api.XposedModule;

public final class FingerprintModule extends XposedModule {
    static final String TAG = "HyperFingerprint";
    static final String SYSTEM_UI = "com.android.systemui";
    private static final String FOD = "com.miui.keyguard.biometrics.fod.";

    private String processName;

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        processName = param.getProcessName();
    }

    @Override
    public void onPackageReady(PackageReadyParam param) {
        if (!param.isFirstPackage() || !SYSTEM_UI.equals(param.getPackageName())
                || !SYSTEM_UI.equals(processName)) {
            return;
        }

        List<HookHandle> installed = new ArrayList<>();
        try {
            ClassLoader loader = param.getClassLoader();
            Class<?> painter = load(loader, FOD + "MiuiGxzwAnimationView");
            Class<?> animationRoot = load(loader, FOD + "MiuiGxzwAnimView$MiuiGxzwAnimViewInternal");
            Class<?> touchRoot = load(loader, FOD + "MiuiGxzwIconView");
            Class<?> fodWindow = load(loader, FOD + "GxzwNoRotateFrameLayout");
            Field keyguardAuth = booleanField(fodWindow, "mKeyguardAuthen");
            Field animationDozing = booleanField(animationRoot, "mDozing");
            Field touchDozing = booleanField(touchRoot, "mDozing");

            Method draw = painter.getDeclaredMethod("onDraw", Canvas.class);
            Method sensorDown = touchRoot.getDeclaredMethod("onTouchDown");
            Method shadeTouch = load(loader, SYSTEM_UI + ".shade.NotificationShadeWindowView")
                    .getDeclaredMethod("dispatchTouchEvent", MotionEvent.class);
            Method keyguardChanged = load(loader, SYSTEM_UI
                    + ".statusbar.policy.KeyguardStateControllerImpl")
                    .getDeclaredMethod("notifyKeyguardState", boolean.class, boolean.class);
            Method appCreate = load(loader, param.getApplicationInfo().className)
                    .getDeclaredMethod("onCreate");

            FingerprintAppearance appearance = installAppearance(loader, painter, installed);
            SystemUiState state = new SystemUiState(appearance);
            installed.add(observe(draw, chain -> {
                View view = (View) chain.getThisObject();
                Object parent = view.getParent();
                // The swipe hint subclasses this view and has its own role in the same window.
                if (view.getClass() == painter && animationRoot.isInstance(parent)) {
                    state.trackPainter(view);
                    long deadline = state.deadline();
                    boolean timed = deadline != RevealWindow.INACTIVE;
                    boolean styled = appearance != null && appearance.hasCustomStyle();
                    if ((timed || styled) && keyguardAuth.getBoolean(parent)) {
                        boolean dozing = animationDozing.getBoolean(parent);
                        if (!dozing && timed && SystemClock.uptimeMillis() >= deadline) {
                            return null;
                        }
                        if (styled && (dozing || timed)
                                && appearance.drawStaticFrame(view, (Canvas) chain.getArg(0), dozing)) {
                            return null;
                        }
                    }
                }
                return chain.proceed();
            }));

            installed.add(observe(shadeTouch, chain -> {
                state.observeTouch((View) chain.getThisObject(), (MotionEvent) chain.getArg(0));
                return chain.proceed();
            }));

            installed.add(observe(sensorDown, chain -> {
                Object receiver = chain.getThisObject();
                if (keyguardAuth.getBoolean(receiver) && !touchDozing.getBoolean(receiver)) {
                    state.sensorTouched(SystemClock.uptimeMillis());
                }
                return chain.proceed();
            }));

            installed.add(observe(keyguardChanged, chain -> {
                boolean showing = (Boolean) chain.getArg(0);
                boolean occluded = (Boolean) chain.getArg(1);
                Object result = chain.proceed();
                state.setKeyguardVisible(showing && !occluded);
                return result;
            }));

            installed.add(observe(appCreate, chain -> {
                Object result = chain.proceed();
                if (Looper.myLooper() == Looper.getMainLooper()) {
                    try {
                        state.watchSettings(getRemotePreferences(DurationSetting.GROUP));
                    } catch (RuntimeException error) {
                        log(Log.WARN, TAG, "Could not read LSPosed icon settings", error);
                    }
                    state.start((Context) chain.getThisObject());
                    log(Log.INFO, TAG, "Watching the default display and lock screen taps");
                }
                return result;
            }));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            for (HookHandle handle : installed) {
                handle.unhook();
            }
            log(Log.WARN, TAG, "SystemUI fingerprint hooks unavailable on this build", error);
        }
    }

    private FingerprintAppearance installAppearance(ClassLoader loader, Class<?> painter,
            List<HookHandle> installed) {
        try {
            Class<?> frames = load(loader, FOD + "MiuiGxzwFrameAnimation");
            Method decode = frames.getDeclaredMethod("decodeBitmap", Object.class, boolean.class);
            FingerprintAppearance appearance = new FingerprintAppearance(frames, painter, this::openRemoteFile);
            installed.add(observe(decode, chain -> {
                Object result = chain.proceed();
                if (result instanceof Bitmap bitmap && chain.getArg(0) instanceof Integer id
                        && !(Boolean) chain.getArg(1)) {
                    try {
                        appearance.captureFrame(chain.getThisObject(), id, bitmap);
                    } catch (Resources.NotFoundException ignored) {
                        // Frames from other resource tables are left to SystemUI.
                    }
                }
                return result;
            }));
            return appearance;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            log(Log.WARN, TAG, "Static fingerprint appearance unavailable on this build", error);
            return null;
        }
    }

    private HookHandle observe(Method method, Hooker hooker) {
        return hook(method).setExceptionMode(ExceptionMode.PROTECTIVE).intercept(hooker);
    }

    private static Class<?> load(ClassLoader loader, String name) throws ClassNotFoundException {
        return Class.forName(name, false, loader);
    }

    private static Field booleanField(Class<?> owner, String name) throws NoSuchFieldException {
        Field field = owner.getDeclaredField(name);
        if (field.getType() != boolean.class) {
            throw new NoSuchFieldException(owner.getName() + "." + name + " is not boolean");
        }
        field.setAccessible(true);
        return field;
    }
}
