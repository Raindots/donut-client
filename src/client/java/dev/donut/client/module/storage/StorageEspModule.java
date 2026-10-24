package dev.donut.client.module.storage;

import dev.donut.client.config.FinderSettings;
import dev.donut.client.module.ModuleCategory;
import dev.donut.client.module.ToggleModule;

public final class StorageEspModule extends ToggleModule {
    public StorageEspModule() {
        super("storage_esp", "Storage ESP", ModuleCategory.STORAGE, "Highlights nearby storage blocks and containers");
    }

    public FinderSettings settings() {
        return context != null ? context.config().storage : new FinderSettings();
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
