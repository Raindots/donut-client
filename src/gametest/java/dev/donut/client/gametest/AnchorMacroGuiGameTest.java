package dev.donut.client.gametest;

import com.mojang.blaze3d.platform.InputConstants;
import dev.donut.client.config.AnchorMacroSettings;
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

public final class AnchorMacroGuiGameTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        int module = ModuleKeybinds.ANCHOR_MACRO;
        String tile = "module" + module;
        String settings = "m" + module + ".";
        var config = new ClientConfig();
        var path = FabricLoader.getInstance().getConfigDir().resolve("anchor-macro-gui-gametest.json");
        var store = new ConfigStore(path);
        int previousScale = context.computeOnClient(client -> client.options.guiScale().get());
        String originalBinding = context.computeOnClient(client -> ModuleKeybinds.mapping(module).saveString());
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
                    && GuiTestInput.visible(context, "module" + ModuleKeybinds.AUTO_TOTEM)
                    && !GuiTestInput.visible(context, "module2"), "Combat did not show its three modules");
            GuiTestInput.click(context, tile);
            GuiTestInput.click(context, "enable" + module);
            require(config.anchorMacro.enabled, "The tile did not enable Anchor Macro");
            require(!GuiTestInput.visible(context, settings + "doubleAnchor"), "Single mode showed the Double Anchor toggle");

            GuiTestInput.click(context, settings + "mode");
            GuiTestInput.click(context, settings + "doubleAnchor");
            GuiTestInput.click(context, settings + "safeAnchor");
            GuiTestInput.click(context, settings + "guardSlot");
            GuiTestInput.click(context, settings + "ifUnguarded");
            GuiTestInput.click(context, settings + "switchBack");
            GuiTestInput.click(context, settings + "switchBackDelay");
            var saved = store.load().anchorMacro;
            require(saved.mode == AnchorMacroSettings.Mode.DOUBLE && saved.doubleAnchor && saved.safeAnchor,
                    "Double mode and cover settings did not save");
            require(saved.guardSlot == 5 && saved.ifUnguarded == AnchorMacroSettings.Unguarded.CANCEL
                    && saved.switchBack && saved.switchBackDelay == 5, "Conditional controls did not save");

            GuiTestInput.click(context, "keybind" + module);
            context.getInput().pressKey(GLFW.GLFW_KEY_F8);
            require(context.computeOnClient(client -> ModuleKeybinds.mapping(module).saveString().equals(
                    InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_F8).getName())),
                    "Anchor Macro shortcut did not update the Minecraft Controls mapping");
            GuiTestInput.click(context, "favorite" + module);
            GuiTestInput.click(context, "tab1");
            require(GuiTestInput.visible(context, tile) && store.load().anchorMacro.favorite,
                    "Anchor Macro did not appear in Favorites");
            GuiTestInput.click(context, "tab5");
            GuiTestInput.click(context, "search");
            GuiTestInput.type(context, "autoanchor");
            require(GuiTestInput.visible(context, tile), "Searching AutoAnchor did not find Anchor Macro");
            GuiTestInput.closeAnimated(context);
        } finally {
            context.runOnClient(client -> {
                ModuleKeybinds.setKey(client, module, InputConstants.getKey(originalBinding));
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
