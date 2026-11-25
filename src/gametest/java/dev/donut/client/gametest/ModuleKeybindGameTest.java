package dev.donut.client.gametest;

import com.mojang.blaze3d.platform.InputConstants;
import dev.donut.client.config.ClientConfig;
import dev.donut.client.config.ConfigStore;
import dev.donut.client.config.DonutConfigPaths;
import dev.donut.client.freecam.FreecamController;
import dev.donut.client.gui.DonutScreen;
import dev.donut.client.gui.ModuleArrayList;
import dev.donut.client.input.ModuleKeybinds;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.input.KeyEvent;
import org.lwjgl.glfw.GLFW;

/** Uses the production screen, saved options and keyboard event route in an isolated game-test world. */
public final class ModuleKeybindGameTest implements FabricClientGameTest {
    private static final int[] TEST_KEYS = {GLFW.GLFW_KEY_F8, GLFW.GLFW_KEY_F9, GLFW.GLFW_KEY_F10};

    @Override
    public void runTest(ClientGameTestContext context) {
        ConfigStore store = new ConfigStore(DonutConfigPaths.resolve(FabricLoader.getInstance().getConfigDir()).active());
        ClientConfig originalConfig = store.load();
        FreecamController freecam = FreecamController.getInstance();
        String[] originalKeys = context.computeOnClient(client -> new String[] {
                ModuleKeybinds.mapping(5).saveString(), ModuleKeybinds.mapping(8).saveString(),
                ModuleKeybinds.mapping(2).saveString()});
        int originalGuiScale = context.computeOnClient(client -> client.options.guiScale().get());
        boolean originalHideGui = context.computeOnClient(client -> client.options.hideGui);
        KeyMapping openGui = context.computeOnClient(client -> Arrays.stream(client.options.keyMappings)
                .filter(key -> key.getName().equals("key.donut.open_gui")).findFirst().orElseThrow());
        String originalOpenKey = context.computeOnClient(client -> openGui.saveString());
        context.runOnClient(client -> {
            openGui.setKey(InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_RIGHT_SHIFT));
            KeyMapping.resetMapping();
            client.options.guiScale().set(1);
            client.options.hideGui = false;
        });
        context.getInput().resizeWindow(1280, 800);

        try {
            try (var world = context.worldBuilder().create()) {
                world.getServer().runCommand("gamemode creative @a");
                world.getServer().runCommand("time set noon");
                world.getServer().runCommand("fill -10 64 -10 10 64 10 minecraft:smooth_stone");
                world.getServer().runCommand("setblock -2 65 0 minecraft:chest");
                world.getServer().runCommand("setblock 2 65 0 minecraft:spawner");
                world.getServer().runCommand("tp @a 0.5 65 8.5 180 10");
                context.waitTicks(5);
                world.getClientWorld().waitForChunksRender();
                context.waitFor(client -> client.player != null && client.player.onGround());

                try {
                    openGui(context, openGui);
                    // The configured GUI shortcut toggles the screen off as well as on.
                    context.getInput().pressKey(openGui);
                    context.waitForScreen(null);
                    openGui(context, openGui);
                    if (!originalConfig.showHud) setHud(context, store, true);

                    for (int module = 0; module < TEST_KEYS.length; module++) {
                        GuiTestInput.click(context, "keybind" + moduleId(module));
                        context.getInput().pressKey(TEST_KEYS[module]);
                        assertKey(context, module, TEST_KEYS[module]);
                    }
                    require(store.load().fullbright.enabled == originalConfig.fullbright.enabled
                                    && store.load().nameHider.enabled == originalConfig.nameHider.enabled,
                            "Assigning keys in the GUI must not toggle modules");
                    context.runOnClient(client -> require(!freecam.isActive(), "Assigning the Freecam key must not activate it"));
                    screenshot(context, "donut-keybind-01-assigned-normal");

                    // Escape cancels capture without closing the GUI or changing the existing binding.
                    GuiTestInput.click(context, "keybind5");
                    screenshot(context, "donut-keybind-02-listening");
                    context.getInput().holdKey(GLFW.GLFW_KEY_ESCAPE);
                    context.runOnClient(client -> client.screen.keyPressed(new KeyEvent(GLFW.GLFW_KEY_ESCAPE, 0, 0)));
                    context.runOnClient(client -> require(client.screen instanceof DonutScreen,
                            "A repeated Escape should not close the screen after cancelling key capture"));
                    context.getInput().releaseKey(GLFW.GLFW_KEY_ESCAPE);
                    assertKey(context, 0, TEST_KEYS[0]);

                    // Both documented clear shortcuts work, and keyboard capture can start again afterwards.
                    GuiTestInput.click(context, "keybind8");
                    context.getInput().pressKey(GLFW.GLFW_KEY_DELETE);
                    context.runOnClient(client -> require(ModuleKeybinds.mapping(8).isUnbound(),
                            "Delete did not clear the selected module binding"));
                    GuiTestInput.click(context, "keybind8");
                    context.getInput().pressKey(TEST_KEYS[1]);
                    GuiTestInput.click(context, "keybind5");
                    context.getInput().pressKey(GLFW.GLFW_KEY_BACKSPACE);
                    context.runOnClient(client -> require(ModuleKeybinds.mapping(5).isUnbound(),
                            "Backspace did not clear the selected module binding"));
                    GuiTestInput.click(context, "keybind5");
                    context.getInput().pressKey(TEST_KEYS[0]);

                    // A mouse binding goes through the same capture flow, then returns to a keyboard key.
                    GuiTestInput.click(context, "keybind2");
                    context.getInput().pressMouse(GLFW.GLFW_MOUSE_BUTTON_4);
                    context.runOnClient(client -> require(ModuleKeybinds.mapping(2).saveString().equals(
                                    InputConstants.Type.MOUSE.getOrCreate(GLFW.GLFW_MOUSE_BUTTON_4).getName()),
                            "Mouse button capture did not update the Freecam binding"));
                    GuiTestInput.click(context, "keybind2");
                    context.getInput().pressKey(TEST_KEYS[2]);

                    // Check actual disk persistence and reload, not just the current mapping object's value.
                    context.runOnClient(client -> {
                        String options;
                        try {
                            options = Files.readString(client.gameDirectory.toPath().resolve("options.txt"));
                        } catch (IOException error) {
                            throw new AssertionError("Cannot read the saved module bindings", error);
                        }
                        for (int module = 0; module < TEST_KEYS.length; module++) {
                            KeyMapping mapping = ModuleKeybinds.mapping(moduleId(module));
                            require(options.contains("key_" + mapping.getName() + ":" + mapping.saveString()),
                                    "The assigned binding was not written to options.txt: " + mapping.getName());
                            mapping.setKey(InputConstants.UNKNOWN);
                        }
                        client.options.load();
                        KeyMapping.resetMapping();
                    });
                    for (int module = 0; module < TEST_KEYS.length; module++) assertKey(context, module, TEST_KEYS[module]);

                    context.runOnClient(client -> {
                        client.options.guiScale().set(2);
                        client.resizeDisplay();
                    });
                    context.waitTicks(2);
                    screenshot(context, "donut-keybind-03-assigned-scaled");
                    GuiTestInput.rightClick(context, "module5");
                    GuiTestInput.click(context, "keybind5"); // Binding in the expanded card at GUI scale 2.
                    screenshot(context, "donut-keybind-04-settings-listening-scaled");
                    context.getInput().pressKey(TEST_KEYS[0]);
                    assertKey(context, 0, TEST_KEYS[0]);
                    context.getInput().pressKey(GLFW.GLFW_KEY_ESCAPE);
                    context.waitForScreen(null);

                    // Holding a newly bound key toggles once; releasing it does not queue a second toggle.
                    boolean brightBefore = store.load().fullbright.enabled;
                    context.getInput().holdKey(TEST_KEYS[0]);
                    context.waitTicks(6);
                    require(store.load().fullbright.enabled != brightBefore, "The Fullbright binding did not toggle its module");
                    // Simulate the click enqueued by GLFW_REPEAT while the physical key stays held.
                    context.runOnClient(client -> KeyMapping.click(InputConstants.Type.KEYSYM.getOrCreate(TEST_KEYS[0])));
                    context.waitTicks(2);
                    require(store.load().fullbright.enabled != brightBefore, "A repeated key event toggled the module again");
                    context.getInput().releaseKey(TEST_KEYS[0]);
                    context.waitTicks(2);
                    require(store.load().fullbright.enabled != brightBefore, "A held binding toggled more than once");
                    context.getInput().pressKey(TEST_KEYS[0]);
                    require(store.load().fullbright.enabled == brightBefore, "A second press did not turn Fullbright back");

                    // Closing the GUI while an unrelated movement key is held must leave shortcuts usable.
                    openGui(context, openGui);
                    context.getInput().holdKey(options -> options.keySprint);
                    context.getInput().pressKey(GLFW.GLFW_KEY_ESCAPE);
                    context.waitForScreen(null);
                    context.waitTicks(2);
                    context.getInput().pressKey(TEST_KEYS[0]);
                    require(store.load().fullbright.enabled != brightBefore,
                            "Holding Sprint after closing the GUI blocked a module shortcut");
                    context.getInput().releaseKey(options -> options.keySprint);
                    context.getInput().pressKey(TEST_KEYS[0]);
                    require(store.load().fullbright.enabled == brightBefore,
                            "Storage did not toggle back after releasing Sprint");

                    boolean namesBefore = store.load().nameHider.enabled;
                    context.getInput().pressKey(TEST_KEYS[1]);
                    require(store.load().nameHider.enabled != namesBefore, "The Name Hider binding did not toggle its module");
                    context.getInput().pressKey(TEST_KEYS[2]);
                    context.waitFor(client -> freecam.isActive());

                    // Ordinary key presses while a GUI is open must not toggle or replay after closing it.
                    openGui(context, openGui);
                    context.getInput().pressKey(TEST_KEYS[0]);
                    require(store.load().fullbright.enabled == brightBefore, "A module toggled while its GUI was open");
                    context.getInput().pressKey(GLFW.GLFW_KEY_ESCAPE);
                    context.waitForScreen(null);
                    context.waitTicks(2);
                    require(store.load().fullbright.enabled == brightBefore, "A GUI key press replayed after closing it");

                    setModule(context, store, 0, true);
                    setModule(context, store, 1, true);
                    assertHudRows(context);
                    screenshot(context, "donut-arraylist-01-active-scaled");
                    context.getInput().pressKey(GLFW.GLFW_KEY_F1);
                    context.runOnClient(client -> require(client.options.hideGui, "F1 did not hide the HUD"));
                    screenshot(context, "donut-arraylist-02-hidden-with-f1");
                    context.getInput().pressKey(GLFW.GLFW_KEY_F1);
                    context.runOnClient(client -> {
                        client.options.guiScale().set(1);
                        client.resizeDisplay();
                    });
                    context.waitTicks(2);
                    assertHudRows(context);
                    screenshot(context, "donut-arraylist-03-active-normal");
                    context.getInput().pressKey(TEST_KEYS[2]);
                    context.waitFor(client -> !freecam.isActive());
                    setModule(context, store, 0, false);
                    setModule(context, store, 1, false);
                    screenshot(context, "donut-arraylist-04-disabled");
                } finally {
                    context.setScreen(() -> null);
                    context.runOnClient(client -> {
                        freecam.setEnabled(false);
                        for (int module = 0; module < TEST_KEYS.length; module++) {
                            ModuleKeybinds.setKey(client, module, InputConstants.Type.KEYSYM.getOrCreate(TEST_KEYS[module]));
                        }
                    });
                    context.getInput().releaseKey(TEST_KEYS[0]);
                    context.getInput().releaseKey(options -> options.keySprint);
                    context.waitTicks(2);
                    setModule(context, store, 0, originalConfig.fullbright.enabled);
                    setModule(context, store, 1, originalConfig.nameHider.enabled);
                    if (!originalConfig.showHud) {
                        openGui(context, openGui);
                        setHud(context, store, false);
                        context.setScreen(() -> null);
                    }
                }
            }
        } finally {
            context.runOnClient(client -> {
                for (int module = 0; module < originalKeys.length; module++) {
                    ModuleKeybinds.mapping(moduleId(module)).setKey(InputConstants.getKey(originalKeys[module]));
                }
                openGui.setKey(InputConstants.getKey(originalOpenKey));
                KeyMapping.resetMapping();
                client.options.guiScale().set(originalGuiScale);
                client.options.hideGui = originalHideGui;
                client.options.save();
                client.resizeDisplay();
            });
        }
    }

    private static void openGui(ClientGameTestContext context, KeyMapping openGui) {
        context.getInput().pressKey(openGui);
        context.waitForScreen(DonutScreen.class);
        GuiTestInput.settle(context);
    }

    private static void setModule(ClientGameTestContext context, ConfigStore store, int module, boolean enabled) {
        ClientConfig saved = store.load();
        if ((module == 0 ? saved.fullbright.enabled : saved.nameHider.enabled) != enabled) {
            context.getInput().pressKey(TEST_KEYS[module]);
        }
        ClientConfig after = store.load();
        require((module == 0 ? after.fullbright.enabled : after.nameHider.enabled) == enabled,
                "Module " + module + " did not reach the requested enabled state");
    }

    private static void setHud(ClientGameTestContext context, ConfigStore store, boolean enabled) {
        GuiTestInput.click(context, "tab6");
        if (store.load().showHud != enabled) GuiTestInput.click(context, "hud");
        require(store.load().showHud == enabled, "The array-list appearance setting did not update");
        GuiTestInput.click(context, "tab0");
    }

    private static int moduleId(int slot) { return switch (slot) { case 0 -> 5; case 1 -> 8; default -> 2; }; }

    private static void assertKey(ClientGameTestContext context, int module, int key) {
        context.runOnClient(client -> require(ModuleKeybinds.mapping(moduleId(module)).saveString().equals(
                        InputConstants.Type.KEYSYM.getOrCreate(key).getName()),
                "The selected key was not assigned to module " + module));
    }

    private static void assertHudRows(ClientGameTestContext context) {
        context.waitFor(client -> {
            java.util.List<?> rows = (java.util.List<?>) field(ModuleArrayList.class, "rows");
            if (rows.size() < 3) return false;
            float last = Float.POSITIVE_INFINITY;
            java.util.Set<String> names = new java.util.HashSet<>();
            for (Object row : rows) {
                if (((Number) field(row, "animation")).floatValue() != 1) return false;
                float width = ((Number) field(row, "nameWidth")).floatValue();
                require(width <= last, "Spectre's array list is not sorted widest first");
                last = width;
                names.add((String) field(row, "name"));
            }
            return names.containsAll(java.util.List.of("Fullbright", "Name Hider", "Freecam"));
        });
    }

    private static Object field(Object owner, String name) {
        try {
            var field = (owner instanceof Class<?> type ? type : owner.getClass()).getDeclaredField(name);
            field.setAccessible(true);
            return field.get(owner instanceof Class<?> ? null : owner);
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }

    private static void screenshot(ClientGameTestContext context, String name) {
        context.takeScreenshot(TestScreenshotOptions.of(name).withSize(1280, 800));
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
