package dev.donut.client.module;

import net.minecraft.client.Minecraft;

public abstract class ToggleModule implements Module {
    protected final String id;
    protected final String name;
    protected final ModuleCategory category;
    protected final String description;
    protected ModuleContext context;

    protected ToggleModule(String id, String name, ModuleCategory category, String description) {
        this.id = id;
        this.name = name;
        this.category = category;
        this.description = description;
    }

    @Override public String id() { return id; }
    @Override public String name() { return name; }
    @Override public ModuleCategory category() { return category; }
    @Override public String description() { return description; }

    @Override
    public void init(ModuleContext context) {
        this.context = context;
    }

    public void toggle() {
        setEnabled(!isEnabled());
    }
}
