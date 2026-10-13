package dev.donut.client.gui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.resources.Identifier;

/** Small, antialiased vector drawing helpers for the click GUI. */
final class UiPaint {
    private static final FontDescription UI_FONT = new FontDescription.Resource(
            Identifier.fromNamespaceAndPath("donut", "ui"));

    private UiPaint() { }

    static Component label(String value) {
        return Component.literal(value).withStyle(style -> style.withFont(UI_FONT));
    }

    static void text(GuiGraphics g, Font font, String text, int x, int y, int color) {
        g.drawString(font, label(text), x, y, color, false);
    }

    static void strong(GuiGraphics g, Font font, String text, int x, int y, int color) {
        g.drawString(font, label(text).copy().withStyle(style -> style.withBold(true)), x, y, color, false);
    }

    static int width(Font font, String text) {
        return font.width(label(text));
    }

    static void right(GuiGraphics g, Font font, String text, int right, int y, int color) {
        text(g, font, text, right - width(font, text), y, color);
    }

    static int alpha(int rgb, double alpha) {
        return ((int) (Math.clamp(alpha, 0.0, 1.0) * 255) << 24) | (rgb & 0xFFFFFF);
    }

    static int blend(int a, int b, double t) {
        t = Math.clamp(t, 0.0, 1.0);
        return 0xFF000000 | ((int) (((a >> 16) & 255) * (1 - t) + ((b >> 16) & 255) * t) << 16)
                | ((int) (((a >> 8) & 255) * (1 - t) + ((b >> 8) & 255) * t) << 8)
                | (int) ((a & 255) * (1 - t) + (b & 255) * t);
    }

    static void round(GuiGraphics g, int x, int y, int w, int h, int radius, int color) {
        round(g, (float) x, y, w, h, radius, color);
    }

    static void round(GuiGraphics g, float x, float y, float w, float h, float radius, int color) {
        SmoothGeometry.rounded(g, x, y, w, h, radius, 0, color);
    }

    static void outline(GuiGraphics g, int x, int y, int w, int h, int radius, int color) {
        outline(g, (float) x, y, w, h, radius, color);
    }

    static void outline(GuiGraphics g, float x, float y, float w, float h, float radius, int color) {
        SmoothGeometry.rounded(g, x, y, w, h, radius, 1, color);
    }

    static void shadow(GuiGraphics g, int x, int y, int w, int h, int radius, int color) {
        SmoothGeometry.shadow(g, x, y, w, h, radius, color);
    }

    static void line(GuiGraphics g, int x1, int y1, int x2, int y2, int color) {
        line(g, x1 + .5f, y1 + .5f, x2 + .5f, y2 + .5f, color);
    }

    static void line(GuiGraphics g, float x1, float y1, float x2, float y2, int color) {
        SmoothGeometry.line(g, x1, y1, x2, y2, 1.2f, color);
    }

    static void search(GuiGraphics g, int x, int y, int color) {
        outline(g, (float) x, y, 8, 8, 4, color);
        line(g, x + 7f, y + 7f, x + 10f, y + 10f, color);
    }

    static void chest(GuiGraphics g, int x, int y, int color) {
        outline(g, x + 1, y + 3, 15, 12, 2, color);
        line(g, x + 2f, y + 8f, x + 7f, y + 8f, color);
        line(g, x + 10f, y + 8f, x + 15f, y + 8f, color);
        round(g, x + 7, y + 7, 3, 4, 1, color);
    }

    static void spawner(GuiGraphics g, int x, int y, int color) {
        outline(g, x + 1, y + 1, 15, 15, 2, color);
        line(g, x + 6f, y + 2f, x + 6f, y + 15f, color);
        line(g, x + 11f, y + 2f, x + 11f, y + 15f, color);
        line(g, x + 2f, y + 6f, x + 15f, y + 6f, color);
        line(g, x + 2f, y + 11f, x + 15f, y + 11f, color);
    }

    /** Ancient debris' ringed top face. */
    static void debris(GuiGraphics g, int x, int y, int color) {
        outline(g, x + 1, y + 1, 15, 15, 2, color);
        outline(g, x + 5, y + 5, 7, 7, 3, color);
        round(g, x + 7, y + 7, 3, 3, 1, color);
    }

    static void camera(GuiGraphics g, int x, int y, int color) {
        outline(g, x, y + 4, 17, 11, 2, color);
        outline(g, x + 5, y + 6, 7, 7, 3, color);
        round(g, x + 3, y + 2, 6, 3, 1, color);
        round(g, x + 13, y + 6, 2, 2, 1, color);
    }

    static void star(GuiGraphics g, int x, int y, int color) {
        float[] path = new float[20];
        for (int i = 0; i < 10; i++) {
            double angle = -Math.PI / 2 + i * Math.PI / 5;
            float radius = i % 2 == 0 ? 7.5f : 3.6f;
            path[i * 2] = x + 7.5f + (float) Math.cos(angle) * radius;
            path[i * 2 + 1] = y + 7.5f + (float) Math.sin(angle) * radius;
        }
        SmoothGeometry.closedStroke(g, path, 1.15f, color);
    }

    static void cube(GuiGraphics g, int x, int y, int size, int color, double outlineAlpha, double fillAlpha) {
        int dx = size / 3, dy = size / 4;
        round(g, x, y + dy, size, size, 1, alpha(color, fillAlpha));
        int c = alpha(color, outlineAlpha);
        outline(g, x, y + dy, size, size, 1, c);
        outline(g, x + dx, y, size, size, 1, alpha(color, outlineAlpha * 0.45));
        line(g, x, y + dy, x + dx, y, c);
        line(g, x + size - 1, y + dy, x + size + dx - 1, y, c);
        line(g, x, y + size + dy - 1, x + dx, y + size - 1, c);
        line(g, x + size - 1, y + size + dy - 1, x + size + dx - 1, y + size - 1, c);
    }
}
