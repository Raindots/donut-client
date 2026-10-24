package dev.donut.client.module;

import net.minecraft.client.Minecraft;

public interface Module {
    String id();
    String name();
    ModuleCategory category();
    String description();
    boolean isEnabled();
    void setEnabled(boolean enabled);

    default void init(ModuleContext context) {}
    default void onEnable() {}
    default void onDisable() {}
    default void tick(Minecraft client) {}
    default void close() {}
}
