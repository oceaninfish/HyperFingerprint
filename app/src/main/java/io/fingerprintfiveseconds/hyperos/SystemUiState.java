package io.fingerprintfiveseconds.hyperos;

import android.app.KeyguardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.hardware.SensorManager;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Display;
import android.view.GestureDetector;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

final class SystemUiState implements DisplayManager.DisplayListener {
    private static final long PICKUP_WAKE_GRACE_MS = 2_000;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final RevealWindow window = new RevealWindow();
    private final Set<View> painters = Collections.newSetFromMap(new WeakHashMap<>());
    private final Runnable expire = this::redraw;
    private final SharedPreferences.OnSharedPreferenceChangeListener settingsListener = (prefs, key) -> {
        if (IconSettings.isSettingKey(key)) {
            main.post(() -> readSettings(prefs));
        }
    };

    private DisplayManager displays;
    private SharedPreferences settingsPrefs;
    private IconSettings settings = IconSettings.DEFAULTS;
    private PickupMonitor pickupMonitor;
    private long pendingPickup = -1;
    private GestureDetector taps;
    private boolean trackingTap;
    private boolean screenOn;
    private boolean keyguardVisible;
    private boolean receivedKeyguardState;

    void watchSettings(SharedPreferences prefs) {
        if (settingsPrefs != null) {
            return;
        }
        prefs.registerOnSharedPreferenceChangeListener(settingsListener);
        settingsPrefs = prefs;
        readSettings(prefs);
    }

    private void readSettings(SharedPreferences prefs) {
        IconSettings next;
        try {
            next = IconSettings.read(prefs);
        } catch (RuntimeException error) {
            Log.w(FingerprintModule.TAG, "Could not read the icon settings", error);
            return;
        }
        if (!next.equals(settings)) {
            settings = next;
            window.setDuration(next.duration());
            if (!next.tap()) {
                trackingTap = false;
            }
            if (!next.pickup()) {
                pendingPickup = -1;
            }
            updatePickupMonitor();
        }
    }

    void start(Context context) {
        if (displays != null) {
            return;
        }
        DisplayManager manager = context.getSystemService(DisplayManager.class);
        if (manager == null) {
            return;
        }
        if (!receivedKeyguardState) {
            KeyguardManager keyguard = context.getSystemService(KeyguardManager.class);
            keyguardVisible = keyguard != null && keyguard.isKeyguardLocked();
        }
        SensorManager sensors = context.getSystemService(SensorManager.class);
        if (sensors != null) {
            try {
                pickupMonitor = new PickupMonitor(sensors, main, this::onPickup);
            } catch (RuntimeException error) {
                Log.w(FingerprintModule.TAG, "Could not initialize the pickup sensor", error);
            }
        }
        taps = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent event) {
                return true;
            }

            @Override
            public boolean onSingleTapUp(MotionEvent event) {
                revealFromTap(SystemClock.uptimeMillis());
                return true;
            }
        }, main);
        manager.registerDisplayListener(this, main);
        displays = manager;
        onDisplayChanged(Display.DEFAULT_DISPLAY);
    }

    void setKeyguardVisible(boolean visible) {
        if (Looper.myLooper() != main.getLooper()) {
            main.post(() -> setKeyguardVisible(visible));
            return;
        }
        receivedKeyguardState = true;
        keyguardVisible = visible;
        if (!visible) {
            pendingPickup = -1;
        }
        updateWindow();
    }

    @Override
    public void onDisplayChanged(int id) {
        if (id == Display.DEFAULT_DISPLAY) {
            Display display = displays.getDisplay(id);
            screenOn = display != null && display.getState() == Display.STATE_ON;
            updateWindow();
        }
    }

    @Override
    public void onDisplayAdded(int id) {
        onDisplayChanged(id);
    }

    @Override
    public void onDisplayRemoved(int id) {
        onDisplayChanged(id);
    }

    void observeTouch(View shade, MotionEvent event) {
        if (Looper.myLooper() != main.getLooper() || taps == null || !window.isActive()
                || !(settings.tap() || settings.touch())) {
            return;
        }
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            Display display = shade.getDisplay();
            boolean fingerDown = display != null && display.getDisplayId() == Display.DEFAULT_DISPLAY
                    && event.isFromSource(InputDevice.SOURCE_TOUCHSCREEN)
                    && event.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER;
            trackingTap = fingerDown && settings.tap();
            if (fingerDown && settings.touch()) {
                reveal(SystemClock.uptimeMillis());
            }
        }
        if (trackingTap) {
            taps.onTouchEvent(event);
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                trackingTap = false;
            }
        }
    }

    void sensorTouched(long now) {
        if (Looper.myLooper() == main.getLooper()) {
            revealFromSensorTouch(now);
        } else {
            main.post(() -> revealFromSensorTouch(now));
        }
    }

    void trackPainter(View view) {
        synchronized (painters) {
            painters.add(view);
        }
    }

    boolean shouldHide() {
        return window.shouldHide(SystemClock.uptimeMillis());
    }

    private void updateWindow() {
        boolean pickupNearWake = pendingPickup >= 0
                && SystemClock.elapsedRealtime() - pendingPickup <= PICKUP_WAKE_GRACE_MS;
        boolean revealOnEntry = settings.wake() || (settings.pickup() && pickupNearWake);
        if (window.update(screenOn, keyguardVisible, revealOnEntry, SystemClock.uptimeMillis())) {
            pendingPickup = -1;
            trackingTap = false;
            scheduleRedraw();
        }
        updatePickupMonitor();
    }

    private void updatePickupMonitor() {
        if (pickupMonitor != null) {
            pickupMonitor.update(settings.pickup() && keyguardVisible, screenOn);
        }
    }

    private void onPickup(long elapsed) {
        if (!settings.pickup() || !keyguardVisible
                || SystemClock.elapsedRealtime() - elapsed > PICKUP_WAKE_GRACE_MS) {
            return;
        }
        if (window.isActive()) {
            reveal(SystemClock.uptimeMillis());
        } else {
            // If the system wakes the display for this lift, let pickup show the icon at wake.
            pendingPickup = elapsed;
        }
    }

    private void revealFromTap(long now) {
        if (settings.tap()) {
            reveal(now);
        }
    }

    private void revealFromSensorTouch(long now) {
        if (settings.tap() || settings.touch()) {
            reveal(now);
        }
    }

    private void reveal(long now) {
        if (window.reveal(now)) {
            scheduleRedraw();
        }
    }

    private void scheduleRedraw() {
        main.removeCallbacks(expire);
        if (window.deadline() != RevealWindow.INACTIVE) {
            main.postAtTime(expire, window.deadline());
        }
        redraw();
    }

    private void redraw() {
        synchronized (painters) {
            for (View view : painters) {
                view.postInvalidate();
            }
        }
    }
}
