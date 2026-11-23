package io.fingerprintfiveseconds.hyperos;

import android.content.SharedPreferences;

record IconSettings(int duration, boolean pickup, boolean wake, boolean tap, boolean touch,
                    String iconFile, int opacity, int sizeTenths) {
    static final int MIN_SIZE_TENTHS = 5;
    static final int MAX_SIZE_TENTHS = 20;
    static final int DEFAULT_SIZE_TENTHS = 10;

    private static final String PICKUP = "show_on_pickup";
    private static final String WAKE = "show_on_wake";
    private static final String TAP = "show_on_tap";
    private static final String TOUCH = "show_on_touch";
    private static final String IMAGE = "static_icon_file";
    private static final String OPACITY = "static_icon_opacity";
    private static final String SIZE = "static_icon_size_tenths";

    static final IconSettings DEFAULTS = new IconSettings(DurationSetting.DEFAULT, true, true, true,
            false, "", 100, DEFAULT_SIZE_TENTHS);

    IconSettings {
        tap = tap && !touch;
        iconFile = isIconFile(iconFile) ? iconFile : "";
        opacity = Math.max(0, Math.min(100, opacity));
        sizeTenths = Math.max(MIN_SIZE_TENTHS, Math.min(MAX_SIZE_TENTHS, sizeTenths));
    }

    static IconSettings read(SharedPreferences prefs) {
        return new IconSettings(
                DurationSetting.clamp(prefs.getInt(DurationSetting.KEY, DEFAULTS.duration)),
                prefs.getBoolean(PICKUP, DEFAULTS.pickup),
                prefs.getBoolean(WAKE, DEFAULTS.wake),
                prefs.getBoolean(TAP, DEFAULTS.tap), prefs.getBoolean(TOUCH, DEFAULTS.touch),
                prefs.getString(IMAGE, DEFAULTS.iconFile), prefs.getInt(OPACITY, DEFAULTS.opacity),
                prefs.getInt(SIZE, DEFAULT_SIZE_TENTHS));
    }

    SharedPreferences.Editor writeTo(SharedPreferences prefs) {
        return prefs.edit().putInt(DurationSetting.KEY, duration)
                .putBoolean(PICKUP, pickup).putBoolean(WAKE, wake)
                .putBoolean(TAP, tap).putBoolean(TOUCH, touch)
                .putString(IMAGE, iconFile).putInt(OPACITY, opacity).putInt(SIZE, sizeTenths);
    }

    static boolean isSettingKey(String key) {
        return key == null || DurationSetting.KEY.equals(key) || PICKUP.equals(key)
                || WAKE.equals(key) || TAP.equals(key) || TOUCH.equals(key)
                || IMAGE.equals(key) || OPACITY.equals(key) || SIZE.equals(key);
    }

    static String readIconFile(SharedPreferences prefs) {
        String name = prefs.getString(IMAGE, "");
        return isIconFile(name) ? name : "";
    }

    static boolean isIconFile(String name) {
        return name != null && name.matches("icon_[0-9a-f]{32}\\.png");
    }
}
