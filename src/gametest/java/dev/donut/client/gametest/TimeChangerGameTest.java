package dev.donut.client.gametest;

import com.mojang.blaze3d.platform.InputConstants;
import dev.donut.client.config.ClientConfig;
import dev.donut.client.config.ConfigStore;
import dev.donut.client.config.DonutConfigPaths;
import dev.donut.client.config.TimeChangerSettings;
import dev.donut.client.gui.DonutScreen;
import dev.donut.client.input.ModuleKeybinds;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.world.attribute.EnvironmentAttributes;
import org.lwjgl.glfw.GLFW;

import javax.imageio.ImageIO;
import java.io.IOException;

/** Compares rendered local time with the clocks on both sides of an integrated world. */
public final class TimeChangerGameTest implements FabricClientGameTest {
    private static final int MODULE = ModuleKeybinds.TIME_CHANGER;
    private static final int SHORTCUT = GLFW.GLFW_KEY_F8;

    @Override
    public void runTest(ClientGameTestContext context) {
        try (var world = context.worldBuilder().create()) {
            var server = world.getServer();
            prepare(context, server);
            world.getClientWorld().waitForChunksRender();
            int originalScale = context.computeOnClient(client -> client.options.guiScale().get());
            String originalKey = context.computeOnClient(client -> ModuleKeybinds.mapping(MODULE).saveString());
            context.getInput().resizeWindow(1280, 800);
            context.runOnClient(client -> {
                client.options.guiScale().set(1);
                client.resizeDisplay();
            });

            var config = openSettings(context);
            var store = new ConfigStore(DonutConfigPaths.resolve(FabricLoader.getInstance().getConfigDir()).active());
            var original = config.timeChanger;
            var settings = new TimeChangerSettings();
            context.runOnClient(client -> config.timeChanger = settings);
            try {
                GuiTestInput.closeAnimated(context);
                context.waitTicks(3);
                var day = context.computeOnClient(TimeChangerGameTest::sky);
                int daylightLevel = context.computeOnClient(client -> client.level.getSkyDarken());
                long baselineTick = server.computeOnServer(mc -> mc.overworld().getGameTime());
                double daySky = skyLuminance(context, "server-noon");

                openSettings(context);
                chooseMidnight(context, settings);
                var saved = store.load().timeChanger;
                require(saved.enabled && saved.favorite && saved.time == 18_000, "Time Changer preferences did not save");

                var night = context.computeOnClient(TimeChangerGameTest::sky);
                double nightSky = skyLuminance(context, "local-midnight");
                require(night.sunAngle != day.sunAngle && night.stars > day.stars && night.skyLight < day.skyLight,
                        "Midnight did not change the celestial angles, stars and lightmap");
                require(night.skyColor != day.skyColor && night.fogColor != day.fogColor && night.cloudColor != day.cloudColor,
                        "Midnight did not change the sky, fog and cloud colors");
                require(nightSky < daySky * .7, "The rendered midnight sky did not get darker");
                require(server.computeOnServer(mc -> mc.overworld().getDayTime()) == 6_000,
                        "The local override changed the server's day time");
                require(server.computeOnServer(mc -> mc.overworld().getGameTime()) > baselineTick,
                        "Time Changer paused the server simulation");
                require(context.computeOnClient(client -> client.level.getDayTime() == 6_000
                                && client.level.getSkyDarken() == daylightLevel
                                && client.level.environmentAttributes().getDimensionValue(EnvironmentAttributes.SUN_ANGLE) == day.sunAngle),
                        "The local override changed the synchronized clock or gameplay environment");

                server.runCommand("time set 12000");
                context.waitFor(client -> client.level.getDayTime() == 12_000);
                require(night.equals(context.computeOnClient(TimeChangerGameTest::sky)),
                        "A server clock update reset the selected local time");

                context.getInput().pressKey(SHORTCUT);
                context.waitFor(client -> !settings.enabled && sky(client).sunAngle == client.level
                        .environmentAttributes().getDimensionValue(EnvironmentAttributes.SUN_ANGLE));
                require(!night.equals(context.computeOnClient(TimeChangerGameTest::sky)),
                        "Disabling Time Changer did not restore the current world time");
                require(server.computeOnServer(mc -> mc.overworld().getDayTime()) == 12_000,
                        "Disabling Time Changer changed the server clock");
            } finally {
                context.runOnClient(client -> {
                    config.timeChanger = original;
                    ModuleKeybinds.setKey(client, MODULE, InputConstants.getKey(originalKey));
                    client.options.guiScale().set(originalScale);
                    client.resizeDisplay();
                    store.save(config);
                });
            }
        }
    }

    private static void prepare(ClientGameTestContext context, TestServerContext server) {
        server.runCommand("gamerule minecraft:advance_time false");
        server.runCommand("gamerule minecraft:advance_weather false");
        server.runCommand("weather clear");
        server.runCommand("time set noon");
        server.runCommand("fill -6 64 -4 6 64 24 minecraft:white_concrete");
        server.runCommand("tp @a 0.5 65 0.5 0 -10");
        context.waitFor(client -> client.level.getDayTime() == 6_000 && client.player.onGround()
                && client.player.distanceToSqr(0.5, 65, 0.5) < 0.001);
    }

    private static ClientConfig openSettings(ClientGameTestContext context) {
        KeyMapping openGui = context.computeOnClient(client -> KeyMapping.get("key.donut.open_gui"));
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

    private static void chooseMidnight(ClientGameTestContext context, TimeChangerSettings settings) {
        String prefix = "m" + MODULE + ".";
        GuiTestInput.click(context, "tab0");
        GuiTestInput.click(context, "search");
        GuiTestInput.type(context, "timechanger");
        GuiTestInput.click(context, "module" + MODULE);
        GuiTestInput.click(context, prefix + "time");
        require(settings.time != 6_000, "The time slider did not update the local time");
        GuiTestInput.click(context, prefix + "sunrise");
        require(settings.time == 0, "Sunrise did not select 06:00");
        GuiTestInput.click(context, prefix + "noon");
        require(settings.time == 6_000, "Noon did not select 12:00");
        GuiTestInput.click(context, prefix + "sunset");
        require(settings.time == 12_000, "Sunset did not select 18:00");
        GuiTestInput.click(context, prefix + "midnight");
        require(settings.time == 18_000, "Midnight did not select 00:00");

        GuiTestInput.click(context, "keybind" + MODULE);
        context.getInput().pressKey(SHORTCUT);
        require(context.computeOnClient(client -> ModuleKeybinds.mapping(MODULE).saveString().equals("key.keyboard.f8")),
                "Time Changer shortcut did not bind");
        GuiTestInput.click(context, "enable" + MODULE);
        GuiTestInput.click(context, "favorite" + MODULE);
        GuiTestInput.click(context, "tab1");
        require(GuiTestInput.visible(context, "module" + MODULE), "Time Changer is missing from Favorites");
        GuiTestInput.closeAnimated(context);
        context.waitTicks(3);
    }

    private static Sky sky(Minecraft client) {
        var probe = client.gameRenderer.getMainCamera().attributeProbe();
        return new Sky(probe.getValue(EnvironmentAttributes.SUN_ANGLE, 1),
                probe.getValue(EnvironmentAttributes.STAR_BRIGHTNESS, 1),
                probe.getValue(EnvironmentAttributes.SKY_LIGHT_FACTOR, 1),
                probe.getValue(EnvironmentAttributes.SKY_COLOR, 1),
                probe.getValue(EnvironmentAttributes.FOG_COLOR, 1),
                probe.getValue(EnvironmentAttributes.CLOUD_COLOR, 1));
    }

    private static double skyLuminance(ClientGameTestContext context, String label) {
        var screenshot = context.takeScreenshot(TestScreenshotOptions.of("time-changer-" + label).withSize(960, 600));
        try {
            var image = ImageIO.read(screenshot.toFile());
            int samples = 0;
            double luminance = 0;
            for (int y = image.getHeight() / 8; y < image.getHeight() / 4; y++) {
                for (int x = image.getWidth() / 4; x < image.getWidth() * 3 / 4; x++) {
                    int rgb = image.getRGB(x, y);
                    luminance += .2126 * (rgb >> 16 & 255) + .7152 * (rgb >> 8 & 255) + .0722 * (rgb & 255);
                    samples++;
                }
            }
            return luminance / samples;
        } catch (IOException error) {
            throw new AssertionError("Could not read Time Changer screenshot", error);
        }
    }

    private record Sky(float sunAngle, float stars, float skyLight, int skyColor, int fogColor, int cloudColor) {}

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
