package dev.donut.client.gametest;

import com.mojang.blaze3d.platform.InputConstants;
import dev.donut.client.config.AutoTotemSettings;
import dev.donut.client.config.ClientConfig;
import dev.donut.client.config.ConfigStore;
import dev.donut.client.gui.DonutScreen;
import dev.donut.client.input.ModuleKeybinds;
import java.io.IOException;
import java.nio.file.Files;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.loader.api.FabricLoader;
import org.lwjgl.glfw.GLFW;

public final class AutoTotemGuiGameTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        int module = ModuleKeybinds.AUTO_TOTEM;
        String tile = "module" + module;
        String settings = "m" + module + ".";
        var config = new ClientConfig();
        var path = FabricLoader.getInstance().getConfigDir().resolve("auto-totem-gui-gametest.json");
        var store = new ConfigStore(path);
        int previousScale = context.computeOnClient(client -> client.options.guiScale().get());
        String originalKey = context.computeOnClient(client -> ModuleKeybinds.mapping(module).saveString());
        context.getInput().resizeWindow(1280, 800);
        context.runOnClient(client -> {
            client.options.guiScale().set(1);
            client.resizeDisplay();
        });

        try (var world = context.worldBuilder().create()) {
            GuiTestInput.openAnimated(context, () -> new DonutScreen(config, () -> store.save(config)));
            GuiTestInput.click(context, "tab7");
            require(GuiTestInput.visible(context, tile)
                    && GuiTestInput.visible(context, "module" + ModuleKeybinds.CRYSTAL_AURA)
                    && !GuiTestInput.visible(context, "module2"), "Combat did not list the correct modules");
            GuiTestInput.click(context, tile);
            GuiTestInput.click(context, "enable" + module);
            require(config.autoTotem.enabled, "Auto Totem tile did not enable the module");

            require(GuiTestInput.visible(context, settings + "hotbarRefill")
                    && !GuiTestInput.visible(context, settings + "hotbarSlot")
                    && !GuiTestInput.visible(context, settings + "switchBackDelay"), "Inventory mode showed the wrong settings");
            GuiTestInput.click(context, settings + "hotbarRefill");
            GuiTestInput.click(context, settings + "hotbarSlot");
            require(store.load().autoTotem.hotbarSlot == 5 && store.load().autoTotem.hotbarRefill,
                    "Inventory hotbar reserve settings did not save");

            GuiTestInput.click(context, settings + "mode");
            require(store.load().autoTotem.mode == AutoTotemSettings.Mode.HOTBAR_SWAP,
                    "Hotbar Swap mode did not save");
            require(GuiTestInput.visible(context, settings + "switchBackDelay")
                    && !GuiTestInput.visible(context, settings + "hotbarRefill")
                    && !GuiTestInput.visible(context, settings + "closeDelay"), "Hotbar Swap showed the wrong settings");

            GuiTestInput.click(context, "keybind" + module);
            context.getInput().pressKey(GLFW.GLFW_KEY_F10);
            require(context.computeOnClient(client -> ModuleKeybinds.mapping(module).saveString().equals(
                    InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_F10).getName())),
                    "Auto Totem shortcut did not update the Minecraft Controls mapping");
            GuiTestInput.click(context, "favorite" + module);
            GuiTestInput.click(context, "tab1");
            require(GuiTestInput.visible(context, tile) && store.load().autoTotem.favorite,
                    "Auto Totem did not appear in Favorites");
            GuiTestInput.click(context, "tab5");
            GuiTestInput.click(context, "search");
            GuiTestInput.type(context, "autoinvtotem");
            require(GuiTestInput.visible(context, tile), "Auto Inventory Totem search did not find Auto Totem");
            GuiTestInput.closeAnimated(context);
        } finally {
            context.runOnClient(client -> {
                ModuleKeybinds.setKey(client, module, InputConstants.getKey(originalKey));
                client.options.guiScale().set(previousScale);
                client.resizeDisplay();
            });
            try {
                Files.deleteIfExists(path);
            } catch (IOException failure) {
                throw new AssertionError(failure);
            }
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
