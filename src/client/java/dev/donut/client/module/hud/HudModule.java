package dev.donut.client.module.hud;

import dev.donut.client.module.ModuleCategory;
import dev.donut.client.module.ToggleModule;

public final class HudModule extends ToggleModule {
    public HudModule() {
        super("hud", "HUD Overlay", ModuleCategory.HUD, "Displays active modules and client information on screen");
    }

    @Override
    public boolean isEnabled() {
        return context != null ? context.config().showHud : true;
    }

    @Override
    public void setEnabled(boolean enabled) {
        if (context != null) {
            context.config().showHud = enabled;
            context.save();
        }
    }
}
