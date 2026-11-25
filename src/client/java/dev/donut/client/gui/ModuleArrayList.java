// SPDX-License-Identifier: GPL-3.0-only
// Visuals adapted from Spectre 26.2 HudRenderer/ModuleListRows by 4ldenz.
package dev.donut.client.gui;

import dev.donut.client.config.ClientConfig;
import dev.donut.client.autobuild.AutoBuilderController;
import dev.donut.client.fly.FlyController;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/** Spectre's connected, width-sorted module rows bound to Donut's active modules. */
public final class ModuleArrayList {
    private static final String[] NAMES = {"Freecam", "AutoBuilder", "Base Finder",
            "Fullbright", "Fly", "Progress Bar", "Name Hider", "Hotbar", "Amethyst Scanner", "Light Debug",
            "Sound Detector", "BlockESP", "Staff Detector", "Crystal Aura", "Auto Totem", "Anchor Macro",
            "Triggerbot", "Aim Assist", "Player ESP", "Time Changer"};
    private static final float[] animation = new float[NAMES.length];
    private static long lastFrame;
    private static List<Row> rows = List.of();
    private ModuleArrayList() { }

    public static void reset() {
        lastFrame = 0;
        java.util.Arrays.fill(animation, 0);
        rows = List.of();
    }

    public static void render(GuiGraphics g, Font font, int ignored, ClientConfig config, boolean freecamActive) {
        if (!config.showHud) { reset(); return; }
        Minecraft client = Minecraft.getInstance();
        float guiScale = client.getWindow().getGuiScale();
        float s = (float) (Math.round(Math.clamp(client.getWindow().getHeight() / 1080.0 * config.clickGuiStyle.scale, .6, 3.5) * 20) / 20.0);
        long now = System.nanoTime();
        float dt = lastFrame == 0 ? 1f / 60 : Math.clamp((now - lastFrame) / 1_000_000_000f, 0, .1f);
        lastFrame = now;
        boolean[] enabled = {freecamActive,
                AutoBuilderController.get().isRunning(), config.chunkFinder.enabled, config.fullbright.enabled,
                FlyController.getInstance() != null && FlyController.getInstance().isActive(),
                config.progressBar.enabled, config.nameHider.enabled, config.hotbar.enabled, config.amethyst.enabled,
                config.lightDebug.enabled, config.soundDetector.enabled, config.blockEsp.enabled, config.staffDetector.enabled,
                config.crystalAura.enabled, config.autoTotem.enabled, config.anchorMacro.enabled,
                config.triggerbot.enabled, config.aimAssist.enabled, config.playerEsp.enabled, config.timeChanger.enabled};
        float k = config.clickGuiStyle.animationSpeed == 0 ? 1 : 1 - (float) Math.exp(-12 * dt * config.clickGuiStyle.animationSpeed);
        float previousFontScale = WorkspacePaint.pushScale(s);
        g.pose().pushMatrix();
        try {
            g.pose().scale(1 / guiScale, 1 / guiScale);
            List<Row> ordered = new ArrayList<>();
            for (int i = 0; i < NAMES.length; i++) {
                float target = enabled[i] ? 1 : 0;
                animation[i] += (target - animation[i]) * k;
                if (Math.abs(animation[i] - target) < .005f) animation[i] = target;
                if (animation[i] > 0) ordered.add(new Row(i, NAMES[i], WorkspacePaint.strongWidth(font, NAMES[i]), animation[i]));
            }
            ordered.sort(Comparator.comparingDouble((Row row) -> -row.nameWidth).thenComparing(Row::name));
            rows = List.copyOf(ordered);
            float right = client.getWindow().getWidth() - Math.round(10 * s), y = Math.round(10 * s);
            float fullHeight = 18 * s, radius = 6 * s;
            List<Step> steps = new ArrayList<>();
            for (Row row : ordered) {
                float open = row.animation * row.animation * (3 - 2 * row.animation);
                float w = Math.round(12 * s + row.nameWidth);
                float top = Math.round(y), bottom = Math.round(y + fullHeight * open);
                y += fullHeight * open;
                if (bottom > top) {
                    float slide = Math.round(w * (1 - open));
                    steps.add(new Step(row, right - w + slide, top, right + slide, bottom, Math.min(1, row.animation * 1.5f)));
                }
            }
            drawBackground(g, steps, radius, s);
            for (Step step : steps) {
                float x = Math.round(step.right - 6 * s - step.row.nameWidth);
                float textY = Math.round(step.top + Math.floor((fullHeight - 17 * s) / 2) - (fullHeight - step.height()) / 2);
                g.enableScissor((int) Math.floor(step.left), (int) step.top, (int) Math.ceil(step.right), (int) step.bottom);
                float alpha = step.row.animation * step.row.animation;
                WorkspacePaint.strong(g, font, step.row.name, (int) x + 1, (int) textY + 1, scaleAlpha(0x8C000000, alpha));
                WorkspacePaint.strong(g, font, step.row.name, (int) x, (int) textY, scaleAlpha(0xFFFFFFFF, alpha));
                g.disableScissor();
            }
        } finally {
            g.pose().popMatrix();
            WorkspacePaint.popScale(previousFontScale);
        }
    }

    private static void drawBackground(GuiGraphics g, List<Step> steps, float radius, float s) {
        for (int k = 3; k >= 1; k--) {
            float spread = k * Math.max(1, s);
            for (int i = 0; i < steps.size(); i++) {
                Step q = steps.get(i);
                float t = q.top - (i == 0 ? spread : 0);
                float b = q.bottom + (i == steps.size() - 1 ? spread + Math.max(1, s) : 0);
                SmoothGeometry.corners(g, q.left - spread, t, q.width() + spread * 2, b - t,
                        radius + spread, corners(steps, i), scaleAlpha(0x0D000000, q.alpha));
            }
        }
        for (int i = 0; i < steps.size(); i++) {
            Step q = steps.get(i), up = i > 0 ? steps.get(i - 1) : null, down = i + 1 < steps.size() ? steps.get(i + 1) : null;
            int color = scaleAlpha(0x9916181D, q.alpha);
            SmoothGeometry.corners(g, q.left, q.top, q.width(), q.height(), radius, corners(steps, i), color);
            if (up != null && up.left < q.left - .5f) fillet(g, q.left, q.top, -1, 1, Math.min(radius, Math.min(q.left - up.left, q.height())), color);
            if (up != null && up.right > q.right + .5f) fillet(g, q.right, q.top, 1, 1, Math.min(radius, Math.min(up.right - q.right, q.height())), color);
            if (down != null && down.left < q.left - .5f) fillet(g, q.left, q.bottom, -1, -1, Math.min(radius, Math.min(q.left - down.left, q.height())), color);
            if (down != null && down.right > q.right + .5f) fillet(g, q.right, q.bottom, 1, -1, Math.min(radius, Math.min(down.right - q.right, q.height())), color);
        }
    }

    private static int corners(List<Step> rows, int i) {
        Step q = rows.get(i), up = i > 0 ? rows.get(i - 1) : null, dn = i + 1 < rows.size() ? rows.get(i + 1) : null;
        int mask = 0;
        if (up == null || up.left > q.left + .5f) mask |= 16;
        if (up == null || up.right < q.right - .5f) mask |= 32;
        if (dn == null || dn.left > q.left + .5f) mask |= 64;
        if (dn == null || dn.right < q.right - .5f) mask |= 128;
        return mask;
    }

    private static void fillet(GuiGraphics g, float px, float py, int dx, int dy, float r, int color) {
        if (r < 1) return;
        float cx = px + dx * r, cy = py + dy * r;
        double a0 = dx < 0 ? 0 : Math.PI;
        double a1 = dy > 0 ? (dx < 0 ? -Math.PI / 2 : Math.PI * 1.5) : Math.PI / 2;
        float[] path = new float[20];
        path[0] = px; path[1] = py;
        for (int i = 0; i <= 8; i++) {
            double a = a0 + (a1 - a0) * i / 8;
            path[2 + i * 2] = cx + (float) Math.cos(a) * r;
            path[3 + i * 2] = cy + (float) Math.sin(a) * r;
        }
        SpectreMascotCanvas canvas = new SpectreMascotCanvas(g, 0, 0, 1, 1);
        canvas.polygon(path, color);
        canvas.submit(g);
    }

    private static int scaleAlpha(int color, float alpha) { return Math.round((color >>> 24) * alpha) << 24 | color & 0xFFFFFF; }
    private record Row(int module, String name, float nameWidth, float animation) { }
    private record Step(Row row, float left, float top, float right, float bottom, float alpha) {
        float width() { return right - left; }
        float height() { return bottom - top; }
    }
}
