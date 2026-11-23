package io.fingerprintfiveseconds.hyperos;

final class RevealWindow {
    static final long INACTIVE = Long.MAX_VALUE;

    // The main thread writes the deadline and the fingerprint window's thread reads it.
    private volatile long deadline = INACTIVE;
    private long durationMs = DurationSetting.DEFAULT * 1_000L;
    private long lastReveal;
    private boolean screenOn;
    private boolean keyguardVisible;

    boolean update(boolean screenOn, boolean keyguardVisible, boolean revealOnEntry, long now) {
        boolean wasActive = isActive();
        this.screenOn = screenOn;
        this.keyguardVisible = keyguardVisible;
        if (wasActive == isActive()) {
            return false;
        }
        lastReveal = now;
        deadline = isActive() ? now + (revealOnEntry ? durationMs : 0) : INACTIVE;
        return true;
    }

    boolean reveal(long now) {
        if (!isActive() || now < lastReveal) {
            return false;
        }
        lastReveal = now;
        deadline = now + durationMs;
        return true;
    }

    void setDuration(int seconds) {
        durationMs = DurationSetting.clamp(seconds) * 1_000L;
    }

    boolean isActive() {
        return screenOn && keyguardVisible;
    }

    long deadline() {
        return deadline;
    }

    boolean shouldHide(long now) {
        return now >= deadline;
    }
}
