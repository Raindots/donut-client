package dev.donut.client.gametest;

import com.mojang.authlib.GameProfile;
import com.mojang.blaze3d.platform.InputConstants;
import dev.donut.client.config.ClientConfig;
import dev.donut.client.config.ConfigStore;
import dev.donut.client.config.DonutConfigPaths;
import dev.donut.client.config.PlayerEspSettings;
import dev.donut.client.gui.DonutScreen;
import dev.donut.client.input.ModuleKeybinds;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Blocks;
import org.lwjgl.glfw.GLFW;

import javax.imageio.ImageIO;
import java.io.IOException;
import java.util.UUID;

/** Exercises the production module controls and both world rendering depth modes. */
public final class PlayerEspGameTest implements FabricClientGameTest {
    private static final int MODULE = ModuleKeybinds.PLAYER_ESP;
    private static final int TARGET = 2_000_000_130;
    private static final int SHORTCUT = GLFW.GLFW_KEY_F9;
    private static final String WALL = "-2 65 3 2 67 3";
    private static final BlockPos WALL_CENTER = new BlockPos(0, 66, 3);

    @Override
    public void runTest(ClientGameTestContext context) {
        try (var world = context.worldBuilder().create()) {
            var server = world.getServer();
            prepare(context, server);
            world.getClientWorld().waitForChunksRender();
            String originalKey = context.computeOnClient(client -> ModuleKeybinds.mapping(MODULE).saveString());
            int originalScale = context.computeOnClient(client -> client.options.guiScale().get());
            context.getInput().resizeWindow(1280, 800);
            context.runOnClient(client -> {
                client.options.guiScale().set(1);
                client.resizeDisplay();
            });

            var config = openSettings(context);
            var store = new ConfigStore(DonutConfigPaths.resolve(FabricLoader.getInstance().getConfigDir()).active());
            var originalSettings = config.playerEsp;
            var settings = new PlayerEspSettings();
            context.runOnClient(client -> config.playerEsp = settings);
            try {
                configureInGui(context, settings);
                var saved = store.load().playerEsp;
                require(saved.enabled && saved.favorite && !saved.throughWalls && saved.color == 0xFF00FF
                        && saved.range == settings.range && saved.opacity == settings.opacity, "Player ESP settings did not save");

                RemotePlayer target = context.computeOnClient(client -> {
                    var player = new RemotePlayer(client.level, new GameProfile(UUID.randomUUID(), "EspFixture"));
                    player.setId(TARGET);
                    position(player, 8.5);
                    client.level.addEntity(player);
                    settings.opacity = 255;
                    settings.range = 128;
                    return player;
                });
                checkFrame(context, "occluded", false);
                context.runOnClient(client -> settings.throughWalls = true);
                checkFrame(context, "through-walls", true);

                context.runOnClient(client -> target.setInvisible(true));
                checkFrame(context, "invisible", false);
                context.runOnClient(client -> {
                    target.setInvisible(false);
                    settings.range = PlayerEspSettings.MIN_RANGE;
                    position(target, 23.5);
                });
                checkFrame(context, "out-of-range", false);

                context.runOnClient(client -> {
                    position(target, 8.5);
                    settings.throughWalls = false;
                });
                server.runCommand("fill " + WALL + " minecraft:air");
                context.waitFor(client -> client.level.getBlockState(WALL_CENTER).isAir());
                world.getClientWorld().waitForChunksRender();
                checkFrame(context, "visible", true);
                context.runOnClient(client -> settings.opacity = 0);
                checkFrame(context, "transparent", false);
                context.runOnClient(client -> settings.opacity = 255);

                context.getInput().pressKey(SHORTCUT);
                context.waitFor(client -> !settings.enabled);
                checkFrame(context, "disabled", false);
                context.getInput().pressKey(SHORTCUT);
                context.waitFor(client -> settings.enabled);
                checkFrame(context, "enabled", true);
                context.runOnClient(client -> client.level.removeEntity(TARGET, Entity.RemovalReason.DISCARDED));
                checkFrame(context, "removed", false);
            } finally {
                context.runOnClient(client -> {
                    config.playerEsp = originalSettings;
                    client.level.removeEntity(TARGET, Entity.RemovalReason.DISCARDED);
                    ModuleKeybinds.setKey(client, MODULE, InputConstants.getKey(originalKey));
                    client.options.guiScale().set(originalScale);
                    client.resizeDisplay();
                    store.save(config);
                });
            }
        }
    }

    private static void prepare(ClientGameTestContext context, TestServerContext server) {
        server.runCommand("fill -4 65 -2 4 68 26 minecraft:air");
        server.runCommand("fill -4 64 -2 4 64 26 minecraft:smooth_stone");
        server.runCommand("fill " + WALL + " minecraft:smooth_stone");
        server.runCommand("tp @a 0.5 65 0.5 0 0");
        context.waitFor(client -> client.player.onGround() && client.player.distanceToSqr(0.5, 65, 0.5) < 0.001
                && client.level.getBlockState(WALL_CENTER).is(Blocks.SMOOTH_STONE));
    }

    private static ClientConfig openSettings(ClientGameTestContext context) {
        KeyMapping openGui = context.computeOnClient(client -> KeyMapping.get("key.donut.open_gui"));
        context.waitTicks(2);
        context.getInput().pressKey(openGui);
        context.waitForScreen(DonutScreen.class);
        return context.computeOnClient(client -> {
            try {
                var field = DonutScreen.class.getDeclaredField("config");
                field.setAccessible(true);
                return (ClientConfig) field.get(client.screen);
            } catch (ReflectiveOperationException error) {
                throw new AssertionError(error);
            }
        });
    }

    private static void configureInGui(ClientGameTestContext context, PlayerEspSettings settings) {
        String prefix = "m" + MODULE + ".";
        GuiTestInput.click(context, "tab0");
        GuiTestInput.click(context, "search");
        GuiTestInput.type(context, "playeresp");
        GuiTestInput.click(context, "module" + MODULE);
        GuiTestInput.click(context, "enable" + MODULE);
        GuiTestInput.click(context, prefix + "range");
        GuiTestInput.click(context, prefix + "opacity");
        GuiTestInput.click(context, prefix + "throughWalls");
        GuiTestInput.click(context, prefix + "colorOutline color");
        GuiTestInput.click(context, "hex");
        GuiTestInput.type(context, "FF00FF");
        GuiTestInput.click(context, "colorDone");
        require(settings.range != 128 && settings.opacity > 0 && settings.opacity < 255, "Player ESP sliders did not update");
        GuiTestInput.click(context, "keybind" + MODULE);
        context.getInput().pressKey(SHORTCUT);
        require(context.computeOnClient(client -> ModuleKeybinds.mapping(MODULE).saveString().equals("key.keyboard.f9")),
                "Player ESP shortcut did not bind");
        GuiTestInput.click(context, "favorite" + MODULE);
        GuiTestInput.click(context, "tab1");
        require(GuiTestInput.visible(context, "module" + MODULE), "Player ESP is missing from Favorites");
        GuiTestInput.closeAnimated(context);
        context.waitTicks(2);
    }

    private static void position(RemotePlayer player, double z) {
        player.setPos(0.5, 65, z);
        player.setOldPosAndRot();
    }

    private static void checkFrame(ClientGameTestContext context, String label, boolean expected) {
        context.waitTicks(3);
        var screenshot = context.takeScreenshot(TestScreenshotOptions.of("player-esp-" + label).withSize(960, 600));
        try {
            var image = ImageIO.read(screenshot.toFile());
            int pixels = 0;
            for (int y = image.getHeight() / 4; y < image.getHeight() * 3 / 4; y++) {
                for (int x = image.getWidth() / 4; x < image.getWidth() * 3 / 4; x++) {
                    int color = image.getRGB(x, y);
                    if ((color >> 16 & 255) > 220 && (color >> 8 & 255) < 40 && (color & 255) > 220) pixels++;
                }
            }
            require(expected ? pixels > 50 : pixels == 0, "Player ESP frame " + label + " had " + pixels + " outline pixels");
        } catch (IOException error) {
            throw new AssertionError("Could not read Player ESP screenshot", error);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
