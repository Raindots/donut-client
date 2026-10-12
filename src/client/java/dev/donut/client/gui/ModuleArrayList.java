package dev.donut.client.gui;

import dev.donut.client.config.ClientConfig;
import dev.donut.client.autobuild.AutoBuilderController;
import dev.donut.client.fly.FlyController;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/** Quiet, right-aligned names for the modules currently active in the world. */
public final class ModuleArrayList {
    private ModuleArrayList() { }

    public static void render(GuiGraphics graphics, Font font, int screenWidth,
                              ClientConfig config, boolean freecamActive) {
        if (!config.showHud) return;
        List<String> names = new ArrayList<>(7);
        if (config.storage.enabled) names.add("StorageFinder");
        if (config.spawner.enabled) names.add("SpawnerFinder");
        if (config.chunkFinder.enabled) names.add("Base Finder");
        if (config.fullbright.enabled) names.add("Fullbright");
        if (freecamActive) names.add("Freecam");
        if (AutoBuilderController.get().isRunning()) names.add("AutoBuilder");
        if (FlyController.getInstance() != null && FlyController.getInstance().isActive()) names.add("Fly");
        names.sort(Comparator.comparingInt((String name) -> UiPaint.width(font, name)).reversed());

        int right = screenWidth - 11;
        // Draw every shadow first so a later row cannot dim an earlier row's text.
        for (int i = 0; i < names.size(); i++) {
            int width = UiPaint.width(font, names.get(i));
            UiPaint.shadow(graphics, right - width - 4, 8 + i * 16, width + 8, 15, 5, 0x90000000);
        }
        for (int i = 0; i < names.size(); i++) {
            UiPaint.right(graphics, font, names.get(i), right, 11 + i * 16, 0xFFF0F2F6);
        }
    }
}
