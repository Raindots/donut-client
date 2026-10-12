package dev.donut.client.gametest;

import com.mojang.blaze3d.platform.InputConstants;
import dev.donut.client.config.ClientConfig;
import dev.donut.client.config.ConfigStore;
import dev.donut.client.gui.DonutScreen;
import dev.donut.client.input.ModuleKeybinds;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicInteger;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/** Real card input, category membership and scaled clipping for the fifth module. */
public final class ChunkFinderGuiGameTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        ClientConfig config = new ClientConfig();
        config.chunkFinder.simulationDistance = 2;
        config.chunkFinder.sensitivity = 1;
        config.chunkFinder.alpha = 0;
        AtomicInteger saves = new AtomicInteger();
        String originalBinding = context.computeOnClient(client -> ModuleKeybinds.mapping(4).saveString());
        int originalScale = context.computeOnClient(client -> client.options.guiScale().get());
        context.runOnClient(client -> {
            client.options.guiScale().set(1);
            client.resizeDisplay();
        });
        context.getInput().resizeWindow(1280, 800);

        try (var world = context.worldBuilder().create()) {
            world.getServer().runCommand("gamemode creative @a");
            world.getServer().runCommand("tp @a 0.5 65 0.5");
            context.waitTicks(3);
            GuiTestInput.openAnimated(context, () -> new DonutScreen(config, saves::incrementAndGet));
            require(GuiTestInput.visible(context, "module4"), "Base Finder is missing from Visual modules");
            require(!GuiTestInput.visible(context, "m4.simulationDistance"), "Base Finder must start collapsed");
            context.runOnClient(client -> {
                require(moduleName((DonutScreen) client.screen).equals("Base Finder"),
                        "The rendered module card still displays its former Chunk Finder name");
                require(ModuleKeybinds.mapping(4).getName().equals("key.donut.toggle_chunk_finder"),
                        "Renaming Base Finder changed its existing saved shortcut identifier");
                require(Component.translatable(ModuleKeybinds.mapping(4).getName()).getString().equals("Toggle Base Finder"),
                        "Minecraft's controls menu still displays the former Chunk Finder name");
            });
            GuiTestInput.assertExpansionReverses(context, 4);
            GuiTestInput.rightClick(context, "module4");
            require(!config.chunkFinder.enabled && saves.get() == 0,
                    "Opening Base Finder settings changed or saved its activation state");

            GuiTestInput.click(context, "m4.simulationDistance");
            GuiTestInput.click(context, "m4.sensitivity");
            GuiTestInput.click(context, "m4.alpha");
            context.runOnClient(client -> {
                require(config.chunkFinder.simulationDistance == 9, "Simulation Distance slider did not update");
                require(config.chunkFinder.sensitivity == 3, "Sensitivity slider did not update");
                require(Math.abs(config.chunkFinder.alpha - 128) <= 1, "Alpha slider did not update");
            });
            GuiTestInput.click(context, "m4.smartAdjustment");
            require(!config.chunkFinder.smartAdjustment, "Smart Adjustment toggle did not update");
            GuiTestInput.scrollTop(context);
            screenshot(context, "basefinder-01-settings");

            for (String filter : new String[] {"kelp", "caveVines", "vines", "amethyst", "bamboo", "cocoa", "beeNest"}) {
                GuiTestInput.click(context, "m4." + filter);
            }
            context.runOnClient(client -> require(!config.chunkFinder.kelp && !config.chunkFinder.caveVines
                            && !config.chunkFinder.vines && !config.chunkFinder.amethyst && !config.chunkFinder.bamboo
                            && !config.chunkFinder.cocoa && !config.chunkFinder.beeNest,
                    "At least one Base Finder indicator toggle did not update"));
            require(saves.get() >= 11, "Base Finder settings did not invoke the save callback");
            GuiTestInput.assertContentClipped(context);
            screenshot(context, "basefinder-02-scrolled-filters");

            GuiTestInput.click(context, "favorite4");
            GuiTestInput.clickWithFade(context, "tab1", "contentProgress");
            require(config.chunkFinder.favorite && GuiTestInput.visible(context, "module4"),
                    "Base Finder did not appear in Favorites");
            require(!GuiTestInput.visible(context, "module0"), "Unstarred modules appeared in Favorites");
            GuiTestInput.clickWithFade(context, "tab3", "contentProgress");
            require(!GuiTestInput.visible(context, "module4") && GuiTestInput.visible(context, "module3"),
                    "Base Finder was assigned to the Blatant category");
            GuiTestInput.clickWithFade(context, "tab0", "contentProgress");
            GuiTestInput.click(context, "search");
            GuiTestInput.type(context, "basefinder");
            require(GuiTestInput.visible(context, "module4") && !GuiTestInput.visible(context, "module0")
                            && !GuiTestInput.visible(context, "module1") && !GuiTestInput.visible(context, "module2"),
                    "Searching for Base Finder did not filter the Visual cards");
            GuiTestInput.click(context, "enable4");
            require(config.chunkFinder.enabled, "Base Finder card switch did not activate the module");

            int beforeBinding = saves.get();
            GuiTestInput.click(context, "keybind4");
            context.getInput().pressKey(GLFW.GLFW_KEY_F8);
            assertBound(context, GLFW.GLFW_KEY_F8);
            require(config.chunkFinder.enabled && saves.get() == beforeBinding,
                    "Assigning a Base Finder shortcut changed the module settings");
            GuiTestInput.click(context, "keybind4");
            context.getInput().pressKey(GLFW.GLFW_KEY_ESCAPE);
            context.waitForScreen(DonutScreen.class);
            assertBound(context, GLFW.GLFW_KEY_F8);
            GuiTestInput.click(context, "keybind4");
            context.getInput().pressKey(GLFW.GLFW_KEY_DELETE);
            context.runOnClient(client -> require(ModuleKeybinds.mapping(4).isUnbound(),
                    "Delete did not clear the Base Finder shortcut"));
            GuiTestInput.click(context, "keybind4");
            context.getInput().pressKey(GLFW.GLFW_KEY_F8);
            assertBound(context, GLFW.GLFW_KEY_F8);
            context.runOnClient(client -> {
                try {
                    var mapping = ModuleKeybinds.mapping(4);
                    String options = Files.readString(client.gameDirectory.toPath().resolve("options.txt"));
                    require(options.contains("key_" + mapping.getName() + ":" + mapping.saveString()),
                            "The Base Finder shortcut was not saved to options.txt");
                } catch (IOException error) {
                    throw new AssertionError("Cannot read saved Base Finder shortcut", error);
                }
                client.options.guiScale().set(2);
                client.resizeDisplay();
            });
            GuiTestInput.settle(context);
            GuiTestInput.click(context, "m4.beeNest");
            require(config.chunkFinder.beeNest, "Scaled Base Finder filter did not accept input");
            GuiTestInput.assertContentClipped(context);
            screenshot(context, "basefinder-03-settings-scaled");
            GuiTestInput.closeAnimated(context);
            testProductionShortcut(context);
        } finally {
            context.setScreen(() -> null);
            context.getInput().releaseKey(GLFW.GLFW_KEY_F8);
            context.runOnClient(client -> {
                ModuleKeybinds.setKey(client, 4, InputConstants.getKey(originalBinding));
                client.options.guiScale().set(originalScale);
                client.resizeDisplay();
            });
        }
    }

    private static void testProductionShortcut(ClientGameTestContext context) {
        ConfigStore store = new ConfigStore(FabricLoader.getInstance().getConfigDir().resolve("donut-client.json"));
        boolean originalEnabled = store.load().chunkFinder.enabled;
        try {
            context.waitTicks(2);
            context.getInput().holdKey(GLFW.GLFW_KEY_F8);
            context.waitTicks(6);
            require(store.load().chunkFinder.enabled != originalEnabled,
                    "The Base Finder shortcut did not toggle the production module");
            context.runOnClient(client -> KeyMapping.click(InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_F8)));
            context.waitTicks(2);
            require(store.load().chunkFinder.enabled != originalEnabled,
                    "A repeated held key toggled Base Finder again");
            context.getInput().releaseKey(GLFW.GLFW_KEY_F8);
            context.waitTicks(2);
            if (store.load().chunkFinder.enabled) screenshot(context, "basefinder-07-production-hud");
            context.getInput().pressKey(GLFW.GLFW_KEY_F8);
            require(store.load().chunkFinder.enabled == originalEnabled,
                    "A second Base Finder shortcut press did not toggle the module back");
        } finally {
            context.getInput().releaseKey(GLFW.GLFW_KEY_F8);
            context.waitTicks(2);
            if (store.load().chunkFinder.enabled != originalEnabled) context.getInput().pressKey(GLFW.GLFW_KEY_F8);
            require(store.load().chunkFinder.enabled == originalEnabled,
                    "The Base Finder shortcut test did not restore its activation state");
        }
    }

    private static void assertBound(ClientGameTestContext context, int key) {
        context.runOnClient(client -> require(ModuleKeybinds.mapping(4).saveString().equals(
                        InputConstants.Type.KEYSYM.getOrCreate(key).getName()),
                "Base Finder did not capture the selected keyboard shortcut"));
    }

    private static String moduleName(DonutScreen screen) {
        try {
            Method method = DonutScreen.class.getDeclaredMethod("name", int.class);
            method.setAccessible(true);
            return (String) method.invoke(screen, 4);
        } catch (ReflectiveOperationException error) {
            throw new AssertionError("Cannot inspect the rendered Base Finder card name", error);
        }
    }

    private static void screenshot(ClientGameTestContext context, String name) {
        context.takeScreenshot(TestScreenshotOptions.of(name).withSize(1280, 800));
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
