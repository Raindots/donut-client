package dev.donut.client.config;

public final class AutoTotemSettings {
    public enum Mode { INVENTORY, HOTBAR_SWAP }

    public transient boolean enabled;
    public boolean favorite;
    public Mode mode = Mode.INVENTORY;
    public boolean hotbarRefill;
    public int hotbarSlot = 1;
    public int reactionDelay;
    public int swapDelay = 1;
    public int closeDelay;
    public int switchBackDelay = 2;
    public int jitter;

    public void sanitize() {
        if (mode == null) mode = Mode.INVENTORY;
        hotbarSlot = Math.clamp(hotbarSlot, 1, 9);
        reactionDelay = Math.clamp(reactionDelay, 0, 10);
        swapDelay = Math.clamp(swapDelay, 1, 10);
        closeDelay = Math.clamp(closeDelay, 0, 10);
        switchBackDelay = Math.clamp(switchBackDelay, 0, 10);
        jitter = Math.clamp(jitter, 0, 3);
    }
}
