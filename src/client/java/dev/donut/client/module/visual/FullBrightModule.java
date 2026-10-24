package dev.donut.client.module.visual;

import dev.donut.client.DonutClient;
import dev.donut.client.config.FullBrightSettings;
import dev.donut.client.module.ModuleCategory;
import dev.donut.client.module.ToggleModule;
import net.minecraft.client.Minecraft;

public final class FullBrightModule extends ToggleModule {
    private static FullBrightModule instance;

    public FullBrightModule() {
        super("fullbright", "FullBright", ModuleCategory.STORAGE, "Brightens the world by applying maximum night vision ambient lighting");
        instance = this;
    }

    public static FullBrightModule getInstance() {
        return instance;
    }

    public static boolean isFullBrightActive() {
        return instance != null && instance.isEnabled();
    }

    public FullBrightSettings settings() {
        if (context != null && context.config() != null) {
            return context.config().fullBright;
        }
        DonutClient client = DonutClient.getInstance();
        return client != null ? client.getConfig().fullBright : new FullBrightSettings();
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
