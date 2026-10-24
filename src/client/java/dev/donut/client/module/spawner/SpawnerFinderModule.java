package dev.donut.client.module.spawner;

import dev.donut.client.config.FinderSettings;
import dev.donut.client.module.ModuleCategory;
import dev.donut.client.module.ToggleModule;

public final class SpawnerFinderModule extends ToggleModule {
    public SpawnerFinderModule() {
        super("spawner_finder", "Spawner Finder", ModuleCategory.WORLD, "Highlights nearby monster and trial spawners");
    }

    public FinderSettings settings() {
        return context != null ? context.config().spawner : new FinderSettings();
    }

    @Override
    public boolean isEnabled() {
        return settings().enabled;
    }

    @Override
    public void setEnabled(boolean enabled) {
        settings().enabled = enabled;
        if (context != null) {
            context.save();
        }
    }
}
