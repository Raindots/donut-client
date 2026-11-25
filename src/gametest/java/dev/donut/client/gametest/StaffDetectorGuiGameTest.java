package dev.donut.client.gametest;

import dev.donut.client.config.ClientConfig;
import dev.donut.client.config.ConfigStore;
import dev.donut.client.config.DonutConfigPaths;
import dev.donut.client.gui.DonutScreen;
import dev.donut.client.gui.StaffRosterScreen;
import dev.donut.client.input.ModuleKeybinds;
import java.nio.file.Files;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import org.lwjgl.glfw.GLFW;

/** Native roster validation, persistence, search, settings, two GUI scales and production shortcut. */
public final class StaffDetectorGuiGameTest implements FabricClientGameTest {
    @Override public void runTest(ClientGameTestContext context) {
        var config = new ClientConfig();
        var file = FabricLoader.getInstance().getConfigDir().resolve("staff-detector-gametest.json");
        var store = new ConfigStore(file);
        int scale = context.computeOnClient(mc -> mc.options.guiScale().get());
        String key = context.computeOnClient(mc -> ModuleKeybinds.mapping(14).saveString());
        context.getInput().resizeWindow(1280, 800);
        context.runOnClient(mc -> { mc.options.guiScale().set(1); mc.resizeDisplay(); });
        try (var world = context.worldBuilder().create()) {
            GuiTestInput.openAnimated(context, () -> new DonutScreen(config, () -> store.save(config)));
            GuiTestInput.click(context, "search"); GuiTestInput.type(context, "staff");
            require(GuiTestInput.visible(context, "module14"), "Staff Detector missing from search");
            GuiTestInput.click(context, "module14"); GuiTestInput.click(context, "favorite14");
            require(store.load().staffDetector.favorite, "Staff favorite did not persist");
            GuiTestInput.click(context, "m14.vanishAlerts");
            require(!store.load().staffDetector.vanishAlerts, "Vanish/tab alert preference did not persist");
            GuiTestInput.click(context, "m14.vanishAlerts");
            require(store.load().staffDetector.vanishAlerts, "Vanish/tab alerts could not be restored");
            GuiTestInput.click(context, "m14.completionChecks");
            require(store.load().staffDetector.completionChecks, "Completion preference did not persist");
            GuiTestInput.click(context, "m14.completionChecks");
            GuiTestInput.clickOpeningScreen(context, "m14.roster"); context.waitForScreen(StaffRosterScreen.class);
            edit(context, "invalid name!"); click(context, "Add staff");
            require(config.staffDetector.roster("singleplayer").isEmpty(), "Invalid staff name was saved");
            edit(context, "FixtureStaff"); click(context, "Add staff");
            edit(context, "fixturestaff"); click(context, "Add staff");
            require(store.load().staffDetector.roster("singleplayer").size() == 1, "Duplicate staff name was saved");
            edit(context, "c458951b-707e-486b-a35a-d46ddf1411da"); click(context, "Add staff");
            require(store.load().staffDetector.roster("singleplayer").size() == 2, "UUID entry was not saved");
            context.takeScreenshot(TestScreenshotOptions.of("staff-detector-02-roster").withSize(1280, 800));
            context.runOnClient(mc -> {
                var event = new MouseButtonEvent(24, 85, new MouseButtonInfo(0, 0));
                require(mc.screen.mouseClicked(event, false), "Staff entry could not be selected"); mc.screen.mouseReleased(event);
            });
            click(context, "Remove selected"); require(store.load().staffDetector.roster("singleplayer").size() == 1, "Staff removal did not persist");
            click(context, "Evidence"); click(context, "Staff list"); click(context, "Done"); context.waitForScreen(DonutScreen.class);
            context.takeScreenshot(TestScreenshotOptions.of("staff-detector-03-settings").withSize(1280, 800));
            GuiTestInput.click(context, "keybind14"); context.getInput().pressKey(GLFW.GLFW_KEY_F9);
            GuiTestInput.closeAnimated(context);
            var production = new ConfigStore(DonutConfigPaths.resolve(FabricLoader.getInstance().getConfigDir()).active());
            boolean before = production.load().staffDetector.enabled;
            context.getInput().pressKey(GLFW.GLFW_KEY_F9); context.waitTicks(3);
            require(production.load().staffDetector.enabled != before, "Production Staff Detector shortcut did not toggle");
            context.getInput().pressKey(GLFW.GLFW_KEY_F9); context.waitTicks(3);
            require(production.load().staffDetector.enabled == before, "Production Staff Detector shortcut did not restore state");
            context.runOnClient(mc -> { mc.options.guiScale().set(2); mc.resizeDisplay(); });
            GuiTestInput.openAnimated(context, () -> new DonutScreen(config, () -> store.save(config)));
            GuiTestInput.click(context, "search"); GuiTestInput.type(context, "vanish"); GuiTestInput.click(context, "module14");
            GuiTestInput.click(context, "m14.showHud"); require(!store.load().staffDetector.showHud, "Presence HUD preference did not persist");
            context.takeScreenshot(TestScreenshotOptions.of("staff-detector-04-settings-scale2").withSize(1280, 800));
            GuiTestInput.clickOpeningScreen(context, "m14.roster"); context.waitForScreen(StaffRosterScreen.class);
            context.takeScreenshot(TestScreenshotOptions.of("staff-detector-05-roster-scale2").withSize(1280, 800));
            click(context, "Done"); context.waitForScreen(DonutScreen.class); GuiTestInput.closeAnimated(context);
        } finally {
            context.runOnClient(mc -> {
                ModuleKeybinds.setKey(mc, 14, com.mojang.blaze3d.platform.InputConstants.getKey(key));
                mc.options.guiScale().set(scale); mc.resizeDisplay();
            });
            try { Files.deleteIfExists(file); } catch (Exception failure) { throw new AssertionError(failure); }
        }
    }
    private static void edit(ClientGameTestContext context, String value) {
        context.runOnClient(mc -> ((EditBox) mc.screen.children().stream().filter(child -> child instanceof EditBox).findFirst().orElseThrow()).setValue(value));
        context.waitTick();
    }
    private static void click(ClientGameTestContext context, String label) {
        context.runOnClient(mc -> {
            Button button = mc.screen.children().stream().filter(child -> child instanceof Button).map(child -> (Button) child)
                    .filter(child -> child.visible && child.getMessage().getString().equals(label)).findFirst().orElseThrow();
            var event = new MouseButtonEvent(button.getX() + button.getWidth() / 2.0, button.getY() + button.getHeight() / 2.0, new MouseButtonInfo(0, 0));
            require(mc.screen.mouseClicked(event, false), "Staff editor ignored " + label);
            if (mc.screen != null) mc.screen.mouseReleased(event);
        });
        context.waitTicks(2);
    }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
