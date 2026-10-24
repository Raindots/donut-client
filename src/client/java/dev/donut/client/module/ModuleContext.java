package dev.donut.client.module;

import dev.donut.client.config.ClientConfig;
import dev.donut.client.config.ConfigStore;
import dev.donut.client.finder.FinderScanner;
import dev.donut.client.freecam.FreecamController;
import net.minecraft.client.Minecraft;

public record ModuleContext(
        Minecraft client,
        ClientConfig config,
        ConfigStore configStore,
        FinderScanner scanner,
        FreecamController freecam,
        Runnable saveCallback
) {
    public void save() {
        if (saveCallback != null) {
            saveCallback.run();
        }
    }
}
