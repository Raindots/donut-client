package dev.donut.client.gui;

import dev.donut.client.config.ClientConfig;
import dev.donut.client.config.StaffDetectorSettings;
import dev.donut.client.staff.StaffDetectorController;
import dev.donut.client.staff.StaffDetectorEngine;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/** Server-scoped roster editor and bounded evidence history, using native accessible widgets. */
public final class StaffRosterScreen extends Screen {
    private final Screen parent;
    private final ClientConfig config;
    private final Runnable save;
    private final String server;
    private EditBox input;
    private Button remove;
    private String draft = "", message = "Usernames bind to UUIDs when their server profile is observed.";
    private StaffDetectorSettings.Member selected;
    private boolean evidence;
    private int offset;

    public StaffRosterScreen(Screen parent, ClientConfig config, Runnable save) {
        super(Component.literal("Staff list / evidence"));
        this.parent = parent; this.config = config; this.save = save;
        server = StaffDetectorController.serverKey(Minecraft.getInstance());
    }
    private StaffDetectorController controller() {
        var current = StaffDetectorController.get();
        return current != null && current.uses(config) ? current : null;
    }
    private List<StaffDetectorSettings.Member> members() { return config.staffDetector.roster(server); }
    private List<StaffDetectorEngine.Evidence> events() { return controller() == null ? List.of() : controller().history(); }
    private int rows() { return Math.max(1, (height - 158) / 46); }
    private int total() { return evidence ? events().size() : members().size(); }
    private int maxOffset() { return Math.max(0, total() - rows()); }
    @Override protected void init() {
        input = addRenderableWidget(new EditBox(font, 14, 44, Math.max(100, width - 116), 20, Component.literal("Staff username or UUID")));
        input.setMaxLength(36); input.setValue(draft); input.setHint(Component.literal("Minecraft username or full UUID"));
        input.setResponder(value -> draft = value); input.visible = !evidence;
        var add = addRenderableWidget(Button.builder(Component.literal("Add staff"), button -> add())
                .bounds(width - 96, 44, 82, 20).build());
        add.visible = !evidence; add.active = minecraft.level != null;
        int bw = Math.max(50, Math.min(116, (width - 40) / 4));
        int x = (width - (bw * 4 + 12)) / 2;
        addRenderableWidget(Button.builder(Component.literal("Previous"), button -> offset = Math.max(0, offset - rows()))
                .bounds(x, height - 58, bw, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Next"), button -> offset = Math.min(maxOffset(), offset + rows()))
                .bounds(x + bw + 4, height - 58, bw, 20).build());
        addRenderableWidget(Button.builder(Component.literal(evidence ? "Staff list" : "Evidence"), button -> {
            evidence = !evidence; offset = 0; selected = null; rebuildWidgets();
        }).bounds(x + (bw + 4) * 2, height - 58, bw, 20).build());
        remove = addRenderableWidget(Button.builder(Component.literal("Remove selected"), button -> {
            if (selected != null) { config.staffDetector.remove(server, selected); save.run(); selected = null; message = "Staff entry removed."; }
        }).bounds(x + (bw + 4) * 3, height - 58, bw, 20).build());
        remove.active = false; remove.visible = !evidence;
        addRenderableWidget(Button.builder(Component.literal("Done"), button -> onClose())
                .bounds(width / 2 - 50, height - 26, 100, 20).build());
        if (!evidence) setInitialFocus(input);
    }
    private void add() {
        if (minecraft.level == null) { message = "Join a world to edit that server's staff list."; return; }
        try {
            boolean added = config.staffDetector.add(server, draft);
            if (added) { save.run(); input.setValue(""); message = "Added. Identity resolves from received server profiles."; }
            else message = "That staff entry is already saved.";
        } catch (IllegalArgumentException failure) { message = failure.getMessage(); }
    }
    @Override public void render(GuiGraphics g, int mx, int my, float delta) {
        g.fill(0, 0, width, height, 0xF0101722);
        g.drawCenteredString(font, evidence ? "Staff evidence history" : "Staff list for this server", width / 2, 10, 0xFFE5EFF9);
        g.drawCenteredString(font, font.plainSubstrByWidth(server, width - 28), width / 2, 27, 0xFFAAC2D5);
        offset = Math.clamp(offset, 0, maxOffset());
        int y = 74;
        boolean privacy = config.nameHider.enabled;
        var views = controller() == null ? List.<StaffDetectorEngine.View>of() : controller().views();
        for (int index = offset; index < Math.min(total(), offset + rows()); index++) {
            boolean chosen = !evidence && members().get(index) == selected;
            g.fill(14, y, width - 14, y + 43, chosen ? 0xFF304553 : 0xFF1C2937);
            String name, detail, observation;
            if (evidence) {
                var event = events().get(index);
                name = (privacy ? "Staff member" : event.name()) + " | " + event.kind();
                long age = Math.max(0, (System.nanoTime() - event.timeNanos()) / 1_000_000_000L);
                detail = (event.possible() ? "Possible / uncertain" : "Direct observation") + " | " + age + "s ago";
                observation = event.detail();
            } else {
                var member = members().get(index);
                name = privacy ? "Staff member" : member.label();
                detail = member.id() == null ? "Awaiting profile: identity unverified" : privacy ? "UUID bound" : member.uuid;
                var view = views.stream().filter(value -> value.id().equals(member.id())).findFirst().orElse(null);
                observation = view == null ? "Presence unknown" : view.description();
            }
            g.drawString(font, font.plainSubstrByWidth(name, width - 48), 22, y + 5, 0xFFE2EDF6, false);
            g.drawString(font, font.plainSubstrByWidth(detail, width - 48), 22, y + 17, 0xFF9CB9D1, false);
            g.drawString(font, font.plainSubstrByWidth(observation, width - 48), 22, y + 29, 0xFFD5CAA4, false);
            y += 46;
        }
        if (total() == 0) g.drawCenteredString(font, evidence ? "No observations recorded in this session." : "Add the staff you want to track on this server.", width / 2, 84, 0xFFAAC2D5);
        String footer = evidence ? "History records observations. It cannot establish who is watching you." : message;
        g.drawString(font, font.plainSubstrByWidth(footer, width - 28), 14, height - 85, 0xFFB4C7D9, false);
        String status = controller() == null ? "Enable Staff Detector in this world to observe presence." : controller().diagnostics();
        g.drawString(font, font.plainSubstrByWidth(status, width - 28), 14, height - 73, 0xFF9AAFC3, false);
        remove.active = !evidence && selected != null && members().contains(selected);
        super.render(g, mx, my, delta);
    }
    @Override public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        if (super.mouseClicked(event, doubled)) return true;
        if (!evidence && event.button() == 0 && event.x() >= 14 && event.x() < width - 14 && event.y() >= 74 && event.y() < 74 + rows() * 46) {
            int index = offset + (int) ((event.y() - 74) / 46);
            if (index < members().size()) { selected = members().get(index); remove.active = true; return true; }
        }
        return false;
    }
    @Override public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        offset = Math.clamp(offset - (int) Math.signum(vertical), 0, maxOffset()); return true;
    }
    @Override public void onClose() { minecraft.setScreen(parent); }
    @Override public boolean isPauseScreen() { return false; }
}
