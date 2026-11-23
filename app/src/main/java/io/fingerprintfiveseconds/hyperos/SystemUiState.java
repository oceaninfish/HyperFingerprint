package io.fingerprintfiveseconds.hyperos;

import android.app.KeyguardManager;
import android.content.Context;
import android.content.SharedPreferences;
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
    private final Handler main = new Handler(Looper.getMainLooper());
    private final RevealWindow window = new RevealWindow();
    private final Set<View> painters = Collections.newSetFromMap(new WeakHashMap<>());
    private final Runnable expire = this::redraw;
    private final SharedPreferences.OnSharedPreferenceChangeListener durationListener = (prefs, key) -> {
        if (key == null || DurationSetting.KEY.equals(key)) {
            main.post(() -> readDuration(prefs));
        }
    };

    private DisplayManager displays;
    private SharedPreferences durationPrefs;
    private int loadedSeconds;
    private GestureDetector taps;
    private boolean trackingTap;
    private boolean screenOn;
    private boolean keyguardVisible;
    private boolean receivedKeyguardState;

    void watchDuration(SharedPreferences prefs) {
        if (durationPrefs != null) {
            return;
        }
        prefs.registerOnSharedPreferenceChangeListener(durationListener);
        durationPrefs = prefs;
        readDuration(prefs);
    }

    private void readDuration(SharedPreferences prefs) {
        int seconds;
        try {
            seconds = DurationSetting.clamp(prefs.getInt(DurationSetting.KEY, DurationSetting.DEFAULT));
        } catch (RuntimeException error) {
            Log.w(FingerprintModule.TAG, "Could not read the icon duration", error);
            return;
        }
        if (seconds != loadedSeconds) {
            window.setDuration(seconds);
            loadedSeconds = seconds;
            Log.i(FingerprintModule.TAG, "Lock screen icon duration: " + seconds + " s");
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
        taps = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent event) {
                return true;
            }

            @Override
            public boolean onSingleTapUp(MotionEvent event) {
                reveal(SystemClock.uptimeMillis());
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
        if (Looper.myLooper() != main.getLooper() || taps == null || !window.isActive()) {
            return;
        }
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            Display display = shade.getDisplay();
            trackingTap = display != null && display.getDisplayId() == Display.DEFAULT_DISPLAY
                    && event.isFromSource(InputDevice.SOURCE_TOUCHSCREEN)
                    && event.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER;
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
            reveal(now);
        } else {
            main.post(() -> reveal(now));
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
        if (window.update(screenOn, keyguardVisible, SystemClock.uptimeMillis())) {
            trackingTap = false;
            scheduleRedraw();
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
