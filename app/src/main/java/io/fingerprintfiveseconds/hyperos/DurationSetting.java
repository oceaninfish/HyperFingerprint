package io.fingerprintfiveseconds.hyperos;

final class DurationSetting {
    static final String GROUP = "fingerprint_icon_settings";
    static final String KEY = "duration_seconds";
    static final int MIN = 1;
    static final int MAX = 20;
    static final int DEFAULT = 5;

    private DurationSetting() {}

    static int clamp(int seconds) {
        return Math.max(MIN, Math.min(MAX, seconds));
    }
}
