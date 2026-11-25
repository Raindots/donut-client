package dev.donut.client.gametest;

import com.mojang.blaze3d.platform.InputConstants;
import dev.donut.client.config.ClientConfig;
import dev.donut.client.config.ConfigProfileStore;
import dev.donut.client.config.ConfigStore;
import dev.donut.client.gui.DonutScreen;
import dev.donut.client.input.ModuleKeybinds;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.input.KeyEvent;
import org.lwjgl.glfw.GLFW;

/** Exercises the profile editor and profile application through the main Donut workspace. */
public final class ConfigProfilesGuiGameTest implements FabricClientGameTest {
    private static final int MODULE = ModuleKeybinds.CRYSTAL_AURA;
    private static final String FIRST_KEY = "key.keyboard.f8";
    private static final String SECOND_KEY = "key.keyboard.f9";
    private static final String NAME = "Builder's Workspace";
    private static final String DESCRIPTION = "A warm accent and crystal timing for the main build world.";

    @Override
    public void runTest(ClientGameTestContext context) {
        int originalScale = context.computeOnClient(client -> client.options.guiScale().get());
        String originalKey = context.computeOnClient(client -> ModuleKeybinds.mapping(MODULE).saveString());
        try {
            Path directory = Files.createTempDirectory(Files.createDirectories(FabricLoader.getInstance().getConfigDir()),
                    "donut-config-profiles-gametest-");
            Path activeFile = directory.resolve("donut-client.json");
            Path profilesFile = directory.resolve("donut-configs.json");
            try {
                exerciseProfiles(context, activeFile, profilesFile);
            } finally {
                context.runOnClient(client -> {
                    ModuleKeybinds.setKey(client, MODULE, InputConstants.getKey(originalKey));
                    client.options.guiScale().set(originalScale);
                    client.options.save();
                    client.resizeDisplay();
                });
                Files.deleteIfExists(activeFile);
                Files.deleteIfExists(profilesFile);
                Files.deleteIfExists(directory.resolve("sender.json"));
                Path exports = directory.resolve("exports");
                if (Files.isDirectory(exports)) {
                    try (var files = Files.list(exports)) {
                        for (Path export : files.toList()) Files.deleteIfExists(export);
                    }
                    Files.delete(exports);
                }
                Files.deleteIfExists(directory);
            }
        } catch (IOException failure) {
            throw new AssertionError("Could not prepare or verify local profile files", failure);
        }
    }

    private static void exerciseProfiles(ClientGameTestContext context, Path activeFile, Path profilesFile) throws IOException {
        ConfigStore activeStore = new ConfigStore(activeFile);
        ConfigProfileStore profiles = new ConfigProfileStore(profilesFile);
        ClientConfig config = new ClientConfig();
        String keyName = context.computeOnClient(client -> ModuleKeybinds.mapping(MODULE).getName());
        Path optionsFile = context.computeOnClient(client -> client.gameDirectory.toPath().resolve("options.txt"));
        resize(context, 1280, 800, 1);

        try (var world = context.worldBuilder().create()) {
            world.getClientWorld().waitForChunksRender();
            setCurrent(context, config, 3, 0x56DCCB, FIRST_KEY);
            openConfigs(context, config, activeStore, profiles);
            GuiTestInput.click(context, "configsNew");
            fillField(context, "configsName", NAME);
            fillField(context, "configsDescription", DESCRIPTION);
            fillField(context, "configsColorHex", "D9A86A");
            GuiTestInput.click(context, "configsSaveNew");

            var saved = profiles.list().getFirst();
            require(saved.name().equals(NAME) && saved.description().equals(DESCRIPTION) && saved.color() == 0xD9A86A,
                    "Profile name, description, or color did not save");
            require(saved.config().crystalAura.placeDelay == 3 && FIRST_KEY.equals(saved.keybinds().get(keyName)),
                    "Profile did not capture the current timing and keybind");
            require(Files.readString(profilesFile).contains(DESCRIPTION), "The profile description is missing from the local text file");

            setCurrent(context, config, 8, 0x9C82F7, SECOND_KEY);
            fillField(context, "configsName", "Workshop");
            fillField(context, "configsDescription", "Main world building profile with custom timing.");
            context.runOnClient(client -> require(client.screen.keyPressed(new KeyEvent(GLFW.GLFW_KEY_ENTER, 0, 0)),
                    "The description did not handle a line break"));
            GuiTestInput.type(context, "Purple HUD accent and a dedicated crystal shortcut.");
            GuiTestInput.click(context, "configsSaveDetails");
            var renamed = profiles.list().getFirst();
            require(renamed.id().equals(saved.id()) && renamed.name().equals("Workshop")
                            && renamed.description().equals("Main world building profile with custom timing.\n"
                                    + "Purple HUD accent and a dedicated crystal shortcut."),
                    "Saving profile details did not keep the same profile identity and update its metadata");
            require(renamed.config().crystalAura.placeDelay == 3 && FIRST_KEY.equals(renamed.keybinds().get(keyName)),
                    "Editing metadata replaced the saved settings");

            GuiTestInput.click(context, "configsLoad");
            assertApplied(context, config, activeStore, optionsFile, keyName, 3, 0x56DCCB, FIRST_KEY);

            setCurrent(context, config, 8, 0x9C82F7, SECOND_KEY);
            GuiTestInput.click(context, "configsUpdate");
            var updated = profiles.list().getFirst();
            require(updated.id().equals(saved.id()) && updated.name().equals("Workshop")
                            && updated.config().crystalAura.placeDelay == 8 && SECOND_KEY.equals(updated.keybinds().get(keyName)),
                    "Updating the snapshot did not capture the current settings under the existing name");

            profiles.create("Daily Mining", "Cool-toned setup for ore scanning and the mining HUD.", 0x65D4CB,
                    new ClientConfig(), Map.of());
            profiles.create("Shortcuts & HUD", "An alternate layout for compact displays.", 0xAD91EC,
                    new ClientConfig(), Map.of());
            GuiTestInput.closeAnimated(context);

            setCurrent(context, config, 1, 0x56DCCB, FIRST_KEY);
            openConfigs(context, config, activeStore, new ConfigProfileStore(profilesFile));
            GuiTestInput.click(context, "configsProfile" + saved.id());
            GuiTestInput.click(context, "configsLoad");
            assertApplied(context, config, activeStore, optionsFile, keyName, 8, 0x9C82F7, SECOND_KEY);
            require(GuiTestInput.visible(context, "configsImport") && GuiTestInput.visible(context, "configsOpenFolder"),
                    "The sharing controls were not visible");
            context.takeScreenshot(TestScreenshotOptions.of("config-profiles-01-desktop").withSize(1280, 800));

            resize(context, 960, 640, 2);
            GuiTestInput.settle(context);
            require(GuiTestInput.visible(context, "configsName") && GuiTestInput.visible(context, "configsLoad"),
                    "The selected profile editor became inaccessible in a compact window");
            context.takeScreenshot(TestScreenshotOptions.of("config-profiles-02-compact").withSize(960, 640));

            GuiTestInput.click(context, "configsDuplicate");
            var duplicate = profiles.list().stream().filter(profile -> profile.name().equals("Workshop copy")).findFirst()
                    .orElseThrow(() -> new AssertionError("Duplicate did not save a copy"));
            require(!duplicate.id().equals(saved.id()) && duplicate.config().crystalAura.placeDelay == 8,
                    "Duplicate did not keep an independent copy of the saved settings");
            fillField(context, "configsSearch", "workshop copy");
            require(GuiTestInput.visible(context, "configsProfile" + duplicate.id())
                            && !GuiTestInput.visible(context, "configsProfile" + saved.id()),
                    "Searching did not filter the profile list");
            GuiTestInput.click(context, "configsDelete");
            require(profiles.list().size() == 4, "Delete removed a profile before confirmation");
            GuiTestInput.click(context, "configsConfirmDelete");
            require(profiles.list().size() == 3 && profiles.list().stream().noneMatch(profile -> profile.id().equals(duplicate.id())),
                    "Confirming deletion did not remove the duplicate");

            fillField(context, "configsSearch", "Workshop");
            GuiTestInput.click(context, "configsProfile" + saved.id());
            GuiTestInput.click(context, "configsExport");
            Path directory = profilesFile.getParent();
            Path exported;
            try (var files = Files.list(directory.resolve("exports"))) {
                var exports = files.toList();
                require(exports.size() == 1, "Export did not create one share file");
                exported = exports.getFirst();
            }
            var exportedProfiles = new ConfigProfileStore(exported).list();
            require(exportedProfiles.size() == 1 && exportedProfiles.getFirst().id().equals(saved.id())
                            && SECOND_KEY.equals(exportedProfiles.getFirst().keybinds().get(keyName)),
                    "Export did not contain only the selected settings and keybinds");

            var sender = new ConfigProfileStore(directory.resolve("sender.json"));
            var senderSettings = new ClientConfig();
            senderSettings.crystalAura.placeDelay = 12;
            var sent = sender.create("Workshop", "Shared from another installation", 0xAABBCC, senderSettings, Map.of());
            Path received = sender.exportProfile(sent.id());
            context.runOnClient(client -> client.screen.onFilesDrop(List.of(received)));
            GuiTestInput.settle(context);
            var imported = profiles.list().stream().filter(profile -> profile.description().equals(sent.description())).findFirst()
                    .orElseThrow(() -> new AssertionError("Dropping a shared file did not import its config"));
            require(!imported.name().equalsIgnoreCase(sent.name()) && imported.config().crystalAura.placeDelay == 12
                            && profiles.list().size() == 4 && activeStore.load().crystalAura.placeDelay == 8,
                    "Import replaced a profile or changed the active settings");
            GuiTestInput.click(context, "configsLoad");
            require(activeStore.load().crystalAura.placeDelay == 12, "The imported profile could not be loaded");
            GuiTestInput.closeAnimated(context);
        }
    }

    private static void openConfigs(ClientGameTestContext context, ClientConfig config, ConfigStore activeStore,
                                    ConfigProfileStore profiles) {
        GuiTestInput.openAnimated(context, () -> new DonutScreen(config, activeStore, profiles));
        GuiTestInput.click(context, "tab8");
    }

    private static void fillField(ClientGameTestContext context, String id, String text) {
        GuiTestInput.click(context, id);
        context.runOnClient(client -> require(client.screen.keyPressed(
                new KeyEvent(GLFW.GLFW_KEY_A, 0, GLFW.GLFW_MOD_CONTROL | GLFW.GLFW_MOD_SUPER)),
                "The profile field did not handle select-all: " + id));
        GuiTestInput.type(context, text);
    }

    private static void resize(ClientGameTestContext context, int width, int height, int scale) {
        context.getInput().resizeWindow(width, height);
        context.runOnClient(client -> {
            client.options.guiScale().set(scale);
            client.resizeDisplay();
        });
    }

    private static void setCurrent(ClientGameTestContext context, ClientConfig config, int delay, int accent, String key) {
        context.runOnClient(client -> {
            config.crystalAura.placeDelay = delay;
            config.accentColor = accent;
            ModuleKeybinds.setKey(client, MODULE, InputConstants.getKey(key));
        });
    }

    private static void assertApplied(ClientGameTestContext context, ClientConfig config, ConfigStore store,
                                      Path optionsFile, String mapping, int delay, int accent, String key) throws IOException {
        require(context.computeOnClient(client -> config.crystalAura.placeDelay == delay && config.accentColor == accent
                        && ModuleKeybinds.mapping(MODULE).saveString().equals(key)),
                "Loading did not apply the profile to the live settings and keybind");
        var disk = store.load();
        require(disk.crystalAura.placeDelay == delay && disk.accentColor == accent,
                "Loading did not persist the active module and appearance settings");
        require(Files.readAllLines(optionsFile).contains("key_" + mapping + ":" + key),
                "Loading did not persist the keybind to Minecraft Controls");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
