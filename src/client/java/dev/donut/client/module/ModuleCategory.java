package dev.donut.client.module;

public enum ModuleCategory {
    STORAGE("Storage", "Storage overlays and container inspection"),
    WORLD("World", "World entity and block utilities"),
    MOVEMENT("Movement", "Camera and navigation controls"),
    HUD("HUD", "Heads-up display indicators"),
    MISC("Misc", "Miscellaneous client features");

    private final String displayName;
    private final String description;

    ModuleCategory(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }

    public String displayName() { return displayName; }
    public String description() { return description; }
}
