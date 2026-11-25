package dev.donut.client.gui;

import dev.donut.client.config.ClientConfig;
import dev.donut.client.config.ConfigProfileStore;
import dev.donut.client.config.ConfigProfileStore.Profile;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

import static dev.donut.client.gui.UiPaint.alpha;
import static dev.donut.client.gui.UiPaint.blend;
import static dev.donut.client.gui.UiPaint.outline;
import static dev.donut.client.gui.UiPaint.round;
import static dev.donut.client.gui.WorkspacePaint.smallText;
import static dev.donut.client.gui.WorkspacePaint.strong;
import static dev.donut.client.gui.WorkspacePaint.text;

/** The local profile editor inside the Donut workspace. */
final class ConfigProfilesPanel {
    private static final int TEXT = 0xFFF0F4F8, MUTED = 0xFFADB9C8, SUBTLE = 0xFF92A3B5;
    private static final int SURFACE = 0xFF1D2230, FIELD = 0xFF151A24, BORDER = 0xFF394455;
    private static final int TEAL = 0x82E2D1, DANGER = 0xFFFA94A5;
    private static final int[] SWATCHES = {0x56DCCB, 0x6FA2F1, 0xB695ED, 0xED91B2, 0xEFB478, 0xA8CD7C};
    private static final Comparator<Profile> BY_NAME = Comparator.comparing(Profile::name, String.CASE_INSENSITIVE_ORDER);
    private static final String DEFAULT_STATUS = "Choose Import or drop a shared config file here.";

    @FunctionalInterface
    interface HitRegistry {
        void hit(String id, int x, int y, int width, int height, Runnable action);
    }

    @FunctionalInterface
    interface ProfileLoader {
        void load(Profile profile) throws IOException;
    }

    private final ConfigProfileStore store;
    private final ClientConfig live;
    private final Supplier<Map<String, String>> keybinds;
    private final ProfileLoader loader;
    private List<Profile> profiles = List.of();
    private Profile selected;
    private UUID lastLoaded;
    private boolean checkedStorage;
    private boolean confirmingDelete;
    private boolean error;
    private String status = DEFAULT_STATUS;
    private String draftName = "", draftDescription = "", draftHex = hex(SWATCHES[0]);
    private EditBox searchBox, nameBox, hexBox;
    private MultiLineEditBox descriptionBox;
    private AbstractWidget focused, mouseField;
    private int listX, listY, listW, listH, listOffset, listLimit, rowHeight;
    private double mouseX, mouseY;
    private float fontScale;
    private boolean interactive;

    ConfigProfilesPanel(ConfigProfileStore store, ClientConfig live, Supplier<Map<String, String>> keybinds, ProfileLoader loader) {
        this.store = store;
        this.live = live;
        this.keybinds = keybinds;
        this.loader = loader;
    }

    void activate() {
        refresh(false);
    }

    void deactivate() {
        focus(null);
        mouseField = null;
        confirmingDelete = false;
        interactive = false;
    }

    void render(GuiGraphics g, Font font, int x, int y, int width, int height,
                double mouseX, double mouseY, float fontScale, HitRegistry hits) {
        this.mouseX = mouseX;
        this.mouseY = mouseY;
        this.fontScale = fontScale;
        interactive = true;
        boolean compact = height < 320;
        int railW = Math.clamp(width / 3, 130, 292), gap = 10;
        int editorW = width - railW - gap;
        int padding = compact ? 10 : 15;
        ensureFields(font, Math.max(40, editorW - 2 * padding - 8));
        int headerH = compact ? 48 : 62;
        int headerButton = compact ? 89 : 114;
        int buttonH = compact ? 24 : 30;
        int newX = x + width - headerButton - 12;
        int refreshW = compact ? 57 : 76;
        int refreshX = newX - refreshW - 7;
        int importW = compact ? 59 : 76;
        int importX = refreshX - importW - 7;
        int folderW = compact ? 79 : 96;
        int folderX = importX - folderW - 7;
        int headerButtonsY = y + (compact ? 5 : 8);
        int accent = blend(live.accentColor, TEAL, .65);

        round(g, x, y, width, headerH, 10, blend(SURFACE, accent, .10));
        outline(g, x, y, width, headerH, 10, alpha(accent, .29));
        UiPaint.chest(g, x + 15, y + (compact ? 11 : 15), accent);
        WorkspacePaint.title(g, font, "Configs", x + 41, y + (compact ? 6 : 9), TEXT);
        smallText(g, font, clipStatus(font, status, width - 30), x + 15,
                y + (compact ? 32 : 43), error ? DANGER : MUTED);
        button(g, font, hits, "configsOpenFolder", "Open folder", folderX, headerButtonsY, folderW, buttonH,
                0, this::openFolder);
        button(g, font, hits, "configsImport", "Import", importX, headerButtonsY, importW, buttonH,
                0, this::chooseImportFile);
        button(g, font, hits, "configsRefresh", "Refresh", refreshX, headerButtonsY, refreshW, buttonH,
                0, () -> refresh(true));
        button(g, font, hits, "configsNew", "+ New profile", newX, headerButtonsY, headerButton, buttonH,
                accent, this::beginNew);

        int panelY = y + headerH + 10, panelH = height - headerH - 10;
        drawList(g, font, hits, x, panelY, railW, panelH, compact, accent);
        drawEditor(g, font, hits, x + railW + gap, panelY, editorW, panelH, compact, accent);
    }

    private void drawList(GuiGraphics g, Font font, HitRegistry hits, int x, int y, int w, int h, boolean compact, int accent) {
        panel(g, x, y, w, h, BORDER);
        int padding = compact ? 9 : 13;
        strong(g, font, "Saved profiles", x + padding, y + padding, TEXT);
        String count = Integer.toString(profiles.size());
        smallText(g, font, count, x + w - padding - WorkspacePaint.smallWidth(font, count), y + padding + 1, SUBTLE);

        int searchY = y + (compact ? 29 : 36), searchH = compact ? 23 : 29;
        int searchX = x + padding, searchW = w - padding * 2;
        field(g, hits, "configsSearch", searchBox, searchX, searchY, searchW, searchH, accent, 21);
        UiPaint.search(g, searchX + 6, searchY + (searchH - 9) / 2, SUBTLE);

        listX = searchX;
        listY = searchY + searchH + 8;
        listW = searchW;
        listH = y + h - padding - listY;
        rowHeight = compact ? 36 : 53;
        String query = searchBox.getValue().strip().toLowerCase(Locale.ROOT);
        List<Profile> visible = query.isEmpty() ? profiles : profiles.stream().filter(profile ->
                profile.name().toLowerCase(Locale.ROOT).contains(query)
                        || profile.description().toLowerCase(Locale.ROOT).contains(query)).toList();
        listLimit = Math.max(0, visible.size() * rowHeight - listH);
        listOffset = Math.clamp(listOffset, 0, listLimit);
        if (visible.isEmpty()) {
            String empty = query.isEmpty() ? "No saved profiles" : "No matching profiles";
            smallText(g, font, clipStatus(font, empty, listW - 8), listX + 4, listY + 9, SUBTLE);
            return;
        }

        int rowW = listW - (listLimit > 0 ? 6 : 0);
        g.enableScissor(listX, listY, listX + listW, listY + listH);
        try {
            int first = listOffset / rowHeight;
            for (int i = first; i < visible.size(); i++) {
                int rowY = listY + i * rowHeight - listOffset;
                if (rowY >= listY + listH) break;
                Profile profile = visible.get(i);
                drawProfile(g, font, hits, profile, listX, rowY, rowW, rowHeight - 5, compact);
            }
        } finally {
            g.disableScissor();
        }
        if (listLimit > 0) {
            int bar = Math.max(13, (int) ((long) listH * listH / (listLimit + listH)));
            int top = listY + listOffset * (listH - bar) / listLimit;
            round(g, listX + listW - 3, listY, 3, listH, 2, FIELD);
            round(g, listX + listW - 3, top, 3, bar, 2, alpha(accent, .46));
        }
    }

    private void drawProfile(GuiGraphics g, Font font, HitRegistry hits, Profile profile,
                             int x, int y, int w, int h, boolean compact) {
        boolean chosen = selected != null && selected.id().equals(profile.id());
        boolean loaded = profile.id().equals(lastLoaded);
        int color = profile.color();
        double tint = chosen ? .17 : inside(x, y, w, h) ? .12 : .05;
        round(g, x, y, w, h, 6, blend(FIELD, color, tint));
        outline(g, x, y, w, h, 6, chosen ? alpha(color, .62) : BORDER);
        round(g, x + 5, y + 6, 2, h - 12, 1, alpha(color, .96));
        int reserved = loaded ? 19 : 0;
        strong(g, font, clip(font, profile.name(), w - 21 - reserved), x + 13, y + (compact ? 4 : 6), TEXT);
        if (loaded) round(g, x + w - 12, y + (compact ? 7 : 10), 5, 5, 3, 0xFF9CE2CB);
        String summary = loaded ? "Last loaded" : profile.description().lines().filter(value -> !value.isBlank()).findFirst().orElse("");
        smallText(g, font, clipStatus(font, summary, w - 20), x + 13, y + (compact ? 17 : 26), MUTED);

        int top = Math.max(y, listY), bottom = Math.min(y + h, listY + listH);
        hits.hit("configsProfile" + profile.id(), x, top, w, bottom - top, () -> select(profile));
    }

    private void drawEditor(GuiGraphics g, Font font, HitRegistry hits, int x, int y, int w, int h,
                            boolean compact, int accent) {
        int cardColor = draftColor(false);
        panel(g, x, y, w, h, blend(BORDER, cardColor, .25));
        int padding = compact ? 10 : 15;
        int left = x + padding, right = x + w - padding, innerW = right - left;
        int buttonH = compact ? 22 : 29, buttonGap = compact ? 6 : 8;
        int action = y + h - padding - buttonH;
        int primary = action - buttonH - buttonGap;
        int nameY = y + (compact ? 41 : 52), nameH = compact ? 22 : 29;
        int descY = nameY + nameH + (compact ? 19 : 24);
        int paletteH = compact ? 21 : 27;
        int paletteY = primary - paletteH - (compact ? 8 : 13);
        int descH = Math.max(24, paletteY - descY - 5);

        boolean changed = selected != null && hasChanges();
        String heading = selected == null ? "New profile" : "Profile details";
        strong(g, font, heading, left, y + padding, TEXT);
        if (changed) {
            String hint = "Unsaved details";
            smallText(g, font, hint, right - WorkspacePaint.smallWidth(font, hint), y + padding + 2, accent);
        }
        smallText(g, font, "Name", left, nameY - (compact ? 13 : 16), MUTED);
        field(g, hits, "configsName", nameBox, left, nameY, innerW, nameH, accent, 7);
        smallText(g, font, "Description", left, descY - (compact ? 13 : 16), MUTED);
        field(g, hits, "configsDescription", descriptionBox, left, descY, innerW, descH, accent, 4);
        drawPalette(g, font, hits, left, right, paletteY, paletteH, cardColor, accent, compact);

        if (selected == null) {
            button(g, font, hits, "configsSaveNew", "Save current config", left, action, innerW, buttonH, accent,
                    this::saveNew);
            smallText(g, font, "Captures your current settings and keybinds.", left, primary + (compact ? 5 : 7), SUBTLE);
            return;
        }

        int third = (innerW - 2 * buttonGap) / 3;
        button(g, font, hits, "configsLoad", "Load", left, primary, third, buttonH, accent, this::load);
        button(g, font, hits, "configsSaveDetails", "Save details", left + third + buttonGap, primary, third, buttonH,
                0, () -> update(false));
        button(g, font, hits, "configsUpdate", "Update config", left + (third + buttonGap) * 2, primary,
                innerW - 2 * (third + buttonGap), buttonH, 0, () -> update(true));

        int half = (innerW - buttonGap) / 2;
        if (confirmingDelete) {
            button(g, font, hits, "configsCancelDelete", "Cancel", left, action, half, buttonH, 0,
                    () -> confirmingDelete = false);
            button(g, font, hits, "configsConfirmDelete", "Confirm delete", left + half + buttonGap, action,
                    innerW - half - buttonGap, buttonH, DANGER, this::delete);
        } else {
            button(g, font, hits, "configsExport", "Export", left, action, third, buttonH, 0, this::exportProfile);
            button(g, font, hits, "configsDuplicate", "Duplicate", left + third + buttonGap, action, third, buttonH,
                    0, this::duplicate);
            button(g, font, hits, "configsDelete", "Delete", left + (third + buttonGap) * 2, action,
                    innerW - 2 * (third + buttonGap), buttonH, 0, () -> confirmingDelete = true);
        }
    }

    private void drawPalette(GuiGraphics g, Font font, HitRegistry hits, int x, int right, int y, int h,
                             int color, int accent, boolean compact) {
        int labelW = WorkspacePaint.smallWidth(font, "Accent");
        int hexW = compact ? 53 : 71;
        smallText(g, font, "Accent", x, y + (h - (compact ? 9 : 13)) / 2, MUTED);
        field(g, hits, "configsColorHex", hexBox, right - hexW, y, hexW, h, accent, 6);
        int left = x + labelW + 8;
        int item = Math.min(compact ? 21 : 30, (right - hexW - 8 - left) / SWATCHES.length);
        int swatchSize = Math.min(h - 4, item - 3);
        for (int i = 0; i < SWATCHES.length; i++) {
            int swatch = SWATCHES[i];
            int sx = left + i * item, sy = y + (h - swatchSize) / 2;
            round(g, sx, sy, swatchSize, swatchSize, 4, alpha(swatch, .93));
            if (color == swatch || inside(sx, sy, swatchSize, swatchSize)) {
                outline(g, sx - 1, sy - 1, swatchSize + 2, swatchSize + 2, 5, TEXT);
            }
            hits.hit("configsColor" + i, sx, y, swatchSize, h, () -> hexBox.setValue(hex(swatch)));
        }
    }

    private void ensureFields(Font font, int descriptionWidth) {
        if (searchBox == null) {
            searchBox = edit(font, "Search profiles", 64);
            searchBox.setResponder(value -> listOffset = 0);
            nameBox = edit(font, "Profile name", ConfigProfileStore.MAX_NAME_LENGTH);
            nameBox.setValue(draftName);
            nameBox.setResponder(value -> draftName = value);
            hexBox = edit(font, "#RRGGBB", 7);
            hexBox.setFilter(value -> value.matches("(?i)#?[0-9a-f]{0,6}"));
            hexBox.setValue(draftHex);
            hexBox.setResponder(value -> draftHex = value);
        }
        // The vanilla text area fixes its line-wrap width at construction.
        if (descriptionBox == null || descriptionBox.getWidth() != descriptionWidth) {
            MultiLineEditBox previous = descriptionBox;
            descriptionBox = MultiLineEditBox.builder().setShowBackground(false).setShowDecorations(false)
                    .setTextColor(TEXT).setTextShadow(false).setPlaceholder(Component.literal("Add a description"))
                    .build(font, descriptionWidth, 46, Component.literal("Profile description"));
            descriptionBox.setCharacterLimit(ConfigProfileStore.MAX_DESCRIPTION_LENGTH);
            descriptionBox.setValue(draftDescription);
            descriptionBox.setValueListener(value -> draftDescription = value);
            if (previous != null && focused == previous) focus(descriptionBox);
            if (mouseField == previous) mouseField = null;
        }
    }

    private static EditBox edit(Font font, String hint, int limit) {
        EditBox box = new EditBox(font, 0, 0, 100, 20, Component.literal(hint));
        box.setBordered(false);
        box.setTextShadow(false);
        box.setTextColor(TEXT);
        box.setHint(Component.literal(hint).withColor(SUBTLE & 0xFFFFFF));
        box.setMaxLength(limit);
        return box;
    }

    private void field(GuiGraphics g, HitRegistry hits, String id, AbstractWidget field,
                       int x, int y, int w, int h, int accent, int inset) {
        round(g, x, y, w, h, 5, FIELD);
        outline(g, x, y, w, h, 5, field == focused ? alpha(accent, .85) : BORDER);
        boolean multiline = field instanceof MultiLineEditBox;
        int fieldY = y + (multiline ? 1 : (h - 9) / 2);
        int innerW = w - inset - (multiline ? 4 : 5), innerH = y + h - fieldY - 1;
        if (field.getX() != x + inset) field.setX(x + inset);
        if (field.getY() != fieldY) field.setY(fieldY);
        if (field.getWidth() != innerW) field.setWidth(innerW);
        if (field.getHeight() != innerH) {
            field.setHeight(innerH);
            if (field instanceof MultiLineEditBox area) area.refreshScrollAmount();
        }
        field.setAlpha((UiPaint.withOpacity(0xFFFFFFFF) >>> 24) / 255f);
        field.render(g, (int) mouseX, (int) mouseY, 0f);
        hits.hit(id, x, y, w, h, () -> focus(field));
    }

    private void button(GuiGraphics g, Font font, HitRegistry hits, String id, String label,
                        int x, int y, int w, int h, int accent, Runnable action) {
        boolean hover = inside(x, y, w, h);
        int fill = accent == 0 ? (hover ? 0xFF354252 : 0xFF293341) : blend(accent, 0xFFFFFF, hover ? .17 : .02);
        round(g, x, y, w, h, 6, fill);
        if (accent == 0) outline(g, x, y, w, h, 6, BORDER);
        String shown = trimToWidth(label, w - 7, value -> WorkspacePaint.width(font, value));
        int labelX = x + (w - WorkspacePaint.width(font, shown)) / 2;
        int labelY = y + (h - Math.round(16 * fontScale)) / 2;
        text(g, font, shown, labelX, labelY,
                accent == 0 ? TEXT : 0xFF132129);
        hits.hit(id, x, y, w, h, () -> {
            focus(null);
            action.run();
        });
    }

    private static void panel(GuiGraphics g, int x, int y, int w, int h, int edge) {
        round(g, x, y, w, h, 9, SURFACE);
        outline(g, x, y, w, h, 9, edge);
    }

    boolean mouseClicked(double x, double y, MouseButtonEvent event, boolean doubled) {
        if (!interactive || event.button() != GLFW.GLFW_MOUSE_BUTTON_LEFT) return false;
        for (AbstractWidget field : inputFields()) {
            if (!field.isMouseOver(x, y)) continue;
            focus(field);
            mouseField = field;
            field.mouseClicked(new MouseButtonEvent(x, y, event.buttonInfo()), doubled);
            return true;
        }
        focus(null);
        return false;
    }

    boolean mouseDragged(double x, double y, MouseButtonEvent event, double dx, double dy) {
        if (mouseField == null) return false;
        return mouseField.mouseDragged(new MouseButtonEvent(x, y, event.buttonInfo()), dx, dy);
    }

    boolean mouseReleased(double x, double y, MouseButtonEvent event) {
        if (mouseField == null) return false;
        AbstractWidget released = mouseField;
        mouseField = null;
        return released.mouseReleased(new MouseButtonEvent(x, y, event.buttonInfo()));
    }

    boolean mouseScrolled(double x, double y, double vertical) {
        if (!interactive) return false;
        if (descriptionBox.isMouseOver(x, y)) return descriptionBox.mouseScrolled(x, y, 0, vertical);
        if (x < listX || x >= listX + listW || y < listY || y >= listY + listH) return false;
        listOffset = Math.clamp(listOffset - (int) Math.round(vertical * rowHeight), 0, listLimit);
        return true;
    }

    boolean keyPressed(KeyEvent event) {
        if (!interactive) return false;
        if (event.key() == GLFW.GLFW_KEY_ESCAPE && (focused != null || confirmingDelete)) {
            if (focused != null) focus(null);
            else confirmingDelete = false;
            return true;
        }
        if (event.key() == GLFW.GLFW_KEY_F && event.hasControlDown()) {
            focus(searchBox);
            searchBox.moveCursorToEnd(false);
            searchBox.setHighlightPos(0);
            return true;
        }
        if (focused == null) return false;
        if (event.key() == GLFW.GLFW_KEY_TAB) {
            List<AbstractWidget> fields = inputFields();
            int index = fields.indexOf(focused) + ((event.modifiers() & GLFW.GLFW_MOD_SHIFT) != 0 ? -1 : 1);
            if (index < 0 || index >= fields.size()) {
                focus(null);
                return false;
            }
            focus(fields.get(index));
            return true;
        }
        if (focused != descriptionBox && (event.key() == GLFW.GLFW_KEY_ENTER || event.key() == GLFW.GLFW_KEY_KP_ENTER)) {
            focus(null);
            return true;
        }
        focused.keyPressed(event);
        return true;
    }

    boolean charTyped(CharacterEvent event) {
        if (focused == null) return false;
        focused.charTyped(event);
        return true;
    }

    private List<AbstractWidget> inputFields() {
        return List.of(searchBox, nameBox, descriptionBox, hexBox);
    }

    private void focus(AbstractWidget field) {
        if (focused != field) {
            if (focused != null) focused.setFocused(false);
            focused = field;
            if (field != null) field.setFocused(true);
        }
    }

    private void refresh(boolean manual) {
        try {
            boolean retainDraft = selected != null && hasChanges();
            UUID previous = selected == null ? null : selected.id();
            profiles = store.list().stream().sorted(BY_NAME).toList();
            Profile existing = previous == null ? null : profiles.stream()
                    .filter(profile -> profile.id().equals(previous)).findFirst().orElse(null);
            if (!checkedStorage || previous != null && existing == null) select(profiles.isEmpty() ? null : profiles.getFirst());
            else if (existing != null && !retainDraft) select(existing);
            else if (existing != null) selected = existing;
            checkedStorage = true;
            message(manual ? "Refreshed local profiles." : DEFAULT_STATUS, false);
        } catch (IOException | IllegalArgumentException failure) {
            failure(failure);
        }
    }

    private void beginNew() {
        select(null);
        message(DEFAULT_STATUS, false);
        focus(nameBox);
    }

    private void openFolder() {
        try {
            Path directory = Files.createDirectories(store.path().getParent());
            Util.getPlatform().openPath(directory);
        } catch (IOException | SecurityException failure) {
            message("Could not open the Donut config folder. Check that it is accessible.", true);
        }
    }

    private void chooseImportFile() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var filters = stack.mallocPointer(1).put(stack.UTF8("*.json")).flip();
            String chosen = TinyFileDialogs.tinyfd_openFileDialog("Import Donut config",
                    store.path().getParent() + File.separator, filters, "Donut config (*.json)", false);
            if (chosen != null) importFiles(List.of(Path.of(chosen)));
        } catch (RuntimeException | LinkageError failure) {
            message("Could not open the file picker. Drop a shared config file here to import it.", true);
        }
    }

    void importFiles(List<Path> files) {
        if (files.isEmpty()) return;
        var imported = new ArrayList<Profile>();
        Exception firstFailure = null;
        for (Path file : files) {
            try {
                imported.addAll(store.importProfiles(file));
            } catch (IOException | IllegalArgumentException failure) {
                if (firstFailure == null) firstFailure = failure;
            }
        }
        if (imported.isEmpty()) {
            if (firstFailure == null) message("No new profiles to import.", false);
            else failure(firstFailure);
            return;
        }
        focus(null);
        remember(imported);
        String result = imported.size() == 1 ? "Imported " + imported.getFirst().name() + "."
                : "Imported " + imported.size() + " profiles.";
        if (firstFailure != null) result += " " + firstFailure.getMessage();
        message(result, firstFailure != null);
    }

    private void exportProfile() {
        try {
            Path file = store.exportProfile(selected.id());
            message("Exported to exports/" + file.getFileName(), false);
        } catch (IOException | IllegalArgumentException failure) {
            failure(failure);
        }
    }

    private void select(Profile profile) {
        selected = profile;
        confirmingDelete = false;
        draftName = profile == null ? "" : profile.name();
        draftDescription = profile == null ? "" : profile.description();
        draftHex = hex(profile == null ? live.accentColor : profile.color());
        if (nameBox != null) {
            nameBox.setValue(draftName);
            descriptionBox.setValue(draftDescription);
            hexBox.setValue(draftHex);
        }
    }

    private void saveNew() {
        try {
            Profile profile = store.create(draftName, draftDescription, draftColor(true), live, keybinds.get());
            remember(List.of(profile));
            message("Saved " + profile.name() + ".", false);
        } catch (IOException | IllegalArgumentException failure) {
            failure(failure);
        }
    }

    private void load() {
        confirmingDelete = false;
        try {
            loader.load(selected);
            lastLoaded = selected.id();
            message("Loaded " + selected.name() + ".", false);
        } catch (IOException | IllegalArgumentException failure) {
            failure(failure);
        }
    }

    private void update(boolean snapshot) {
        confirmingDelete = false;
        try {
            Profile profile = store.update(selected.id(), draftName, draftDescription, draftColor(true),
                    snapshot ? live : null, snapshot ? keybinds.get() : null);
            remember(List.of(profile));
            message(snapshot ? "Updated " + profile.name() + " from current settings." : "Saved profile details.", false);
        } catch (IOException | IllegalArgumentException failure) {
            failure(failure);
        }
    }

    private void duplicate() {
        try {
            Profile original = selected;
            String name = copyName(original.name(), " copy");
            for (int number = 2; hasName(name); number++) name = copyName(original.name(), " copy " + number);
            Profile profile = store.create(name, original.description(), original.color(), original.config(), original.keybinds());
            remember(List.of(profile));
            message("Created " + profile.name() + ".", false);
        } catch (IOException | IllegalArgumentException failure) {
            failure(failure);
        }
    }

    private static String copyName(String name, String suffix) {
        int end = Math.min(name.length(), ConfigProfileStore.MAX_NAME_LENGTH - suffix.length());
        if (end < name.length() && Character.isHighSurrogate(name.charAt(end - 1))) end--;
        return name.substring(0, end).stripTrailing() + suffix;
    }

    private boolean hasName(String name) {
        return profiles.stream().anyMatch(profile -> profile.name().equalsIgnoreCase(name));
    }

    private void delete() {
        try {
            Profile old = selected;
            store.delete(old.id());
            profiles = profiles.stream().filter(profile -> !profile.id().equals(old.id())).toList();
            if (old.id().equals(lastLoaded)) lastLoaded = null;
            select(profiles.isEmpty() ? null : profiles.getFirst());
            message("Deleted " + old.name() + ".", false);
        } catch (IOException | IllegalArgumentException failure) {
            failure(failure);
        }
    }

    private void remember(List<Profile> additions) {
        Set<UUID> ids = new HashSet<>();
        for (Profile profile : additions) ids.add(profile.id());
        List<Profile> refreshed = new ArrayList<>(profiles);
        refreshed.removeIf(previous -> ids.contains(previous.id()));
        refreshed.addAll(additions);
        refreshed.sort(BY_NAME);
        profiles = List.copyOf(refreshed);
        Profile first = additions.getFirst();
        select(first);
        if (searchBox != null) searchBox.setValue("");
        listOffset = Math.max(0, profiles.indexOf(first) * rowHeight - listH + rowHeight);
    }

    private boolean hasChanges() {
        return !draftName.equals(selected.name()) || !draftDescription.equals(selected.description())
                || !draftHex.equalsIgnoreCase(hex(selected.color()));
    }

    private int draftColor(boolean validate) {
        String color = draftHex.startsWith("#") ? draftHex.substring(1) : draftHex;
        if (color.length() == 6) return Integer.parseInt(color, 16);
        if (validate) throw new IllegalArgumentException("Enter an accent in #RRGGBB format.");
        return selected == null ? live.accentColor : selected.color();
    }

    private static String hex(int color) {
        return String.format(Locale.ROOT, "#%06X", color & 0xFFFFFF);
    }

    private void failure(Exception failure) {
        message(failure.getMessage() == null ? "Could not access the local profile file." : failure.getMessage(), true);
    }

    private void message(String value, boolean failed) {
        status = value;
        error = failed;
    }

    private boolean inside(int x, int y, int w, int h) {
        return mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
    }

    private static String clip(Font font, String value, int width) {
        return trimToWidth(value, width, text -> WorkspacePaint.strongWidth(font, text));
    }

    private static String clipStatus(Font font, String value, int width) {
        return trimToWidth(value, width, text -> WorkspacePaint.smallWidth(font, text));
    }

    private static String trimToWidth(String value, int width, ToIntFunction<String> measure) {
        if (measure.applyAsInt(value) <= width) return value;
        while (!value.isEmpty()) {
            value = value.substring(0, value.offsetByCodePoints(value.length(), -1));
            if (measure.applyAsInt(value + "…") <= width) return value + "…";
        }
        return "";
    }
}
