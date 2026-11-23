package io.fingerprintfiveseconds.hyperos;

import android.content.SharedPreferences;

record IconSettings(int duration, boolean pickup, boolean wake, boolean tap, boolean touch) {
    private static final String PICKUP = "show_on_pickup";
    private static final String WAKE = "show_on_wake";
    private static final String TAP = "show_on_tap";
    private static final String TOUCH = "show_on_touch";

    static final IconSettings DEFAULTS = new IconSettings(DurationSetting.DEFAULT, true, true, true, false);

    IconSettings {
        tap = tap && !touch;
    }

    static IconSettings read(SharedPreferences prefs) {
        return new IconSettings(
                DurationSetting.clamp(prefs.getInt(DurationSetting.KEY, DEFAULTS.duration)),
                prefs.getBoolean(PICKUP, DEFAULTS.pickup),
                prefs.getBoolean(WAKE, DEFAULTS.wake),
                prefs.getBoolean(TAP, DEFAULTS.tap), prefs.getBoolean(TOUCH, DEFAULTS.touch));
    }

    SharedPreferences.Editor writeTo(SharedPreferences prefs) {
        return prefs.edit().putInt(DurationSetting.KEY, duration)
                .putBoolean(PICKUP, pickup).putBoolean(WAKE, wake)
                .putBoolean(TAP, tap).putBoolean(TOUCH, touch);
    }

    static boolean isSettingKey(String key) {
        return key == null || DurationSetting.KEY.equals(key) || PICKUP.equals(key)
                || WAKE.equals(key) || TAP.equals(key) || TOUCH.equals(key);
    }
}
