package dev.donut.client.config;

public final class ChunkFinderSettings {
    public boolean enabled = false;
    public boolean favorite = false;
    public int simulationDistance = 6;
    public int sensitivity = 3;
    public boolean smartAdjustment = true;
    public int alpha = 80;
    public boolean kelp = true;
    public boolean caveVines = true;
    public boolean vines = true;
    public boolean amethyst = true;
    public boolean bamboo = true;
    public boolean cocoa = true;
    public boolean beeNest = true;
    public int color = 0xC447FF;

    public void sanitize() {
        simulationDistance = Math.clamp(simulationDistance, 2, 16);
        sensitivity = Math.clamp(sensitivity, 1, 5);
        alpha = Math.clamp(alpha, 0, 255);
        color &= 0xFFFFFF;
    }
}
