package dev.donut.client.gui;

import dev.donut.client.config.ClientConfig;
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
        List<String> names = new ArrayList<>(4);
        if (config.storage.enabled) names.add("StorageFinder");
        if (config.spawner.enabled) names.add("SpawnerFinder");
        if (freecamActive) names.add("Freecam");
        if (config.fullBright.enabled) names.add("FullBright");
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
