package dev.donut.client.config;

public final class PlayerEspSettings {
    public static final int MIN_RANGE = 16;
    public static final int MAX_RANGE = 256;

    public boolean enabled;
    public boolean favorite;
    public int range = 128;
    public int color = 0x56DCCB;
    public int opacity = 255;
    public boolean throughWalls = true;

    public void sanitize() {
        range = Math.clamp(range, MIN_RANGE, MAX_RANGE);
        color &= 0xFFFFFF;
        opacity = Math.clamp(opacity, 0, 255);
    }
}
