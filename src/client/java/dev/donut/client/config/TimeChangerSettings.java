package dev.donut.client.config;

public final class TimeChangerSettings {
    public static final int TICKS_PER_DAY = 24_000;

    public boolean enabled;
    public boolean favorite;
    public int time = 6_000;

    public void sanitize() {
        time = Math.clamp(time, 0, TICKS_PER_DAY - 1);
    }
}
