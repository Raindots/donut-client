package dev.donut.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.donut.client.config.ClientConfig;
import dev.donut.client.config.ConfigStore;
import dev.donut.client.finder.FinderScanner;
import dev.donut.client.chunkfinder.ChunkFinderScanner;
import dev.donut.client.fullbright.FullbrightController;
import dev.donut.client.freecam.FreecamController;
import dev.donut.client.fly.FlyController;
import dev.donut.client.gui.DonutScreen;
import dev.donut.client.gui.ModuleArrayList;
import dev.donut.client.input.ModuleKeybinds;
import dev.donut.client.render.HighlightRenderer;
import dev.donut.client.autobuild.AutoBuilderController;
import dev.donut.client.gui.AutoBuilderScreen;
import dev.donut.client.gui.AutoBuilderHud;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
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
    private ConfigStore store;
    private ClientConfig config;
    private FinderScanner scanner;
    private ChunkFinderScanner chunkScanner;

    @Override
    public void onInitializeClient() {
        store = new ConfigStore(FabricLoader.getInstance().getConfigDir().resolve("donut-client.json"));
        config = store.load();
        scanner = new FinderScanner(config);
        chunkScanner = new ChunkFinderScanner(config);
        FullbrightController fullbright = new FullbrightController(config);
        ClientTickEvents.START_CLIENT_TICK.register(fullbright::tick);
        FreecamController freecam = new FreecamController(config);
        ClientTickEvents.START_CLIENT_TICK.register(freecam::tick);
        FlyController fly = new FlyController(config);
        ClientTickEvents.START_CLIENT_TICK.register(fly::tick);
        ServerTickEvents.START_SERVER_TICK.register(fly::serverTick);
        ServerLifecycleEvents.SERVER_STOPPING.register(fly::serverStopping);
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> fly.serverDisconnected(server, handler.player));
        AutoBuilderController autoBuilder = AutoBuilderController.get();
        ClientTickEvents.START_CLIENT_TICK.register(autoBuilder::tick);
        HighlightRenderer renderer = new HighlightRenderer(config, scanner, chunkScanner);
        renderer.register();

        KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MOD_ID, "client"));
        KeyMapping open = bind("open_gui", GLFW.GLFW_KEY_RIGHT_SHIFT, category);
        KeyMapping storage = bind("toggle_storage", InputConstants.UNKNOWN.getValue(), category);
        KeyMapping spawner = bind("toggle_spawner", InputConstants.UNKNOWN.getValue(), category);
        KeyMapping camera = bind("toggle_freecam", GLFW.GLFW_KEY_F6, category);
        KeyMapping builder = bind("open_autobuilder", InputConstants.UNKNOWN.getValue(), category);
        KeyMapping chunks = bind("toggle_chunk_finder", InputConstants.UNKNOWN.getValue(), category);
        KeyMapping bright = bind("toggle_fullbright", InputConstants.UNKNOWN.getValue(), category);
        KeyMapping flight = bind("toggle_fly", InputConstants.UNKNOWN.getValue(), category);
        ModuleKeybinds.initialize(storage, spawner, camera, builder, chunks, bright, flight);

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
            if (ModuleKeybinds.consumePress(client, 3)) {
                if (!inputBlocked && client.screen == null && client.player != null)
                    client.setScreen(new AutoBuilderScreen(null, config, this::save));
            }
            if (ModuleKeybinds.consumePress(client, 4)) {
                if (!inputBlocked && client.screen == null && client.player != null) {
                    config.chunkFinder.enabled = !config.chunkFinder.enabled;
                    save();
                }
            }
            if (ModuleKeybinds.consumePress(client, 5)) {
                if (!inputBlocked && client.screen == null && client.player != null) {
                    config.fullbright.enabled = !config.fullbright.enabled;
                    save();
                }
            }
            if (ModuleKeybinds.consumePress(client, 6)) {
                if (!inputBlocked && client.screen == null && client.player != null) fly.setEnabled(!fly.isActive());
            }
            fly.tick(client);
            scanner.tick(client);
            chunkScanner.tick(client);
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
            AutoBuilderHud.render(graphics, client.font, client.getWindow().getGuiScaledHeight(), config);
        });

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> { scanner.clear(); chunkScanner.clear(); freecam.disable(); fly.clientDisconnected(handler, client); autoBuilder.disconnect(); });
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            fullbright.close();
            freecam.disable();
            fly.disable();
            autoBuilder.disconnect();
            save();
            renderer.close();
        });
    }

    private KeyMapping bind(String name, int key, KeyMapping.Category category) {
        return KeyBindingHelper.registerKeyBinding(new KeyMapping("key.donut." + name, InputConstants.Type.KEYSYM, key, category));
    }

    private void save() { store.save(config); }
}
