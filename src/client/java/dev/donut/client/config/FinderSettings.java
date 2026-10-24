package dev.donut.client.config;

public final class FinderSettings {
    public boolean enabled = false;
    public boolean favorite = false;
    public boolean throughWalls = true;
    public boolean distanceFade = true;
    public RenderMode mode = RenderMode.BOTH;
    public double range = 96;
    public double outlineOpacity = 0.9;
    public double fillOpacity = 0.16;
    public double lineWidth = 1.5;
    public boolean chests = true;
    public boolean trappedChests = true;
    public boolean enderChests = true;
    public boolean shulkers = true;
    public boolean droppers = true;
    public boolean hoppers = true;
    public boolean trialSpawners = true;
    public boolean perTypeColors = true;
    public boolean labels = false;
    public boolean distance = false;
    public int color = 0x4C86FF;
    public int chestColor = 0xFFC66B;
    public int shulkerColor = 0xBA8CFF;
    public int dropperColor = 0x6DDAC5;
    public int hopperColor = 0x68B4FF;

    public void sanitize() {
        if (mode == null) mode = RenderMode.BOTH;
        range = clamp(range, 16, 256, 96);
        outlineOpacity = clamp(outlineOpacity, 0, 1, 0.9);
        fillOpacity = clamp(fillOpacity, 0, 1, 0.16);
        lineWidth = clamp(lineWidth, 0.5, 5, 1.5);
        color &= 0xFFFFFF;
        chestColor &= 0xFFFFFF;
        shulkerColor &= 0xFFFFFF;
        dropperColor &= 0xFFFFFF;
        hopperColor &= 0xFFFFFF;
    }

    private static double clamp(double value, double min, double max, double fallback) {
        return Double.isFinite(value) ? Math.clamp(value, min, max) : fallback;
    }
}
