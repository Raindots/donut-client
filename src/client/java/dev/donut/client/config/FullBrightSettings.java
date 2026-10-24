package dev.donut.client.config;

public final class FullBrightSettings {
    public boolean enabled = false;
    public boolean favorite = false;

    public void sanitize() {
        // boolean fields do not require clamping
    }
}
