package dev.donut.client.module.freecam;

import dev.donut.client.config.FreecamSettings;
import dev.donut.client.freecam.FreecamController;
import dev.donut.client.module.ModuleCategory;
import dev.donut.client.module.ToggleModule;

public final class FreecamModule extends ToggleModule {
    public FreecamModule() {
        super("freecam", "Freecam", ModuleCategory.MOVEMENT, "Detach the camera and explore nearby terrain freely");
    }

    public FreecamSettings settings() {
        return context != null ? context.config().freecam : new FreecamSettings();
    }

    @Override
    public boolean isEnabled() {
        return context != null ? context.freecam().isActive() : settings().enabled;
    }

    @Override
    public void setEnabled(boolean enabled) {
        if (context != null) {
            context.freecam().setEnabled(enabled);
            context.save();
        } else {
            settings().enabled = enabled;
        }
    }
}
