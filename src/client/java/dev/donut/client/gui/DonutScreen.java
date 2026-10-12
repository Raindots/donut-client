package dev.donut.client.gui;

import com.mojang.blaze3d.platform.InputConstants;
import dev.donut.client.config.ClientConfig;
import dev.donut.client.config.FinderSettings;
import dev.donut.client.config.RenderMode;
import dev.donut.client.input.ModuleKeybinds;
import dev.donut.client.autobuild.AutoBuilderController;
import dev.donut.client.fly.FlyController;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

import static dev.donut.client.gui.UiPaint.*;

/** Compact module cards with independently expandable, inline settings. */
public final class DonutScreen extends Screen {
    private static final int W = 760, H = 402;
    private static final int TEXT = 0xFFECECF3, MUTED = 0xFFA3A3B2, DIM = 0xFF6C6C80;
    private static final int PANEL = 0xFF101117, CARD = 0xFF11151C, BORDER = 0xFF292A36;
    private static final int CX = 166, CW = 582, TOP = 46, BOTTOM = 372;
    private static final int CARD_W = 283, CARD_HEADER = 44, CARD_GAP = 8;
    private static final int[] PALETTE = {0x4C86FF, 0x68B4FF, 0x6DDAC5, 0x7EDD8E, 0xC5E774,
            0xFFC66B, 0xFF975F, 0xFF707B, 0xEC80B5, 0xBA8CFF, 0xC8D1E8, 0xFFFFFF};

    private final ClientConfig config;
    private final Runnable save;
    private final List<Hit> hits = new ArrayList<>();
    private final Map<String, Double> animations = new HashMap<>();
    private final boolean[] expanded = new boolean[ModuleKeybinds.MODULE_COUNT];
    private final boolean[] colorsExpanded = new boolean[2];
    private final boolean[] filtersExpanded = new boolean[2];
    private final double[] expansion = new double[ModuleKeybinds.MODULE_COUNT];
    private final double[] colorsExpansion = new double[2];
    private final double[] filtersExpansion = new double[2];
    private double screenProgress, contentProgress, modalProgress;
    private boolean closing;
    private InputConstants.Key openingKey;
    private float scale = 1;
    private float originX, originY;
    private double mouseX, mouseY;
    private int tab;
    private double scroll;
    private double scrollTarget;
    private long lastFrame;
    private double frameSeconds;
    private int contentHeight;
    private String query = "";
    private boolean searchFocused;
    private boolean dirty;
    private Hit dragging;
    private String keyboardFocus;
    private ColorEditor colorEditor;
    private boolean collectingClip;
    private boolean contentInteractive = true;
    private int clipTop = TOP, clipBottom = BOTTOM;
    private int binding = -1;
    private int hoveredBinding = -1;
    private InputConstants.Key capturedKey;
    // Row coordinates and IDs are scoped to the card currently being rendered.
    private int rowX, rowW;
    private String rowPrefix = "";

    public DonutScreen(ClientConfig config, Runnable save) {
        super(Component.literal("Donut Client"));
        this.config = config;
        this.save = save;
    }

    @Override
    protected void init() {
        scale = Math.min(1.0f, Math.min((width - 16f) / W, (height - 16f) / H));
        scale = Math.max(0.1f, scale);
        originX = (width - W * scale) / 2f;
        originY = (height - H * scale) / 2f;
        dragging = null;
        hits.clear();
        KeyMapping open = KeyMapping.get("key.donut.open_gui");
        if (lastFrame == 0 && open != null) {
            InputConstants.Key key = InputConstants.getKey(open.saveString());
            if (key.getType() == InputConstants.Type.KEYSYM
                    && InputConstants.isKeyDown(minecraft.getWindow(), key.getValue())) openingKey = key;
        }
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float delta) {
        long now = System.nanoTime();
        frameSeconds = lastFrame == 0 ? 1.0 / 60 : Math.min(.1, (now - lastFrame) / 1_000_000_000.0);
        lastFrame = now;
        screenProgress = advance(screenProgress, !closing, closing ? .16 : .20);
        contentProgress = advance(contentProgress, true, .18);
        modalProgress = advance(modalProgress, colorEditor != null, .18);
        for (int i = 0; i < expansion.length; i++) expansion[i] = advance(expansion[i], expanded[i], .22);
        for (int i = 0; i < colorsExpansion.length; i++) {
            colorsExpansion[i] = advance(colorsExpansion[i], colorsExpanded[i], .18);
            filtersExpansion[i] = advance(filtersExpansion[i], filtersExpanded[i], .18);
        }
        if (closing && screenProgress == 0) return;
        scroll = ease(scroll, scrollTarget, 18);
        mouseX = (mx - originX) / scale;
        mouseY = (my - originY) / scale;
        hoveredBinding = -1;
        hits.clear();
        double screenOpacity = pushOpacity(smooth(screenProgress));
        g.pose().pushMatrix();
        try {
        g.fill(0, 0, width, height, withOpacity(0x66050911));
        g.pose().translate(originX, originY);
        g.pose().scale(scale, scale);
        shadow(g, 0, 0, W, H, 12, 0x80000000);
        round(g, 0, 0, W, H, 12, BORDER);
        round(g, 1, 1, W - 2, H - 2, 11, PANEL);
        round(g, 7, 7, 150, H - 14, 10, blend(PANEL, config.accentColor, .10));
        outline(g, 7, 7, 150, H - 14, 10, blend(BORDER, config.accentColor, .13));
        round(g, CX - 3, TOP - 5, CW + 4, BOTTOM - TOP + 7, 8, blend(PANEL, config.accentColor, .08));
        drawChrome(g);
        double contentOpacity = pushOpacity(smooth(contentProgress));
        try {
            if (tab == 2) drawAppearance(g);
            else drawOverview(g);
        } finally { popOpacity(contentOpacity); }
        drawFooter(g);
        if (colorEditor != null) {
            double modalOpacity = pushOpacity(smooth(modalProgress));
            try { drawColorEditor(g); }
            finally { popOpacity(modalOpacity); }
        }
        if (keyboardFocus != null) {
            hits.stream().filter(h -> h.id.equals(keyboardFocus)).findFirst().ifPresent(h ->
                    outline(g, h.x - 1, h.y - 1, h.w + 2, h.h + 2, 5, alpha(config.accentColor, .85)));
        }
        } finally {
            g.pose().popMatrix();
            popOpacity(screenOpacity);
        }
    }

    private void drawChrome(GuiGraphics g) {
        round(g, 21, 23, 24, 24, 12, alpha(config.accentColor, 1));
        round(g, 28, 30, 10, 10, 5, PANEL);
        round(g, 36, 25, 3, 3, 1, TEXT);
        strong(g, font, "DONUT", 56, 28, TEXT);
        text(g, font, "CLIENT", 57, 43, DIM);
        round(g, 20, 65, 124, 1, 0, alpha(config.accentColor, .15));
        text(g, font, "MODULES", 22, 83, DIM);
        sidebar(g, "Visual", 105, 0);
        sidebar(g, "Blatant", 135, 3);
        sidebar(g, "Favorites", 165, 1);
        text(g, font, "GENERAL", 22, 220, DIM);
        sidebar(g, "Appearance", 241, 2);

        round(g, 16, H - 61, 132, 45, 7, 0xFF101319);
        round(g, 27, H - 44, 7, 7, 3, alpha(config.accentColor, 1));
        text(g, font, "Donut Client", 43, H - 50, TEXT);
        int enabled = (config.storage.enabled ? 1 : 0) + (config.spawner.enabled ? 1 : 0) + (config.freecam.enabled ? 1 : 0)
                + (AutoBuilderController.get().isRunning() ? 1 : 0) + (config.chunkFinder.enabled ? 1 : 0)
                + (config.fullbright.enabled ? 1 : 0)
                + (FlyController.getInstance() != null && FlyController.getInstance().isActive() ? 1 : 0);
        text(g, font, enabled + " / " + ModuleKeybinds.MODULE_COUNT + " active", 43, H - 35, MUTED);

        round(g, CX, 8, CW, 30, 7, 0xFF10141A);
        outline(g, CX, 8, CW, 30, 7, BORDER);
        String title = switch (tab) {
            case 1 -> "Your favorites";
            case 2 -> "Appearance";
            case 3 -> "Blatant modules";
            default -> "Visual modules";
        };
        text(g, font, title, CX + 11, 19, TEXT);
        if (tab != 2) drawSearch(g);
        double closeHover = animate("closeHover", inside(W - 36, 12, 22, 22) ? 1 : 0);
        round(g, W - 36, 12, 22, 22, 5, blend(0xFF10141A, BORDER, closeHover));
        line(g, W - 29, 19, W - 22, 26, MUTED);
        line(g, W - 22, 19, W - 29, 26, MUTED);
        hit("close", W - 36, 12, 22, 22, this::onClose);
        round(g, CX + 8, 37, CW - 16, 1, 0, alpha(config.accentColor, .6));
    }

    private void sidebar(GuiGraphics g, String label, int y, int target) {
        boolean active = tab == target;
        double hover = animate("navHover" + target, inside(16, y, 132, 26) ? 1 : 0);
        round(g, 16, y, 132, 26, 5, blend(blend(PANEL, config.accentColor, .10),
                blend(PANEL, config.accentColor, active ? .24 : .15), active ? 1 : hover));
        if (active) round(g, 16, y + 6, 2, 14, 1, alpha(config.accentColor, 1));
        int tint = active ? alpha(config.accentColor, 1) : DIM;
        if (target == 0) camera(g, 26, y + 4, tint);
        else if (target == 1) star(g, 27, y + 5, tint);
        else if (target == 3) cube(g, 26, y + 5, 16, tint, 1, .18);
        else {
            outline(g, 27, y + 6, 14, 14, 7, tint);
            round(g, 32, y + 11, 4, 4, 2, tint);
        }
        text(g, font, label, 53, y + 9, active ? TEXT : MUTED);
        hit("tab" + target, 16, y, 132, 26, () -> {
            if (tab != target || !query.isEmpty()) contentProgress = 0;
            tab = target;
            scroll = scrollTarget = 0;
            query = "";
            searchFocused = false;
            keyboardFocus = null;
        });
    }

    private void drawSearch(GuiGraphics g) {
        int x = CX + CW - 245;
        round(g, x, 13, 208, 20, 5, searchFocused ? BORDER : 0xFF090C11);
        search(g, x + 8, 18, MUTED);
        String shown = query.isEmpty() && !searchFocused ? "Search modules..." : query + (searchFocused && System.currentTimeMillis() % 1000 < 500 ? "|" : "");
        while (UiPaint.width(font, shown) > 178 && !shown.isEmpty()) shown = removeFirstCharacter(shown);
        text(g, font, shown, x + 25, 19, query.isEmpty() ? DIM : TEXT);
        hit("search", x, 13, 208, 20, () -> searchFocused = true);
    }

    private void drawOverview(GuiGraphics g) {
        int[] heights = {0, 0};
        int[] columns = new int[ModuleKeybinds.MODULE_COUNT];
        int[] offsets = new int[ModuleKeybinds.MODULE_COUNT];
        int count = 0;
        for (int index = 0; index < ModuleKeybinds.MODULE_COUNT; index++) {
            if (!matches(index)) continue;
            // Keep each card in its column while cards above it expand and collapse.
            int column = count % 2;
            columns[index] = column;
            offsets[index] = heights[column];
            heights[column] += cardHeight(index) + CARD_GAP;
            count++;
        }
        contentHeight = Math.max(0, Math.max(heights[0], heights[1]) - CARD_GAP);
        clampScroll();
        beginContent(g);
        for (int index = 0; index < ModuleKeybinds.MODULE_COUNT; index++) {
            if (matches(index)) moduleCard(g, index, CX + columns[index] * (CARD_W + CARD_GAP), TOP + offsets[index] - (int) scroll);
        }
        if (count == 0) {
            round(g, CX, TOP, CW - 8, 74, 7, CARD);
            text(g, font, query.isEmpty() ? "Keep your essentials close." : "No matching modules", CX + 13, TOP + 18, TEXT);
            String hint = query.isEmpty() ? "Star a module to add it to Favorites."
                    : tab == 3 ? "Try fly, vanilla, autobuilder or schematic."
                    : tab == 1 ? "Try storage, freecam, fly or autobuilder."
                    : "Try storage, spawner, freecam or camera.";
            text(g, font, hint, CX + 13, TOP + 37, MUTED);
        }
        endContent(g);
    }

    private boolean matches(int index) {
        if ((tab == 0 && (index == 3 || index == 6)) || (tab == 3 && index != 3 && index != 6)) return false;
        if (tab == 1 && !favorite(index)) return false;
        String words = switch (index) {
            case 0 -> "storagefinder chests trapped ender shulker droppers";
            case 1 -> "spawnerfinder spawners trial";
            case 2 -> "freecam free camera fly perspective spectator";
            case 4 -> "base finder basefinder chunk finder chunkfinder geode amethyst activity kelp cave vines bamboo cocoa bee nest";
            case 5 -> "fullbright full bright brightness gamma night dark lighting";
            case 6 -> "fly flight vanilla singleplayer single player multiplayer multi player lan server movement creative";
            default -> "autobuilder auto builder schematic litematic building materials navigation";
        };
        return words.contains(query.toLowerCase(Locale.ROOT).trim());
    }

    private int cardHeight(int index) {
        int body = index == 6 ? 147 : index == 5 ? 57 : index == 4 ? 358 : index == 3 ? 112 : index == 2 ? 213 : 282 + colorHeight(index) + filterHeight(index);
        return CARD_HEADER + (int) Math.round(body * smooth(expansion[index]));
    }

    private int colorHeight(int index) {
        int full = 24 + (index == 0 ? 24 + (config.storage.perTypeColors ? 72 : 0) : 0);
        return (int) Math.round(full * smooth(colorsExpansion[index]));
    }

    private int filterHeight(int index) {
        return (int) Math.round((index == 0 ? 115 : 46) * smooth(filtersExpansion[index]));
    }

    private void moduleCard(GuiGraphics g, int index, int x, int y) {
        boolean enabled = enabled(index);
        int height = cardHeight(index);
        double hover = animate("cardHover" + index, inside(x, y, CARD_W, CARD_HEADER) ? 1 : 0);
        round(g, x, y, CARD_W, height, 7, blend(CARD, 0xFF1A1D27, hover * .5));
        outline(g, x, y, CARD_W, height, 7, blend(BORDER, config.accentColor, .12 + .16 * smooth(expansion[index])));
        double enabledGlow = animate("enabledGlow" + index, enabled ? 1 : 0);
        round(g, x + 1, y + 10, 2, CARD_HEADER - 20, 1, alpha(config.accentColor, enabledGlow));
        strong(g, font, name(index), x + 10, y + 10, TEXT);
        String marker = expanded[index] ? "-" : "+";
        text(g, font, marker, x + CARD_W - 78, y + 10, DIM);
        hit("module" + index, x, y, CARD_W, CARD_HEADER, () -> toggleEnabled(index), () -> {
            expanded[index] = !expanded[index];
            keyboardFocus = null;
            dragging = null;
        });
        keybindButton(g, index, x + 10, y + 24, 116, 15);
        double favoriteGlow = animate("favoriteGlow" + index, favorite(index) ? 1 : 0);
        star(g, x + CARD_W - 59f, y + 16f, 12f, blend(DIM, 0xFFFFC66B, favoriteGlow));
        hit("favorite" + index, x + CARD_W - 64, y + 11, 22, 22, () -> change(() -> toggleFavorite(index)));
        if (index == 2) freecamToggle(g, "enable2", x + CARD_W - 33, y + 16);
        else toggle(g, "enable" + index, x + CARD_W - 33, y + 16, enabled, () -> toggleEnabled(index));
        if (height <= CARD_HEADER + 1) return;
        Clip previousClip = pushClip(g, x + 1, y + CARD_HEADER + 1, CARD_W - 2, height - CARD_HEADER - 1);
        double bodyOpacity = pushOpacity(smooth(expansion[index]));
        boolean wasInteractive = contentInteractive;
        contentInteractive &= expanded[index];
        try {
        round(g, x + 10, y + CARD_HEADER + 1, CARD_W - 20, 1, 0, BORDER);
        rowX = x + 11;
        rowW = CARD_W - 22;
        rowPrefix = "m" + index + ".";
        if (index == 6) drawFlySettings(g, y + CARD_HEADER + 10);
        else if (index == 5) drawFullbrightSettings(g, y + CARD_HEADER + 10);
        else if (index == 4) drawChunkFinderSettings(g, y + CARD_HEADER + 10);
        else if (index == 3) drawAutoBuilderSettings(g, y + CARD_HEADER + 10);
        else if (index == 2) drawFreecamSettings(g, y + CARD_HEADER + 10);
        else drawFinderSettings(g, index, y + CARD_HEADER + 10);
        } finally {
            contentInteractive = wasInteractive;
            popOpacity(bodyOpacity);
            popClip(g, previousClip);
        }
    }

    private FinderSettings settings(int index) { return index == 0 ? config.storage : config.spawner; }
    private boolean enabled(int index) { return index == 6 ? FlyController.enabled() : index == 5 ? config.fullbright.enabled : index == 4 ? config.chunkFinder.enabled : index == 3 ? AutoBuilderController.get().isRunning() : index == 2 ? config.freecam.enabled : settings(index).enabled; }
    private boolean favorite(int index) { return index == 6 ? config.fly.favorite : index == 5 ? config.fullbright.favorite : index == 4 ? config.chunkFinder.favorite : index == 3 ? config.autoBuilder.favorite : index == 2 ? config.freecam.favorite : settings(index).favorite; }
    private void toggleFavorite(int index) {
        if (index == 6) config.fly.favorite = !config.fly.favorite;
        else if (index == 5) config.fullbright.favorite = !config.fullbright.favorite;
        else if (index == 4) config.chunkFinder.favorite = !config.chunkFinder.favorite;
        else if (index == 3) config.autoBuilder.favorite = !config.autoBuilder.favorite;
        else if (index == 2) config.freecam.favorite = !config.freecam.favorite;
        else settings(index).favorite = !settings(index).favorite;
        if (tab == 1) contentProgress = 0;
    }
    private void toggleEnabled(int index) {
        if (index == 6) {
            FlyController controller = FlyController.getInstance();
            if (controller != null) controller.setEnabled(!FlyController.enabled());
        } else if (index == 5) {
            change(() -> config.fullbright.enabled = !config.fullbright.enabled);
        } else if (index == 4) {
            change(() -> config.chunkFinder.enabled = !config.chunkFinder.enabled);
        } else if (index == 3) {
            if (AutoBuilderController.get().isRunning()) AutoBuilderController.get().pause();
            flush();
            minecraft.setScreen(new AutoBuilderScreen(this, config, save));
        } else if (index == 2) {
            if (config.freecam.enabled || canUseFreecam()) config.freecam.enabled = !config.freecam.enabled;
        } else change(() -> settings(index).enabled = !settings(index).enabled);
    }
    private String name(int index) {
        return switch (index) { case 0 -> "StorageFinder"; case 1 -> "SpawnerFinder"; case 2 -> "Freecam"; case 4 -> "Base Finder"; case 5 -> "Fullbright"; case 6 -> "Fly"; default -> "AutoBuilder"; };
    }

    private void drawFlySettings(GuiGraphics g, int y) {
        section(g, "VANILLA FLIGHT", y); y += 18;
        text(g, font, "Vanilla flight", rowX, y, MUTED); y += 24;
        section(g, "YOUR CONTROLS", y); y += 18;
        controlHint(g, "Move", keyName(minecraft.options.keyUp) + " / " + keyName(minecraft.options.keyLeft)
                + " / " + keyName(minecraft.options.keyDown) + " / " + keyName(minecraft.options.keyRight), y); y += 20;
        controlHint(g, "Up / down", keyName(minecraft.options.keyJump) + " / " + keyName(minecraft.options.keyShift), y); y += 20;
        controlHint(g, "Sprint", keyName(minecraft.options.keySprint), y); y += 20;
        text(g, font, "Server rules may restrict flight.", rowX, y, DIM);
    }

    private void drawFullbrightSettings(GuiGraphics g, int y) {
        section(g, "BRIGHTNESS", y);
        controlHint(g, "Gamma", "1000", y + 19);
    }

    private void drawChunkFinderSettings(GuiGraphics g, int y) {
        var s = config.chunkFinder;
        section(g, "DETECTION", y); y += 17;
        slider(g, "simulationDistance", "Simulation distance", y, () -> s.simulationDistance,
                value -> s.simulationDistance = (int) value, 2, 16, 1, " chunks"); y += 30;
        slider(g, "sensitivity", "Sensitivity", y, () -> s.sensitivity,
                value -> s.sensitivity = (int) value, 1, 5, 1, ""); y += 30;
        settingToggle(g, "smartAdjustment", "Smart adjustment", y, () -> s.smartAdjustment,
                () -> s.smartAdjustment = !s.smartAdjustment); y += 27;
        slider(g, "alpha", "Alpha", y, () -> s.alpha,
                value -> s.alpha = (int) value, 0, 255, 1, ""); y += 31;
        colorRow(g, "Highlight color", y, () -> s.color, color -> s.color = color); y += 30;
        section(g, "INDICATORS", y); y += 17;
        settingToggle(g, "kelp", "Kelp", y, () -> s.kelp, () -> s.kelp = !s.kelp); y += 23;
        settingToggle(g, "caveVines", "Cave vines", y, () -> s.caveVines, () -> s.caveVines = !s.caveVines); y += 23;
        settingToggle(g, "vines", "Vines", y, () -> s.vines, () -> s.vines = !s.vines); y += 23;
        settingToggle(g, "amethyst", "Amethyst", y, () -> s.amethyst, () -> s.amethyst = !s.amethyst); y += 23;
        settingToggle(g, "bamboo", "Bamboo", y, () -> s.bamboo, () -> s.bamboo = !s.bamboo); y += 23;
        settingToggle(g, "cocoa", "Cocoa", y, () -> s.cocoa, () -> s.cocoa = !s.cocoa); y += 23;
        settingToggle(g, "beeNest", "Bee nest", y, () -> s.beeNest, () -> s.beeNest = !s.beeNest);
    }

    private void drawAutoBuilderSettings(GuiGraphics g, int y) {
        section(g, "SCHEMATICS", y);
        text(g, font, ".litematic previews and automatic building", rowX, y + 18, MUTED);
        text(g, font, "Select a file, position it, then choose Start.", rowX, y + 34, DIM);
        round(g, rowX, y + 53, rowW, 25, 5, blend(CARD, config.accentColor, .28));
        text(g, font, "Open AutoBuilder", rowX + 12, y + 61, TEXT);
        text(g, font, ">", rowX + rowW - 15, y + 61, MUTED);
        hit("openAutoBuilder", rowX, y + 53, rowW, 25, () -> toggleEnabled(3));
        text(g, font, "Includes materials, ghost preview and navigation", rowX, y + 89, DIM);
    }
    private boolean canUseFreecam() { return minecraft.level != null && minecraft.player != null; }

    private void freecamToggle(GuiGraphics g, String id, int x, int y) {
        if (!config.freecam.enabled && !canUseFreecam()) {
            round(g, x, y, 23, 12, 6, 0xFF262A34);
            round(g, x + 2, y + 2, 8, 8, 4, DIM);
            return;
        }
        toggle(g, id, x, y, config.freecam.enabled, () -> toggleEnabled(2));
    }

    private void drawFinderSettings(GuiGraphics g, int index, int y) {
        FinderSettings s = settings(index);
        section(g, "RENDERING", y); y += 17;
        modeRow(g, s, y); y += 27;
        slider(g, "outlineOpacity", "Outline opacity", y, () -> s.outlineOpacity, v -> s.outlineOpacity = v, 0, 1, .01, "%"); y += 28;
        slider(g, "fillOpacity", "Fill opacity", y, () -> s.fillOpacity, v -> s.fillOpacity = v, 0, 1, .01, "%"); y += 28;
        slider(g, "lineWidth", "Outline width", y, () -> s.lineWidth, v -> s.lineWidth = v, .5, 5, .1, " px"); y += 28;
        slider(g, "range", "Detection range", y, () -> s.range, v -> s.range = v, 16, 256, 1, " blocks"); y += 28;
        settingToggle(g, "walls", "Through walls", y, () -> s.throughWalls, () -> s.throughWalls = !s.throughWalls); y += 23;
        settingToggle(g, "fade", "Distance fade", y, () -> s.distanceFade, () -> s.distanceFade = !s.distanceFade); y += 28;
        group(g, "groupColors", "Colors", y, colorsExpanded[index], () -> colorsExpanded[index] = !colorsExpanded[index]); y += 20;
        int colorsY = y;
        if (colorHeight(index) > 0) {
            Clip previousClip = pushClip(g, rowX - 2, y, rowW + 4, colorHeight(index));
            double previousOpacity = pushOpacity(smooth(colorsExpansion[index]));
            boolean previousInteractive = contentInteractive;
            contentInteractive &= colorsExpanded[index];
            try {
            colorRow(g, "Base highlight", y, () -> s.color, value -> s.color = value); y += 24;
            if (index == 0) {
                settingToggle(g, "perType", "Colors by block type", y, () -> s.perTypeColors, () -> s.perTypeColors = !s.perTypeColors); y += 24;
                if (s.perTypeColors) {
                    colorRow(g, "Chests", y, () -> s.chestColor, value -> s.chestColor = value); y += 24;
                    colorRow(g, "Shulkers", y, () -> s.shulkerColor, value -> s.shulkerColor = value); y += 24;
                    colorRow(g, "Droppers", y, () -> s.dropperColor, value -> s.dropperColor = value); y += 24;
                }
            }
            } finally {
                contentInteractive = previousInteractive;
                popOpacity(previousOpacity);
                popClip(g, previousClip);
            }
        }
        y = colorsY + colorHeight(index);
        group(g, "groupFilters", "Block filters", y, filtersExpanded[index], () -> filtersExpanded[index] = !filtersExpanded[index]); y += 20;
        int filtersY = y;
        if (filterHeight(index) > 0) {
            Clip previousClip = pushClip(g, rowX - 2, y, rowW + 4, filterHeight(index));
            double previousOpacity = pushOpacity(smooth(filtersExpansion[index]));
            boolean previousInteractive = contentInteractive;
            contentInteractive &= filtersExpanded[index];
            try {
            if (index == 0) {
                settingToggle(g, "chests", "Chests", y, () -> s.chests, () -> s.chests = !s.chests); y += 23;
                settingToggle(g, "trapped", "Trapped chests", y, () -> s.trappedChests, () -> s.trappedChests = !s.trappedChests); y += 23;
                settingToggle(g, "ender", "Ender chests", y, () -> s.enderChests, () -> s.enderChests = !s.enderChests); y += 23;
                settingToggle(g, "shulkers", "Shulker boxes", y, () -> s.shulkers, () -> s.shulkers = !s.shulkers); y += 23;
                settingToggle(g, "droppers", "Droppers", y, () -> s.droppers, () -> s.droppers = !s.droppers); y += 23;
            } else {
                settingToggle(g, "trial", "Trial spawners", y, () -> s.trialSpawners, () -> s.trialSpawners = !s.trialSpawners); y += 23;
                text(g, font, "Monster spawners are always included.", rowX, y + 3, DIM); y += 23;
            }
            } finally {
                contentInteractive = previousInteractive;
                popOpacity(previousOpacity);
                popClip(g, previousClip);
            }
        }
        y = filtersY + filterHeight(index);
        text(g, font, "Highlights use loaded chunks only.", rowX, y + 7, DIM);
    }

    private void drawFreecamSettings(GuiGraphics g, int y) {
        var s = config.freecam;
        section(g, "MOVEMENT", y); y += 17;
        slider(g, "freecamSpeed", "Camera speed", y, () -> s.speed, value -> s.speed = value, .1, 5, .1, " blocks/tick"); y += 30;
        slider(g, "freecamBoost", "Sprint boost", y, () -> s.boostMultiplier, value -> s.boostMultiplier = value, 1, 10, .1, "x"); y += 36;
        section(g, "YOUR CONTROLS", y); y += 18;
        controlHint(g, "Move", keyName(minecraft.options.keyUp) + " / " + keyName(minecraft.options.keyLeft)
                + " / " + keyName(minecraft.options.keyDown) + " / " + keyName(minecraft.options.keyRight), y); y += 20;
        controlHint(g, "Up / down", keyName(minecraft.options.keyJump) + " / " + keyName(minecraft.options.keyShift), y); y += 20;
        controlHint(g, "Speed boost", keyName(minecraft.options.keySprint), y); y += 25;
        text(g, font, canUseFreecam() ? "Close this menu to fly. Disable to return." : "Join a world to enable Freecam.", rowX, y, MUTED);
    }

    private String keyName(KeyMapping key) { return key.getTranslatedKeyMessage().getString(); }

    private void keybindButton(GuiGraphics g, int index, int x, int y, int w, int h) {
        KeyMapping mapping = ModuleKeybinds.mapping(index);
        if (mapping == null) return;
        boolean capturing = binding == index;
        boolean hovered = inside(x, y, w, h) && (!collectingClip || mouseY >= TOP && mouseY < BOTTOM);
        if (hovered) hoveredBinding = index;
        boolean conflict = !conflicts(index).isEmpty();
        double emphasis = animate("bindHover" + index, hovered || capturing ? 1 : 0);
        round(g, x, y, w, h, 3, blend(0xFF090C12, config.accentColor, .10 * emphasis));
        outline(g, x, y, w, h, 3, blend(BORDER, config.accentColor, capturing ? .85 : .28 * emphasis));
        String value = capturing ? "Press a key..." : (conflict ? "! Key: " : "Key: ")
                + (mapping.isUnbound() ? "None" : keyName(mapping));
        value = ellipsize(value, (int) ((w - 12) / .85));
        smallText(g, font, value, x + 6f, y + 4f, capturing ? TEXT : conflict ? 0xFFFFC66B : MUTED);
        hit("keybind" + index, x, y, w, h, () -> {
            binding = index;
            dragging = null;
            searchFocused = false;
            keyboardFocus = null;
        });
    }

    private String conflicts(int index) {
        KeyMapping mapping = ModuleKeybinds.mapping(index);
        if (mapping == null || mapping.isUnbound()) return "";
        List<String> names = new ArrayList<>();
        for (KeyMapping other : minecraft.options.keyMappings) {
            if (other != mapping && mapping.same(other)) names.add(Component.translatable(other.getName()).getString());
        }
        return String.join(", ", names);
    }

    private void drawFooter(GuiGraphics g) {
        String message = "Left click: toggle  /  Right click: settings";
        int color = MUTED;
        if (binding >= 0) {
            message = "Press key or mouse button. Delete clears. ESC cancels.";
            color = TEXT;
        } else if (hoveredBinding >= 0 && colorEditor == null) {
            String conflict = conflicts(hoveredBinding);
            KeyMapping mapping = ModuleKeybinds.mapping(hoveredBinding);
            message = conflict.isEmpty()
                    ? (mapping.isUnbound() ? "Click to bind a key or mouse button."
                    : "Bound to " + keyName(mapping) + ". Click to change.")
                    : "Also used by: " + conflict;
            if (!conflict.isEmpty()) color = 0xFFFFC66B;
        } else if (tab == 2) message = "Changes save automatically";
        text(g, font, ellipsize(message, CW - 82), CX + 3, H - 17, color);
        right(g, font, binding >= 0 ? "ESC Cancel" : "ESC Close", W - 14, H - 17, DIM);
    }

    private String ellipsize(String value, int maxWidth) {
        if (UiPaint.width(font, value) <= maxWidth) return value;
        while (!value.isEmpty() && UiPaint.width(font, value + "...") > maxWidth) value = removeLastCharacter(value);
        return value + "...";
    }

    private void controlHint(GuiGraphics g, String label, String keys, int y) {
        text(g, font, label, rowX, y, TEXT);
        right(g, font, ellipsize(keys, rowW - 91), rowX + rowW, y, MUTED);
    }

    private void clampScroll() {
        int maxScroll = Math.max(0, contentHeight - (BOTTOM - TOP));
        scroll = Math.clamp(scroll, 0, maxScroll);
        scrollTarget = Math.clamp(scrollTarget, 0, maxScroll);
    }

    private void beginContent(GuiGraphics g) {
        g.enableScissor(CX, TOP, CX + CW, BOTTOM);
        collectingClip = true;
        clipTop = TOP;
        clipBottom = BOTTOM;
    }

    private Clip pushClip(GuiGraphics g, int x, int y, int w, int h) {
        Clip previous = new Clip(clipTop, clipBottom);
        clipTop = Math.max(clipTop, y);
        clipBottom = Math.min(clipBottom, y + h);
        g.enableScissor(x, y, x + w, y + Math.max(0, h));
        return previous;
    }

    private void popClip(GuiGraphics g, Clip previous) {
        g.disableScissor();
        clipTop = previous.top;
        clipBottom = previous.bottom;
    }

    private void endContent(GuiGraphics g) {
        collectingClip = false;
        g.disableScissor();
        int viewHeight = BOTTOM - TOP;
        int maxScroll = Math.max(0, contentHeight - viewHeight);
        if (maxScroll > 0) {
            round(g, CX + CW - 3, TOP + 2, 3, viewHeight - 4, 1, CARD);
            int thumbHeight = Math.max(25, viewHeight * viewHeight / contentHeight);
            int thumbY = TOP + (int) (scroll / maxScroll * (viewHeight - thumbHeight));
            round(g, CX + CW - 3, thumbY, 3, thumbHeight, 1, alpha(config.accentColor, .65));
            dragHit("scrollbar", CX + CW - 6, TOP, 7, viewHeight,
                    value -> scroll = scrollTarget = Math.clamp(value, 0, 1) * maxScroll, true);
        }
    }

    private void section(GuiGraphics g, String title, int y) {
        text(g, font, title, rowX, y, DIM);
        int start = rowX + UiPaint.width(font, title) + 9;
        round(g, start, y + 4, Math.max(0, rowX + rowW - start), 1, 0, BORDER);
    }

    private void group(GuiGraphics g, String id, String title, int y, boolean open, Runnable action) {
        text(g, font, open ? "-" : "+", rowX, y + 4, MUTED);
        text(g, font, title, rowX + 12, y + 4, MUTED);
        int start = rowX + 20 + UiPaint.width(font, title);
        round(g, start, y + 8, Math.max(0, rowX + rowW - start), 1, 0, BORDER);
        hit(rowPrefix + id, rowX - 2, y, rowW + 4, 19, action);
    }

    private void modeRow(GuiGraphics g, FinderSettings s, int y) {
        text(g, font, "Render mode", rowX, y + 6, TEXT);
        int x = rowX + rowW - 171;
        for (RenderMode mode : RenderMode.values()) {
            final int bx = x;
            boolean active = s.mode == mode;
            round(g, x, y, 55, 20, 4, active ? blend(CARD, config.accentColor, .46) : 0xFF0B0E14);
            String label = modeName(mode);
            text(g, font, label, x + (55 - UiPaint.width(font, label)) / 2, y + 6, active ? TEXT : MUTED);
            hit(rowPrefix + "mode" + mode, bx, y, 55, 20, () -> change(() -> s.mode = mode));
            x += 58;
        }
    }

    private String modeName(RenderMode mode) {
        return switch (mode) { case OUTLINE -> "Outline"; case FILL -> "Filled"; case BOTH -> "Both"; };
    }

    private void slider(GuiGraphics g, String id, String label, int y, DoubleSupplier getter,
                        DoubleConsumer setter, double min, double max, double step, String unit) {
        double value = getter.getAsDouble();
        text(g, font, label, rowX, y, TEXT);
        String display = unit.equals("%") ? Math.round(value * 100) + "%"
                : step >= 1 ? Math.round(value) + unit : String.format(Locale.ROOT, "%.1f%s", value, unit);
        right(g, font, display, rowX + rowW, y, MUTED);
        int sx = rowX + 3, sw = rowW - 6;
        double t = Math.clamp((value - min) / (max - min), 0, 1);
        round(g, sx, y + 17, sw, 4, 2, 0xFF30303F);
        round(g, sx, y + 17, Math.max(4, (int) (sw * t)), 4, 2, alpha(config.accentColor, 1));
        round(g, sx + (int) (sw * t) - 3, y + 15, 7, 8, 4, TEXT);
        dragHit(rowPrefix + id, sx, y + 11, sw, 16, position -> {
            double next = Math.round((min + Math.clamp(position, 0, 1) * (max - min)) / step) * step;
            setter.accept(Math.clamp(next, min, max)); dirty = true;
        }, false, direction -> { setter.accept(Math.clamp(getter.getAsDouble() + direction * step, min, max)); dirty = true; flush(); });
    }

    private void settingToggle(GuiGraphics g, String id, String label, int y, BooleanSupplier value, Runnable action) {
        text(g, font, label, rowX, y + 5, TEXT);
        toggle(g, rowPrefix + id, rowX + rowW - 23, y + 3, value.getAsBoolean(), () -> change(action));
    }

    private void toggle(GuiGraphics g, String id, int x, int y, boolean on, Runnable action) {
        double position = animate("toggle" + id, on ? 1 : 0);
        round(g, x, y, 23, 12, 6, blend(0xFF343844, config.accentColor, position));
        round(g, x + 2 + (float) (11 * position), y + 2, 8, 8, 4, blend(0xFF9B9FAC, 0xFFFFFFFF, position));
        hit(id, x - 3, y - 4, 29, 20, action);
    }

    private void colorRow(GuiGraphics g, String label, int y, IntSupplier getter, IntConsumer setter) {
        text(g, font, label, rowX, y + 6, TEXT);
        int x = rowX + rowW - 101;
        round(g, x, y, 101, 20, 4, 0xFF0B0E14);
        outline(g, x, y, 101, 20, 4, BORDER);
        round(g, x + 7, y + 5, 10, 10, 3, alpha(getter.getAsInt(), 1));
        text(g, font, hex(getter.getAsInt()), x + 25, y + 6, MUTED);
        text(g, font, ">", x + 88, y + 6, DIM);
        hit(rowPrefix + "color" + label, x, y, 101, 20,
                () -> { colorEditor = new ColorEditor(label, getter, setter); modalProgress = 0; keyboardFocus = null; });
    }

    private void drawAppearance(GuiGraphics g) {
        contentHeight = 225;
        clampScroll();
        beginContent(g);
        round(g, CX, TOP, CW - 8, 216, 7, CARD);
        outline(g, CX, TOP, CW - 8, 216, 7, BORDER);
        rowX = CX + 12;
        rowW = CW - 32;
        rowPrefix = "";
        int y = TOP + 14;
        section(g, "INTERFACE", y); y += 23;
        colorRow(g, "Accent color", y, () -> config.accentColor, value -> config.accentColor = value); y += 29;
        settingToggle(g, "hud", "Array list", y, () -> config.showHud, () -> config.showHud = !config.showHud); y += 37;
        section(g, "CONTROLS", y); y += 21;
        text(g, font, "Left click a module or its switch to toggle it.", rowX, y, MUTED); y += 18;
        text(g, font, "Right click its header to open or close settings.", rowX, y, MUTED); y += 18;
        text(g, font, "Tab navigates. Shift + Enter opens a focused module.", rowX, y, MUTED); y += 18;
        text(g, font, "Star favorites, drag sliders, and click the key box to assign a shortcut.", rowX, y, DIM);
        endContent(g);
    }

    private void drawColorEditor(GuiGraphics g) {
        // Modal hit testing replaces the underlying settings' controls.
        hits.clear();
        g.fill(0, 0, W, H, withOpacity(0xAC06090F));
        int w = 318, h = 252, x = (W - w) / 2, y = (H - h) / 2;
        round(g, x - 1, y - 1, w + 2, h + 2, 11, BORDER);
        round(g, x, y, w, h, 10, PANEL);
        strong(g, font, colorEditor.title, x + 18, y + 17, TEXT);
        text(g, font, "Choose a color", x + 18, y + 35, MUTED);
        round(g, x + w - 53, y + 16, 33, 33, 8, alpha(colorEditor.getter.getAsInt(), 1));
        for (int i = 0; i < PALETTE.length; i++) {
            int px = x + 18 + i * 24;
            int color = PALETTE[i];
            round(g, px, y + 61, 18, 18, 5, alpha(color, 1));
            if (colorEditor.getter.getAsInt() == color) outline(g, px - 2, y + 59, 22, 22, 6, TEXT);
            hit("swatch" + i, px - 2, y + 59, 22, 22, () -> { colorEditor.set(color); flush(); });
        }
        colorChannel(g, "Red", 16, 0xFFF17886, x, y + 99);
        colorChannel(g, "Green", 8, 0xFF84DCB3, x, y + 133);
        colorChannel(g, "Blue", 0, 0xFF81AEFF, x, y + 167);
        round(g, x + 18, y + 210, 141, 27, 6, colorEditor.hexFocused ? BORDER : CARD);
        String value = "#" + colorEditor.hex + (colorEditor.hexFocused && System.currentTimeMillis() % 1000 < 500 ? "|" : "");
        text(g, font, value, x + 29, y + 220, colorEditor.hex.length() == 6 ? TEXT : 0xFFFFC66B);
        hit("hex", x + 18, y + 210, 141, 27, () -> { colorEditor.hexFocused = true; colorEditor.replaceHex = true; });
        round(g, x + w - 111, y + 210, 93, 27, 6, alpha(config.accentColor, 1));
        text(g, font, "Done", x + w - 80, y + 220, 0xFFFFFFFF);
        hit("colorDone", x + w - 111, y + 210, 93, 27, this::closeColor);
    }

    private void colorChannel(GuiGraphics g, String label, int shift, int tint, int x, int y) {
        int value = (colorEditor.getter.getAsInt() >> shift) & 255;
        text(g, font, label, x + 18, y, tint);
        right(g, font, Integer.toString(value), x + 298, y, MUTED);
        int sx = x + 76, sw = 181;
        round(g, sx, y + 3, sw, 4, 2, BORDER);
        round(g, sx, y + 3, Math.max(4, (int) (sw * value / 255.0)), 4, 2, tint);
        round(g, sx + (int) (sw * value / 255.0) - 4, y, 9, 10, 4, TEXT);
        dragHit("rgb" + shift, sx, y - 5, sw, 20, t -> {
            int channel = (int) Math.round(Math.clamp(t, 0, 1) * 255);
            int next = (colorEditor.getter.getAsInt() & ~(255 << shift)) | channel << shift;
            colorEditor.set(next);
        }, false, direction -> {
            int channel = Math.clamp(((colorEditor.getter.getAsInt() >> shift) & 255) + direction, 0, 255);
            colorEditor.set((colorEditor.getter.getAsInt() & ~(255 << shift)) | channel << shift);
            flush();
        });
    }

    private void closeColor() { colorEditor = null; dragging = null; keyboardFocus = null; flush(); }
    private String hex(int color) { return String.format(Locale.ROOT, "#%06X", color & 0xFFFFFF); }

    private double ease(double current, double target, double speed) {
        double result = current + (target - current) * (1 - Math.exp(-speed * frameSeconds));
        return Math.abs(result - target) < .001 ? target : result;
    }

    private double advance(double value, boolean showing, double duration) {
        return Math.clamp(value + (showing ? 1 : -1) * frameSeconds / duration, 0, 1);
    }

    private static double smooth(double value) { return value * value * (3 - 2 * value); }

    private double animate(String id, double target) {
        double result = ease(animations.getOrDefault(id, target), target, 16);
        animations.put(id, result);
        return result;
    }

    private boolean inside(int x, int y, int w, int h) {
        return mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
    }

    private void hit(String id, int x, int y, int w, int h, Runnable action) {
        hit(id, x, y, w, h, action, null);
    }

    private void hit(String id, int x, int y, int w, int h, Runnable action, Runnable secondary) {
        if (!contentInteractive || closing) return;
        int top = collectingClip ? Math.max(y, clipTop) : y;
        int bottom = collectingClip ? Math.min(y + h, clipBottom) : y + h;
        if (top >= bottom) return;
        hits.add(new Hit(id, x, top, w, bottom - top, action, secondary, null, false, null));
    }

    private void dragHit(String id, int x, int y, int w, int h, DoubleConsumer action, boolean vertical) {
        dragHit(id, x, y, w, h, action, vertical, null);
    }

    private void dragHit(String id, int x, int y, int w, int h, DoubleConsumer action, boolean vertical, IntConsumer nudge) {
        if (!contentInteractive || closing) return;
        int top = collectingClip ? Math.max(y, clipTop) : y;
        int bottom = collectingClip ? Math.min(y + h, clipBottom) : y + h;
        if (top >= bottom) return;
        hits.add(new Hit(id, x, top, w, bottom - top, null, null, action, vertical, nudge));
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        if (closing) return true;
        if (binding >= 0) {
            ModuleKeybinds.setKey(minecraft, binding, InputConstants.Type.MOUSE.getOrCreate(event.button()));
            binding = -1;
            return true;
        }
        boolean rightClick = event.button() == GLFW.GLFW_MOUSE_BUTTON_RIGHT;
        if (event.button() != GLFW.GLFW_MOUSE_BUTTON_LEFT && !rightClick) return false;
        double x = (event.x() - originX) / scale, y = (event.y() - originY) / scale;
        keyboardFocus = null;
        searchFocused = false;
        if (colorEditor != null) colorEditor.hexFocused = false;
        for (int i = hits.size() - 1; i >= 0; i--) {
            Hit h = hits.get(i);
            if (!h.contains(x, y)) continue;
            if (rightClick) {
                // A header's small left-click controls do not obstruct right-click expansion.
                if (h.secondary == null) continue;
                h.secondary.run();
            } else if (h.drag != null) { dragging = h; h.move(x, y); }
            else h.action.run();
            return true;
        }
        return true;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (closing) return true;
        if (dragging == null) return false;
        dragging.move((event.x() - originX) / scale, (event.y() - originY) / scale);
        return true;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (dragging != null) { dragging = null; flush(); return true; }
        return false;
    }

    @Override
    public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        if (closing || colorEditor != null || binding >= 0) return true;
        double localX = (x - originX) / scale, localY = (y - originY) / scale;
        if (localX >= CX && localX <= CX + CW && localY >= TOP && localY <= BOTTOM) {
            scrollTarget = Math.clamp(scrollTarget - vertical * 31, 0, Math.max(0, contentHeight - (BOTTOM - TOP)));
            keyboardFocus = null;
            return true;
        }
        return false;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (closing) return true;
        // A held capture/cancel key must not repeat into ordinary GUI navigation.
        InputConstants.Key pressed = InputConstants.getKey(event);
        if (pressed.equals(openingKey)) return true;
        if (pressed.equals(capturedKey)) return true;
        if (binding >= 0) {
            capturedKey = pressed;
            if (event.key() != GLFW.GLFW_KEY_ESCAPE) {
                InputConstants.Key key = event.key() == GLFW.GLFW_KEY_DELETE || event.key() == GLFW.GLFW_KEY_BACKSPACE
                        ? InputConstants.UNKNOWN : pressed;
                ModuleKeybinds.setKey(minecraft, binding, key);
            }
            binding = -1;
            return true;
        }
        KeyMapping open = KeyMapping.get("key.donut.open_gui");
        if (open != null && !open.isUnbound() && pressed.equals(InputConstants.getKey(open.saveString()))) {
            onClose();
            return true;
        }
        if (event.key() == GLFW.GLFW_KEY_ESCAPE) {
            if (colorEditor != null) closeColor();
            else if (searchFocused) { searchFocused = false; query = ""; contentProgress = 0; }
            else onClose();
            return true;
        }
        if (colorEditor != null && colorEditor.hexFocused) {
            if (event.key() == GLFW.GLFW_KEY_V && event.hasControlDown()) {
                String pasted = minecraft.keyboardHandler.getClipboard().trim().replaceFirst("^#", "");
                if (pasted.matches("(?i)[0-9a-f]{6}")) { colorEditor.set(Integer.parseInt(pasted, 16)); flush(); }
                return true;
            }
            if (event.key() == GLFW.GLFW_KEY_C && event.hasControlDown()) {
                minecraft.keyboardHandler.setClipboard("#" + colorEditor.hex); return true;
            }
            if (event.key() == GLFW.GLFW_KEY_BACKSPACE) {
                colorEditor.hex = colorEditor.replaceHex ? "" : colorEditor.hex.substring(0, Math.max(0, colorEditor.hex.length() - 1));
                colorEditor.replaceHex = false;
                return true;
            }
            if (event.key() == GLFW.GLFW_KEY_ENTER) { closeColor(); return true; }
            if (event.key() == GLFW.GLFW_KEY_A && (event.modifiers() & GLFW.GLFW_MOD_CONTROL) != 0) {
                colorEditor.replaceHex = true; return true;
            }
        }
        if (searchFocused) {
            if (event.key() == GLFW.GLFW_KEY_V && event.hasControlDown()) {
                String pasted = minecraft.keyboardHandler.getClipboard().replaceAll("[\\p{Cntrl}]", "");
                query = (query + pasted).substring(0, Math.min(64, query.length() + pasted.length())); contentProgress = 0; return true;
            }
            if (event.key() == GLFW.GLFW_KEY_BACKSPACE) { query = removeLastCharacter(query); contentProgress = 0; return true; }
            if (event.key() == GLFW.GLFW_KEY_ENTER) { searchFocused = false; return true; }
            if (event.key() == GLFW.GLFW_KEY_A && (event.modifiers() & GLFW.GLFW_MOD_CONTROL) != 0) { query = ""; contentProgress = 0; return true; }
        }
        if (event.key() == GLFW.GLFW_KEY_TAB && !hits.isEmpty()) {
            int index = -1;
            for (int i = 0; i < hits.size(); i++) if (hits.get(i).id.equals(keyboardFocus)) index = i;
            int direction = (event.modifiers() & GLFW.GLFW_MOD_SHIFT) != 0 ? -1 : 1;
            keyboardFocus = hits.get(Math.floorMod(index + direction, hits.size())).id;
            searchFocused = false;
            return true;
        }
        if (keyboardFocus != null && (event.key() == GLFW.GLFW_KEY_ENTER || event.key() == GLFW.GLFW_KEY_SPACE)) {
            hits.stream().filter(h -> h.id.equals(keyboardFocus)).findFirst().ifPresent(h -> {
                if (h.secondary != null && (event.modifiers() & GLFW.GLFW_MOD_SHIFT) != 0) h.secondary.run();
                else if (h.action != null) h.action.run();
            });
            // Enter/Space that opens capture is not itself the new binding on repeat.
            if (binding >= 0) capturedKey = pressed;
            return true;
        }
        if (keyboardFocus != null && (event.key() == GLFW.GLFW_KEY_LEFT || event.key() == GLFW.GLFW_KEY_RIGHT)) {
            hits.stream().filter(h -> h.id.equals(keyboardFocus)).findFirst().ifPresent(h -> {
                if (h.nudge != null) h.nudge.accept(event.key() == GLFW.GLFW_KEY_RIGHT ? 1 : -1);
            });
            return true;
        }
        if (colorEditor == null && !searchFocused) {
            if (event.key() == GLFW.GLFW_KEY_PAGE_DOWN || event.key() == GLFW.GLFW_KEY_PAGE_UP) {
                scrollTarget = Math.clamp(scrollTarget + (event.key() == GLFW.GLFW_KEY_PAGE_DOWN ? 170 : -170), 0, Math.max(0, contentHeight - (BOTTOM - TOP)));
                keyboardFocus = null; return true;
            }
            if (event.key() == GLFW.GLFW_KEY_HOME) { scrollTarget = 0; return true; }
            if (event.key() == GLFW.GLFW_KEY_END) { scrollTarget = Math.max(0, contentHeight - (BOTTOM - TOP)); return true; }
        }
        return false;
    }

    @Override
    public boolean keyReleased(KeyEvent event) {
        if (InputConstants.getKey(event).equals(openingKey)) openingKey = null;
        if (InputConstants.getKey(event).equals(capturedKey)) capturedKey = null;
        return super.keyReleased(event);
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        if (closing || binding >= 0 || capturedKey != null) return true;
        if (colorEditor != null && colorEditor.hexFocused) {
            int c = event.codepoint();
            if (Character.digit(c, 16) >= 0 && c < 128) {
                if (colorEditor.replaceHex) { colorEditor.hex = ""; colorEditor.replaceHex = false; }
                if (colorEditor.hex.length() < 6) colorEditor.hex += Character.toString(c).toUpperCase(Locale.ROOT);
                if (colorEditor.hex.length() == 6) { colorEditor.setter.accept(Integer.parseInt(colorEditor.hex, 16)); dirty = true; flush(); }
            }
            return true;
        }
        if (searchFocused && event.isAllowedChatCharacter() && query.length() < 64) {
            query += event.codepointAsString();
            contentProgress = 0;
            return true;
        }
        return false;
    }

    private void change(Runnable action) { action.run(); dirty = true; flush(); }
    private void flush() { if (dirty) { save.run(); dirty = false; } }
    private String removeFirstCharacter(String value) { return value.isEmpty() ? value : value.substring(value.offsetByCodePoints(0, 1)); }
    private String removeLastCharacter(String value) { return value.isEmpty() ? value : value.substring(0, value.offsetByCodePoints(value.length(), -1)); }

    @Override
    public void tick() {
        // Complete removal during the input tick so cleanup cannot consume the next fresh key press.
        if (closing && screenProgress == 0 && minecraft.screen == this) minecraft.setScreen(null);
    }

    @Override
    public void onClose() {
        if (closing) return;
        flush();
        closing = true;
        dragging = null;
        binding = -1;
        keyboardFocus = null;
        searchFocused = false;
    }

    @Override
    public void removed() {
        binding = -1;
        capturedKey = null;
        ModuleKeybinds.suppressUntilReleased(minecraft);
        flush();
    }

    @Override
    public boolean isPauseScreen() { return false; }

    private record Hit(String id, int x, int y, int w, int h, Runnable action, Runnable secondary, DoubleConsumer drag, boolean vertical, IntConsumer nudge) {
        boolean contains(double mx, double my) { return mx >= x && my >= y && mx < x + w && my < y + h; }
        void move(double mx, double my) { drag.accept(vertical ? (my - y) / h : (mx - x) / w); }
    }

    private record Clip(int top, int bottom) { }

    private final class ColorEditor {
        final String title;
        final IntSupplier getter;
        final IntConsumer setter;
        String hex;
        boolean hexFocused;
        boolean replaceHex;
        ColorEditor(String title, IntSupplier getter, IntConsumer setter) {
            this.title = title;
            this.getter = getter;
            this.setter = setter;
            this.hex = DonutScreen.this.hex(getter.getAsInt()).substring(1);
        }
        void set(int color) {
            setter.accept(color & 0xFFFFFF);
            hex = DonutScreen.this.hex(color).substring(1);
            dirty = true;
        }
    }
}
