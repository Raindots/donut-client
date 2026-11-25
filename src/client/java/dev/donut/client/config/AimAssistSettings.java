package dev.donut.client.config;

public final class AimAssistSettings {
    public enum TargetMode { FOV, CLOSEST }
    public enum AimPart { HEAD, TORSO, LEGS }

    public transient boolean enabled;
    public boolean favorite;
    public TargetMode targetMode = TargetMode.FOV;
    public AimPart aimPart = AimPart.TORSO;
    public double range = 3.2;
    public double fov = 90;
    public double speed = 0.18;
    public boolean throughWalls;
    public boolean swords = true;
    public boolean axes = true;
    public boolean mace = true;
    public boolean spearsAndTridents = true;
    public boolean everything;

    public void sanitize() {
        if (targetMode == null) targetMode = TargetMode.FOV;
        if (aimPart == null) aimPart = AimPart.TORSO;
        range = bounded(range, 3.2, 0, 10);
        fov = bounded(fov, 90, 0, 360);
        speed = bounded(speed, 0.18, 0.01, 1);
    }

    private static double bounded(double value, double fallback, double min, double max) {
        return Double.isFinite(value) ? Math.clamp(value, min, max) : fallback;
    }
}
