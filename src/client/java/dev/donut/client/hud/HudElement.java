package dev.donut.client.hud;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

public interface HudElement {
    String id();
    String displayName();
    boolean isEnabled();
    void setEnabled(boolean enabled);
    int getX();
    int getY();
    void setPosition(int x, int y);
    int getWidth(Font font);
    int getHeight(Font font);
    void render(GuiGraphics graphics, Font font, int screenWidth, int screenHeight);
    
    default boolean isHovered(double mouseX, double mouseY, Font font) {
        int x = getX();
        int y = getY();
        return mouseX >= x && mouseX <= x + getWidth(font) && mouseY >= y && mouseY <= y + getHeight(font);
    }
}
