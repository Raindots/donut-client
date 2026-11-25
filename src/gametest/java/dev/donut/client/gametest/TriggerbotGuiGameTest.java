package dev.donut.client.gametest;

import com.mojang.blaze3d.platform.InputConstants;
import dev.donut.client.config.ClientConfig;
import dev.donut.client.config.ConfigStore;
import dev.donut.client.config.TriggerbotSettings;
import dev.donut.client.gui.CrystalAuraFriendsScreen;
import dev.donut.client.gui.DonutScreen;
import dev.donut.client.input.ModuleKeybinds;
import java.io.IOException;
import java.nio.file.Files;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.components.EditBox;
import org.lwjgl.glfw.GLFW;

public final class TriggerbotGuiGameTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        int module = ModuleKeybinds.TRIGGERBOT;
        String tile = "module" + module;
        String settings = "m" + module + ".";
        var config = new ClientConfig();
        config.crystalAura.setFriends("TrustedAlly");
        var path = FabricLoader.getInstance().getConfigDir().resolve("triggerbot-gui-gametest.json");
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
            GuiTestInput.click(context, tile);
            require(!GuiTestInput.visible(context, "module2"), "A non-Combat module appeared alongside Triggerbot");
            GuiTestInput.click(context, "enable" + module);
            require(config.triggerbot.enabled, "The tile did not enable Triggerbot");

            GuiTestInput.click(context, settings + "reaction");
            GuiTestInput.click(context, settings + "jitter");
            GuiTestInput.click(context, settings + "crits");
            GuiTestInput.click(context, settings + "holdAttack");
            GuiTestInput.click(context, settings + "hostiles");
            GuiTestInput.click(context, settings + "swords");
            GuiTestInput.click(context, settings + "anyItem");
            require(!GuiTestInput.visible(context, settings + "swords"), "Any Item left the sword filter visible");
            var saved = store.load().triggerbot;
            require(saved.reaction == 5 && saved.jitter == 2 && saved.crits == TriggerbotSettings.Crits.ONLY,
                    "Triggerbot timing controls did not save");
            require(saved.holdAttack && saved.hostiles && saved.anyItem && !saved.swords,
                    "Triggerbot targeting and item controls did not save");

            GuiTestInput.clickOpeningScreen(context, settings + "friends");
            context.waitForScreen(CrystalAuraFriendsScreen.class);
            require(context.computeOnClient(client -> client.screen.children().stream()
                    .filter(EditBox.class::isInstance).map(EditBox.class::cast)
                    .anyMatch(edit -> edit.getValue().equals("TrustedAlly"))), "Triggerbot did not open the shared combat roster");
            context.runOnClient(client -> client.screen.onClose());
            context.waitForScreen(DonutScreen.class);

            GuiTestInput.click(context, "keybind" + module);
            context.getInput().pressKey(GLFW.GLFW_KEY_F8);
            require(context.computeOnClient(client -> ModuleKeybinds.mapping(module).saveString().equals(
                    InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_F8).getName())),
                    "Triggerbot shortcut did not update the Minecraft Controls mapping");
            GuiTestInput.click(context, "favorite" + module);
            GuiTestInput.click(context, "tab1");
            require(GuiTestInput.visible(context, tile) && store.load().triggerbot.favorite,
                    "Triggerbot did not appear in Favorites");
            GuiTestInput.click(context, "tab5");
            GuiTestInput.click(context, "search");
            GuiTestInput.type(context, "trigger bot");
            require(GuiTestInput.visible(context, tile), "The Trigger Bot alias did not find the module");
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
