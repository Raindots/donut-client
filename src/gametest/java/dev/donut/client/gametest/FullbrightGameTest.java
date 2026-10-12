package dev.donut.client.gametest;

import com.mojang.blaze3d.platform.InputConstants;
import dev.donut.client.config.ClientConfig;
import dev.donut.client.config.ConfigStore;
import dev.donut.client.fullbright.FullbrightController;
import dev.donut.client.gui.DonutScreen;
import dev.donut.client.input.ModuleKeybinds;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import javax.imageio.ImageIO;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.CameraType;
import net.minecraft.client.KeyMapping;
import net.minecraft.world.effect.MobEffects;
import org.lwjgl.glfw.GLFW;

/** Exercises the actual GUI, shortcuts, saved options and rendered pixels in an unlit room. */
public final class FullbrightGameTest implements FabricClientGameTest {
    private static final int MODULE = 5;
    private static final int TEST_KEY = GLFW.GLFW_KEY_F7;
    private static final double TEST_GAMMA = .35;

    @Override
    public void runTest(ClientGameTestContext context) {
        FullbrightController controller = FullbrightController.getInstance();
        require(controller != null, "The production Fullbright controller was not initialized");
        ConfigStore store = new ConfigStore(FabricLoader.getInstance().getConfigDir().resolve("donut-client.json"));
        ClientConfig originalConfig = store.load();
        int originalScale = context.computeOnClient(client -> client.options.guiScale().get());
        double originalGamma = context.computeOnClient(client -> client.options.gamma().get());
        boolean originalHideGui = context.computeOnClient(client -> client.options.hideGui);
        CameraType originalCamera = context.computeOnClient(client -> client.options.getCameraType());
        KeyMapping openGui = context.computeOnClient(client -> Arrays.stream(client.options.keyMappings)
                .filter(key -> key.getName().equals("key.donut.open_gui")).findFirst().orElseThrow());
        String originalOpenKey = context.computeOnClient(client -> openGui.saveString());
        String originalModuleKey = context.computeOnClient(client -> ModuleKeybinds.mapping(MODULE).saveString());
        context.runOnClient(client -> {
            openGui.setKey(InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_RIGHT_SHIFT));
            KeyMapping.resetMapping();
            client.options.guiScale().set(1);
            client.options.hideGui = false;
            client.options.gamma().set(TEST_GAMMA);
            client.options.setCameraType(CameraType.FIRST_PERSON);
            client.resizeDisplay();
        });
        context.getInput().resizeWindow(1280, 800);

        try (var world = context.worldBuilder().create()) {
            world.getServer().runCommand("gamemode creative @a");
            world.getServer().runCommand("time set midnight");
            world.getServer().runCommand("effect clear @a");
            world.getServer().runCommand("fill -8 60 -8 8 67 8 minecraft:smooth_stone hollow");
            world.getServer().runCommand("fill -6 61 -7 -3 65 -7 minecraft:red_terracotta");
            world.getServer().runCommand("fill -2 61 -7 1 65 -7 minecraft:white_terracotta");
            world.getServer().runCommand("fill 2 61 -7 5 65 -7 minecraft:green_terracotta");
            world.getServer().runCommand("tp @a 0.5 61 4.5 180 0");
            context.waitTicks(8);
            world.getClientWorld().waitForChunksRender();
            context.waitFor(client -> client.player != null && client.player.onGround());

            try {
                openGui(context, openGui);
                require(GuiTestInput.visible(context, "module5"), "Fullbright is missing from Visual modules");
                setEnabled(context, store, false);
                if (store.load().fullbright.favorite) GuiTestInput.click(context, "favorite5");
                GuiTestInput.assertExpansionReverses(context, MODULE);
                GuiTestInput.rightClick(context, "module5");
                require(!store.load().fullbright.enabled, "Opening Fullbright settings activated it");
                screenshot(context, "fullbright-01-settings");

                GuiTestInput.click(context, "favorite5");
                GuiTestInput.clickWithFade(context, "tab1", "contentProgress");
                require(store.load().fullbright.favorite && GuiTestInput.visible(context, "module5"),
                        "Fullbright did not appear in Favorites");
                GuiTestInput.clickWithFade(context, "tab3", "contentProgress");
                require(!GuiTestInput.visible(context, "module5") && GuiTestInput.visible(context, "module3"),
                        "Fullbright appeared in the Blatant category");
                GuiTestInput.clickWithFade(context, "tab0", "contentProgress");
                GuiTestInput.click(context, "search");
                GuiTestInput.type(context, "gamma");
                require(GuiTestInput.visible(context, "module5") && !GuiTestInput.visible(context, "module0")
                                && !GuiTestInput.visible(context, "module1") && !GuiTestInput.visible(context, "module4"),
                        "Gamma search did not isolate Fullbright");

                GuiTestInput.click(context, "keybind5");
                context.getInput().pressKey(TEST_KEY);
                assertBound(context);
                require(!store.load().fullbright.enabled && !controller.isActive(),
                        "Capturing the Fullbright shortcut activated the module");
                GuiTestInput.click(context, "enable5");
                context.waitFor(client -> controller.isActive());
                assertGamma(context, TEST_GAMMA, true);
                context.runOnClient(client -> client.options.save());
                assertSavedGamma(context, TEST_GAMMA);
                context.runOnClient(client -> {
                    ModuleKeybinds.mapping(MODULE).setKey(InputConstants.UNKNOWN);
                    client.options.load();
                    KeyMapping.resetMapping();
                });
                assertBound(context);
                assertGamma(context, TEST_GAMMA, true);
                context.runOnClient(client -> {
                    client.options.guiScale().set(2);
                    client.resizeDisplay();
                });
                GuiTestInput.settle(context);
                GuiTestInput.assertContentClipped(context);
                screenshot(context, "fullbright-02-settings-scaled-active");
                GuiTestInput.click(context, "enable5");
                context.waitFor(client -> !controller.isActive());
                assertGamma(context, TEST_GAMMA, false);
                GuiTestInput.closeAnimated(context);

                context.runOnClient(client -> {
                    require(!client.player.hasEffect(MobEffects.NIGHT_VISION),
                            "Night Vision invalidated the dark-room fixture");
                    client.options.hideGui = true;
                });
                context.waitTicks(8);
                double dark = luminance(screenshot(context, "fullbright-03-dark-room-off"));

                context.getInput().holdKey(TEST_KEY);
                context.waitTicks(6);
                require(store.load().fullbright.enabled && controller.isActive(),
                        "The production Fullbright shortcut did not enable the module");
                assertGamma(context, TEST_GAMMA, true);
                context.runOnClient(client -> KeyMapping.click(InputConstants.Type.KEYSYM.getOrCreate(TEST_KEY)));
                context.waitTicks(2);
                require(controller.isActive(), "A repeated held key toggled Fullbright again");
                context.getInput().releaseKey(TEST_KEY);
                context.waitTicks(8);
                double bright = luminance(screenshot(context, "fullbright-04-dark-room-on"));
                require(bright > dark + 40 && bright > dark * 2,
                        "Fullbright did not illuminate rendered room pixels: off=" + dark + ", on=" + bright);
                require(bright < 250, "Gamma 1000 destroyed room detail by producing an all-white frame");

                // The ordinary brightness preference can still be edited and saved while Fullbright renders.
                context.runOnClient(client -> {
                    client.options.gamma().set(.7);
                    client.options.save();
                });
                assertGamma(context, .7, true);
                assertSavedGamma(context, .7);
                context.runOnClient(client -> client.options.gamma().set(TEST_GAMMA));
                context.getInput().pressKey(TEST_KEY);
                context.waitTicks(8);
                require(!store.load().fullbright.enabled && !controller.isActive(),
                        "The second Fullbright shortcut did not disable the module");
                assertGamma(context, TEST_GAMMA, false);
                double restored = luminance(screenshot(context, "fullbright-05-dark-room-restored"));
                require(Math.abs(restored - dark) < 8,
                        "Disabling Fullbright did not restore the rendered darkness: before=" + dark + ", after=" + restored);
            } finally {
                context.getInput().releaseKey(TEST_KEY);
                context.setScreen(() -> null);
                context.runOnClient(client -> client.options.hideGui = false);
                context.waitTicks(2);
                openGui(context, openGui);
                setEnabled(context, store, originalConfig.fullbright.enabled);
                if (store.load().fullbright.favorite != originalConfig.fullbright.favorite) {
                    GuiTestInput.click(context, "favorite5");
                }
                context.setScreen(() -> null);
            }
        } finally {
            context.setScreen(() -> null);
            context.getInput().releaseKey(TEST_KEY);
            context.runOnClient(client -> {
                ModuleKeybinds.mapping(MODULE).setKey(InputConstants.getKey(originalModuleKey));
                openGui.setKey(InputConstants.getKey(originalOpenKey));
                KeyMapping.resetMapping();
                client.options.guiScale().set(originalScale);
                client.options.gamma().set(originalGamma);
                client.options.hideGui = originalHideGui;
                client.options.setCameraType(originalCamera);
                client.options.save();
                client.resizeDisplay();
            });
        }
    }

    private static void openGui(ClientGameTestContext context, KeyMapping open) {
        context.getInput().pressKey(open);
        context.waitForScreen(DonutScreen.class);
        GuiTestInput.settle(context);
    }

    private static void setEnabled(ClientGameTestContext context, ConfigStore store, boolean enabled) {
        if (store.load().fullbright.enabled != enabled) GuiTestInput.click(context, "enable5");
        require(store.load().fullbright.enabled == enabled, "The Fullbright switch did not save its enabled state");
    }

    private static void assertBound(ClientGameTestContext context) {
        context.runOnClient(client -> require(ModuleKeybinds.mapping(MODULE).saveString().equals(
                        InputConstants.Type.KEYSYM.getOrCreate(TEST_KEY).getName()),
                "Fullbright did not capture or reload its keyboard shortcut"));
    }

    private static void assertGamma(ClientGameTestContext context, double ordinaryGamma, boolean active) {
        context.runOnClient(client -> {
            require(Math.abs(client.options.gamma().get() - ordinaryGamma) < .000001,
                    "Fullbright overwrote the user's ordinary brightness preference");
            double effective = FullbrightController.effectiveGamma(client.options.gamma().get());
            require(Math.abs(effective - (active ? 1000 : ordinaryGamma)) < .000001,
                    "The render gamma did not follow the module activation state: " + effective);
        });
    }

    private static void assertSavedGamma(ClientGameTestContext context, double expected) {
        context.runOnClient(client -> {
            try {
                Path optionsPath = client.gameDirectory.toPath().resolve("options.txt");
                String gamma = Files.readAllLines(optionsPath).stream().filter(line -> line.startsWith("gamma:"))
                        .findFirst().orElseThrow(() -> new AssertionError("The saved gamma option is missing"));
                require(Math.abs(Double.parseDouble(gamma.substring("gamma:".length())) - expected) < .000001,
                        "Fullbright contaminated the saved brightness preference: " + gamma);
                KeyMapping mapping = ModuleKeybinds.mapping(MODULE);
                require(Files.readString(optionsPath).contains("key_" + mapping.getName() + ":" + mapping.saveString()),
                        "The Fullbright shortcut was not saved in options.txt");
            } catch (IOException error) {
                throw new AssertionError("Cannot read Fullbright's saved options", error);
            }
        });
    }

    private static Path screenshot(ClientGameTestContext context, String name) {
        return context.takeScreenshot(TestScreenshotOptions.of(name).withSize(1280, 800));
    }

    private static double luminance(Path screenshot) {
        try {
            BufferedImage image = ImageIO.read(screenshot.toFile());
            require(image != null, "Cannot decode the dark-room screenshot");
            double sum = 0;
            int count = 0;
            for (int y = image.getHeight() / 5; y < image.getHeight() * 3 / 5; y++) {
                for (int x = image.getWidth() / 5; x < image.getWidth() * 4 / 5; x++) {
                    int pixel = image.getRGB(x, y);
                    sum += .2126 * ((pixel >> 16) & 255) + .7152 * ((pixel >> 8) & 255) + .0722 * (pixel & 255);
                    count++;
                }
            }
            return sum / count;
        } catch (IOException error) {
            throw new AssertionError("Cannot read rendered Fullbright pixels", error);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
