package dev.donut.client.gui;

import dev.donut.client.autobuild.AutoBuilderController;
import dev.donut.client.autobuild.LitematicLoader;
import dev.donut.client.autobuild.MaterialTracker;
import dev.donut.client.autobuild.PlacementTransform;
import dev.donut.client.autobuild.Schematic;
import dev.donut.client.config.ClientConfig;
import dev.donut.client.input.ModuleKeybinds;
import net.minecraft.util.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static dev.donut.client.gui.UiPaint.*;

/** Animated schematic library, positioning controls and live materials modal. */
public final class AutoBuilderScreen extends Screen {
    private static final int W = 840, H = 510;
    private static final int TEXT = 0xFFECECF3, MUTED = 0xFFA3A3B2, DIM = 0xFF6C6C80;
    private static final int PANEL = 0xFF101117, CARD = 0xFF11151C, BORDER = 0xFF292A36;
    private final Screen parent;
    private final ClientConfig config;
    private final Runnable save;
    private final AutoBuilderController builder = AutoBuilderController.get();
    private final SchematicModelPreview model = new SchematicModelPreview();
    private final List<Hit> hits = new ArrayList<>();
    private final Map<String, Double> hoverAnimations = new HashMap<>();
    private final String[] coordinates = {"0", "0", "0"};
    private List<Path> files = List.of();
    private Path folder, selectedFile;
    private MaterialTracker.Snapshot materials;
    private MaterialTracker.Scan materialScan;
    private Schematic materialsSchematic;
    private PlacementTransform materialsTransform;
    private ClientLevel materialsWorld;
    private LocalPlayer materialsPlayer;
    private boolean loading, refreshing, initialized, closing, materialsOpen, modalClosing, draggingPreview;
    private boolean replaceText;
    private long lastFrame, materialRefresh;
    private int requestId, fileScroll, materialScroll, keyboardFocus = -1, coordinateFocus = -1;
    private float scale = 1, originX, originY;
    private double mouseX, mouseY, progress, modalProgress, seconds;
    private String message = "", searchQuery = "", details = "";
    private boolean searchFocused;

    public AutoBuilderScreen(Screen parent, ClientConfig config, Runnable save) {
        super(Component.literal("AutoBuilder"));
        this.parent = parent;
        this.config = config;
        this.save = save;
    }

    @Override protected void init() {
        scale = Math.max(.1f, Math.min(1, Math.min((width - 16f) / W, (height - 16f) / H)));
        originX = (width - W * scale) / 2f;
        originY = (height - H * scale) / 2f;
        if (!initialized) {
            initialized = true;
            folder = minecraft.gameDirectory.toPath().resolve("schematics").resolve("donut");
            syncCoordinates();
            refreshFiles();
        }
        hits.clear();
    }

    @Override public void render(GuiGraphics g, int mx, int my, float delta) {
        long now = System.nanoTime();
        seconds = lastFrame == 0 ? 1.0 / 60 : Math.min(.1, (now - lastFrame) / 1_000_000_000.0);
        lastFrame = now;
        progress = Math.clamp(progress + seconds / (closing ? -.16 : .20), 0, 1);
        modalProgress = Math.clamp(modalProgress + seconds / ((materialsOpen || !details.isEmpty()) && !modalClosing ? .18 : -.14), 0, 1);
        mouseX = (mx - originX) / scale;
        mouseY = (my - originY) / scale;
        hits.clear();
        if (closing && progress == 0) return;
        double previous = pushOpacity(smooth(progress));
        g.pose().pushMatrix();
        try {
            g.fill(0, 0, width, height, withOpacity(0x78050911));
            g.pose().translate(originX, originY);
            g.pose().scale(scale, scale);
            shadow(g, 0, 0, W, H, 12, 0x80000000);
            round(g, 0, 0, W, H, 12, BORDER);
            round(g, 1, 1, W - 2, H - 2, 11, PANEL);
            strong(g, font, "AutoBuilder", 21, 20, TEXT);
            text(g, font, "SCHEMATIC LIBRARY", 21, 39, DIM);
            button(g, "close", "Close", W - 75, 16, 56, true, false, this::onClose);
            drawLibrary(g);
            drawPreview(g);
            drawPositioning(g);
            drawActions(g);
            if (materialsOpen || !details.isEmpty()) {
                hits.clear();
                double modalOpacity = pushOpacity(smooth(modalProgress));
                try {
                    g.fill(0, 0, W, H, withOpacity(0xB006090F));
                    if (materialsOpen) drawMaterials(g); else drawDetails(g);
                } finally { popOpacity(modalOpacity); }
            }
            if (keyboardFocus >= 0 && keyboardFocus < hits.size()) {
                Hit h = hits.get(keyboardFocus);
                outline(g, h.x - 1, h.y - 1, h.w + 2, h.h + 2, 5, alpha(config.accentColor, .85));
            }
        } finally { g.pose().popMatrix(); popOpacity(previous); }
    }

    private void drawLibrary(GuiGraphics g) {
        round(g, 14, 62, 218, 382, 8, blend(PANEL, config.accentColor, .07));
        text(g, font, "schematics/donut/", 25, 76, MUTED);
        button(g, "folder", "Open Folder", 24, 96, 116, true, false, () -> {
            try { Files.createDirectories(folder); Util.getPlatform().openPath(folder); }
            catch (Exception e) { message = "Cannot open folder: " + errorMessage(e); }
        });
        button(g, "refresh", refreshing ? "..." : "Refresh", 148, 96, 73, !refreshing, false, this::refreshFiles);
        text(g, font, files.size() + " schematic" + (files.size() == 1 ? "" : "s"), 25, 132, DIM);
        g.enableScissor(24, 151, 222, 423);
        try {
            if (files.isEmpty()) {
                text(g, font, "Add .litematic files here.", 27, 167, MUTED);
                text(g, font, "Then choose Refresh.", 27, 184, DIM);
            }
            for (int i = 0; i < files.size(); i++) {
                int y = 151 + i * 31 - fileScroll;
                if (y + 28 < 151 || y > 423) continue;
                Path file = files.get(i);
                boolean selected = file.equals(selectedFile);
                round(g, 24, y, 198, 28, 5, blend(CARD, config.accentColor, selected ? .25 : inside(24,y,198,28) ? .10 : 0));
                text(g, font, ellipsize(file.getFileName().toString(), 176), 33, y + 10, selected ? TEXT : MUTED);
                hit("file" + i, 24, Math.max(151, y), 198, Math.min(423, y + 28) - Math.max(151, y), () -> load(file));
            }
        } finally { g.disableScissor(); }
        scrollbar(g, 226, 151, 271, files.size() * 31, fileScroll);
    }

    private void drawPreview(GuiGraphics g) {
        Schematic schematic = builder.schematic();
        String title = loading ? "Loading and validating..." : schematic == null ? "Choose a schematic" : schematic.name();
        strong(g, font, ellipsize(title, 380), 250, 67, TEXT);
        button(g, "previous", "<", 745, 62, 32, !files.isEmpty() && !loading, false, () -> nextFile(-1));
        button(g, "next", ">", 785, 62, 32, !files.isEmpty() && !loading, false, () -> nextFile(1));
        round(g, 246, 95, 576, 171, 8, 0xFF090D14);
        outline(g, 246, 95, 576, 171, 8, BORDER);
        g.enableScissor(250, 100, 818, 259);
        try {
            for (int x = 252; x < 818; x += 24) line(g, x, 99, x, 263, 0x142F445D);
            for (int y = 99; y < 263; y += 24) line(g, 250, y, 818, y, 0x142F445D);
            model.setSchematic(schematic);
            model.render(g, 252, 102, 564, 154);
            if (schematic == null) {
                text(g, font, "Your 3D preview will appear here", 405, 166, MUTED);
                text(g, font, "Supports multiple regions in .litematic files", 373, 185, DIM);
            }
        } finally { g.disableScissor(); }
        hit("model", 246, 95, 576, 171, () -> draggingPreview = true);
        if (schematic != null) {
            BlockPos d = schematic.dimensions();
            text(g, font, d.getX() + " x " + d.getY() + " x " + d.getZ() + " blocks  /  "
                    + number(schematic.blocks().size()) + " blocks  /  " + schematic.regionCount() + " regions", 253, 276, MUTED);
            text(g, font, model.sampled() ? "Drag to rotate / Scroll to zoom / Surface sampled" : "Drag to rotate / Scroll to zoom", 253, 294, DIM);
            button(g, "resetView", "Reset view", 728, 273, 89, true, false, model::reset);
        }
    }

    private void drawPositioning(GuiGraphics g) {
        boolean available = builder.schematic() != null && !loading;
        text(g, font, "WORLD POSITION", 251, 322, DIM);
        for (int axis = 0; axis < 3; axis++) {
            final int index = axis;
            int x = 251 + axis * 191;
            text(g, font, new String[]{"X", "Y", "Z"}[axis], x, 347, MUTED);
            button(g, "minus" + axis, "-", x + 17, 338, 24, available, false, () -> nudge(index, -1));
            round(g, x + 44, 338, 100, 24, 4, coordinateFocus == axis ? BORDER : 0xFF090D14);
            String shown = coordinates[axis] + (coordinateFocus == axis && System.currentTimeMillis() % 1000 < 500 ? "|" : "");
            text(g, font, ellipsize(shown, 90), x + 50, 347, available ? TEXT : DIM);
            if (available) hit("coordinate" + axis, x + 44, 338, 100, 24, () -> { coordinateFocus = index; replaceText = true; });
            button(g, "plus" + axis, "+", x + 147, 338, 24, available, false, () -> nudge(index, 1));
        }
        PlacementTransform t = builder.transform();
        button(g, "atPlayer", "At player", 251, 376, 92, available && minecraft.player != null, false, () -> {
            PlacementTransform current = builder.transform();
            builder.setTransform(new PlacementTransform(minecraft.player.blockPosition(), current.quarterTurns(), current.mirrorX())); syncCoordinates();
        });
        button(g, "rotate", "Rotate " + Math.floorMod(t.quarterTurns(), 4) * 90 + " deg", 351, 376, 114, available, false, () -> {
            if (!commitCoordinates()) return;
            PlacementTransform current = builder.transform();
            builder.setTransform(new PlacementTransform(current.origin(), current.quarterTurns() + 1, current.mirrorX())); invalidateMaterials();
        });
        button(g, "mirror", "Mirror X: " + (t.mirrorX() ? "On" : "Off"), 473, 376, 119, available, t.mirrorX(), () -> {
            if (!commitCoordinates()) return;
            PlacementTransform current = builder.transform();
            builder.setTransform(new PlacementTransform(current.origin(), current.quarterTurns(), !current.mirrorX())); invalidateMaterials();
        });
        button(g, "preview", "Show in world", 600, 376, 113, available && minecraft.level != null, false, () -> {
            if (!commitCoordinates()) return;
            builder.setPreviewVisible(true); minecraft.setScreen(null);
        });
        button(g, "cancelPreview", "Cancel Preview", 721, 376, 96, builder.previewVisible(), false, () -> builder.setPreviewVisible(false));
        text(g, font, "Ghost opacity", 253, 415, DIM);
        button(g, "opacityDown", "-", 347, 406, 24, true, false, () -> setOpacity(-.05));
        text(g, font, Math.round(config.autoBuilder.previewOpacity * 100) + "%", 380, 415, MUTED);
        button(g, "opacityUp", "+", 421, 406, 24, true, false, () -> setOpacity(.05));
        if (builder.schematic() != null && !builder.schematic().warnings().isEmpty())
            button(g, "warnings", builder.schematic().warnings().size() + " file notices", 668, 406, 149, true, false,
                    () -> openDetails(String.join("\n", builder.schematic().warnings())));
    }

    private void drawActions(GuiGraphics g) {
        round(g, 16, 450, W - 32, 1, 0, BORDER);
        boolean available = builder.schematic() != null && !loading;
        String status = builder.status().name().toLowerCase(Locale.ROOT);
        status = Character.toUpperCase(status.charAt(0)) + status.substring(1);
        text(g, font, status + "  " + Math.round(builder.progress() * 100) + "%", 24, 464, TEXT);
        round(g, 24, 482, 203, 4, 2, BORDER);
        round(g, 24, 482, (int) (203 * builder.progress()), 4, 2, alpha(config.accentColor, 1));
        button(g, "materials", "Materials", 251, 459, 103, available, false, () -> {
            if (!commitCoordinates()) return;
            materialsOpen = true; modalClosing = false; modalProgress = 0; materialScroll = 0;
            searchFocused = false; keyboardFocus = -1; invalidateMaterials(); refreshMaterials();
        });
        String action = builder.status() == AutoBuilderController.Status.PAUSED ? "Resume" : builder.isRunning() ? "Pause" : "Start";
        button(g, "start", action, 364, 459, 99, available && minecraft.level != null, true, () -> {
            if (!commitCoordinates()) return;
            if (builder.isRunning()) builder.pause();
            else {
                minecraft.setScreen(null);
                if (builder.status() == AutoBuilderController.Status.PAUSED) builder.resume(); else builder.start();
            }
        });
        button(g, "cancel", "Cancel", 473, 459, 89, available, false, builder::cancel);
        String reason = message.isEmpty() ? builder.reason() : message;
        text(g, font, ellipsize(reason, 238), 579, 469, message.isEmpty() ? MUTED : 0xFFFFC66B);
        if (!reason.isEmpty()) hit("reason", 575, 459, 242, 26, () -> openDetails(reason));
        text(g, font, "Server-confirmed placement / Walk + jump navigation", 251, 493, DIM);
    }

    private void drawMaterials(GuiGraphics g) {
        int x = 29, y = 43, w = 782, h = 418;
        round(g, x - 1, y - 1, w + 2, h + 2, 11, BORDER);
        round(g, x, y, w, h, 10, PANEL);
        strong(g, font, "Materials", x + 20, y + 18, TEXT);
        if (materialScan != null) text(g, font, "Updating... " + Math.round(materialScan.progress() * 100) + "%", x + 116, y + 19, MUTED);
        button(g, "closeMaterials", "Done", x + w - 78, y + 12, 59, true, false, this::closeModal);
        if (materials == null) { text(g, font, "Reading loaded world and inventory...", x + 20, y + 51, MUTED); return; }
        text(g, font, number(materials.totalBlocks()) + " blocks  /  " + number(materials.correctBlocks()) + " correct  /  "
                + number(materials.remainingBlocks()) + " remaining  /  " + number(materials.unknownBlocks()) + " unknown", x + 20, y + 47, MUTED);
        round(g, x + 20, y + 69, 355, 26, 5, searchFocused ? BORDER : 0xFF090D14);
        search(g, x + 30, y + 77, MUTED);
        String query = searchQuery.isEmpty() && !searchFocused ? "Search materials..." : searchQuery + (searchFocused && System.currentTimeMillis() % 1000 < 500 ? "|" : "");
        text(g, font, ellipsize(query, 313), x + 47, y + 78, searchQuery.isEmpty() ? DIM : TEXT);
        hit("materialSearch", x + 20, y + 69, 355, 26, () -> { searchFocused = true; replaceText = false; });
        boolean creative = minecraft.player != null && minecraft.player.getAbilities().instabuild;
        button(g, "getMaterials", "Get Materials", x + w - 163, y + 69, 143, creative, false, () -> { builder.getMaterials(minecraft); invalidateMaterials(); refreshMaterials(); });
        if (!creative) text(g, font, "Creative only", x + w - 264, y + 78, DIM);
        int[] cols = {334, 397, 468, 531, 594, 663, 737};
        String[] labels = {"Total", "Correct", "Remain", "Hotbar", "Inv.", "Missing", "Unknown"};
        text(g, font, "Material", x + 20, y + 116, DIM);
        for (int i = 0; i < cols.length; i++) right(g, font, labels[i], cols[i] + 40, y + 116, DIM);
        round(g, x + 18, y + 133, w - 36, 1, 0, BORDER);
        List<MaterialTracker.Row> rows = filteredRows();
        materialScroll = Math.clamp(materialScroll, 0, Math.max(0, rows.size() * 28 - 192));
        g.enableScissor(x + 18, y + 140, x + w - 17, y + 332);
        try {
            for (int i = 0; i < rows.size(); i++) {
                int ry = y + 140 + i * 28 - materialScroll;
                if (ry + 27 < y + 140 || ry >= y + 332) continue;
                MaterialTracker.Row row = rows.get(i);
                if (i % 2 == 0) round(g, x + 18, ry, w - 36, 27, 3, CARD);
                g.renderItem(row.item().getDefaultInstance(), x + 23, ry + 5);
                text(g, font, ellipsize(row.name(), 233), x + 48, ry + 10, TEXT);
                int[] values = {row.total(), row.correct(), row.remaining(), row.hotbar(), row.inventory(), row.missing(), row.unknown()};
                for (int col = 0; col < values.length; col++) right(g, font, number(values[col]), cols[col] + 40, ry + 10,
                        col == 5 && values[col] > 0 ? 0xFFFF8797 : col == 6 && values[col] > 0 ? 0xFFFFC66B : MUTED);
            }
            if (rows.isEmpty()) text(g, font, "No matching materials.", x + 25, y + 155, MUTED);
        } finally { g.disableScissor(); }
        scrollbar(g, x + w - 13, y + 140, 192, rows.size() * 28, materialScroll);
        text(g, font, "Counts are items. Inventory excludes hotbar. Unknown = unloaded blocks.", x + 20, y + 347, DIM);
        String lower = materials.conflicts() > 0 ? materials.conflicts() + " occupied positions require manual correction."
                : "Double slabs count twice. Paired blocks count their placement item once.";
        text(g, font, lower, x + 20, y + 365, materials.conflicts() > 0 ? 0xFFFFC66B : DIM);
        text(g, font, ellipsize(builder.reason(), 738), x + 20, y + 391, MUTED);
    }

    private void drawDetails(GuiGraphics g) {
        round(g, 135, 114, 570, 280, 10, PANEL);
        outline(g, 135, 114, 570, 280, 10, BORDER);
        strong(g, font, "AutoBuilder notice", 154, 134, TEXT);
        List<String> lines = wrap(details, 525);
        g.enableScissor(152, 160, 686, 343);
        for (int i = 0; i < lines.size(); i++) text(g, font, lines.get(i), 154, 162 + i * 17 - materialScroll, MUTED);
        g.disableScissor();
        scrollbar(g, 692, 160, 181, lines.size() * 17, materialScroll);
        button(g, "doneDetails", "Done", 586, 356, 98, true, true, this::closeModal);
        button(g, "copyDetails", "Copy details", 154, 356, 125, true, false, () -> minecraft.keyboardHandler.setClipboard(details));
    }

    private void refreshFiles() {
        refreshing = true;
        Path directory = folder;
        CompletableFuture.supplyAsync(() -> {
            try {
                Files.createDirectories(directory);
                try (var stream = Files.list(directory)) {
                    return stream.filter(path -> Files.isRegularFile(path) && path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".litematic"))
                            .sorted(Comparator.comparing(path -> path.getFileName().toString().toLowerCase(Locale.ROOT))).toList();
                }
            } catch (IOException e) { throw new java.util.concurrent.CompletionException(e); }
        }).whenComplete((result, error) -> minecraft.execute(() -> {
            refreshing = false;
            if (error != null) message = "Cannot read schematic folder: " + errorMessage(error);
            else { files = result; fileScroll = 0; message = files.isEmpty() ? "Add .litematic files using Open Folder." : ""; }
        }));
    }

    private void load(Path file) {
        if (loading) return;
        int request = ++requestId;
        selectedFile = file;
        loading = true;
        message = "";
        if (builder.isRunning()) builder.pause();
        CompletableFuture.supplyAsync(() -> {
            try { return LitematicLoader.load(file); }
            catch (IOException e) { throw new java.util.concurrent.CompletionException(e); }
        }).whenComplete((value, error) -> minecraft.execute(() -> {
            if (request != requestId) return;
            loading = false;
            if (error != null) { message = "Invalid schematic: " + errorMessage(error); return; }
            builder.setSchematic(value);
            if (minecraft.player != null) builder.setTransform(new PlacementTransform(minecraft.player.blockPosition(), 0, false));
            syncCoordinates();
            invalidateMaterials();
            model.setSchematic(value);
            message = value.warnings().isEmpty() ? "" : "Loaded with " + value.warnings().size() + " notice(s).";
        }));
    }

    private void nextFile(int direction) {
        if (files.isEmpty()) return;
        int index = files.indexOf(selectedFile);
        load(files.get(Math.floorMod(index < 0 ? 0 : index + direction, files.size())));
    }
    private void syncCoordinates() {
        BlockPos p = builder.transform().origin();
        coordinates[0] = Integer.toString(p.getX()); coordinates[1] = Integer.toString(p.getY()); coordinates[2] = Integer.toString(p.getZ());
        invalidateMaterials();
    }
    private boolean commitCoordinates() {
        PlacementTransform t = builder.transform();
        try {
            BlockPos pos = new BlockPos(Integer.parseInt(coordinates[0]), Integer.parseInt(coordinates[1]), Integer.parseInt(coordinates[2]));
            if (Math.abs((long) pos.getX()) > 29_999_984 || Math.abs((long) pos.getZ()) > 29_999_984 || Math.abs((long) pos.getY()) > 2048)
                throw new NumberFormatException();
            if (!pos.equals(t.origin())) builder.setTransform(new PlacementTransform(pos, t.quarterTurns(), t.mirrorX()));
            if (message.startsWith("Invalid coordinates.")) message = "";
            invalidateMaterials();
        } catch (NumberFormatException e) {
            message = "Invalid coordinates. Use whole numbers within world bounds.";
            coordinateFocus = -1; syncCoordinates(); return false;
        }
        coordinateFocus = -1;
        return true;
    }
    private void nudge(int axis, int amount) {
        commitCoordinates();
        coordinates[axis] = Long.toString((long) Integer.parseInt(coordinates[axis]) + amount);
        commitCoordinates();
    }
    private void setOpacity(double amount) { config.autoBuilder.previewOpacity += amount; config.autoBuilder.sanitize(); save.run(); }
    private void invalidateMaterials() {
        materialRefresh = 0;
        if (materialScan != null) materialScan.cancel();
        materialScan = null;
        if (materialsSchematic != builder.schematic() || !builder.transform().equals(materialsTransform)
                || minecraft != null && (materialsWorld != minecraft.level || materialsPlayer != minecraft.player)) materials = null;
    }
    private void refreshMaterials() {
        Schematic value = builder.schematic();
        PlacementTransform placement = builder.transform();
        if (materialsSchematic != value || !placement.equals(materialsTransform)
                || materialsWorld != minecraft.level || materialsPlayer != minecraft.player) {
            invalidateMaterials(); materials = null;
            materialsSchematic = value; materialsTransform = placement;
            materialsWorld = minecraft.level; materialsPlayer = minecraft.player;
        }
        if (materialScan == null) materialScan = MaterialTracker.beginSnapshot(minecraft, value, placement);
    }
    private List<MaterialTracker.Row> filteredRows() {
        if (materials == null) return List.of();
        String query = searchQuery.toLowerCase(Locale.ROOT).trim();
        return materials.rows().stream().filter(row -> row.name().toLowerCase(Locale.ROOT).contains(query)).toList();
    }
    private void openDetails(String value) {
        details = value; modalClosing = false; modalProgress = 0; materialScroll = 0; keyboardFocus = -1;
    }
    private void closeModal() { modalClosing = true; searchFocused = false; keyboardFocus = -1; }

    private void button(GuiGraphics g, String id, String label, int x, int y, int w, boolean enabled, boolean primary, Runnable action) {
        double hover = hoverAnimations.getOrDefault(id, 0.0);
        hover += ((enabled && inside(x, y, w, 24) ? 1 : 0) - hover) * (1 - Math.exp(-seconds * 18));
        hoverAnimations.put(id, hover);
        round(g, x, y, w, 24, 5, blend(CARD, config.accentColor, enabled ? (primary ? .55 : .09) + hover * .18 : 0));
        outline(g, x, y, w, 24, 5, blend(BORDER, config.accentColor, enabled ? (primary ? .45 : 0) + hover * .3 : 0));
        String shown = ellipsize(label, w - 10);
        text(g, font, shown, x + (w - UiPaint.width(font, shown)) / 2, y + 9, enabled ? TEXT : DIM);
        if (enabled) hit(id, x, y, w, 24, action);
    }
    private void hit(String id, int x, int y, int w, int h, Runnable action) {
        if (!closing && !modalClosing && h > 0) hits.add(new Hit(id, x, y, w, h, action));
    }
    private boolean inside(int x, int y, int w, int h) { return mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h; }
    private void scrollbar(GuiGraphics g, int x, int y, int h, int content, int scroll) {
        if (content <= h) return;
        int thumb = Math.max(18, h * h / content);
        round(g, x, y, 3, h, 1, CARD);
        round(g, x, y + (int) ((double) scroll / (content - h) * (h - thumb)), 3, thumb, 1, alpha(config.accentColor, .6));
    }
    private String ellipsize(String text, int max) {
        if (text == null) return "";
        if (UiPaint.width(font, text) <= max) return text;
        while (!text.isEmpty() && UiPaint.width(font, text + "...") > max) text = removeLast(text);
        return text + "...";
    }
    private List<String> wrap(String value, int max) {
        List<String> lines = new ArrayList<>();
        for (String paragraph : value.split("\\n")) {
            String line = "";
            for (String word : paragraph.split(" ")) {
                if (!line.isEmpty() && UiPaint.width(font, line + " " + word) > max) { lines.add(line); line = ""; }
                line += (line.isEmpty() ? "" : " ") + word;
            }
            lines.add(line);
        }
        return lines;
    }
    private static String number(int value) { return String.format(Locale.ROOT, "%,d", value); }
    private static String errorMessage(Throwable error) {
        while (error.getCause() != null) error = error.getCause();
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }
    private static String removeLast(String value) { return value.isEmpty() ? value : value.substring(0, value.offsetByCodePoints(value.length(), -1)); }
    private static double smooth(double value) { return value * value * (3 - 2 * value); }

    @Override public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        if (closing || modalClosing) return true;
        if (event.button() != GLFW.GLFW_MOUSE_BUTTON_LEFT) return false;
        double x = (event.x() - originX) / scale, y = (event.y() - originY) / scale;
        Hit target = null;
        for (int i = hits.size() - 1; i >= 0; i--) if (hits.get(i).contains(x, y)) { target = hits.get(i); break; }
        if (coordinateFocus >= 0 && (target == null || !target.id.equals("coordinate" + coordinateFocus)) && !commitCoordinates()) return true;
        searchFocused = target != null && target.id.equals("materialSearch");
        keyboardFocus = -1;
        if (target != null) target.action.run();
        return true;
    }
    @Override public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (draggingPreview && !materialsOpen && details.isEmpty()) { model.rotate(dx / scale, dy / scale); return true; }
        return false;
    }
    @Override public boolean mouseReleased(MouseButtonEvent event) { draggingPreview = false; return true; }
    @Override public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        if (closing || modalClosing) return true;
        double lx = (x - originX) / scale, ly = (y - originY) / scale;
        if (materialsOpen) materialScroll = Math.clamp(materialScroll - (int) (vertical * 28), 0, Math.max(0, filteredRows().size() * 28 - 192));
        else if (!details.isEmpty()) materialScroll = Math.clamp(materialScroll - (int) (vertical * 25), 0, Math.max(0, wrap(details, 525).size() * 17 - 181));
        else if (lx >= 246 && lx < 822 && ly >= 95 && ly < 266) model.zoom(vertical);
        else if (lx >= 14 && lx < 234 && ly >= 151 && ly <= 423) fileScroll = Math.clamp(fileScroll - (int) (vertical * 31), 0, Math.max(0, files.size() * 31 - 272));
        return true;
    }
    @Override public boolean keyPressed(KeyEvent event) {
        if (closing || modalClosing) return true;
        if (event.key() == GLFW.GLFW_KEY_ESCAPE) {
            if (coordinateFocus >= 0) { coordinateFocus = -1; syncCoordinates(); }
            else if (materialsOpen || !details.isEmpty()) closeModal(); else onClose();
            return true;
        }
        if (coordinateFocus >= 0 || searchFocused) {
            String value = coordinateFocus >= 0 ? coordinates[coordinateFocus] : searchQuery;
            if (event.hasControlDown() && event.key() == GLFW.GLFW_KEY_A) { replaceText = true; return true; }
            if (event.hasControlDown() && event.key() == GLFW.GLFW_KEY_V) {
                String pasted = minecraft.keyboardHandler.getClipboard().replaceAll("[\\p{Cntrl}]", "");
                if (coordinateFocus >= 0) pasted = pasted.replaceAll("[^0-9-]", "");
                value = (replaceText ? "" : value) + pasted;
                setInput(value.substring(0, Math.min(coordinateFocus >= 0 ? 11 : 64, value.length()))); replaceText = false; return true;
            }
            if (event.key() == GLFW.GLFW_KEY_BACKSPACE) { setInput(replaceText ? "" : removeLast(value)); replaceText = false; return true; }
            if (event.key() == GLFW.GLFW_KEY_ENTER) { if (coordinateFocus >= 0) commitCoordinates(); else searchFocused = false; return true; }
        }
        if (event.key() == GLFW.GLFW_KEY_TAB && !hits.isEmpty()) {
            if (coordinateFocus >= 0) commitCoordinates();
            keyboardFocus = Math.floorMod(keyboardFocus + (event.hasShiftDown() ? -1 : 1), hits.size());
            searchFocused = false; return true;
        }
        if ((event.key() == GLFW.GLFW_KEY_ENTER || event.key() == GLFW.GLFW_KEY_SPACE) && keyboardFocus >= 0 && keyboardFocus < hits.size()) {
            hits.get(keyboardFocus).action.run(); return true;
        }
        return false;
    }
    private void setInput(String value) { if (coordinateFocus >= 0) coordinates[coordinateFocus] = value; else { searchQuery = value; materialScroll = 0; } }
    @Override public boolean charTyped(CharacterEvent event) {
        if (closing || modalClosing || !event.isAllowedChatCharacter()) return true;
        if (coordinateFocus >= 0) {
            int cp = event.codepoint();
            if ((cp >= '0' && cp <= '9') || cp == '-') {
                if (replaceText) coordinates[coordinateFocus] = "";
                replaceText = false;
                if (coordinates[coordinateFocus].length() < 11) coordinates[coordinateFocus] += event.codepointAsString();
            }
            return true;
        }
        if (searchFocused) {
            if (replaceText) searchQuery = "";
            replaceText = false;
            if (searchQuery.length() < 64) searchQuery += event.codepointAsString();
            materialScroll = 0; return true;
        }
        return false;
    }
    @Override public void tick() {
        if (closing && progress == 0 && minecraft.screen == this) minecraft.setScreen(parent);
        if (modalClosing && modalProgress == 0) {
            materialsOpen = false; details = ""; modalClosing = false; materialScroll = 0;
        }
        if (materialsOpen && !modalClosing && !closing) {
            if (materialsSchematic != builder.schematic() || !builder.transform().equals(materialsTransform)
                    || materialsWorld != minecraft.level || materialsPlayer != minecraft.player
                    || materialScan == null && System.currentTimeMillis() - materialRefresh >= 750) refreshMaterials();
            if (materialScan != null && materialScan.advance(minecraft, 4096, 4_000_000L)) {
                materials = materialScan.result(); materialScan = null;
                materialRefresh = System.currentTimeMillis();
            }
        } else if (materialScan != null) {
            materialScan.cancel(); materialScan = null; materialRefresh = 0;
        }
    }
    @Override public void onClose() {
        if (closing) return;
        commitCoordinates(); closing = true; draggingPreview = false; keyboardFocus = -1;
    }
    @Override public void removed() { requestId++; loading = false; invalidateMaterials(); ModuleKeybinds.suppressUntilReleased(minecraft); }
    @Override public boolean isPauseScreen() { return false; }
    private record Hit(String id, int x, int y, int w, int h, Runnable action) {
        boolean contains(double mx, double my) { return mx >= x && mx < x + w && my >= y && my < y + h; }
    }
}
