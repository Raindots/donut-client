package dev.donut.client.gui;

import dev.donut.client.config.ClientConfig;
import dev.donut.client.staff.StaffDetectorController;
import java.util.ArrayList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

/** Observations and uncertainty stay explicit, even when no staff packets are visible. */
public final class StaffDetectorHud {
    private StaffDetectorHud() { }
    public static void render(GuiGraphics g, ClientConfig config, StaffDetectorController controller) {
        var mc = Minecraft.getInstance(); var settings = config.staffDetector;
        if (!settings.enabled || !settings.showHud || !config.showHud || mc.player == null || mc.screen != null || mc.options.hideGui) return;
        var lines = new ArrayList<String>(); var colors = new ArrayList<Integer>();
        lines.add("Staff Detector | Observed presence"); colors.add(0xFFE5EFF9);
        var views = controller.views().stream().filter(view -> view.online() || view.nearby() || view.possibleHidden() || view.rosterLost() || view.recentSpectator()).toList();
        for (var view : views.stream().limit(settings.maxHudRows).toList()) {
            lines.add((config.nameHider.enabled ? "Staff member" : view.name()) + " | " + view.description());
            colors.add(view.invisible() || view.spectator() ? 0xFFFFC875 : view.possibleHidden() || view.recentSpectator() || view.online() && !view.listed() ? 0xFFFFE4A1 : 0xFFB5C9DA);
        }
        if (views.isEmpty()) { lines.add("No staff presence observed"); colors.add(0xFFB5C9DA); }
        if (views.size() > settings.maxHudRows) { lines.add("+" + (views.size() - settings.maxHudRows) + " more in staff list"); colors.add(0xFFB5C9DA); }
        lines.add("Hidden presence is unknown"); colors.add(0xFF9CAFC1);
        int w = Math.min(Math.min(460, g.guiWidth() - 16), lines.stream().mapToInt(mc.font::width).max().orElse(100) + 16);
        int x = 8, y = 8, h = 12 + lines.size() * 12;
        UiPaint.round(g, x, y, w, h, 5, 0xD0141C27);
        for (int index = 0; index < lines.size(); index++)
            g.drawString(mc.font, mc.font.plainSubstrByWidth(lines.get(index), Math.max(1, w - 16)), x + 8, y + 7 + index * 12, colors.get(index), false);
    }
}
