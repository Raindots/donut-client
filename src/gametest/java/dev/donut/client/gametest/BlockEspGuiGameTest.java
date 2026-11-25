package dev.donut.client.gametest;

import dev.donut.client.config.BlockEspSettings;
import dev.donut.client.config.ClientConfig;
import dev.donut.client.config.ConfigStore;
import dev.donut.client.config.DonutConfigPaths;
import dev.donut.client.gui.BlockEspSeedScreen;
import dev.donut.client.gui.DonutScreen;
import dev.donut.client.input.ModuleKeybinds;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import org.lwjgl.glfw.GLFW;

/** Real menu/category/search, persisted exact seed, native validation, settings and production shortcut. */
public final class BlockEspGuiGameTest implements FabricClientGameTest {
    @Override public void runTest(ClientGameTestContext context) {
        var config = new ClientConfig();
        Path fixture = FabricLoader.getInstance().getConfigDir().resolve("blockesp-gametest.json");
        var store = new ConfigStore(fixture);
        int scale = context.computeOnClient(mc -> mc.options.guiScale().get());
        String key = context.computeOnClient(mc -> ModuleKeybinds.mapping(13).saveString());
        context.getInput().resizeWindow(1280, 800);
        context.runOnClient(mc -> { mc.options.guiScale().set(1); mc.resizeDisplay(); });
        try (var world = context.worldBuilder().create()) {
            GuiTestInput.openAnimated(context, () -> new DonutScreen(config, () -> store.save(config)));
            GuiTestInput.click(context, "search"); GuiTestInput.type(context, "blockesp");
            require(GuiTestInput.visible(context, "module13"), "BlockESP missing from module search");
            GuiTestInput.click(context, "module13");
            GuiTestInput.click(context, "favorite13");
            GuiTestInput.click(context, "m13.coal");
            require(store.load().blockEsp.favorite && !store.load().blockEsp.coal && store.load().blockEsp.emerald, "Target preferences did not persist");
            GuiTestInput.clickOpeningScreen(context, "m13.seed"); context.waitForScreen(BlockEspSeedScreen.class);
            edit(context, "9223372036854775808"); click(context, "Apply seed");
            require(context.computeOnClient(mc -> mc.screen instanceof BlockEspSeedScreen), "Overflowing seed was accepted");
            require(store.load().blockEsp.seed.equals(BlockEspSettings.DEFAULT_SEED), "Invalid seed damaged saved preferences");
            edit(context, "-9223372036854775808"); click(context, "Apply seed");
            context.waitForScreen(DonutScreen.class);
            require(store.load().blockEsp.seed.equals("-9223372036854775808"), "Signed minimum seed did not persist exactly");
            GuiTestInput.clickOpeningScreen(context, "m13.seed"); edit(context, BlockEspSettings.DEFAULT_SEED); click(context, "Apply seed");
            context.waitForScreen(DonutScreen.class);
            GuiTestInput.click(context, "m13.tracers");
            require(store.load().blockEsp.tracers, "Tracer preference did not persist");
            context.takeScreenshot(TestScreenshotOptions.of("blockesp-02-settings").withSize(1280, 800));
            GuiTestInput.click(context, "keybind13"); context.getInput().pressKey(GLFW.GLFW_KEY_F9);
            GuiTestInput.closeAnimated(context);
            var production = new ConfigStore(DonutConfigPaths.resolve(FabricLoader.getInstance().getConfigDir()).active());
            boolean before = production.load().blockEsp.enabled;
            context.getInput().pressKey(GLFW.GLFW_KEY_F9); context.waitTicks(3);
            require(production.load().blockEsp.enabled != before, "Production BlockESP shortcut did not toggle");
            context.getInput().pressKey(GLFW.GLFW_KEY_F9); context.waitTicks(3);
            require(production.load().blockEsp.enabled == before, "Production BlockESP shortcut did not restore state");
            context.runOnClient(mc -> { mc.options.guiScale().set(2); mc.resizeDisplay(); });
            GuiTestInput.openAnimated(context, () -> new DonutScreen(config, () -> store.save(config)));
            GuiTestInput.click(context, "search"); GuiTestInput.type(context, "deepslate");
            GuiTestInput.click(context, "module13");
            GuiTestInput.click(context, "m13.showHud");
            context.takeScreenshot(TestScreenshotOptions.of("blockesp-03-settings-scale2").withSize(1280, 800));
            GuiTestInput.closeAnimated(context);
        } finally {
            context.runOnClient(mc -> {
                ModuleKeybinds.setKey(mc, 13, com.mojang.blaze3d.platform.InputConstants.getKey(key));
                mc.options.guiScale().set(scale); mc.resizeDisplay();
            });
            try { Files.deleteIfExists(fixture); } catch (Exception error) { throw new AssertionError(error); }
        }
    }
    private static void edit(ClientGameTestContext context, String value) {
        context.runOnClient(mc -> ((EditBox) mc.screen.children().stream().filter(child -> child instanceof EditBox).findFirst().orElseThrow()).setValue(value));
        context.waitTick();
    }
    private static void click(ClientGameTestContext context, String label) {
        context.runOnClient(mc -> {
            Button button = mc.screen.children().stream().filter(child -> child instanceof Button).map(child -> (Button) child)
                    .filter(child -> child.getMessage().getString().equals(label)).findFirst().orElseThrow();
            var event = new MouseButtonEvent(button.getX() + button.getWidth() / 2.0, button.getY() + button.getHeight() / 2.0, new MouseButtonInfo(0, 0));
            require(mc.screen.mouseClicked(event, false), "Seed editor ignored " + label);
            // Applying can replace the current screen. Release the button on the new screen if present.
            if (mc.screen != null) mc.screen.mouseReleased(event);
        });
        context.waitTicks(2);
    }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
