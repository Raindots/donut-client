package dev.donut.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.donut.client.config.ClientConfig;
import dev.donut.client.config.ConfigStore;
import dev.donut.client.finder.FinderScanner;
import dev.donut.client.freecam.FreecamController;
import dev.donut.client.gui.DonutScreen;
import dev.donut.client.gui.ModuleArrayList;
import dev.donut.client.input.ModuleKeybinds;
import dev.donut.client.render.HighlightRenderer;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

public final class DonutClient implements ClientModInitializer {
    public static final String MOD_ID = "donut";
    private static DonutClient instance;
    private ConfigStore store;
    private ClientConfig config;
    private FinderScanner scanner;
    private FreecamController freecam;
    private final dev.donut.client.module.ModuleManager moduleManager = new dev.donut.client.module.ModuleManager();

    public static DonutClient getInstance() { return instance; }
    public dev.donut.client.module.ModuleManager getModuleManager() { return moduleManager; }
    public ClientConfig getConfig() { return config; }
    public FinderScanner getScanner() { return scanner; }

    @Override
    public void onInitializeClient() {
        instance = this;
        store = new ConfigStore(FabricLoader.getInstance().getConfigDir().resolve("donut-client.json"));
        config = store.load();
        scanner = new FinderScanner(config);
        freecam = new FreecamController(config);

        moduleManager.register(new dev.donut.client.module.storage.StorageEspModule());
        moduleManager.register(new dev.donut.client.module.spawner.SpawnerFinderModule());
        moduleManager.register(new dev.donut.client.module.freecam.FreecamModule());
        moduleManager.register(new dev.donut.client.module.hud.HudModule());

        dev.donut.client.module.ModuleContext context = new dev.donut.client.module.ModuleContext(
                Minecraft.getInstance(), config, store, scanner, freecam, this::save
        );
        moduleManager.init(context);

        ClientTickEvents.START_CLIENT_TICK.register(freecam::tick);
        HighlightRenderer renderer = new HighlightRenderer(config, scanner);
        renderer.register();
        dev.donut.client.render.LabelOverlayRenderer labelRenderer = new dev.donut.client.render.LabelOverlayRenderer(config, scanner);
        labelRenderer.register();

        KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MOD_ID, "client"));
        KeyMapping open = bind("open_gui", GLFW.GLFW_KEY_RIGHT_SHIFT, category);
        KeyMapping storage = bind("toggle_storage", InputConstants.UNKNOWN.getValue(), category);
        KeyMapping spawner = bind("toggle_spawner", InputConstants.UNKNOWN.getValue(), category);
        KeyMapping camera = bind("toggle_freecam", GLFW.GLFW_KEY_F6, category);
        ModuleKeybinds.initialize(storage, spawner, camera);

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            boolean inputBlocked = ModuleKeybinds.inputBlocked(client);
            while (open.consumeClick()) {
                if (!inputBlocked && client.screen == null) client.setScreen(new DonutScreen(config, this::save));
            }
            if (ModuleKeybinds.consumePress(client, 0)) {
                if (!inputBlocked && client.screen == null && client.player != null) { config.storage.enabled = !config.storage.enabled; save(); }
            }
            if (ModuleKeybinds.consumePress(client, 1)) {
                if (!inputBlocked && client.screen == null && client.player != null) { config.spawner.enabled = !config.spawner.enabled; save(); }
            }
            if (ModuleKeybinds.consumePress(client, 2)) {
                if (!inputBlocked && client.screen == null && client.player != null) freecam.setEnabled(!freecam.isActive());
            }
            scanner.tick(client);
            moduleManager.tick(client);
        });

        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            if (screen instanceof TitleScreen) {
                Screens.getButtons(screen).add(Button.builder(Component.literal("Donut"), button ->
                        client.setScreen(new DonutScreen(config, this::save)))
                        .bounds(width - 66, height - 28, 58, 20).build());
            }
        });

        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(MOD_ID, "status"), (graphics, delta) -> {
            Minecraft client = Minecraft.getInstance();
            if (!config.showHud || client.player == null || client.screen != null || client.options.hideGui) return;
            ModuleArrayList.render(graphics, client.font, client.getWindow().getGuiScaledWidth(), config, freecam.isActive());
        });

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> { scanner.clear(); freecam.disable(); });
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            moduleManager.close();
            freecam.disable();
            save();
            renderer.close();
        });
    }

    private KeyMapping bind(String name, int key, KeyMapping.Category category) {
        return KeyBindingHelper.registerKeyBinding(new KeyMapping("key.donut." + name, InputConstants.Type.KEYSYM, key, category));
    }

    private void save() { store.save(config); }
}
