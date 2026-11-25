package dev.donut.client.gui;

import dev.donut.client.config.CrystalAuraSettings;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Edits the player names to protect when Skip friends is active. */
public final class CrystalAuraFriendsScreen extends Screen {
    private final Screen parent;
    private final CrystalAuraSettings settings;
    private final Runnable save;
    private String draft;

    public CrystalAuraFriendsScreen(Screen parent, CrystalAuraSettings settings, Runnable save) {
        super(Component.literal("Combat friends"));
        this.parent = parent;
        this.settings = settings;
        this.save = save;
        draft = settings.friends;
    }

    @Override
    protected void init() {
        int w = Math.min(340, width - 32);
        int x = (width - w) / 2;
        int y = height / 2 - 15;
        EditBox field = addRenderableWidget(new EditBox(font, x, y, w, 22, Component.literal("Friend usernames")));
        field.setMaxLength(CrystalAuraSettings.MAX_FRIENDS_TEXT_LENGTH);
        field.setValue(draft);
        field.setHint(Component.literal("Separate usernames with commas"));
        field.setResponder(value -> draft = value);
        setInitialFocus(field);

        addRenderableWidget(Button.builder(Component.literal("Apply friends"), button -> {
            settings.setFriends(draft);
            save.run();
            onClose();
        }).bounds(x, y + 34, (w - 8) / 2, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Cancel"), button -> onClose())
                .bounds(x + (w + 8) / 2, y + 34, (w - 8) / 2, 20).build());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        graphics.fill(0, 0, width, height, 0xEE101722);
        graphics.drawCenteredString(font, title, width / 2, height / 2 - 60, 0xFFF0F5FA);
        graphics.drawCenteredString(font, "Scoreboard teammates are included automatically.",
                width / 2, height / 2 - 39, 0xFFB5C7D8);
        super.render(graphics, mouseX, mouseY, delta);
    }

    @Override
    public void onClose() { minecraft.setScreen(parent); }

    @Override
    public boolean isPauseScreen() { return false; }
}
