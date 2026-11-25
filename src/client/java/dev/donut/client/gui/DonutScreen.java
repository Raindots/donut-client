// Workspace presentation adapted from Spectre 26.2 by 4ldenz (GPL-3.0-only).
// Original Donut module integration retains its existing attribution.
package dev.donut.client.gui;

import com.mojang.blaze3d.platform.InputConstants;
import dev.donut.client.config.AimAssistSettings;
import dev.donut.client.config.AnchorMacroSettings;
import dev.donut.client.config.AutoTotemSettings;
import dev.donut.client.config.ClientConfig;
import dev.donut.client.config.ConfigProfileStore;
import dev.donut.client.config.ConfigStore;
import dev.donut.client.config.CrystalAuraSettings;
import dev.donut.client.config.DonutConfigPaths;
import dev.donut.client.config.PlayerEspSettings;
import dev.donut.client.config.ProgressBarSettings;
import dev.donut.client.config.TimeChangerSettings;
import dev.donut.client.config.TriggerbotSettings;
import dev.donut.client.input.ModuleKeybinds;
import dev.donut.client.autobuild.AutoBuilderController;
import dev.donut.client.fly.FlyController;
import dev.donut.client.progress.CountdownDuration;
import dev.donut.client.progress.ProgressTimers;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleFunction;
import java.util.function.DoubleSupplier;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

import static dev.donut.client.gui.UiPaint.*;
import static dev.donut.client.gui.WorkspacePaint.text;
import static dev.donut.client.gui.WorkspacePaint.strong;
import static dev.donut.client.gui.WorkspacePaint.smallText;
import static dev.donut.client.gui.WorkspacePaint.right;

/** Spectre's workspace presentation, connected to Donut's existing modules and settings. */
public final class DonutScreen extends Screen {
    private int W = 1320, H = 830;
    private static final int TEXT = 0xFFE7EAF2, MUTED = 0xFFC4C7D3, DIM = 0xFF959BAD;
    private static final int PANEL = 0xFF191B24, RAIL = 0xFF1C1F29, CARD = 0xFF252833;
    private static final int BORDER = 0xFF424655, FIELD = 0xFF15171E, BRAND = 0xFF7ACDF4;
    private static final int LIME = 0xFFCBDC56, SAGE = 0xFFB0DE77;
    private static final int CONTROL = 0xFF2A2E36, CONTROL_HOVER = 0xFF343945;
    private static final int CONTROL_TEXT = 0xFFF2F4F8, CONTROL_MUTED = 0xFFA8AEBA;
    private static final int COMBAT_TAB = 7;
    private static final int CONFIGS_TAB = 8;
    private int CX = 210, CW = 728, TOP = 273, BOTTOM = 785;
    private static final int CARD_HEADER = 78;
    private int railW, bodyX, bodyW, columns;
    private int detailX, detailW, detailTop, detailBottom, detailContentHeight;
    private double detailScroll, detailScrollTarget, detailProgress;
    private int selectedModule = -1, previewModule = -1, hoveredModule = -1;
    private float fontScale = 1;
    private int lastCategory = 5;
    private double tabIndicator;
    private double layoutUiScale = -1;
    private int appearanceSurface;
    private static final int[] PALETTE = {0x4C86FF, 0x68B4FF, 0x6DDAC5, 0x7EDD8E, 0xCBDC56,
            0xFFC66B, 0xFF975F, 0xFF707B, 0xEC80B5, 0xBA8CFF, 0xC8D1E8, 0xFFFFFF};

    private final ClientConfig config;
    private final Runnable save;
    private final ConfigStore activeStore;
    private final ConfigProfilesPanel profiles;
    private final ClickGuiParticles particles = new ClickGuiParticles();
    private final List<Hit> hits = new ArrayList<>();
    private final Map<String, Double> animations = new HashMap<>();
    private double screenProgress, contentProgress, modalProgress;
    private boolean closing;
    private InputConstants.Key openingKey;
    private float scale = 1;
    private float originX, originY;
    private double mouseX, mouseY;
    private int tab = 5;
    private double scroll;
    private double scrollTarget;
    private long lastFrame;
    private double frameSeconds;
    private int contentHeight;
    private String query = "";
    private boolean searchFocused;
    private boolean enabledOnly;
    private boolean dirty;
    private String basesFoundDraft;
    private boolean replaceBasesFound;
    private int editingTimer = -1;
    private String timerDurationDraft;
    private boolean replaceTimerDuration;
    private int timerError = -1;
    private Hit dragging;
    private String keyboardFocus;
    private ColorEditor colorEditor;
    private boolean scannerMethodMenu;
    private int modeX, modeY, modeW;
    private boolean collectingClip;
    private boolean contentInteractive = true;
    private int clipLeft, clipRight, clipTop, clipBottom;
    private int binding = -1;
    private int hoveredBinding = -1;
    private InputConstants.Key capturedKey;
    // Row coordinates and IDs are scoped to the card currently being rendered.
    private int rowX, rowW;
    private String rowPrefix = "";
    private int settingsCursor;
    private String soundStatus = "Capture received activity. Range does not request distant sounds.";

    public DonutScreen(ClientConfig config, Runnable save) {
        this(config, save, null, new ConfigProfileStore(
                DonutConfigPaths.resolve(FabricLoader.getInstance().getConfigDir()).profiles()));
    }

    public DonutScreen(ClientConfig config, ConfigStore activeStore, ConfigProfileStore profiles) {
        this(config, () -> activeStore.save(config), activeStore, profiles);
    }

    private DonutScreen(ClientConfig config, Runnable save, ConfigStore activeStore, ConfigProfileStore profileStore) {
        super(Component.literal("Donut Client"));
        this.config = config;
        this.save = save;
        this.activeStore = activeStore;
        profiles = new ConfigProfilesPanel(profileStore, config, () -> {
            ProgressTimers.tick(config);
            return ModuleKeybinds.profileSnapshot();
        }, this::loadProfile);
    }

    private void loadProfile(ConfigProfileStore.Profile profile) throws IOException {
        var keys = ModuleKeybinds.resolveProfile(profile.keybinds());
        ClientConfig next = ConfigStore.snapshot(profile.config());
        if (activeStore != null) {
            try {
                activeStore.saveOrThrow(next);
            } catch (IOException e) {
                throw new IOException("Could not save the active settings. Check that the config folder is writable.", e);
            }
        }
        ProgressTimers.captureAndPause(config);
        config.applyProfile(next);
        if (activeStore == null) save.run();
        dirty = false;
        ModuleKeybinds.applyProfile(minecraft, keys);
    }

    @Override
    protected void init() {
        // Spectre lays out in framebuffer pixels, independently of Minecraft's GUI scale.
        float guiScale = (float) minecraft.getWindow().getGuiScale();
        float displayW = width * guiScale, displayH = height * guiScale;
        float fit = Math.min(1, Math.min(displayW / 960f, displayH / 620f));
        float canvasW = displayW / fit, canvasH = displayH / fit;
        layoutUiScale = config.clickGuiStyle.scale;
        fontScale = (float) (Math.round(Math.clamp(canvasH / 1080f * layoutUiScale, .6, 3.5) * 20) / 20.0);
        float workspaceScale = Math.min(fontScale, Math.min(canvasW / 1000f, canvasH / 620f));
        // Above the reference's 1080p size, enlarge geometry with the type so
        // labels, fixed-height tiles and the header keep their original proportions.
        float zoom = Math.max(1, fontScale);
        W = Math.round(Math.min(canvasW - 32, 1320 * workspaceScale) / zoom);
        H = Math.round(Math.min(canvasH - 32, 830 * workspaceScale) / zoom);
        scale = fit * zoom / guiScale;
        fontScale /= zoom;
        originX = (width - W * scale) / 2f;
        originY = (height - H * scale) / 2f;
        railW = Math.round(Math.min(178, W * .17f));
        detailW = Math.round(Math.min(350, W * .32f));
        bodyX = railW + 28;
        bodyW = W - railW - detailW - 56;
        detailX = W - detailW - 16;
        CX = bodyX + 4;
        CW = bodyW - 8;
        TOP = 273;
        BOTTOM = H - 45;
        detailTop = 274;
        detailBottom = H - 46;
        columns = bodyW >= 500 ? 2 : 1;
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
        if (layoutUiScale != config.clickGuiStyle.scale && dragging == null) init();
        if (tab != 2 && tab != 6) {
            detailW = Math.round(Math.min(350, W * .32f));
            detailX = W - detailW - 16;
        }
        long now = System.nanoTime();
        frameSeconds = lastFrame == 0 ? 1.0 / 60 : Math.min(.1, (now - lastFrame) / 1_000_000_000.0);
        lastFrame = now;
        screenProgress = advance(screenProgress, !closing, closing ? .16 : .24);
        contentProgress = advance(contentProgress, true, .28);
        detailProgress = advance(detailProgress, true, .18);
        modalProgress = advance(modalProgress, colorEditor != null, .18);
        if (closing && screenProgress == 0) return;
        scroll = ease(scroll, scrollTarget, 18);
        detailScroll = ease(detailScroll, detailScrollTarget, 18);
        float open = (float) easeOut(screenProgress);
        originY = (height - H * scale) / 2f + 12 * (1 - open) * scale;
        mouseX = (mx - originX) / scale;
        mouseY = (my - originY) / scale;
        hoveredBinding = -1;
        hoveredModule = -1;
        hits.clear();
        double previousOpacity = pushOpacity(open);
        float previousFontScale = WorkspacePaint.pushScale(fontScale);
        g.pose().pushMatrix();
        try {
            g.fill(0, 0, width, height, withOpacity(alpha(0, config.clickGuiStyle.backgroundDim)));
            if (config.clickGuiParticles) particles.render(g, width, height, originX, originY,
                    W * scale, H * scale, 0xCBDC56, frameSeconds * config.clickGuiStyle.animationSpeed);
            g.pose().translate(originX, originY);
            g.pose().scale(scale, scale);
            drawFrame(g);
            if (tab == 2) drawAppearance(g);
            else if (tab == 6) drawHudWorkspace(g);
            else if (tab == CONFIGS_TAB) {
                drawCategories(g);
                int x = bodyX + 4;
                profiles.render(g, font, x, 92, W - x - 16, H - 138, mouseX, mouseY, fontScale, this::hit);
            } else {
                drawCategories(g);
                drawWorkspaceHeader(g);
                drawOverview(g);
                if (colorEditor == null && dragging == null && binding < 0 && hoveredModule >= 0
                        && hoveredModule != previewModule) {
                    previewModule = hoveredModule;
                    detailProgress = 0;
                    detailScroll = detailScrollTarget = 0;
                }
                drawDetails(g);
            }
            drawFooter(g);
            if ((scannerMethodMenu) && colorEditor == null) drawModeMenu(g);
            if (colorEditor != null) {
                double modalOpacity = pushOpacity(easeOut(modalProgress));
                try { drawColorEditor(g); }
                finally { popOpacity(modalOpacity); }
            }
            if (keyboardFocus != null) {
                hits.stream().filter(h -> h.id.equals(keyboardFocus)).findFirst().ifPresent(h ->
                        outline(g, h.x - 1, h.y - 1, h.w + 2, h.h + 2, 5, alpha(config.accentColor, .85)));
            }
        } finally {
            g.pose().popMatrix();
            WorkspacePaint.popScale(previousFontScale);
            popOpacity(previousOpacity);
        }
    }

    // Geometry, palette and motion are ported from Spectre's renderWorkspaceFrame.
    private void drawFrame(GuiGraphics g) {
        for (int i = 8; i >= 1; i--) {
            float spread = 2.5f * fontScale * i;
            round(g, -spread, -spread + 4 * fontScale, W + spread * 2, H + spread * 2,
                    18 + spread, 0x14000000);
        }
        surface(g, 0, 0, W, H, corner(18), PANEL, 0.94f);
        g.enableScissor(0, 0, W, 76);
        surface(g, 0, 0, W, H, corner(18), alpha(config.clickGuiStyle.tabBarColor, config.clickGuiStyle.tabBarOpacity), 0.9f);
        g.disableScissor();
        outline(g, 0, 0, W, H, corner(18), chromeBorder());
        line(g, 1, 76, W - 1, 76, chromeBorder());
        line(g, 1, H - 38, W - 1, H - 38, chromeBorder());
        round(g, 20, 21, 32, 32, 16, alpha(LIME, .12));
        DonutLogo.draw(g, 22, 23, 28, config.clickGuiStyle.animationSpeed);
        text(g, font, "DONUT", 62, 21, BRAND);
        text(g, font, "MODULE CONTROL", 62, Math.max(41, 21 + s(20)), DIM);
        String[] labels = {"Modules", "HUD", "Appearance"};
        int[] destinations = {lastCategory, 6, 2};
        int current = tab == 2 ? 2 : tab == 6 ? 1 : 0;
        int navW = Math.max(96, s(96)), navX = W - 24 - navW * labels.length - 34;
        for (int i = 0; i < labels.length; i++) {
            int x = 12 + navX + i * navW, target = destinations[i];
            double h = animate("topNav" + i, current == i || inside(x, 17, navW - 4, 42) ? 1 : 0);
            round(g, x, 17, navW - 4, 42, 9, alpha(LIME, h * .09));
            text(g, font, labels[i], x + (navW - 4 - WorkspacePaint.width(font, labels[i])) / 2, 30,
                    current == i ? TEXT : MUTED);
            String id = i == 0 ? "workspaceModules" : i == 1 ? "tab6" : "tab2";
            hit(id, x, 17, navW - 4, 42, () -> switchTab(target));
        }
        tabIndicator = ease(tabIndicator, current, 18);
        round(g, (float) (12 + navX + tabIndicator * navW + 20), 60, navW - 44, 2, 2, LIME);
        text(g, font, "x", W - 40, 29, MUTED);
        hit("close", W - 44, 24, 22, 26, this::onClose);
    }

    private void switchTab(int target) {
        if (tab == target && query.isEmpty()) return;
        commitBasesFound();
        if (!commitTimerDuration()) cancelTimerDuration();
        if (tab == CONFIGS_TAB) profiles.deactivate();
        tab = target;
        if (tab == CONFIGS_TAB) profiles.activate();
        query = "";
        if (target != 2 && target != 6) lastCategory = target;
        contentProgress = 0;
        scroll = scrollTarget = 0;
        detailScroll = detailScrollTarget = 0;
        searchFocused = false;
        keyboardFocus = null;
        dragging = null;
    }

    private void drawCategories(GuiGraphics g) {
        surface(g, 12, 86, railW, H - 122, corner(9), alpha(config.clickGuiStyle.panelsColor, config.clickGuiStyle.panelsOpacity), 0.9f);
        text(g, font, "WORKSPACE", 26, 99, BRAND);
        category(g, "All modules", 128, 5);
        category(g, "Visual", 178, 0);
        category(g, "Blatant", 221, 3);
        category(g, "Combat", 264, COMBAT_TAB);
        category(g, "Streaming", 307, 4);
        category(g, "Favorites", 347, 1);
        category(g, "Configs", 387, CONFIGS_TAB);
        if (tab == CONFIGS_TAB) return;
        int y = Math.max(430, H - 118);
        round(g, 29, y + 14, 6, 6, 3, enabledOnly ? SAGE : 0xFF685746);
        text(g, font, "Enabled only", 46, y + 10, enabledOnly ? 0xFFD9F3C8 : MUTED);
        hit("enabledOnly", 24, y, railW - 24, 27, () -> {
            enabledOnly = !enabledOnly;
            contentProgress = 0;
            scroll = scrollTarget = 0;
        });
    }

    private void category(GuiGraphics g, String label, int y, int target) {
        boolean chosen = tab == target;
        double hover = animate("category" + target, chosen || inside(18, y, railW - 12, 38) ? 1 : 0);
        round(g, 18, y, railW - 12, 38, 8, alpha(LIME, hover * .08));
        if (chosen) round(g, 18, y + 10, 3, 18, 2, LIME);
        int tint = chosen ? alpha(config.accentColor, 1) : 0xFF7A848C;
        if (target == 5) outline(g, 32, y + 13, 11, 11, 3, MUTED);
        else if (target == 0) camera(g, 30, y + 9, tint);
        else if (target == 3) caution(g, 30, y + 9, tint);
        else if (target == COMBAT_TAB) crystalIcon(g, 30, y + 10, tint);
        else if (target == 1) star(g, 31, y + 10, tint);
        else if (target == CONFIGS_TAB) {
            line(g, 33, y + 10, 45, y + 10, tint);
            outline(g, 31, y + 13, 16, 14, 3, tint);
            line(g, 35, y + 18, 43, y + 18, tint);
            line(g, 35, y + 22, 41, y + 22, tint);
        } else outline(g, 31, y + 13, 14, 12, 3, tint);
        text(g, font, label, 54, y + 12, chosen ? TEXT : MUTED);
        hit("tab" + target, 18, y, railW - 12, 38, () -> switchTab(target));
    }

    private String categoryTitle() {
        return categoryTitle(tab);
    }

    private String moduleCategory(int index) {
        return categoryTitle(moduleCategoryTab(index));
    }

    private static String categoryTitle(int category) {
        return switch (category) {
            case 0 -> "Visual";
            case 1 -> "Favorites";
            case 3 -> "Blatant";
            case 4 -> "Streaming";
            case COMBAT_TAB -> "Combat";
            default -> "All modules";
        };
    }

    private static int moduleCategoryTab(int index) {
        return switch (index) {
            case 3, 6 -> 3;
            case 7, 8, 9 -> 4;
            case ModuleKeybinds.CRYSTAL_AURA, ModuleKeybinds.AUTO_TOTEM, ModuleKeybinds.ANCHOR_MACRO,
                    ModuleKeybinds.TRIGGERBOT, ModuleKeybinds.AIM_ASSIST -> COMBAT_TAB;
            default -> 0;
        };
    }

    private void drawWorkspaceHeader(GuiGraphics g) {
        surface(g, bodyX + 4, 92, bodyW - 8, 110, corner(10), 0xFF252837, 0.78f);
        outline(g, bodyX + 4, 92, bodyW - 8, 110, corner(10), config.clickGuiStyle.liquidGlass ? 0x35FFF2D6 : 0xFF55492C);
        text(g, font, "YOUR MODULE DESK", bodyX + 20, 107, LIME);
        WorkspacePaint.title(g, font, categoryTitle(), bodyX + 20, 130, TEXT);
        text(g, font, "Hover to explore. Select to configure.", bodyX + 20, 160, MUTED);
        DonutLogo.draw(g, bodyX + bodyW - 108, 95, 100, config.clickGuiStyle.animationSpeed);
        int x = bodyX + 4, y = 220, w = bodyW - 8, h = Math.round(30 * fontScale);
        surface(g, x, y, w, h, 7 * fontScale, CONTROL, 0.82f);
        if (searchFocused) outline(g, x, y, w, h, 7 * fontScale, alpha(config.accentColor, .55));
        String shown = query.isEmpty() && !searchFocused ? "Search modules..."
                : query + (searchFocused && System.currentTimeMillis() % 1000 < 500 ? "|" : "");
        while (WorkspacePaint.width(font, shown) > w - 22 && !shown.isEmpty()) shown = removeFirstCharacter(shown);
        text(g, font, shown, x + Math.round(10 * fontScale), y + Math.round(7 * fontScale),
                query.isEmpty() ? CONTROL_MUTED : CONTROL_TEXT);
        hit("search", x, y, w, h, () -> searchFocused = true);
    }

    private String mascotModule() {
        int index = selectedModule >= 0 ? selectedModule : previewModule;
        return switch (index) {
            case 2 -> "freecam";
            case 3 -> "builder"; case 4 -> "sus-chunk-finder"; case 5 -> "fullbright";
            case 6 -> "fly"; case 7 -> "hud"; case 8 -> "name-protect"; case 9 -> "no-inv-leak";
            default -> "idle";
        };
    }

    private void drawOverview(GuiGraphics g) {
        int count = 0;
        for (int index = ModuleKeybinds.FIRST_MODULE; index < ModuleKeybinds.MODULE_LIMIT; index++) if (matches(index)) count++;
        contentHeight = ((count + columns - 1) / columns) * 92 + 10;
        clampScroll();
        beginContent(g);
        int ordinal = 0, tileW = (bodyW - 8 - 12 * (columns - 1)) / columns;
        for (int index = ModuleKeybinds.FIRST_MODULE; index < ModuleKeybinds.MODULE_LIMIT; index++) {
            if (!matches(index)) continue;
            double phase = easeOut(Math.clamp((easeOut(contentProgress) - Math.min(ordinal, 8) * .025) / .8, 0, 1));
            int x = CX + ordinal % columns * (tileW + 12);
            int y = TOP + ordinal / columns * 92 - (int) scroll + (int) ((1 - phase) * 12);
            double opacity = pushOpacity(phase);
            try { moduleTile(g, index, x, y, tileW); }
            finally { popOpacity(opacity); }
            ordinal++;
        }
        if (count == 0) {
            text(g, font, "No modules found", bodyX + 14, TOP + 26, CONTROL_TEXT);
            text(g, font, enabledOnly ? "Try disabling Enabled only." : "Try another name or category.",
                    bodyX + 14, TOP + 53, CONTROL_MUTED);
        }
        endContent(g);
    }

    private void moduleTile(GuiGraphics g, int index, int x, int y, int w) {
        boolean hovered = colorEditor == null && inside(x, y, w, 78) && mouseY >= TOP && mouseY < BOTTOM;
        if (hovered) hoveredModule = index;
        double hover = animate("tile" + index, hovered || selectedModule == index ? 1 : 0);
        surface(g, x, y, w, 78, corner(11), alpha(config.clickGuiStyle.cardsColor, config.clickGuiStyle.cardsOpacity), 0.86f);
        round(g, x, y, w, 78, corner(11), alpha(LIME, hover * .055));
        outline(g, x, y, w, 78, corner(11), selectedModule == index ? LIME : chromeBorder());
        if (enabled(index)) round(g, x + 1, y + 16, 2, 46, 2, LIME);
        text(g, font, ellipsize(name(index), w - 70), x + 14, y + 16, TEXT);
        text(g, font, index == 3 ? "ACTION" : enabled(index) ? "ACTIVE" : "INACTIVE", x + 14, y + 46,
                enabled(index) ? SAGE : DIM);
        hit("module" + index, x, y, w - 48, 78, () -> selectModule(index), () -> selectModule(index));
        if (index == 3) text(g, font, "+", x + w - 27, y + 29, LIME);
        else {
            double on = animate("tileEnabled" + index, enabled(index) ? 1 : 0);
            round(g, x + w - 39, y + 25, 30, 17, 9, on > .5 ? SAGE : 0xFF594535);
            round(g, x + w - 36.5f + (float) on * 13, y + 28, 11, 11, 5.5f, TEXT);
        }
        hit("enable" + index, x + w - 42, y + 13, 32, 52, () -> toggleEnabled(index));
    }

    private void selectModule(int index) {
        commitBasesFound();
        if (!commitTimerDuration()) cancelTimerDuration();
        if (selectedModule != index || previewModule != index) {
            detailProgress = 0;
            detailScroll = detailScrollTarget = 0;
        }
        selectedModule = previewModule = index;
        dragging = null;
        keyboardFocus = null;
    }

    private void drawDetails(GuiGraphics g) {
        int x = detailX, y = 88, w = detailW, h = H - 122;
        surface(g, x, y, w, h, corner(12), alpha(config.clickGuiStyle.panelsColor, config.clickGuiStyle.panelsOpacity), 0.93f);
        outline(g, x, y, w, h, corner(12), chromeBorder());
        text(g, font, selectedModule == previewModule && previewModule >= 0 ? "MODULE SETTINGS" : "MODULE PREVIEW",
                x + 20, y + 20, DIM);
        if (previewModule < 0) {
            text(g, font, "Explore your modules", x + 20, y + 76, TEXT);
            WorkspacePaint.wrapped(g, font, "Hover over a module to see its details here. Select a module to edit its settings.",
                    x + 20, y + 108, w - 40, CONTROL_MUTED);
            return;
        }
        int index = previewModule;
        double opacity = pushOpacity(Math.max(.03, easeOut(detailProgress)));
        try {
            int rise = (int) ((1 - easeOut(detailProgress)) * 6);
            text(g, font, name(index), x + 20, y + 58 + rise, CONTROL_TEXT);
            text(g, font, moduleCategory(index), x + 20, y + 86, CONTROL_MUTED);
            int descriptionH = WorkspacePaint.wrapped(g, font, description(index), x + 20, y + 119, w - 40, CONTROL_TEXT);
            detailTop = y + Math.max(186, 119 + descriptionH + 24);
            detailBottom = y + h - 12;
            line(g, x + 20, detailTop - 10, x + w - 20, detailTop - 10, chromeBorder());
            if (index != selectedModule) {
                button(g, "configureModule", "Configure module", x + 20, detailTop, w - 40, s(34), () -> selectModule(index));
                return;
            }
            clampDetailScroll();
            beginRegion(g, x + 1, detailTop, w - 2, detailBottom - detailTop);
            rowX = x + 20;
            rowW = w - 40;
            rowPrefix = "m" + index + ".";
            settingsCursor = detailTop + 8 - (int) detailScroll;
            button(g, "detailEnable", index == 3 ? "Open AutoBuilder" : enabled(index) ? "Disable module" : "Enable module",
                    rowX, settingsCursor, rowW, s(32), () -> toggleEnabled(index));
            settingsCursor += s(52);
            drawModuleSettings(g, index);
            if (ModuleKeybinds.mapping(index) != null) {
                section(g, "Keybind", 0);
                keybindButton(g, "keybind" + index, index, rowX, settingsCursor, rowW, s(30));
                settingsCursor += s(46);
            }
            section(g, "Configuration", 0);
            settingToggle(g, "favorite", "Favorite", 0, () -> favorite(index), () -> toggleFavorite(index));
            // Preserve the existing binding/favorite control identities for keyboard navigation.
            Hit favoriteHit = hits.isEmpty() ? null : hits.getLast();
            if (favoriteHit != null && favoriteHit.id.equals(rowPrefix + "favorite")) {
                hits.set(hits.size() - 1, new Hit("favorite" + index, favoriteHit.x, favoriteHit.y,
                        favoriteHit.w, favoriteHit.h, favoriteHit.action, null, null, false, null));
            }
            detailContentHeight = settingsCursor + s(20) - detailTop + (int) detailScroll;
            endRegion(g);
            clampDetailScroll();
            drawDetailScrollbar(g);
        } finally { popOpacity(opacity); }
    }

    private int s(float size) { return Math.round(size * fontScale); }

    private float corner(float size) { return (float) (size * config.clickGuiStyle.rounding / 10); }

    private boolean matches(int index) {
        if (enabledOnly && !enabled(index)) return false;
        if ((tab == 0 || tab == 3 || tab == 4 || tab == COMBAT_TAB) && tab != moduleCategoryTab(index)) return false;
        if (tab == 1 && !favorite(index)) return false;
        String words = switch (index) {
            case 2 -> "freecam free camera fly perspective spectator";
            case 4 -> "base finder basefinder chunk finder chunkfinder geode amethyst activity kelp cave vines bamboo cocoa bee nest";
            case 5 -> "fullbright full bright brightness gamma night dark lighting";
            case 6 -> "fly flight vanilla singleplayer single player multiplayer multi player lan server movement creative";
            case 7 -> "progress bar progressbar streaming stream overlay hud hexagon milestone overworld grass nether netherrack end portal";
            case 8 -> "name hider namehider hide username account privacy streamer streaming sidebar scoreboard";
            case 9 -> "hotbar hot bar blur items inventory privacy streamer streaming hud";
            case 11 -> "light debug detector artificial underground denoise lighting";
            case 10 -> "amethyst scanner bypass geode anti-xray palette light anbs crystals";
            case 12 -> "sound detector sounddetector audio player activity alarm warning chest piston machine machinery";
            case 13 -> "blockesp block esp ore ores seed predictor prediction deepslate emerald coal rare";
            case 14 -> "staff detector staffdetector moderator admin spectator invisible vanish presence roster";
            case ModuleKeybinds.CRYSTAL_AURA -> "crystal aura crystal-aura crystalaura auto crystal autocrystal combat end crystal pvp";
            case ModuleKeybinds.AUTO_TOTEM -> "auto totem auto-totem autototem auto inventory totem autoinvtotem combat offhand hotbar swap totem of undying";
            case ModuleKeybinds.ANCHOR_MACRO -> "anchor macro anchor-macro anchormacro auto anchor auto-anchor autoanchor respawn anchor glowstone combat pvp";
            case ModuleKeybinds.TRIGGERBOT -> "triggerbot trigger bot trigger-bot auto attack autoattack crosshair critical hits melee combat pvp";
            case ModuleKeybinds.AIM_ASSIST -> "aim assist aim-assist aimassist tracking player crosshair angle fov combat pvp";
            case ModuleKeybinds.PLAYER_ESP -> "player esp playeresp player-esp player outline box highlight";
            case ModuleKeybinds.TIME_CHANGER -> "time changer timechanger time-changer day night sunrise noon sunset midnight sky";
            default -> "autobuilder auto builder schematic litematic building materials navigation";
        };
        return words.contains(query.toLowerCase(Locale.ROOT).trim());
    }

    private void drawModuleSettings(GuiGraphics g, int index) {
        if (index == ModuleKeybinds.TIME_CHANGER) drawTimeChangerSettings(g);
        else if (index == ModuleKeybinds.PLAYER_ESP) drawPlayerEspSettings(g);
        else if (index == ModuleKeybinds.AIM_ASSIST) drawAimAssistSettings(g);
        else if (index == ModuleKeybinds.TRIGGERBOT) drawTriggerbotSettings(g);
        else if (index == ModuleKeybinds.ANCHOR_MACRO) drawAnchorMacroSettings(g);
        else if (index == ModuleKeybinds.AUTO_TOTEM) drawAutoTotemSettings(g);
        else if (index == ModuleKeybinds.CRYSTAL_AURA) drawCrystalAuraSettings(g);
        else if (index == 14) drawStaffDetectorSettings(g);
        else if (index == 13) drawBlockEspSettings(g);
        else if (index == 12) drawSoundDetectorSettings(g);
        else if (index == 11) drawLightDebugSettings(g);
        else if (index == 10) drawAmethystSettings(g);
        else if (index == 9) drawHotbarSettings(g, 0);
        else if (index == 8) drawNameHiderSettings(g, 0);
        else if (index == 7) drawProgressBarSettings(g, 0);
        else if (index == 6) drawFlySettings(g, 0);
        else if (index == 5) drawFullbrightSettings(g, 0);
        else if (index == 4) drawChunkFinderSettings(g, 0);
        else if (index == 3) drawAutoBuilderSettings(g, 0);
        else if (index == 2) drawFreecamSettings(g, 0);
    }

    private void help(GuiGraphics g, String value) {
        settingsCursor += s(6);
        settingsCursor += WorkspacePaint.wrapped(g, font, value, rowX, settingsCursor, rowW, CONTROL_MUTED) + s(12);
    }

    private void clampDetailScroll() {
        int max = Math.max(0, detailContentHeight - (detailBottom - detailTop));
        detailScroll = Math.clamp(detailScroll, 0, max);
        detailScrollTarget = Math.clamp(detailScrollTarget, 0, max);
    }

    private void beginRegion(GuiGraphics g, int x, int y, int w, int h) {
        g.enableScissor(x, y, x + w, y + Math.max(0, h));
        collectingClip = true;
        clipLeft = x;
        clipRight = x + w;
        clipTop = y;
        clipBottom = y + Math.max(0, h);
    }

    private void endRegion(GuiGraphics g) {
        collectingClip = false;
        g.disableScissor();
    }

    private void drawDetailScrollbar(GuiGraphics g) {
        scrollbar(g, "detailScrollbar", detailX + detailW - 8, detailTop, detailBottom - detailTop,
                detailContentHeight, detailScroll, value -> detailScroll = detailScrollTarget = value);
    }

    private void scrollbar(GuiGraphics g, String id, int x, int y, int viewHeight, int total, double position, DoubleConsumer move) {
        int max = Math.max(0, total - viewHeight);
        if (max <= 0 || viewHeight <= 0) return;
        int thumbHeight = Math.max(s(22), viewHeight * viewHeight / total);
        int thumbY = y + (int) (position / max * (viewHeight - thumbHeight));
        round(g, x, thumbY, Math.max(2, s(3)), thumbHeight, s(2), 0x50FFFFFF);
        dragHit(id, x - 3, y, 8, viewHeight,
                value -> move.accept(Math.clamp(value, 0, 1) * max), true);
    }

    private void drawModeMenu(GuiGraphics g) {
        hits.clear();
        int rowH = s(32), h = rowH * 3;
        int y = Math.clamp(modeY, 82, H - 42 - h);
        shadow(g, modeX, y, modeW, h, s(7), 0x80000000);
        surface(g, modeX, y, modeW, h, s(7), CONTROL, 0.9f);
        outline(g, modeX, y, modeW, h, s(7), 0x30FFFFFF);
        if (scannerMethodMenu) {
            var methods = selectedModule == 10 ? dev.donut.client.config.AmethystSettings.METHODS : dev.donut.client.config.ChunkFinderSettings.METHODS;
            for (int i = 0; i < methods.size(); i++) {
                String method = methods.get(i);
                int top = y + i * rowH;
                if (inside(modeX, top, modeW, rowH)) round(g, modeX, top, modeW, rowH, s(5), CONTROL_HOVER);
                text(g, font, method, modeX + s(10), top + s(8), CONTROL_TEXT);
                hit("m" + selectedModule + ".method" + i, modeX, top, modeW, rowH,
                        () -> { change(() -> { if (selectedModule == 10) config.amethyst.method = method; else config.chunkFinder.method = method; }); scannerMethodMenu = false; });
            }
            return;
        }
    }

    private void button(GuiGraphics g, String id, String label, int x, int y, int w, int h, Runnable action) {
        double hover = animate("button" + id, inside(x, y, w, h) ? 1 : 0);
        surface(g, x, y, w, h, s(7), blend(CONTROL, CONTROL_HOVER, hover), 0.78f);
        String shown = ellipsize(label, w - s(12));
        text(g, font, shown, x + (w - WorkspacePaint.width(font, shown)) / 2, y + (h - s(16)) / 2, CONTROL_TEXT);
        hit(id, x, y, w, h, action);
    }

    private void drawHudWorkspace(GuiGraphics g) {
        detailW = Math.round(Math.min(360 * fontScale, .43f * W));
        detailX = W - detailW - 16;
        int previewW = W - detailW - 48, pad = s(20), h = H - 114;
        surface(g, 16, 88, previewW, h, s(12), 0xFF101823, 0.9f);
        surface(g, detailX, 88, detailW, h, s(22), 0xFF101823, 0.93f);
        outline(g, detailX, 88, detailW, h, s(22), 0x12FFFFFF);
        WorkspacePaint.title(g, font, "HUD", detailX + pad, 88 + pad, CONTROL_TEXT);
        smallText(g, font, "Choose an element to edit it.", detailX + pad, 88 + pad + s(34), CONTROL_MUTED);
        smallText(g, font, "HUD ELEMENTS", 16 + pad, 88 + pad, CONTROL_MUTED);
        int cardW = previewW - pad * 2, y = 88 + s(64);
        int[] modules = {7, 9};
        for (int module : modules) {
            surface(g, 16 + pad, y, cardW, s(84), s(10), 0xFF182230, 0.84f);
            text(g, font, name(module), 16 + pad * 2, y + s(16), CONTROL_TEXT);
            text(g, font, ellipsize(description(module), cardW - pad * 2), 16 + pad * 2, y + s(45), CONTROL_MUTED);
            hit("hudModule" + module, 16 + pad, y, cardW, s(84), () -> { switchTab(4); selectModule(module); });
            y += s(100);
        }
        detailTop = 88 + s(90);
        detailBottom = 88 + h - pad;
        clampDetailScroll();
        beginRegion(g, detailX + pad, detailTop, detailW - pad * 2, detailBottom - detailTop);
        rowX = detailX + pad;
        rowW = detailW - pad * 2;
        rowPrefix = "";
        settingsCursor = detailTop - (int) detailScroll;
        section(g, "General settings", 0);
        settingToggle(g, "hud", "Array list", 0, () -> config.showHud, () -> config.showHud = !config.showHud);
        section(g, "Progress bar", 0);
        settingToggle(g, "hudProgress", "Show progress bar", 0, () -> config.progressBar.enabled, () -> config.progressBar.enabled = !config.progressBar.enabled);
        button(g, "hudMoveOverlay", "Edit position & size", rowX, settingsCursor, rowW, s(34), () -> {
            flush(); minecraft.setScreen(new ProgressBarEditorScreen(this, config, save));
        });
        settingsCursor += s(48);
        help(g, "Drag the progress overlay in the editor to choose its position.");
        detailContentHeight = settingsCursor + s(20) - detailTop + (int) detailScroll;
        endRegion(g);
        drawDetailScrollbar(g);
    }

    private void surface(GuiGraphics g, float x, float y, float w, float h, float radius, int tint, float frost) {
        if (config.clickGuiStyle.liquidGlass) GlassPanel.draw(g, x, y, w, h, radius, withOpacity(tint), frost);
        else UiPaint.round(g, x, y, w, h, radius, tint);
    }

    private int chromeBorder() { return config.clickGuiStyle.liquidGlass ? 0x26FFFFFF : BORDER; }

    private double easeOut(double value) { return 1 - Math.pow(1 - Math.clamp(value, 0, 1), 3); }

    private String description(int index) {
        return switch (index) {
            case 2 -> "Explore freely with a detached camera";
            case 3 -> "Preview schematics and automate building";
            case 4 -> "Inspect objects, growth formations and light clues";
            case 5 -> "Keep dark places bright and clear";
            case 7 -> "Three manual milestones for your stream";
            case 8 -> "Hide your username in the sidebar scoreboard";
            case 9 -> "Adjust hotbar blur to obscure items";
            case 10 -> "Find amethyst from palette and lighting clues";
            case 11 -> "Highlight underground artificial light";
            case 12 -> "Warn about nearby interactions, players and machinery";
            case 13 -> "Predict deepslate emerald and coal from your world seed";
            case 14 -> "Observe known staff and possible hidden presence";
            case ModuleKeybinds.CRYSTAL_AURA -> "Repeat end crystal placement and breaking where you aim";
            case ModuleKeybinds.AUTO_TOTEM -> "Refill an equipped offhand totem with a spare";
            case ModuleKeybinds.ANCHOR_MACRO -> "Place, charge, and detonate respawn anchors where you aim";
            case ModuleKeybinds.TRIGGERBOT -> "Attack entities under your crosshair when your swing is charged";
            case ModuleKeybinds.AIM_ASSIST -> "Gently turn your view toward nearby players";
            case ModuleKeybinds.PLAYER_ESP -> "Draw boxes around nearby players";
            case ModuleKeybinds.TIME_CHANGER -> "Set the time of day you see";
            default -> "Move through your world with vanilla flight";
        };
    }

    private void moduleIcon(GuiGraphics g, int index, int x, int y, int tint) {
        if (index == 14) {
            outline(g, x, y + 4, 18, 10, 5, tint);
            outline(g, x + 7, y + 7, 4, 4, 2, tint);
        } else if (index == 12) {
            outline(g,x,y+6,5,7,1,tint);
            line(g,x+5,y+6,x+10,y+2,tint);line(g,x+10,y+2,x+10,y+17,tint);line(g,x+10,y+17,x+5,y+13,tint);
            line(g,x+13,y+5,x+17,y+8,tint);line(g,x+17,y+8,x+13,y+13,tint);
        } else if (index == ModuleKeybinds.TRIGGERBOT) {
            outline(g, x + 2, y + 2, 14, 14, 6, tint);
            line(g, x + 9, y, x + 9, y + 5, tint);
            line(g, x + 9, y + 13, x + 9, y + 18, tint);
            line(g, x, y + 9, x + 5, y + 9, tint);
            line(g, x + 13, y + 9, x + 18, y + 9, tint);
        } else if (index == 2) camera(g, x, y, tint);
        else if (index == 3 || index == 4 || index == 13) cube(g, x, y, 12, tint, .85, .14);
        else if (index == 5) {
            outline(g, x + 4, y + 4, 9, 9, 4, tint);
            line(g, x + 8, y, x + 8, y + 2, tint);
            line(g, x + 8, y + 15, x + 8, y + 17, tint);
            line(g, x, y + 8, x + 2, y + 8, tint);
            line(g, x + 15, y + 8, x + 17, y + 8, tint);
        } else if (index == 9) {
            outline(g, x - 1, y + 5, 19, 10, 2, tint);
            for (int slot = 0; slot < 3; slot++) {
                round(g, x + 2 + slot * 5, y + 8, 3, 4, 1, tint);
            }
            line(g, x + 1, y + 17, x + 16, y + 1, tint);
        } else if (index == 8) {
            outline(g, x, y + 4, 17, 10, 5, tint);
            outline(g, x + 6, y + 7, 5, 5, 2, tint);
            line(g, x + 1, y + 17, x + 16, y + 1, tint);
        } else if (index == 7) {
            line(g, x + 8, y, x + 16, y + 4, tint);
            line(g, x + 16, y + 4, x + 16, y + 13, tint);
            line(g, x + 16, y + 13, x + 8, y + 17, tint);
            line(g, x + 8, y + 17, x, y + 13, tint);
            line(g, x, y + 13, x, y + 4, tint);
            line(g, x, y + 4, x + 8, y, tint);
        } else {
            line(g, x + 1, y + 14, x + 8, y + 3, tint);
            line(g, x + 8, y + 3, x + 16, y + 14, tint);
            line(g, x + 8, y + 3, x + 8, y + 17, tint);
        }
    }

    private void crystalIcon(GuiGraphics g, int x, int y, int tint) {
        line(g, x + 8, y, x + 16, y + 8, tint);
        line(g, x + 16, y + 8, x + 8, y + 16, tint);
        line(g, x + 8, y + 16, x, y + 8, tint);
        line(g, x, y + 8, x + 8, y, tint);
        round(g, x + 7, y + 7, 3, 3, 1, tint);
    }

    private boolean enabled(int index) {
        if (index == ModuleKeybinds.TIME_CHANGER) return config.timeChanger.enabled;
        if (index == ModuleKeybinds.PLAYER_ESP) return config.playerEsp.enabled;
        if (index == ModuleKeybinds.AIM_ASSIST) return config.aimAssist.enabled;
        if (index == ModuleKeybinds.TRIGGERBOT) return config.triggerbot.enabled;
        if (index == ModuleKeybinds.ANCHOR_MACRO) return config.anchorMacro.enabled;
        if (index == ModuleKeybinds.AUTO_TOTEM) return config.autoTotem.enabled;
        if (index == ModuleKeybinds.CRYSTAL_AURA) return config.crystalAura.enabled;
        return index == 14 ? config.staffDetector.enabled : index == 13 ? config.blockEsp.enabled : index == 12 ? config.soundDetector.enabled : index == 11 ? config.lightDebug.enabled : index == 10 ? config.amethyst.enabled : index == 9 ? config.hotbar.enabled : index == 8 ? config.nameHider.enabled : index == 7 ? config.progressBar.enabled : index == 6 ? FlyController.enabled() : index == 5 ? config.fullbright.enabled : index == 4 ? config.chunkFinder.enabled : index == 3 ? AutoBuilderController.get().isRunning() : index == 2 ? config.freecam.enabled : false;
    }

    private boolean favorite(int index) {
        if (index == ModuleKeybinds.TIME_CHANGER) return config.timeChanger.favorite;
        if (index == ModuleKeybinds.PLAYER_ESP) return config.playerEsp.favorite;
        if (index == ModuleKeybinds.AIM_ASSIST) return config.aimAssist.favorite;
        if (index == ModuleKeybinds.TRIGGERBOT) return config.triggerbot.favorite;
        if (index == ModuleKeybinds.ANCHOR_MACRO) return config.anchorMacro.favorite;
        if (index == ModuleKeybinds.AUTO_TOTEM) return config.autoTotem.favorite;
        if (index == ModuleKeybinds.CRYSTAL_AURA) return config.crystalAura.favorite;
        return index == 14 ? config.staffDetector.favorite : index == 13 ? config.blockEsp.favorite : index == 12 ? config.soundDetector.favorite : index == 11 ? config.lightDebug.favorite : index == 10 ? config.amethyst.favorite : index == 9 ? config.hotbar.favorite : index == 8 ? config.nameHider.favorite : index == 7 ? config.progressBar.favorite : index == 6 ? config.fly.favorite : index == 5 ? config.fullbright.favorite : index == 4 ? config.chunkFinder.favorite : index == 3 ? config.autoBuilder.favorite : index == 2 ? config.freecam.favorite : false;
    }
    private void toggleFavorite(int index) {
        if (index == ModuleKeybinds.TIME_CHANGER) config.timeChanger.favorite = !config.timeChanger.favorite;
        else if (index == ModuleKeybinds.PLAYER_ESP) config.playerEsp.favorite = !config.playerEsp.favorite;
        else if (index == ModuleKeybinds.AIM_ASSIST) config.aimAssist.favorite = !config.aimAssist.favorite;
        else if (index == ModuleKeybinds.TRIGGERBOT) config.triggerbot.favorite = !config.triggerbot.favorite;
        else if (index == ModuleKeybinds.ANCHOR_MACRO) config.anchorMacro.favorite = !config.anchorMacro.favorite;
        else if (index == ModuleKeybinds.AUTO_TOTEM) config.autoTotem.favorite = !config.autoTotem.favorite;
        else if (index == ModuleKeybinds.CRYSTAL_AURA) config.crystalAura.favorite = !config.crystalAura.favorite;
        else if (index == 14) config.staffDetector.favorite = !config.staffDetector.favorite;
        else if (index == 13) config.blockEsp.favorite = !config.blockEsp.favorite;
        else if (index == 12) config.soundDetector.favorite = !config.soundDetector.favorite;
        else if (index == 11) config.lightDebug.favorite = !config.lightDebug.favorite;
        else if (index == 10) config.amethyst.favorite = !config.amethyst.favorite;
        else if (index == 9) config.hotbar.favorite = !config.hotbar.favorite;
        else if (index == 8) config.nameHider.favorite = !config.nameHider.favorite;
        else if (index == 7) config.progressBar.favorite = !config.progressBar.favorite;
        else if (index == 6) config.fly.favorite = !config.fly.favorite;
        else if (index == 5) config.fullbright.favorite = !config.fullbright.favorite;
        else if (index == 4) config.chunkFinder.favorite = !config.chunkFinder.favorite;
        else if (index == 3) config.autoBuilder.favorite = !config.autoBuilder.favorite;
        else if (index == 2) config.freecam.favorite = !config.freecam.favorite;
        if (tab == 1) contentProgress = 0;
    }
    private void toggleEnabled(int index) {
        if (index == ModuleKeybinds.TIME_CHANGER) {
            change(() -> config.timeChanger.enabled = !config.timeChanger.enabled);
        } else if (index == ModuleKeybinds.PLAYER_ESP) {
            change(() -> config.playerEsp.enabled = !config.playerEsp.enabled);
        } else if (index == ModuleKeybinds.AIM_ASSIST) {
            change(() -> config.aimAssist.enabled = !config.aimAssist.enabled);
        } else if (index == ModuleKeybinds.TRIGGERBOT) {
            change(() -> config.triggerbot.enabled = !config.triggerbot.enabled);
        } else if (index == ModuleKeybinds.ANCHOR_MACRO) {
            change(() -> config.anchorMacro.enabled = !config.anchorMacro.enabled);
        } else if (index == ModuleKeybinds.AUTO_TOTEM) {
            change(() -> config.autoTotem.enabled = !config.autoTotem.enabled);
        } else if (index == ModuleKeybinds.CRYSTAL_AURA) {
            change(() -> config.crystalAura.enabled = !config.crystalAura.enabled);
        } else if (index == 14) {
            change(() -> config.staffDetector.enabled = !config.staffDetector.enabled);
        } else if (index == 13) {
            change(() -> config.blockEsp.enabled = !config.blockEsp.enabled);
        } else if (index == 12) {
            change(() -> config.soundDetector.enabled = !config.soundDetector.enabled);
        } else if (index == 11) {
            change(() -> config.lightDebug.enabled = !config.lightDebug.enabled);
        } else if (index == 10) {
            change(() -> config.amethyst.enabled = !config.amethyst.enabled);
        } else if (index == 9) {
            change(() -> config.hotbar.enabled = !config.hotbar.enabled);
        } else if (index == 8) {
            change(() -> config.nameHider.enabled = !config.nameHider.enabled);
        } else if (index == 7) {
            change(() -> config.progressBar.enabled = !config.progressBar.enabled);
        } else if (index == 6) {
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
        }
    }
    private String name(int index) {
        if (index == ModuleKeybinds.TIME_CHANGER) return "Time Changer";
        if (index == ModuleKeybinds.PLAYER_ESP) return "Player ESP";
        if (index == ModuleKeybinds.AIM_ASSIST) return "Aim Assist";
        if (index == ModuleKeybinds.TRIGGERBOT) return "Triggerbot";
        if (index == ModuleKeybinds.ANCHOR_MACRO) return "Anchor Macro";
        if (index == ModuleKeybinds.AUTO_TOTEM) return "Auto Totem";
        if (index == ModuleKeybinds.CRYSTAL_AURA) return "Crystal Aura";
        return switch (index) { case 2 -> "Freecam"; case 4 -> "Base Finder"; case 5 -> "Fullbright"; case 6 -> "Fly"; case 7 -> "Progress Bar"; case 8 -> "Name Hider"; case 9 -> "Hotbar"; case 10 -> "Amethyst Scanner"; case 11 -> "Light Debug"; case 12 -> "Sound Detector"; case 13 -> "BlockESP"; case 14 -> "Staff Detector"; default -> "AutoBuilder"; };
    }

    private void drawAimAssistSettings(GuiGraphics g) {
        AimAssistSettings settings = config.aimAssist;
        section(g, "Targeting", 0);
        String mode = settings.targetMode == AimAssistSettings.TargetMode.FOV ? "FOV" : "Closest";
        button(g, rowPrefix + "targetMode", "Target mode · " + mode, rowX, settingsCursor, rowW, s(34),
                () -> change(() -> settings.targetMode = settings.targetMode == AimAssistSettings.TargetMode.FOV
                        ? AimAssistSettings.TargetMode.CLOSEST : AimAssistSettings.TargetMode.FOV));
        settingsCursor += s(40);
        String aimPart = switch (settings.aimPart) {
            case HEAD -> "Head";
            case TORSO -> "Torso";
            case LEGS -> "Legs";
        };
        button(g, rowPrefix + "aimPart", "Aim part · " + aimPart, rowX, settingsCursor, rowW, s(34),
                () -> change(() -> settings.aimPart = switch (settings.aimPart) {
                    case HEAD -> AimAssistSettings.AimPart.TORSO;
                    case TORSO -> AimAssistSettings.AimPart.LEGS;
                    case LEGS -> AimAssistSettings.AimPart.HEAD;
                }));
        settingsCursor += s(40);
        slider(g, "range", "Range", 0, () -> settings.range, value -> settings.range = value, 0, 10, .1, " blocks");
        slider(g, "fov", "Target FOV", 0, () -> settings.fov, value -> settings.fov = value, 0, 360, 1, "°");
        slider(g, "speed", "Turn strength", 0, () -> settings.speed, value -> settings.speed = value, .01, 1, .01, "");
        settingToggle(g, "throughWalls", "Through walls", 0, () -> settings.throughWalls,
                () -> settings.throughWalls = !settings.throughWalls);

        section(g, "Weapons", 0);
        settingToggle(g, "everything", "Everything", 0, () -> settings.everything,
                () -> settings.everything = !settings.everything);
        if (!settings.everything) {
            settingToggle(g, "swords", "Swords", 0, () -> settings.swords, () -> settings.swords = !settings.swords);
            settingToggle(g, "axes", "Axes", 0, () -> settings.axes, () -> settings.axes = !settings.axes);
            settingToggle(g, "mace", "Mace", 0, () -> settings.mace, () -> settings.mace = !settings.mace);
            settingToggle(g, "spearsAndTridents", "Spears and tridents", 0, () -> settings.spearsAndTridents,
                    () -> settings.spearsAndTridents = !settings.spearsAndTridents);
        }
    }

    private void drawTriggerbotSettings(GuiGraphics g) {
        TriggerbotSettings settings = config.triggerbot;
        section(g, "Timing", 0);
        slider(g, "reaction", "Reaction delay", 0, () -> settings.reaction,
                value -> settings.reaction = (int) value, 0, 10, 1, " ticks");
        slider(g, "jitter", "Random extra delay", 0, () -> settings.jitter,
                value -> settings.jitter = (int) value, 0, 4, 1, " ticks");
        String crits = switch (settings.crits) {
            case OFF -> "Off";
            case SMART -> "Smart";
            case ONLY -> "Only";
        };
        button(g, rowPrefix + "crits", "Critical hits · " + crits, rowX, settingsCursor, rowW, s(34),
                () -> change(() -> settings.crits = switch (settings.crits) {
                    case OFF -> TriggerbotSettings.Crits.SMART;
                    case SMART -> TriggerbotSettings.Crits.ONLY;
                    case ONLY -> TriggerbotSettings.Crits.OFF;
                }));
        settingsCursor += s(40);
        if (settings.crits == TriggerbotSettings.Crits.SMART) {
            help(g, "Waits briefly for a falling critical hit when possible and attacks normally on the ground.");
        }
        settingToggle(g, "holdAttack", "Hold Attack to trigger", 0, () -> settings.holdAttack,
                () -> settings.holdAttack = !settings.holdAttack);

        section(g, "Targets", 0);
        settingToggle(g, "players", "Players", 0, () -> settings.players,
                () -> settings.players = !settings.players);
        settingToggle(g, "hostiles", "Hostile mobs", 0, () -> settings.hostiles,
                () -> settings.hostiles = !settings.hostiles);
        settingToggle(g, "passives", "Other mobs", 0, () -> settings.passives,
                () -> settings.passives = !settings.passives);
        settingToggle(g, "invisible", "Invisible targets", 0, () -> settings.invisible,
                () -> settings.invisible = !settings.invisible);
        settingToggle(g, "skipShielding", "Skip shielding", 0, () -> settings.skipShielding,
                () -> settings.skipShielding = !settings.skipShielding);
        settingToggle(g, "skipFriends", "Skip friends", 0, () -> settings.skipFriends,
                () -> settings.skipFriends = !settings.skipFriends);
        if (settings.skipFriends) combatFriendsButton(g);

        section(g, "Weapons", 0);
        settingToggle(g, "anyItem", "Any item", 0, () -> settings.anyItem,
                () -> settings.anyItem = !settings.anyItem);
        if (!settings.anyItem) {
            settingToggle(g, "swords", "Swords", 0, () -> settings.swords, () -> settings.swords = !settings.swords);
            settingToggle(g, "axes", "Axes", 0, () -> settings.axes, () -> settings.axes = !settings.axes);
            settingToggle(g, "mace", "Mace", 0, () -> settings.mace, () -> settings.mace = !settings.mace);
        }
    }

    private void combatFriendsButton(GuiGraphics g) {
        button(g, rowPrefix + "friends", "Edit combat friends", rowX, settingsCursor, rowW, s(34), () -> {
            flush();
            minecraft.setScreen(new CrystalAuraFriendsScreen(this, config.crystalAura, save));
        });
        settingsCursor += s(40);
    }

    private void drawAnchorMacroSettings(GuiGraphics g) {
        AnchorMacroSettings settings = config.anchorMacro;
        boolean twoCycles = settings.mode == AnchorMacroSettings.Mode.DOUBLE;
        button(g, rowPrefix + "mode", "Mode · " + (twoCycles ? "Double" : "Single"), rowX, settingsCursor, rowW, s(34),
                () -> change(() -> settings.mode = twoCycles ? AnchorMacroSettings.Mode.SINGLE : AnchorMacroSettings.Mode.DOUBLE));
        settingsCursor += s(40);
        help(g, "Press Use on a placed anchor, or hold one and aim at a surface. Keep glowstone in your hotbar.");
        if (twoCycles) {
            settingToggle(g, "doubleAnchor", "Double anchor", 0, () -> settings.doubleAnchor,
                    () -> settings.doubleAnchor = !settings.doubleAnchor);
            help(g, "Tries to replace the first anchor sooner.");
        }

        section(g, "Hotbar and cover", 0);
        slider(g, "safetySlot", "Safety slot", 0, () -> settings.safetySlot,
                value -> settings.safetySlot = (int) value, 1, 9, 1, "");
        settingToggle(g, "safeAnchor", "Safe anchor", 0, () -> settings.safeAnchor,
                () -> settings.safeAnchor = !settings.safeAnchor);
        if (settings.safeAnchor) {
            help(g, "Uses existing cover or tries to place a guard block.");
            slider(g, "guardSlot", "Guard slot", 0, () -> settings.guardSlot,
                    value -> settings.guardSlot = (int) value, 1, 9, 1, "");
            boolean cancel = settings.ifUnguarded == AnchorMacroSettings.Unguarded.CANCEL;
            button(g, rowPrefix + "ifUnguarded", "If unguarded · " + (cancel ? "Cancel" : "Detonate"), rowX, settingsCursor, rowW, s(34),
                    () -> change(() -> settings.ifUnguarded = cancel ? AnchorMacroSettings.Unguarded.DETONATE : AnchorMacroSettings.Unguarded.CANCEL));
            settingsCursor += s(40);
        }
        settingToggle(g, "switchBack", "Switch back", 0, () -> settings.switchBack,
                () -> settings.switchBack = !settings.switchBack);
        if (settings.switchBack) {
            slider(g, "switchBackDelay", "Switch back delay", 0, () -> settings.switchBackDelay,
                    value -> settings.switchBackDelay = (int) value, 0, 10, 1, " ticks");
        }

        section(g, "Timing", 0);
        slider(g, "switchDelay", "Switch delay", 0, () -> settings.switchDelay,
                value -> settings.switchDelay = (int) value, 0, 10, 1, " ticks");
        slider(g, "chargeDelay", "Charge delay", 0, () -> settings.chargeDelay,
                value -> settings.chargeDelay = (int) value, 1, 10, 1, " ticks");
        slider(g, "explodeDelay", "Explode delay", 0, () -> settings.explodeDelay,
                value -> settings.explodeDelay = (int) value, 1, 10, 1, " ticks");
        if (twoCycles || settings.safeAnchor) {
            slider(g, "placeDelay", "Place delay", 0, () -> settings.placeDelay,
                    value -> settings.placeDelay = (int) value, 1, 10, 1, " ticks");
        }
        if (twoCycles) {
            slider(g, "cycleDelay", "Cycle delay", 0, () -> settings.cycleDelay,
                    value -> settings.cycleDelay = (int) value, 0, 20, 1, " ticks");
        }
        slider(g, "jitter", "Random extra delay", 0, () -> settings.jitter,
                value -> settings.jitter = (int) value, 0, 3, 1, " ticks");
    }

    private void drawAutoTotemSettings(GuiGraphics g) {
        AutoTotemSettings settings = config.autoTotem;
        boolean inventory = settings.mode == AutoTotemSettings.Mode.INVENTORY;
        String modeName = inventory ? "Inventory" : "Hotbar Swap";
        button(g, rowPrefix + "mode", "Mode · " + modeName, rowX, settingsCursor, rowW, s(34),
                () -> change(() -> settings.mode = inventory ? AutoTotemSettings.Mode.HOTBAR_SWAP : AutoTotemSettings.Mode.INVENTORY));
        settingsCursor += s(40);
        help(g, "Equip a totem in your offhand once to arm refilling.");

        if (inventory) {
            settingToggle(g, "hotbarRefill", "Hotbar refill", 0, () -> settings.hotbarRefill,
                    () -> settings.hotbarRefill = !settings.hotbarRefill);
            if (settings.hotbarRefill) {
                slider(g, "hotbarSlot", "Reserve hotbar slot", 0, () -> settings.hotbarSlot,
                        value -> settings.hotbarSlot = (int) value, 1, 9, 1, "");
            }
            slider(g, "closeDelay", "Close delay", 0, () -> settings.closeDelay,
                    value -> settings.closeDelay = (int) value, 0, 10, 1, " ticks");
        } else {
            help(g, "Keep a spare totem in your hotbar.");
            slider(g, "switchBackDelay", "Switch back delay", 0, () -> settings.switchBackDelay,
                    value -> settings.switchBackDelay = (int) value, 0, 10, 1, " ticks");
        }

        section(g, "Timing", 0);
        slider(g, "reactionDelay", "Reaction delay", 0, () -> settings.reactionDelay,
                value -> settings.reactionDelay = (int) value, 0, 10, 1, " ticks");
        slider(g, "swapDelay", "Swap delay", 0, () -> settings.swapDelay,
                value -> settings.swapDelay = (int) value, 1, 10, 1, " ticks");
        slider(g, "jitter", "Random extra delay", 0, () -> settings.jitter,
                value -> settings.jitter = (int) value, 0, 3, 1, " ticks");
    }

    private void drawCrystalAuraSettings(GuiGraphics g) {
        CrystalAuraSettings settings = config.crystalAura;
        help(g, "Hold Use Item with end crystals in hand and aim at obsidian, bedrock, or an existing crystal.");

        section(g, "Timing", 0);
        slider(g, "placeDelay", "Place delay", 0, () -> settings.placeDelay,
                value -> settings.placeDelay = (int) value, 0, 20, 1, " ticks");
        slider(g, "breakDelay", "Break delay", 0, () -> settings.breakDelay,
                value -> settings.breakDelay = (int) value, 0, 20, 1, " ticks");
    }

    private void drawHotbarSettings(GuiGraphics g, int ignored) {
        section(g, "Hotbar privacy", 0);
        settingToggle(g, "hideHeldItems", "Hide held items", 0,
                () -> config.hotbar.hideHeldItems, () -> config.hotbar.hideHeldItems = !config.hotbar.hideHeldItems);
        help(g, "Show empty hands in first person and on your local avatar. Item use stays normal.");
        help(g, "Local display only. Other players still see your real equipment.");
        slider(g, "blurAmount", "Blur amount", 0, () -> config.hotbar.blurPercent,
                value -> config.hotbar.blurPercent = (int) Math.round(value), 0, dev.donut.client.config.HotbarSettings.MAX_BLUR_PERCENT, 1, "%");
        help(g, "Choose 0% for a clear hotbar or 140% for the strongest blur.");
        help(g, "Blurs item names, hotbar items, counts and the offhand slot.");
    }

    private void drawNameHiderSettings(GuiGraphics g, int ignored) {
        section(g, "Sidebar privacy", 0);
        settingToggle(g, "blurScoreboard", "Blur entire scoreboard", 0,
                () -> config.nameHider.blurScoreboard, () -> config.nameHider.blurScoreboard = !config.nameHider.blurScoreboard);
        help(g, config.nameHider.blurScoreboard ? "Blurs the whole sidebar, including every name, title and score."
                : "Hides your username in sidebar titles, rows and scores.");
        help(g, "Disable the module to restore the original scoreboard.");
    }

    private void drawProgressBarSettings(GuiGraphics g, int ignored) {
        section(g, "Overlay style", 0);
        slider(g, "overlayScale", "Overlay size", 0, () -> config.progressBar.scale,
                value -> config.progressBar.scale = value,
                ProgressBarSettings.MIN_SCALE, ProgressBarSettings.MAX_SCALE, .05, "%");
        settingToggle(g, "shinyOutline", "Shiny outline", 0, () -> config.progressBar.shinyOutline,
                () -> config.progressBar.shinyOutline = !config.progressBar.shinyOutline);
        help(g, "Animated frame on; empty background off.");
        section(g, "Base counter", 0);
        basesFoundRow(g, 0);
        section(g, "Countdowns", 0);
        for (int stage = 0; stage < 3; stage++) timerRow(g, stage, 0);
        help(g, timerError >= 0 ? "Use HH:MM:SS, from 00:00:01 to 99:59:59."
                : "Set HH:MM:SS, for example 01:00:00 for one hour.");
        section(g, "Manual progress", 0);
        String[] labels = {"Overworld", "Nether", "End"};
        for (int i = 0; i < 3; i++) {
            int stage = i + 1;
            settingToggle(g, "stage" + stage, stage + "  " + labels[i], 0,
                    () -> config.progressBar.completedStages >= stage,
                    () -> config.progressBar.completedStages = config.progressBar.completedStages >= stage ? stage - 1 : stage);
        }
        help(g, "Select a milestone; select a marked one to undo it.");
        button(g, "m7.moveOverlay", "Move overlay", rowX, settingsCursor, rowW, s(32), () -> {
            flush();
            minecraft.setScreen(new ProgressBarEditorScreen(this, config, save));
        });
        settingsCursor += s(42);
        button(g, "m7.resetProgress", "Reset progress", rowX, settingsCursor, rowW, s(32),
                () -> change(() -> config.progressBar.completedStages = 0));
        settingsCursor += s(42);
    }

    private void timerRow(GuiGraphics g, int stage, int ignored) {
        String[] names = {"Overworld", "Nether", "End"};
        boolean running = ProgressTimers.isRunning(config, stage);
        long remaining = ProgressTimers.remainingMillis(config, stage);
        int y = settingsCursor;
        text(g, font, names[stage], rowX, y, CONTROL_TEXT);
        right(g, font, ProgressTimers.formattedTime(config, stage), rowX + rowW, y,
                remaining == 0 ? 0xFFFF818A : CONTROL_MUTED);
        y += s(24);
        int x = rowX, durationW = Math.min(s(116), rowW * 38 / 100);
        int gap = s(8), actionW = (rowW - durationW - gap * 2) / 2;
        boolean focused = editingTimer == stage;
        surface(g, x, y, durationW, s(30), s(7), CONTROL, 0.78f);
        outline(g, x, y, durationW, s(30), s(7), alpha(timerError == stage ? 0xFF818A : config.accentColor, focused ? .8 : .15));
        if (focused && replaceTimerDuration) round(g, x + s(6), y + s(4), durationW - s(12), s(21), s(3), alpha(config.accentColor, .25));
        String value = focused ? timerDurationDraft : CountdownDuration.format(config.progressBar.timers[stage].durationSeconds);
        if (focused && !replaceTimerDuration && System.currentTimeMillis() % 1000 < 500) value += "|";
        text(g, font, value, x + s(8), y + s(7), CONTROL_TEXT);
        hit("m7.timerDuration" + stage, x, y, durationW, s(30), () -> {
            editingTimer = stage;
            timerDurationDraft = CountdownDuration.format(config.progressBar.timers[stage].durationSeconds);
            replaceTimerDuration = true;
            timerError = -1;
            keyboardFocus = "m7.timerDuration" + stage;
        });
        String action = running ? "Pause" : remaining == 0 ? "Restart"
                : remaining < config.progressBar.timers[stage].durationSeconds * 1000L ? "Resume" : "Start";
        button(g, "m7.timerStart" + stage, action, x + durationW + gap, y, actionW, s(30), () -> change(() -> {
            if (ProgressTimers.isRunning(config, stage)) ProgressTimers.pause(config, stage);
            else ProgressTimers.start(config, stage);
        }));
        button(g, "m7.timerReset" + stage, "Reset", x + durationW + actionW + gap * 2, y, actionW, s(30),
                () -> change(() -> ProgressTimers.reset(config, stage)));
        settingsCursor += s(74);
    }

    private boolean commitTimerDuration() {
        if (editingTimer < 0) return true;
        var duration = CountdownDuration.parse(timerDurationDraft);
        if (duration.isEmpty()) { timerError = editingTimer; return false; }
        int stage = editingTimer, seconds = duration.getAsInt();
        cancelTimerDuration();
        timerError = -1;
        if (config.progressBar.timers[stage].durationSeconds != seconds) {
            change(() -> ProgressTimers.setDurationSeconds(config, stage, seconds));
        }
        return true;
    }

    private void cancelTimerDuration() {
        editingTimer = -1;
        timerDurationDraft = null;
        replaceTimerDuration = false;
    }

    private void basesFoundRow(GuiGraphics g, int ignored) {
        int y = settingsCursor;
        text(g, font, "Bases found", rowX, y, CONTROL_TEXT);
        y += s(24);
        int side = s(30), gap = s(8), valueX = rowX + side + gap, valueW = rowW - 2 * (side + gap);
        for (int direction : new int[] {-1, 1}) {
            int x = direction < 0 ? rowX : rowX + rowW - side;
            button(g, "m7.basesFound" + (direction < 0 ? "Minus" : "Plus"), direction < 0 ? "-" : "+",
                    x, y, side, side, () -> change(() -> config.progressBar.basesFound = Math.clamp(
                            config.progressBar.basesFound + direction, 0, ProgressBarSettings.MAX_BASES_FOUND)));
        }
        boolean focused = basesFoundDraft != null;
        surface(g, valueX, y, valueW, side, s(7), CONTROL, 0.78f);
        if (focused) outline(g, valueX, y, valueW, side, s(7), alpha(config.accentColor, .85));
        String shown = focused ? basesFoundDraft : Integer.toString(config.progressBar.basesFound);
        if (focused && replaceBasesFound) round(g, valueX + s(6), y + s(4), valueW - s(12), s(21), s(3), alpha(config.accentColor, .25));
        shown = ellipsize(shown, valueW - s(22));
        if (focused && !replaceBasesFound && System.currentTimeMillis() % 1000 < 500) shown += "|";
        text(g, font, shown, valueX + s(10), y + s(7), CONTROL_TEXT);
        hit("m7.basesFound", valueX, y, valueW, side, () -> {
            basesFoundDraft = Integer.toString(config.progressBar.basesFound);
            replaceBasesFound = true;
            keyboardFocus = "m7.basesFound";
        });
        settingsCursor += s(70);
    }

    private int enteredBasesFound() {
        if (basesFoundDraft == null || basesFoundDraft.isEmpty()) return 0;
        return (int) Math.clamp(Long.parseLong(basesFoundDraft), 0, ProgressBarSettings.MAX_BASES_FOUND);
    }

    private void commitBasesFound() {
        if (basesFoundDraft == null) return;
        int next = enteredBasesFound();
        basesFoundDraft = null;
        replaceBasesFound = false;
        if (config.progressBar.basesFound != next) change(() -> config.progressBar.basesFound = next);
    }

    private void drawFlySettings(GuiGraphics g, int ignored) {
        section(g, "Vanilla flight", 0);
        help(g, "Vanilla flight");
        section(g, "Your controls", 0);
        controlHint(g, "Move", keyName(minecraft.options.keyUp) + " / " + keyName(minecraft.options.keyLeft)
                + " / " + keyName(minecraft.options.keyDown) + " / " + keyName(minecraft.options.keyRight), 0);
        controlHint(g, "Up / down", keyName(minecraft.options.keyJump) + " / " + keyName(minecraft.options.keyShift), 0);
        controlHint(g, "Sprint", keyName(minecraft.options.keySprint), 0);
        help(g, "Server rules may restrict flight.");
    }

    private void drawFullbrightSettings(GuiGraphics g, int ignored) {
        section(g, "Brightness", 0);
        controlHint(g, "Gamma", "1000", 0);
    }

    private void soundButton(GuiGraphics g, String id, String label, Runnable action) {
        button(g,"m12."+id,label,rowX,settingsCursor,rowW,s(32),action);
        settingsCursor+=s(40);
    }

    private void drawSoundDetectorSettings(GuiGraphics g) {
        var value=config.soundDetector;
        section(g,"Detection",0);
        soundButton(g,"preset","Preset: "+value.preset,()->change(()->{
            var presets=dev.donut.client.config.SoundDetectorSettings.PRESETS;
            value.applyPreset(presets.get((presets.indexOf(value.preset)+1)%presets.size()));
        }));
        slider(g,"range","Received-event range",0,()->value.range,next->value.range=next,0,512,1," blocks");
        help(g,"Filters received events only. Villager sounds are normally nearby; block events can reach farther.");
        slider(g,"threshold","Warning threshold",0,()->value.warningThreshold,next->{value.warningThreshold=(int)next;value.criticalThreshold=Math.max(value.criticalThreshold,value.warningThreshold);},1,100,1," points");
        if(value.warningThreshold<100)slider(g,"critical","Critical threshold",0,()->value.criticalThreshold,next->value.criticalThreshold=(int)next,value.warningThreshold,100,1," points");
        else help(g,"Critical threshold: 100 points.");
        settingToggle(g,"playersOnly","Require identified player",0,()->value.playersOnly,()->value.playersOnly=!value.playersOnly);
        settingToggle(g,"self","Filter own actions",0,()->value.suppressSelf,()->value.suppressSelf=!value.suppressSelf);
        settingToggle(g,"probableSelf","Filter probable own actions",0,()->value.suppressProbableSelf,()->value.suppressProbableSelf=!value.suppressProbableSelf);
        settingToggle(g,"nonPlayers","Filter known mobs",0,()->value.ignoreNonPlayers,()->value.ignoreNonPlayers=!value.ignoreNonPlayers);
        settingToggle(g,"clientDerived","Include client-derived sounds",0,()->value.includeClientDerived,()->value.includeClientDerived=!value.includeClientDerived);
        help(g,"An unknown sound never receives a player name based on proximity.");
        section(g,"Sources",0);
        for(String category:dev.donut.client.config.SoundDetectorSettings.CATEGORIES)
            settingToggle(g,"source."+category,SoundSettingsScreen.label(category),0,()->value.categoryEnabled(category),()->value.categories.put(category,!value.categoryEnabled(category)));
        section(g,"Warning delivery",0);
        settingToggle(g,"popup","Warning cards",0,()->value.popup,()->value.popup=!value.popup);
        settingToggle(g,"chime","Local warning chime",0,()->value.chime,()->value.chime=!value.chime);
        settingToggle(g,"chat","Chat notices",0,()->value.chat,()->value.chat=!value.chat);
        settingToggle(g,"markers","World markers",0,()->value.worldMarkers,()->value.worldMarkers=!value.worldMarkers);
        slider(g,"duration","Card duration",0,()->value.durationSeconds,next->value.durationSeconds=next,1,60,1," sec");
        slider(g,"maxCards","Visible cards",0,()->value.maxCards,next->value.maxCards=(int)next,1,5,1,"");
        slider(g,"cooldown","Source cooldown",0,()->value.cooldownSeconds,next->value.cooldownSeconds=next,.1,60,.1," sec");
        settingToggle(g,"menus","Show while in menus",0,()->value.showInMenus,()->value.showInMenus=!value.showInMenus);
        settingToggle(g,"respectHud","Respect global HUD toggle",0,()->value.respectHud,()->value.respectHud=!value.respectHud);
        section(g,"Preview and appearance",0);
        soundButton(g,"previewWarning","Preview warning / move alert",()->{flush();minecraft.setScreen(new SoundAlertEditorScreen(this,config,save,false));});
        soundButton(g,"previewCritical","Preview critical alert",()->{flush();minecraft.setScreen(new SoundAlertEditorScreen(this,config,save,true));});
        slider(g,"scale","Card scale",0,()->value.scale,next->value.scale=next,.5,2,.05,"%");
        slider(g,"opacity","Background opacity",0,()->value.opacity,next->value.opacity=next,.05,1,.01,"%");
        colorRow(g,"Warning accent",0,()->value.warningColor,next->value.warningColor=next);
        colorRow(g,"Critical accent",0,()->value.criticalColor,next->value.criticalColor=next);
        colorRow(g,"Card top",0,()->value.topColor,next->value.topColor=next);
        colorRow(g,"Card bottom",0,()->value.bottomColor,next->value.bottomColor=next);
        slider(g,"volume","Chime volume",0,()->value.volume,next->value.volume=next,0,1,.01,"%");
        soundButton(g,"allSettings","All appearance, audio and behavior controls",()->{flush();minecraft.setScreen(new SoundSettingsScreen(this,config,save));});
        section(g,"Rules, history and profiles",0);
        soundButton(g,"rules","Custom rules / expected areas",()->{flush();minecraft.setScreen(new SoundRulesScreen(this,config,save));});
        soundButton(g,"history","Activity history and evidence",()->{flush();minecraft.setScreen(new SoundHistoryScreen(this,config));});
        soundButton(g,"mute","Mute notifications for 60 seconds",()->{var controller=dev.donut.client.sound.SoundDetectorController.get();if(controller!=null){controller.mute(60);soundStatus="Notifications muted for 60 seconds. Observation continues.";}});
        soundButton(g,"saveProfile","Save current server profile",()->{var controller=dev.donut.client.sound.SoundDetectorController.get();if(controller!=null){controller.saveProfile();save.run();soundStatus="Saved current server preferences.";}});
        soundButton(g,"loadProfile","Load current server profile",()->{var controller=dev.donut.client.sound.SoundDetectorController.get();if(controller!=null){boolean loaded=controller.loadProfile();save.run();soundStatus=loaded?"Loaded this server's saved preferences.":"No saved preferences for this server.";}});
        section(g,"Diagnostics",0);
        soundButton(g,"exportCatalog","Export full block / sound coverage",()->{var controller=dev.donut.client.sound.SoundDetectorController.get();if(controller!=null)try{soundStatus="Exported "+controller.exportCatalog().getFileName();}catch(Exception e){soundStatus="Export failed: "+e.getMessage();}});
        soundButton(g,"advanced","Advanced preferences / import / export",()->{flush();minecraft.setScreen(new SoundSettingsJsonScreen(this,config,save));});
        help(g,soundStatus);
    }

    private void drawStaffDetectorSettings(GuiGraphics g) {
        var value = config.staffDetector;
        section(g, "Staff identity", 0);
        button(g, "m14.roster", "Staff list / evidence", rowX, settingsCursor, rowW, s(34), () -> {
            flush(); minecraft.setScreen(new StaffRosterScreen(this, config, save));
        });
        settingsCursor += s(44);
        help(g, "Add usernames or UUIDs for the current server. Usernames bind to UUIDs when observed.");
        section(g, "Observations", 0);
        settingToggle(g, "spectatorAlerts", "Spectator alerts", 0, () -> value.spectatorAlerts, () -> value.spectatorAlerts = !value.spectatorAlerts);
        settingToggle(g, "vanishAlerts", "Vanish / tab alerts", 0, () -> value.vanishAlerts, () -> value.vanishAlerts = !value.vanishAlerts);
        help(g, "Alerts when known staff are hidden from tab. Recent spectator reports survive removal for the hint lifetime. Fully filtered vanish can leave no signal.");
        settingToggle(g, "invisibilityAlerts", "Invisible nearby alerts", 0, () -> value.invisibilityAlerts, () -> value.invisibilityAlerts = !value.invisibilityAlerts);
        slider(g, "nearbyRange", "Nearby range", 0, () -> value.nearbyRange, next -> value.nearbyRange = (int) next, 8, 256, 1, " blocks");
        settingToggle(g, "completionChecks", "Check /msg completions", 0, () -> value.completionChecks, () -> value.completionChecks = !value.completionChecks);
        help(g, "Sends completion requests only. Repeated tab discrepancies suggest possible hidden presence. Suggestions can be filtered or stale.");
        if (value.completionChecks) {
            slider(g, "completionIntervalSeconds", "Check interval", 0, () -> value.completionIntervalSeconds, next -> value.completionIntervalSeconds = (int) next, 6, 120, 1, "s");
            slider(g, "requiredSamples", "Matching samples", 0, () -> value.requiredSamples, next -> value.requiredSamples = (int) next, 2, 10, 1, "");
        }
        settingToggle(g, "rosterAlerts", "Roster removal alerts", 0, () -> value.rosterAlerts, () -> value.rosterAlerts = !value.rosterAlerts);
        help(g, "Removal hints wait 8 seconds. Logouts, transfers and vanish remain indistinguishable.");
        section(g, "Display", 0);
        settingToggle(g, "showHud", "Presence HUD", 0, () -> value.showHud, () -> value.showHud = !value.showHud);
        settingToggle(g, "chatAlerts", "Chat alerts", 0, () -> value.chatAlerts, () -> value.chatAlerts = !value.chatAlerts);
        slider(g, "maxHudRows", "HUD staff rows", 0, () -> value.maxHudRows, next -> value.maxHudRows = (int) next, 1, 8, 1, "");
        slider(g, "evidenceSeconds", "Hint lifetime", 0, () -> value.evidenceSeconds, next -> value.evidenceSeconds = (int) next, 10, 180, 1, "s");
        slider(g, "alertCooldownSeconds", "Alert cooldown", 0, () -> value.alertCooldownSeconds, next -> value.alertCooldownSeconds = (int) next, 10, 300, 1, "s");
        var controller = dev.donut.client.staff.StaffDetectorController.get();
        help(g, controller != null && controller.uses(config) ? controller.status() : "Hidden presence is unknown. No signal proves someone is watching you.");
        if (controller != null && controller.uses(config)) {
            help(g, controller.diagnostics());
            help(g, controller.queryStatus());
            button(g, "m14.diagnostics", "Copy detection diagnostics", rowX, settingsCursor, rowW, s(34), () ->
                    minecraft.keyboardHandler.setClipboard(controller.status() + "\n" + controller.diagnostics() + "\n" + controller.queryStatus()));
            settingsCursor += s(44);
        }
    }

    private void drawBlockEspSettings(GuiGraphics g) {
        var value = config.blockEsp;
        section(g, "World seed", 0);
        button(g, "m13.seed", "Edit world seed", rowX, settingsCursor, rowW, s(34), () -> {
            flush(); minecraft.setScreen(new BlockEspSeedScreen(this, config, save));
        });
        settingsCursor += s(44);
        help(g, value.seed + " | Vanilla Java 1.21.11");
        section(g, "Targets", 0);
        settingToggle(g, "emerald", "Deepslate emerald", 0, () -> value.emerald, () -> value.emerald = !value.emerald);
        colorRow(g, "Emerald color", 0, () -> value.emeraldColor, next -> value.emeraldColor = next);
        settingToggle(g, "coal", "Deepslate coal", 0, () -> value.coal, () -> value.coal = !value.coal);
        colorRow(g, "Coal color", 0, () -> value.coalColor, next -> value.coalColor = next);
        section(g, "Display", 0);
        slider(g, "radius", "Prediction radius", 0, () -> value.radius, next -> value.radius = (int) next, 1, 8, 1, " chunks");
        slider(g, "opacity", "Opacity", 0, () -> value.opacity, next -> value.opacity = (int) next, 0, 255, 1, "");
        settingToggle(g, "tracers", "Tracers", 0, () -> value.tracers, () -> value.tracers = !value.tracers);
        settingToggle(g, "showHud", "Coordinates / distance HUD", 0, () -> value.showHud, () -> value.showHud = !value.showHud);
        section(g, "Predictions", 0);
        var controller = dev.donut.client.blockesp.BlockEspController.get();
        help(g, controller == null ? "Enable BlockESP in a world to predict ores." : controller.status());
        button(g, "m13.rescan", "Rescan predictions", rowX, settingsCursor, rowW, s(34), () -> {
            var current = dev.donut.client.blockesp.BlockEspController.get();
            if (current != null) current.rescan();
        });
        settingsCursor += s(44);
        help(g, "Predicts original ore positions in the Overworld. Match the version that generated the chunks. Ores may already be mined; larger radii take longer.");
    }

    private void drawPlayerEspSettings(GuiGraphics g) {
        var value = config.playerEsp;
        slider(g, "range", "Range", 0, () -> value.range, next -> value.range = (int) next,
                PlayerEspSettings.MIN_RANGE, PlayerEspSettings.MAX_RANGE, 1, " blocks");
        settingToggle(g, "throughWalls", "Through walls", 0, () -> value.throughWalls,
                () -> value.throughWalls = !value.throughWalls);
        colorRow(g, "Outline color", 0, () -> value.color, next -> value.color = next);
        slider(g, "opacity", "Opacity", 0, () -> value.opacity, next -> value.opacity = (int) next, 0, 255, 1, "");
    }

    private void drawTimeChangerSettings(GuiGraphics g) {
        var value = config.timeChanger;
        slider(g, "time", "Local time", () -> value.time, next -> value.time = (int) next,
                0, TimeChangerSettings.TICKS_PER_DAY - 1, 1, DonutScreen::timeOfDayLabel);
        int gap = s(8);
        int buttonWidth = (rowW - gap) / 2;
        int rightX = rowX + buttonWidth + gap;
        button(g, rowPrefix + "sunrise", "Sunrise", rowX, settingsCursor, buttonWidth, s(34),
                () -> change(() -> value.time = 0));
        button(g, rowPrefix + "noon", "Noon", rightX, settingsCursor, buttonWidth, s(34),
                () -> change(() -> value.time = 6_000));
        settingsCursor += s(40);
        button(g, rowPrefix + "sunset", "Sunset", rowX, settingsCursor, buttonWidth, s(34),
                () -> change(() -> value.time = 12_000));
        button(g, rowPrefix + "midnight", "Midnight", rightX, settingsCursor, buttonWidth, s(34),
                () -> change(() -> value.time = 18_000));
        settingsCursor += s(40);
    }

    private static String timeOfDayLabel(double ticks) {
        int minuteOfDay = ((int) ticks * 1440 / TimeChangerSettings.TICKS_PER_DAY + 360) % 1440;
        return String.format(Locale.ROOT, "%02d:%02d", minuteOfDay / 60, minuteOfDay % 60);
    }

    private void drawLightDebugSettings(GuiGraphics g) {
        var value = config.lightDebug;
        section(g, "Underground light", 0);
        slider(g,"radius","Render radius",0,()->value.radius,next->value.radius=(int)next,1,16,1," chunks");
        slider(g,"minLight","Minimum light",0,()->value.minLight,next->value.minLight=(int)next,1,15,1,"");
        settingToggle(g,"denoise","Denoise",0,()->value.denoise,()->value.denoise=!value.denoise);
        colorRow(g,"Highlight color",0,()->value.color&0xFFFFFF,next->value.color=(value.color&0xFF000000)|(next&0xFFFFFF));
        slider(g,"opacity","Opacity",0,()->(value.color>>>24),next->value.color=((int)next<<24)|(value.color&0xFFFFFF),0,255,1,"");
        help(g,"Spectre Light Debug: block light below Y 0, brighter than skylight. Denoise removes weak isolated light fields.");
    }

    private void drawAmethystSettings(GuiGraphics g) {
        var value = config.amethyst;
        section(g, "Amethyst detection", 0);
        int y = settingsCursor, x = rowX + s(88), w = rowW - s(88);
        text(g,font,"Method",rowX,y+s(10),CONTROL_TEXT);
        button(g,"m10.method",value.method+"  v",x,y,w,s(34),()->{
            scannerMethodMenu=!scannerMethodMenu;
            modeX=x; modeY=y+s(38); modeW=w;
        });
        settingsCursor+=s(44);
        if(value.method.equals("ANBS+ Scan")) slider(g,"minCells","Min Cells",0,()->value.minCells,
                next->value.minCells=(int)next,1,100,1,"");
        settingToggle(g,"tracer","Tracer",0,()->value.tracer,()->value.tracer=!value.tracer);
        help(g,value.method.equals("Bypass") ? "Uses received chunk palettes to mark possible hidden geodes."
                : value.method.equals("Light") ? "Looks for hidden-bud lighting beside amethyst blocks."
                : "Finds the ANBS+ block-light signature in loaded chunks.");
    }

    private void drawChunkFinderSettings(GuiGraphics g, int ignored) {
        var s = config.chunkFinder;
        section(g, "Detection", 0);
        int y = settingsCursor, x = rowX + s(88), w = rowW - s(88);
        text(g, font, "Method", rowX, y + s(10), CONTROL_TEXT);
        button(g, "m4.method", s.method + "  v", x, y, w, s(34), () -> {
            scannerMethodMenu = !scannerMethodMenu;

            modeX = x; modeY = y + s(38); modeW = w;
        });
        settingsCursor += s(44);
        if (!s.method.equals("Activity clues")) {
            slider(g, "simulationDistance", "Simulation distance", 0, () -> s.simulationDistance,
                    value -> s.simulationDistance = (int) value, 2, 16, 1, " chunks");
            slider(g, "sensitivity", "Sensitivity", 0, () -> s.sensitivity,
                    value -> s.sensitivity = (int) value, 1, 20, 1, "");
            help(g, "Minimum clue points. Lower values are more sensitive.");
        } else {
            slider(g, "clueThreshold", "Bee clue points", 0, () -> s.clueThreshold,
                    value -> s.clueThreshold = (int) value, 1, 8, 1, "");
        }
        slider(g, "objectRange", "Object / inspect range", 0, () -> s.objectRange,
                value -> s.objectRange = (int) value, 16, 256, 16, " blocks");
        settingToggle(g, "inspectPanel", "Look-to-inspect panel", 0, () -> s.inspectPanel,
                () -> s.inspectPanel = !s.inspectPanel);
        section(g, "Discovery panel", 0);
        colorRow(g,"Panel top color",0,()->s.panelTopColor,value->s.panelTopColor=value);
        colorRow(g,"Panel bottom color",0,()->s.panelBottomColor,value->s.panelBottomColor=value);
        slider(g,"panelOpacity","Panel opacity",0,()->s.panelOpacity,value->s.panelOpacity=value,.2,1,.05,"%");
        section(g,"Object outlines",0);
        slider(g, "alpha", "Outline opacity", 0, () -> s.alpha,
                value -> s.alpha = (int) value, 0, 255, 1, "");
        section(g, "Growth and geodes", 0);
        settingToggle(g, "smartAdjustment", "Include short / immature growth", 0, () -> !s.smartAdjustment,
                () -> s.smartAdjustment = !s.smartAdjustment);
        slider(g, "kelpLength", "Minimum kelp length", 0, () -> s.kelpLength,
                value -> s.kelpLength = (int) value, 2, 64, 1, "");
        slider(g, "vineLength", "Minimum vine length", 0, () -> s.vineLength,
                value -> s.vineLength = (int) value, 2, 64, 1, "");
        slider(g, "caveVineLength", "Minimum cave vine length", 0, () -> s.caveVineLength,
                value -> s.caveVineLength = (int) value, 2, 64, 1, "");
        slider(g, "bambooLength", "Minimum bamboo height", 0, () -> s.bambooLength,
                value -> s.bambooLength = (int) value, 2, 32, 1, "");
        slider(g, "matureClusters", "Minimum mature crystals", 0, () -> s.matureClusters,
                value -> s.matureClusters = (int) value, 2, 128, 1, "");
        slider(g, "maturePercent", "Minimum mature crystal %", 0, () -> s.maturePercent,
                value -> s.maturePercent = (int) value, 10, 100, 5, "%");
        section(g, "Clue filters", 0);
        settingToggle(g, "kelp", "Kelp", 0, () -> s.kelp, () -> s.kelp = !s.kelp);
        settingToggle(g, "caveVines", "Cave vines", 0, () -> s.caveVines, () -> s.caveVines = !s.caveVines);
        settingToggle(g, "vines", "Vines", 0, () -> s.vines, () -> s.vines = !s.vines);
        settingToggle(g, "amethyst", "Amethyst", 0, () -> s.amethyst, () -> s.amethyst = !s.amethyst);
        settingToggle(g, "bamboo", "Bamboo", 0, () -> s.bamboo, () -> s.bamboo = !s.bamboo);
        settingToggle(g, "cocoa", "Cocoa", 0, () -> s.cocoa, () -> s.cocoa = !s.cocoa);
        settingToggle(g, "beeNest", "Bee nests / hives", 0, () -> s.beeNest, () -> s.beeNest = !s.beeNest);
        section(g, "Objects and colors", 0);
        for (var category : dev.donut.client.chunkfinder.SuspectObjects.Category.values()) {
            settingToggle(g, "objects-" + category.id(), category.label, 0, () -> s.categoryEnabled(category),
                    () -> s.objects.put(category.id(), !s.categoryEnabled(category)));
            colorRow(g, category.label + " color", 0, () -> s.categoryColor(category),
                    value -> s.objectColors.put(category.id(), value & 0xFFFFFF));
        }
        help(g, "Loaded objects and natural growth are clues, not proof of a base.");
    }

    private void drawAutoBuilderSettings(GuiGraphics g, int ignored) {
        section(g, "Schematics", 0);
        help(g, ".litematic + .schem previews and automatic building");
        help(g, "Select a file, position it, then choose Start.");
        button(g, "openAutoBuilder", "Open AutoBuilder", rowX, settingsCursor, rowW, s(34), () -> toggleEnabled(3));
        settingsCursor += s(44);
        settingToggle(g,"spectreCreative","Spectre Creative engine",0,()->config.autoBuilder.spectreCreative,()->config.autoBuilder.spectreCreative=!config.autoBuilder.spectreCreative);
        help(g, "Spectre Creative is single-player only. Turn it off to use the existing Survival and multiplayer engine.");
        help(g, "Includes material and shulker plans, saved placements, world ghosts and walking navigation.");
    }
    private boolean canUseFreecam() { return minecraft.level != null && minecraft.player != null; }

    private void freecamToggle(GuiGraphics g, String id, int x, int y) {
        if (!config.freecam.enabled && !canUseFreecam()) {
            round(g, x, y, 23, 12, 6, BORDER);
            round(g, x + 2, y + 2, 8, 8, 4, DIM);
            return;
        }
        toggle(g, id, x, y, config.freecam.enabled, () -> toggleEnabled(2));
    }


    private void drawFreecamSettings(GuiGraphics g, int ignored) {
        var value = config.freecam;
        section(g, "Movement", 0);
        slider(g, "freecamSpeed", "Camera speed", 0, () -> value.speed, v -> value.speed = v, .1, 5, .1, " blocks/tick");
        slider(g, "freecamBoost", "Sprint boost", 0, () -> value.boostMultiplier, v -> value.boostMultiplier = v, 1, 10, .1, "x");
        section(g, "Your controls", 0);
        controlHint(g, "Move", keyName(minecraft.options.keyUp) + " / " + keyName(minecraft.options.keyLeft)
                + " / " + keyName(minecraft.options.keyDown) + " / " + keyName(minecraft.options.keyRight), 0);
        controlHint(g, "Up / down", keyName(minecraft.options.keyJump) + " / " + keyName(minecraft.options.keyShift), 0);
        controlHint(g, "Speed boost", keyName(minecraft.options.keySprint), 0);
        help(g, canUseFreecam() ? "Close this menu to fly. Disable to return." : "Join a world to enable Freecam.");
    }

    private String keyName(KeyMapping key) { return key.getTranslatedKeyMessage().getString(); }

    private void keybindButton(GuiGraphics g, String id, int index, int x, int y, int w, int h) {
        KeyMapping mapping = ModuleKeybinds.mapping(index);
        if (mapping == null) return;
        boolean capturing = binding == index;
        w = Math.min(w, s(180));
        boolean hovered = inside(x, y, w, h) && (!collectingClip || mouseY >= clipTop && mouseY < clipBottom);
        if (hovered) hoveredBinding = index;
        boolean conflict = !conflicts(index).isEmpty();
        surface(g, x, y, w, h, s(7), capturing ? alpha(config.accentColor, .55) : hovered ? CONTROL_HOVER : CONTROL, 0.78f);
        String value = capturing ? "Press a key..." : (conflict ? "! " : "") + (mapping.isUnbound() ? "None" : keyName(mapping));
        text(g, font, ellipsize(value, w - s(16)), x + s(8), y + s(7), capturing ? CONTROL_TEXT : conflict ? 0xFFFFC66B : CONTROL_TEXT);
        hit(id, x, y, w, h, () -> {
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
        text(g, font, "DONUT   /   YOUR WORKSPACE", 26, H - 25, DIM);
        if (tab == CONFIGS_TAB) return;
        int active = 0;
        for (int index = ModuleKeybinds.FIRST_MODULE; index < ModuleKeybinds.MODULE_LIMIT; index++) if (enabled(index)) active++;
        text(g, font, active + " modules active", W - 180, H - 25, DIM);
        String message = binding >= 0 ? "Press a key. Delete clears. ESC cancels." : "";
        if (hoveredBinding >= 0 && binding < 0) {
            String conflict = conflicts(hoveredBinding);
            message = conflict.isEmpty() ? "Click to assign a key or mouse button." : "Also used by: " + conflict;
        }
        if (!message.isEmpty()) smallText(g, font, ellipsize(message, W - 520), 310, H - 23, CONTROL_MUTED);
    }

    private String ellipsize(String value, int maxWidth) {
        if (WorkspacePaint.width(font, value) <= maxWidth) return value;
        while (!value.isEmpty() && WorkspacePaint.width(font, value + "...") > maxWidth) value = removeLastCharacter(value);
        return value + "...";
    }

    private void controlHint(GuiGraphics g, String label, String keys, int ignored) {
        int y = settingsCursor;
        text(g, font, label, rowX, y + s(10), CONTROL_TEXT);
        int labelW = WorkspacePaint.width(font, label);
        right(g, font, ellipsize(keys, Math.max(s(60), rowW - labelW - s(16))), rowX + rowW, y + s(10), CONTROL_MUTED);
        settingsCursor += s(38);
    }

    private void clampScroll() {
        int maxScroll = Math.max(0, contentHeight - (BOTTOM - TOP));
        scroll = Math.clamp(scroll, 0, maxScroll);
        scrollTarget = Math.clamp(scrollTarget, 0, maxScroll);
    }

    private void beginContent(GuiGraphics g) {
        beginRegion(g, CX, TOP, CW, BOTTOM - TOP);
    }

    private Clip pushClip(GuiGraphics g, int x, int y, int w, int h) {
        Clip previous = new Clip(clipLeft, clipRight, clipTop, clipBottom);
        clipLeft = Math.max(clipLeft, x);
        clipRight = Math.min(clipRight, x + w);
        clipTop = Math.max(clipTop, y);
        clipBottom = Math.min(clipBottom, y + h);
        g.enableScissor(x, y, x + w, y + Math.max(0, h));
        return previous;
    }

    private void popClip(GuiGraphics g, Clip previous) {
        g.disableScissor();
        clipLeft = previous.left;
        clipRight = previous.right;
        clipTop = previous.top;
        clipBottom = previous.bottom;
    }

    private void endContent(GuiGraphics g) {
        endRegion(g);
        scrollbar(g, "scrollbar", CX + CW + 1, TOP, BOTTOM - TOP, contentHeight, scroll,
                value -> scroll = scrollTarget = value);
    }

    private void section(GuiGraphics g, String title, int ignored) {
        settingsCursor += s(16);
        smallText(g, font, title, rowX, settingsCursor, CONTROL_MUTED);
        settingsCursor += s(17);
    }

    private void group(GuiGraphics g, String id, String title, int ignored, boolean open, Runnable action) {
        int y = settingsCursor;
        smallText(g, font, title, rowX, y + s(16), CONTROL_MUTED);
        text(g, font, open ? "-" : "+", rowX + rowW - s(14), y + s(12), CONTROL_MUTED);
        hit(rowPrefix + id, rowX, y, rowW, s(40), action);
        settingsCursor += s(40);
    }



    private void slider(GuiGraphics g, String id, String label, int ignored, DoubleSupplier getter,
                        DoubleConsumer setter, double min, double max, double step, String unit) {
        slider(g, id, label, getter, setter, min, max, step,
                value -> unit.equals("%") ? Math.round(value * (step < 1 ? 100 : 1)) + "%"
                        : step >= 1 ? Math.round(value) + unit : String.format(Locale.ROOT, "%.1f%s", value, unit));
    }

    private void slider(GuiGraphics g, String id, String label, DoubleSupplier getter, DoubleConsumer setter,
                        double min, double max, double step, DoubleFunction<String> format) {
        int y = settingsCursor;
        double value = getter.getAsDouble();
        String display = format.apply(value);
        int pillW = WorkspacePaint.smallWidth(font, display) + s(16), pillH = s(21);
        text(g, font, ellipsize(label, rowW - pillW - s(8)), rowX, y + s(10), CONTROL_TEXT);
        surface(g, rowX + rowW - pillW, y + s(7), pillW, pillH, pillH / 2f, CONTROL, 0.78f);
        smallText(g, font, display, rowX + rowW - pillW + s(8), y + s(11), CONTROL_MUTED);
        int trackX = rowX + s(7), trackW = rowW - s(14), trackY = y + s(45);
        double t = Math.clamp((value - min) / (max - min), 0, 1);
        boolean hovered = inside(rowX, y + s(36), rowW, s(18));
        float r = (hovered ? 8 : 6.5f) * fontScale;
        float knobX = trackX + (float) (trackW * t);
        round(g, trackX, trackY - s(2), trackW, s(4), s(2), 0xFF3A3F4A);
        round(g, trackX, trackY - s(2), Math.max(s(4), (int) (trackW * t)), s(4), s(2), alpha(config.accentColor, 1));
        if (hovered) round(g, knobX - r - s(4), trackY - r - s(4), 2 * (r + s(4)), 2 * (r + s(4)), r + s(4), alpha(config.accentColor, .25));
        round(g, knobX - r, trackY - r, r * 2, r * 2, r, 0xFFF5F6F8);
        dragHit(rowPrefix + id, trackX, y + s(36), trackW, s(18), position -> {
            double next = Math.round((min + Math.clamp(position, 0, 1) * (max - min)) / step) * step;
            setter.accept(Math.clamp(next, min, max)); dirty = true;
        }, false, direction -> { setter.accept(Math.clamp(getter.getAsDouble() + direction * step, min, max)); dirty = true; flush(); });
        settingsCursor += s(64);
    }

    private void settingToggle(GuiGraphics g, String id, String label, int ignored, BooleanSupplier value, Runnable action) {
        int y = settingsCursor, x = rowX + rowW - s(40);
        double hover = animate("settingHover" + rowPrefix + id, inside(rowX, y, rowW, s(46)) ? 1 : 0);
        round(g, rowX - s(6), y, rowW + s(12), s(46), s(6), alpha(0xFFFFFF, .04 * hover));
        text(g, font, ellipsize(label, rowW - s(52)), rowX, y + s(15), CONTROL_TEXT);
        double on = animate("settingToggle" + rowPrefix + id, value.getAsBoolean() ? 1 : 0);
        surface(g, x, y + s(12), s(40), s(22), s(11), blend(0xFF3A3F4A, config.accentColor, on), 0.62f);
        float knobX = x + s(11) + (float) on * s(18), knobY = y + s(23), r = 8.5f * fontScale;
        round(g, knobX - r, knobY - r + fontScale, r * 2, r * 2, r, 0x30000000);
        round(g, knobX - r, knobY - r, r * 2, r * 2, r, 0xFFF5F6F8);
        hit(rowPrefix + id, rowX, y, rowW, s(46), () -> change(action));
        line(g, rowX, y + s(46) - 1, rowX + rowW, y + s(46) - 1, 0x0FFFFFFF);
        settingsCursor += s(46);
    }

    private void toggle(GuiGraphics g, String id, int x, int y, boolean on, Runnable action) {
        double position = animate("toggle" + id, on ? 1 : 0);
        round(g, x, y, 23, 12, 6, blend(BORDER, config.accentColor, position));
        round(g, x + 2 + (float) (11 * position), y + 2, 8, 8, 4, blend(DIM, 0xFFFFFFFF, position));
        hit(id, x - 3, y - 4, 29, 20, action);
    }

    private void colorRow(GuiGraphics g, String label, int ignored, IntSupplier getter, IntConsumer setter) {
        int y = settingsCursor, x = rowX + rowW - s(44);
        text(g, font, ellipsize(label, rowW - s(60)), rowX, y + s(15), CONTROL_TEXT);
        round(g, x, y + s(11), s(44), s(24), s(7), alpha(getter.getAsInt(), 1));
        outline(g, x, y + s(11), s(44), s(24), s(7), alpha(0xFFFFFF, inside(x, y, s(44), s(46)) ? .4 : .15));
        hit(rowPrefix + "color" + label, x, y + s(11), s(44), s(24),
                () -> { colorEditor = new ColorEditor(label, getter, setter); modalProgress = 0; keyboardFocus = null; scannerMethodMenu = false; });
        settingsCursor += s(46);
    }

    private void drawAppearance(GuiGraphics g) {
        var style = config.clickGuiStyle;
        detailW = Math.round(Math.min(360 * fontScale, .43f * W));
        detailX = W - detailW - 16;
        int previewW = W - detailW - 48, panelH = H - 114;
        surface(g, 16, 88, previewW, panelH, 12, 0xFF101823, 0.9f);
        surface(g, detailX, 88, detailW, panelH, s(22), 0xFF101823, 0.93f);
        outline(g, detailX, 88, detailW, panelH, s(22), 0x12FFFFFF);
        int pad = s(20);
        WorkspacePaint.title(g, font, "Appearance", detailX + pad, 88 + pad, CONTROL_TEXT);
        smallText(g, font, "Make the workspace yours.", detailX + pad, 88 + pad + s(34), CONTROL_MUTED);
        String[] surfaces = {"Global", "Panels", "Cards", "Tab bar"};
        int segmentY = 88 + pad + s(65), segmentW = (detailW - pad * 2) / 4;
        for (int i = 0; i < surfaces.length; i++) {
            int item = i, sx = detailX + pad + segmentW * i;
            surface(g, sx, segmentY, segmentW - s(2), s(32), s(6), appearanceSurface == i ? alpha(config.accentColor, .25) : CONTROL, 0.78f);
            smallText(g, font, surfaces[i], sx + (segmentW - WorkspacePaint.smallWidth(font, surfaces[i])) / 2f, segmentY + s(10), CONTROL_TEXT);
            hit("surface" + i, sx, segmentY, segmentW, s(32), () -> { appearanceSurface = item; detailScroll = detailScrollTarget = 0; });
        }
        int sampleW = Math.min(s(440), previewW - s(48)), sampleH = s(appearanceSurface == 3 ? 92 : 250);
        int sampleX = 16 + (previewW - sampleW) / 2, sampleY = 88 + (panelH - sampleH) / 2;
        int sampleColor = appearanceSurface == 1 ? style.panelsColor : appearanceSurface == 3 ? style.tabBarColor : style.cardsColor;
        double sampleOpacity = appearanceSurface == 1 ? style.panelsOpacity : appearanceSurface == 3 ? style.tabBarOpacity : style.cardsOpacity;
        shadow(g, sampleX, sampleY, sampleW, sampleH, s((float) style.rounding), 0x70000000);
        surface(g, sampleX, sampleY, sampleW, sampleH, s((float) style.rounding), alpha(sampleColor, sampleOpacity), 0.88f);
        outline(g, sampleX, sampleY, sampleW, sampleH, s((float) style.rounding), 0x26FFFFFF);
        text(g, font, appearanceSurface == 3 ? "Modules     HUD     Appearance" : "Module preview", sampleX + s(20), sampleY + s(20), CONTROL_TEXT);
        if (appearanceSurface != 3) {
            text(g, font, "A live preview of your theme", sampleX + s(20), sampleY + s(52), CONTROL_MUTED);
            round(g, sampleX + s(12), sampleY + s(90), sampleW - s(24), s(34), s(7), alpha(config.accentColor, .14));
            text(g, font, "Selected module", sampleX + s(22), sampleY + s(99), CONTROL_TEXT);
            text(g, font, "Inactive module", sampleX + s(22), sampleY + s(146), CONTROL_MUTED);
            round(g, sampleX + s(22), sampleY + sampleH - s(37), sampleW - s(44), s(3), s(2), CONTROL);
            round(g, sampleX + s(22), sampleY + sampleH - s(37), (sampleW - s(44)) * .6f, s(3), s(2), alpha(config.accentColor, 1));
            round(g, sampleX + s(22) + (sampleW - s(44)) * .6f - s(5), sampleY + sampleH - s(41), s(10), s(10), s(5), CONTROL_TEXT);
        }
        smallText(g, font, "LIVE PREVIEW", 16 + s(24), 88 + s(24), CONTROL_MUTED);
        detailTop = segmentY + s(46);
        detailBottom = 88 + panelH - pad;
        clampDetailScroll();
        beginRegion(g, detailX + pad - 2, detailTop, detailW - pad * 2 + 4, detailBottom - detailTop);
        rowX = detailX + pad;
        rowW = detailW - pad * 2;
        rowPrefix = "";
        settingsCursor = detailTop - (int) detailScroll;
        if (appearanceSurface == 0) {
            section(g, "Presets", 0);
            int[] accents = {0x56DCCB, 0x78A8FF, 0xBB9AF7, 0xF38BA8, 0xFAB387, 0xCBDC56};
            int stride = Math.min(s(40), rowW / accents.length);
            for (int i = 0; i < accents.length; i++) {
                int accent = accents[i], px = rowX + i * stride;
                round(g, px + s(3), settingsCursor + s(3), s(22), s(22), s(11), alpha(accent, 1));
                if (config.accentColor == accent) outline(g, px, settingsCursor, s(28), s(28), s(14), CONTROL_TEXT);
                hit("preset" + i, px, settingsCursor, s(28), s(28), () -> change(() -> config.accentColor = accent));
            }
            settingsCursor += s(38);
            section(g, "Colors", 0);
            colorRow(g, "Accent color", 0, () -> config.accentColor, value -> config.accentColor = value);
            section(g, "Layout", 0);
            slider(g, "rounding", "Corner radius", 0, () -> style.rounding, value -> style.rounding = value, 0, 16, 1, "");
            slider(g, "backgroundDim", "Background dim", 0, () -> style.backgroundDim, value -> style.backgroundDim = value, 0, .8, .05, "%");
            section(g, "Interface", 0);
            settingToggle(g, "liquidGlass", "Frosted liquid glass", 0,
                    () -> style.liquidGlass, () -> style.liquidGlass = !style.liquidGlass);
            slider(g, "uiScale", "UI scale", 0, () -> style.scale, value -> style.scale = value, .75, 1.5, .05, "%");
            slider(g, "animationSpeed", "Animation speed", 0, () -> style.animationSpeed, value -> style.animationSpeed = value, 0, 2, .1, "x");
            settingToggle(g, "particles", "Ambient particles", 0, () -> config.clickGuiParticles, () -> config.clickGuiParticles = !config.clickGuiParticles);
        } else {
            section(g, surfaces[appearanceSurface], 0);
            colorRow(g, "Color", 0, () -> appearanceSurface == 1 ? style.panelsColor : appearanceSurface == 2 ? style.cardsColor : style.tabBarColor,
                    value -> { if (appearanceSurface == 1) style.panelsColor = value; else if (appearanceSurface == 2) style.cardsColor = value; else style.tabBarColor = value; });
            slider(g, "surfaceOpacity", "Opacity", 0,
                    () -> appearanceSurface == 1 ? style.panelsOpacity : appearanceSurface == 2 ? style.cardsOpacity : style.tabBarOpacity,
                    value -> { if (appearanceSurface == 1) style.panelsOpacity = value; else if (appearanceSurface == 2) style.cardsOpacity = value; else style.tabBarOpacity = value; }, .2, 1, .05, "%");
        }
        detailContentHeight = settingsCursor + s(20) - detailTop + (int) detailScroll;
        endRegion(g);
        clampDetailScroll();
        drawDetailScrollbar(g);
    }

    private void drawColorEditor(GuiGraphics g) {
        // Modal hit testing replaces the underlying settings' controls.
        hits.clear();
        round(g, 0, 0, W, H, corner(18), 0xB0120D09);
        int w = 318, h = 252, x = (W - w) / 2, y = (H - h) / 2;
        shadow(g, x, y, w, h, 14, 0x70000000);
        surface(g, x, y, w, h, 14, 0xFF101823, 1f);
        outline(g, x, y, w, h, 14, chromeBorder());
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
        surface(g, x + 18, y + 210, 141, 27, 6, colorEditor.hexFocused ? BORDER : CARD, 0.8f);
        String value = "#" + colorEditor.hex + (colorEditor.hexFocused && System.currentTimeMillis() % 1000 < 500 ? "|" : "");
        text(g, font, value, x + 29, y + 220, colorEditor.hex.length() == 6 ? TEXT : 0xFFFFC66B);
        hit("hex", x + 18, y + 210, 141, 27, () -> { colorEditor.hexFocused = true; colorEditor.replaceHex = true; });
        surface(g, x + w - 111, y + 210, 93, 27, 6, alpha(config.accentColor, 1), 0.62f);
        int accent = config.accentColor;
        int ink = config.clickGuiStyle.liquidGlass ? TEXT :
                ((accent >> 16 & 255) * .2126 + (accent >> 8 & 255) * .7152 + (accent & 255) * .0722) > 150 ? PANEL : TEXT;
        text(g, font, "Done", x + w - 80, y + 220, ink);
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
        if (config.clickGuiStyle.animationSpeed == 0) return target;
        double result = current + (target - current) * (1 - Math.exp(-speed * frameSeconds * config.clickGuiStyle.animationSpeed));
        return Math.abs(result - target) < .001 ? target : result;
    }

    private double advance(double value, boolean showing, double duration) {
        if (config.clickGuiStyle.animationSpeed == 0) return showing ? 1 : 0;
        return Math.clamp(value + (showing ? 1 : -1) * frameSeconds * config.clickGuiStyle.animationSpeed / duration, 0, 1);
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
        int left = collectingClip ? Math.max(x, clipLeft) : x;
        int right = collectingClip ? Math.min(x + w, clipRight) : x + w;
        if (top >= bottom || left >= right) return;
        hits.add(new Hit(id, left, top, right - left, bottom - top, action, secondary, null, false, null));
    }

    private void dragHit(String id, int x, int y, int w, int h, DoubleConsumer action, boolean vertical) {
        dragHit(id, x, y, w, h, action, vertical, null);
    }

    private void dragHit(String id, int x, int y, int w, int h, DoubleConsumer action, boolean vertical, IntConsumer nudge) {
        if (!contentInteractive || closing) return;
        int top = collectingClip ? Math.max(y, clipTop) : y;
        int bottom = collectingClip ? Math.min(y + h, clipBottom) : y + h;
        int left = collectingClip ? Math.max(x, clipLeft) : x;
        int right = collectingClip ? Math.min(x + w, clipRight) : x + w;
        if (top >= bottom || left >= right) return;
        hits.add(new Hit(id, left, top, right - left, bottom - top, null, null, action, vertical, nudge));
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
        commitBasesFound();
        if (!commitTimerDuration()) cancelTimerDuration();
        double x = (event.x() - originX) / scale, y = (event.y() - originY) / scale;
        keyboardFocus = null;
        searchFocused = false;
        if (colorEditor != null) colorEditor.hexFocused = false;
        if (tab == CONFIGS_TAB && profiles.mouseClicked(x, y, event, doubled)) return true;
        for (int i = hits.size() - 1; i >= 0; i--) {
            Hit h = hits.get(i);
            if (!h.contains(x, y)) continue;
            if (rightClick) {
                // A module tile also supports right-click selection.
                if (h.secondary == null) continue;
                h.secondary.run();
            } else if (h.drag != null) { dragging = h; h.move(x, y); }
            else h.action.run();
            return true;
        }

        scannerMethodMenu = false;
        return true;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (closing) return true;
        if (tab == CONFIGS_TAB && profiles.mouseDragged((event.x() - originX) / scale,
                (event.y() - originY) / scale, event, dx / scale, dy / scale)) return true;
        if (dragging == null) return false;
        dragging.move((event.x() - originX) / scale, (event.y() - originY) / scale);
        return true;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (tab == CONFIGS_TAB && profiles.mouseReleased((event.x() - originX) / scale,
                (event.y() - originY) / scale, event)) return true;
        if (dragging != null) { dragging = null; flush(); return true; }
        return false;
    }

    @Override
    public void onFilesDrop(List<Path> files) {
        if (!closing && tab == CONFIGS_TAB) profiles.importFiles(files);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        if (closing || colorEditor != null || scannerMethodMenu || binding >= 0) return true;
        double localX = (x - originX) / scale, localY = (y - originY) / scale;
        if (tab == CONFIGS_TAB) return profiles.mouseScrolled(localX, localY, vertical);
        if (localX >= detailX && localX <= detailX + detailW && localY >= detailTop && localY <= detailBottom) {
            commitBasesFound();
            if (!commitTimerDuration()) cancelTimerDuration();
            detailScrollTarget = Math.clamp(detailScrollTarget - vertical * s(40), 0,
                    Math.max(0, detailContentHeight - (detailBottom - detailTop)));
            keyboardFocus = null;
            return true;
        }
        if (tab != 2 && tab != 6 && localX >= CX && localX <= CX + CW && localY >= TOP && localY <= BOTTOM) {
            commitBasesFound();
            if (!commitTimerDuration()) cancelTimerDuration();
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
        if (tab == CONFIGS_TAB && profiles.keyPressed(event)) return true;
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
        if (editingTimer >= 0) {
            if (event.key() == GLFW.GLFW_KEY_ESCAPE) {
                capturedKey = pressed;
                cancelTimerDuration();
                timerError = -1;
                keyboardFocus = null;
                return true;
            }
            if (event.key() == GLFW.GLFW_KEY_ENTER || event.key() == GLFW.GLFW_KEY_KP_ENTER) {
                if (commitTimerDuration()) keyboardFocus = null;
                return true;
            }
            if (event.key() == GLFW.GLFW_KEY_TAB) {
                if (!commitTimerDuration()) return true;
            } else {
                if (event.key() == GLFW.GLFW_KEY_A && event.hasControlDown()) replaceTimerDuration = true;
                else if (event.key() == GLFW.GLFW_KEY_V && event.hasControlDown()) {
                    String pasted = minecraft.keyboardHandler.getClipboard().trim();
                    if (pasted.matches("[0-9:]{1,8}")) {
                        String next = (replaceTimerDuration ? "" : timerDurationDraft) + pasted;
                        if (next.length() <= 8) { timerDurationDraft = next; replaceTimerDuration = false; timerError = -1; }
                    } else timerError = editingTimer;
                } else if (event.key() == GLFW.GLFW_KEY_BACKSPACE || event.key() == GLFW.GLFW_KEY_DELETE) {
                    timerDurationDraft = replaceTimerDuration ? "" : removeLastCharacter(timerDurationDraft);
                    replaceTimerDuration = false;
                    timerError = -1;
                }
                return true;
            }
        }
        if (basesFoundDraft != null) {
            if (event.key() == GLFW.GLFW_KEY_ESCAPE) {
                capturedKey = pressed;
                basesFoundDraft = null;
                replaceBasesFound = false;
                keyboardFocus = null;
                return true;
            }
            if (event.key() == GLFW.GLFW_KEY_ENTER || event.key() == GLFW.GLFW_KEY_KP_ENTER) {
                commitBasesFound();
                keyboardFocus = null;
                return true;
            }
            if (event.key() == GLFW.GLFW_KEY_TAB) commitBasesFound();
            else {
                if (event.key() == GLFW.GLFW_KEY_A && event.hasControlDown()) replaceBasesFound = true;
                else if (event.key() == GLFW.GLFW_KEY_V && event.hasControlDown()) {
                    String pasted = minecraft.keyboardHandler.getClipboard().trim();
                    if (pasted.matches("[0-9]+")) {
                        String next = (replaceBasesFound ? "" : basesFoundDraft) + pasted;
                        // Remove leading zeroes before bounding long input to a safe integer.
                        next = next.replaceFirst("^0+(?!$)", "");
                        basesFoundDraft = next.length() > 18 ? Integer.toString(ProgressBarSettings.MAX_BASES_FOUND) : next;
                        replaceBasesFound = false;
                    }
                } else if (event.key() == GLFW.GLFW_KEY_BACKSPACE || event.key() == GLFW.GLFW_KEY_DELETE) {
                    basesFoundDraft = replaceBasesFound ? "" : removeLastCharacter(basesFoundDraft);
                    replaceBasesFound = false;
                }
                return true;
            }
        }
        KeyMapping open = KeyMapping.get("key.donut.open_gui");
        if (open != null && !open.isUnbound() && pressed.equals(InputConstants.getKey(open.saveString()))) {
            onClose();
            return true;
        }
        if (event.key() == GLFW.GLFW_KEY_ESCAPE) {
            if (scannerMethodMenu) { scannerMethodMenu = false; }
            else if (colorEditor != null) closeColor();
            else if (searchFocused) { searchFocused = false; query = ""; contentProgress = 0; }
            else onClose();
            return true;
        }
        if (colorEditor == null && !scannerMethodMenu && tab != 2 && tab != 6 && tab != CONFIGS_TAB
                && event.key() == GLFW.GLFW_KEY_F && event.hasControlDown()) {
            searchFocused = true;
            keyboardFocus = null;
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
        if (colorEditor == null && !scannerMethodMenu && !searchFocused) {
            if (event.key() == GLFW.GLFW_KEY_PAGE_DOWN || event.key() == GLFW.GLFW_KEY_PAGE_UP) {
                if (selectedModule >= 0 || tab == 2 || tab == 6) detailScrollTarget = Math.clamp(detailScrollTarget + (event.key() == GLFW.GLFW_KEY_PAGE_DOWN ? s(170) : -s(170)), 0, Math.max(0, detailContentHeight - (detailBottom - detailTop)));
                else scrollTarget = Math.clamp(scrollTarget + (event.key() == GLFW.GLFW_KEY_PAGE_DOWN ? 170 : -170), 0, Math.max(0, contentHeight - (BOTTOM - TOP)));
                keyboardFocus = null; return true;
            }
            if (event.key() == GLFW.GLFW_KEY_HOME) { if (selectedModule >= 0 || tab == 2 || tab == 6) detailScrollTarget = 0; else scrollTarget = 0; return true; }
            if (event.key() == GLFW.GLFW_KEY_END) { if (selectedModule >= 0 || tab == 2 || tab == 6) detailScrollTarget = Math.max(0, detailContentHeight - (detailBottom - detailTop)); else scrollTarget = Math.max(0, contentHeight - (BOTTOM - TOP)); return true; }
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
        if (tab == CONFIGS_TAB) return profiles.charTyped(event);
        if (editingTimer >= 0) {
            if (event.codepoint() >= '0' && event.codepoint() <= '9' || event.codepoint() == ':') {
                if (replaceTimerDuration) { timerDurationDraft = ""; replaceTimerDuration = false; }
                if (timerDurationDraft.length() < 8) timerDurationDraft += event.codepointAsString();
                timerError = -1;
            }
            return true;
        }
        if (basesFoundDraft != null) {
            if (event.codepoint() >= '0' && event.codepoint() <= '9') {
                if (replaceBasesFound) { basesFoundDraft = ""; replaceBasesFound = false; }
                if (basesFoundDraft.length() < 18) basesFoundDraft += event.codepointAsString();
            }
            return true;
        }
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
        commitBasesFound();
        if (!commitTimerDuration()) cancelTimerDuration();
        flush();
        closing = true;
        dragging = null;
        binding = -1;
        keyboardFocus = null;
        searchFocused = false;
    }

    @Override
    public void removed() {
        profiles.deactivate();
        commitBasesFound();
        if (!commitTimerDuration()) cancelTimerDuration();
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

    private record Clip(int left, int right, int top, int bottom) { }

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
