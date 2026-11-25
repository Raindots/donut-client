package dev.donut.client.gametest;

import dev.donut.client.config.ClientConfig;
import dev.donut.client.config.ConfigStore;
import dev.donut.client.gui.DonutScreen;
import dev.donut.client.input.ModuleKeybinds;
import java.io.IOException;
import java.nio.file.Files;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;

public final class CrystalAuraGuiGameTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        int module = ModuleKeybinds.CRYSTAL_AURA;
        String tile = "module" + module;
        String settings = "m" + module + ".";
        var config = new ClientConfig();
        var path = FabricLoader.getInstance().getConfigDir().resolve("crystal-aura-gui-gametest.json");
        var store = new ConfigStore(path);
        int previousScale = context.computeOnClient(client -> client.options.guiScale().get());
        context.getInput().resizeWindow(1280, 800);
        context.runOnClient(client -> {
            client.options.guiScale().set(1);
            client.resizeDisplay();
        });

        try (var world = context.worldBuilder().create()) {
            GuiTestInput.openAnimated(context, () -> new DonutScreen(config, () -> store.save(config)));
            GuiTestInput.click(context, "tab7");
            require(GuiTestInput.visible(context, tile) && !GuiTestInput.visible(context, "module2"),
                    "Combat did not isolate the Crystal Aura tile");
            GuiTestInput.click(context, tile);
            require(!GuiTestInput.visible(context, settings + "mode")
                            && !GuiTestInput.visible(context, settings + "holdKey")
                            && !GuiTestInput.visible(context, settings + "targetRange"),
                    "Crystal Aura still showed automatic controls");
            require(context.computeOnClient(client -> KeyMapping.get("key.donut.hold_crystal_aura") == null),
                    "Minecraft Controls still registered the automatic hold key");
            GuiTestInput.click(context, settings + "placeDelay");
            GuiTestInput.click(context, settings + "breakDelay");
            var saved = store.load().crystalAura;
            require(saved.placeDelay > 0 && saved.breakDelay > 0, "Crystal Aura did not save the timing controls");
            GuiTestInput.click(context, "enable" + module);
            require(config.crystalAura.enabled, "Combat tile switch did not enable Crystal Aura");
            GuiTestInput.click(context, "favorite" + module);
            GuiTestInput.click(context, "tab1");
            require(GuiTestInput.visible(context, tile) && store.load().crystalAura.favorite,
                    "Crystal Aura did not appear in Favorites");
            GuiTestInput.click(context, "tab5");
            GuiTestInput.click(context, "search");
            GuiTestInput.type(context, "autocrystal");
            require(GuiTestInput.visible(context, tile), "AutoCrystal search did not find Crystal Aura");
            GuiTestInput.closeAnimated(context);
        } finally {
            context.runOnClient(client -> {
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
