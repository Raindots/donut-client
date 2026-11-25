package dev.donut.client.config;

public final class AnchorMacroSettings {
    public enum Mode { SINGLE, DOUBLE }
    public enum Unguarded { DETONATE, CANCEL }

    public transient boolean enabled;
    public boolean favorite;
    public Mode mode = Mode.SINGLE;
    public int safetySlot = 1;
    public int switchDelay = 1;
    public int chargeDelay = 1;
    public int explodeDelay = 1;
    public int cycleDelay = 2;
    public int placeDelay = 1;
    public boolean doubleAnchor;
    public boolean safeAnchor;
    public int guardSlot = 9;
    public Unguarded ifUnguarded = Unguarded.DETONATE;
    public boolean switchBack;
    public int switchBackDelay = 2;
    public int jitter;

    public void sanitize() {
        if (mode == null) mode = Mode.SINGLE;
        if (ifUnguarded == null) ifUnguarded = Unguarded.DETONATE;
        safetySlot = Math.clamp(safetySlot, 1, 9);
        switchDelay = Math.clamp(switchDelay, 0, 10);
        chargeDelay = Math.clamp(chargeDelay, 1, 10);
        explodeDelay = Math.clamp(explodeDelay, 1, 10);
        cycleDelay = Math.clamp(cycleDelay, 0, 20);
        placeDelay = Math.clamp(placeDelay, 1, 10);
        guardSlot = Math.clamp(guardSlot, 1, 9);
        switchBackDelay = Math.clamp(switchBackDelay, 0, 10);
        jitter = Math.clamp(jitter, 0, 3);
    }
}
