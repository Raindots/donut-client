package dev.donut.client.gui;

import dev.donut.client.autobuild.AutoBuilderController;
import dev.donut.client.config.ClientConfig;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/** Small in-world progress display; module names remain in the existing array list. */
public final class AutoBuilderHud {
    private AutoBuilderHud() { }

    public static void render(GuiGraphics g, Font font, int height, ClientConfig config) {
        AutoBuilderController builder = AutoBuilderController.get();
        if (builder.schematic() == null || (!builder.previewVisible()
                && builder.status() != AutoBuilderController.Status.RUNNING
                && builder.status() != AutoBuilderController.Status.PAUSED)) return;
        int y = 12;
        String status = "AutoBuilder  " + builder.status().name().toLowerCase(java.util.Locale.ROOT)
                + "  " + Math.round(builder.progress() * 100) + "%";
        String reason = builder.reason();
        while (UiPaint.width(font, reason) > 300 && reason.length() > 4)
            reason = reason.substring(0, reason.length() - 4) + "...";
        int w = Math.max(210, Math.max(UiPaint.width(font, status), UiPaint.width(font, reason)) + 20);
        UiPaint.round(g, 9, y, w, 45, 7, 0xDA101117);
        UiPaint.text(g, font, status, 19, y + 8, 0xFFF0F2F6);
        UiPaint.smallText(g, font, reason, 19, y + 22, 0xFFB4B4C8);
        UiPaint.round(g, 19, y + 36, w - 20, 2, 1, 0xFF303040);
        UiPaint.round(g, 19, y + 36, (int) ((w - 20) * Math.clamp(builder.progress(), 0, 1)), 2, 1,
                0xFF000000 | config.accentColor);
    }
}
