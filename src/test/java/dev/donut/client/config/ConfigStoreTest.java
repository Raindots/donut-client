package dev.donut.client.config;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigStoreTest {
    @TempDir Path directory;

    @Test void missingFileLoadsDefaultsWithoutCreatingAFile() {
        Path file = directory.resolve("donut.json");
        assertSafeDefaults(new ConfigStore(file).load());
        assertFalse(Files.exists(file));
    }

    @Test void settingsSurviveSaveAndReloadAndExistingFilesCanBeReplaced() {
        Path file = directory.resolve("nested/donut.json");
        ConfigStore store = new ConfigStore(file);
        store.save(new ClientConfig());
        ClientConfig config = new ClientConfig();
        config.fullbright.enabled = true;
        config.fullbright.favorite = true;
        config.freecam.speed = 2.5;
        config.freecam.boostMultiplier = 4;
        config.accentColor = 0x27ADC9;
        config.clickGuiParticles = false;
        config.showHud = false;
        store.save(config);
        ClientConfig loaded = store.load();
        assertTrue(loaded.fullbright.enabled);
        assertTrue(loaded.fullbright.favorite);
        assertEquals(2.5, loaded.freecam.speed);
        assertEquals(4, loaded.freecam.boostMultiplier);
        assertEquals(config.accentColor, loaded.accentColor);
        assertFalse(loaded.clickGuiParticles);
        assertFalse(loaded.showHud);
        assertFalse(Files.exists(file.resolveSibling("donut.json.tmp")));
    }

    @Test void malformedFileIsPreservedBeforeDefaultsAreSaved() throws IOException {
        Path file = directory.resolve("donut.json");
        String damaged = "{broken json";
        Files.writeString(file, damaged);
        ConfigStore store = new ConfigStore(file);
        ClientConfig recovered = store.load();
        assertSafeDefaults(recovered);
        List<Path> backups = backups();
        assertEquals(1, backups.size());
        assertEquals(damaged, Files.readString(backups.getFirst()));
        assertEquals(damaged, Files.readString(file));
        store.save(recovered);
        assertSafeDefaults(store.load());
        assertEquals(damaged, Files.readString(backups.getFirst()));
        assertEquals(1, backups().size());
    }

    @Test void jsonNullIsRecoveredAndBackedUp() throws IOException {
        Path file = directory.resolve("donut.json");
        Files.writeString(file, "null");
        assertSafeDefaults(new ConfigStore(file).load());
        assertEquals(1, backups().size());
        assertEquals("null", Files.readString(backups().getFirst()));
    }

    @Test void removedModuleSettingsAreIgnoredAndDroppedWhenSaving() throws IOException {
        Path file = directory.resolve("donut.json");
        Files.writeString(file, """
                {"storage":{"enabled":true},"spawner":{"enabled":true},
                 "futureOption":true,"fullbright":{"enabled":true,"favorite":true}}
                """);
        ConfigStore store = new ConfigStore(file);
        ClientConfig loaded = store.load();
        assertTrue(loaded.fullbright.enabled);
        assertTrue(loaded.fullbright.favorite);
        store.save(loaded);
        String saved = Files.readString(file);
        assertFalse(saved.contains("\"storage\""));
        assertFalse(saved.contains("\"spawner\""));
        assertTrue(backups().isEmpty());
    }

    @Test void editedJsonIsSanitizedWithoutDiscardingValidSettings() throws IOException {
        Path file = directory.resolve("donut.json");
        Files.writeString(file, """
                {"accentColor":-1,"showHud":false,"freecam":{"speed":10000,"boostMultiplier":-2},
                 "fullbright":{"enabled":true},"clickGuiStyle":null,"chunkFinder":null}
                """);
        ClientConfig loaded = new ConfigStore(file).load();
        assertEquals(0xFFFFFF, loaded.accentColor);
        assertFalse(loaded.showHud);
        assertEquals(5, loaded.freecam.speed);
        assertEquals(1, loaded.freecam.boostMultiplier);
        assertTrue(loaded.fullbright.enabled);
        assertNotNull(loaded.clickGuiStyle);
        assertNotNull(loaded.chunkFinder);
        assertTrue(backups().isEmpty());
    }

    @Test void savingNonFiniteValuesProducesReadableFiniteJson() throws IOException {
        Path file = directory.resolve("donut.json");
        ClientConfig config = new ClientConfig();
        config.freecam.speed = Double.NaN;
        config.freecam.boostMultiplier = Double.POSITIVE_INFINITY;
        config.clickGuiStyle.scale = Double.NEGATIVE_INFINITY;
        assertDoesNotThrow(() -> new ConfigStore(file).save(config));
        ClientConfig loaded = new ConfigStore(file).load();
        assertEquals(1, loaded.freecam.speed);
        assertEquals(3, loaded.freecam.boostMultiplier);
        assertEquals(1, loaded.clickGuiStyle.scale);
        String saved = Files.readString(file);
        assertFalse(saved.contains("NaN"));
        assertFalse(saved.contains("Infinity"));
    }

    @Test void crystalAuraTuningPersistsWithSessionOnlyActivation() throws IOException {
        Path file = directory.resolve("donut.json");
        Files.writeString(file, """
                {"crystalAura":{"enabled":true,"mode":"AUTOMATIC","targetRange":20,"placeDelay":30,"breakDelay":-5,
                  "friends":"Alice, alice Bob_2 Invalid!","damageMode":"SAFE"}}
                """);
        ConfigStore store = new ConfigStore(file);
        ClientConfig config = store.load();
        assertFalse(config.crystalAura.enabled);
        assertEquals(20, config.crystalAura.placeDelay);
        assertEquals(0, config.crystalAura.breakDelay);
        assertEquals("Alice, Bob_2", config.crystalAura.friends);
        assertTrue(config.crystalAura.isFriend("aLiCe"));
        assertFalse(config.crystalAura.isFriend("Ali"));

        config.crystalAura.enabled = true;
        config.crystalAura.favorite = true;
        config.crystalAura.breakDelay = 4;
        store.save(config);
        CrystalAuraSettings loaded = store.load().crystalAura;
        assertFalse(loaded.enabled);
        assertTrue(loaded.favorite);
        assertEquals(20, loaded.placeDelay);
        assertEquals(4, loaded.breakDelay);
        assertTrue(loaded.isFriend("BOB_2"));
        var saved = JsonParser.parseString(Files.readString(file)).getAsJsonObject().getAsJsonObject("crystalAura");
        assertFalse(saved.has("mode"));
        assertFalse(saved.has("targetRange"));
        assertFalse(saved.has("damageMode"));
    }

    @Test void autoTotemSettingsPersistWithSessionOnlyActivation() throws IOException {
        Path file = directory.resolve("donut.json");
        Files.writeString(file, """
                {"autoTotem":{"enabled":true,"mode":"UNKNOWN","hotbarRefill":true,"hotbarSlot":25,
                  "reactionDelay":-3,"swapDelay":0,"closeDelay":26,"switchBackDelay":-1,"jitter":5}}
                """);
        ConfigStore store = new ConfigStore(file);
        ClientConfig config = store.load();
        AutoTotemSettings settings = config.autoTotem;
        assertFalse(settings.enabled);
        assertEquals(AutoTotemSettings.Mode.INVENTORY, settings.mode);
        assertTrue(settings.hotbarRefill);
        assertEquals(9, settings.hotbarSlot);
        assertEquals(0, settings.reactionDelay);
        assertEquals(1, settings.swapDelay);
        assertEquals(10, settings.closeDelay);
        assertEquals(0, settings.switchBackDelay);
        assertEquals(3, settings.jitter);

        settings.enabled = true;
        settings.favorite = true;
        settings.mode = AutoTotemSettings.Mode.HOTBAR_SWAP;
        store.save(config);
        AutoTotemSettings loaded = store.load().autoTotem;
        assertFalse(loaded.enabled);
        assertTrue(loaded.favorite);
        assertEquals(AutoTotemSettings.Mode.HOTBAR_SWAP, loaded.mode);
        assertEquals(9, loaded.hotbarSlot);
        assertEquals(3, loaded.jitter);
    }

    @Test void anchorMacroSettingsPersistWithSessionOnlyActivation() throws IOException {
        Path file = directory.resolve("donut.json");
        Files.writeString(file, """
                {"anchorMacro":{"enabled":true,"mode":"UNKNOWN","ifUnguarded":"UNKNOWN","safetySlot":0,
                  "switchDelay":-1,"chargeDelay":0,"explodeDelay":20,"cycleDelay":21,"placeDelay":0,
                  "guardSlot":10,"switchBackDelay":-1,"jitter":5,"safeAnchor":true}}
                """);
        ConfigStore store = new ConfigStore(file);
        ClientConfig config = store.load();
        AnchorMacroSettings settings = config.anchorMacro;
        assertFalse(settings.enabled);
        assertEquals(AnchorMacroSettings.Mode.SINGLE, settings.mode);
        assertEquals(AnchorMacroSettings.Unguarded.DETONATE, settings.ifUnguarded);
        assertEquals(1, settings.safetySlot);
        assertEquals(0, settings.switchDelay);
        assertEquals(1, settings.chargeDelay);
        assertEquals(10, settings.explodeDelay);
        assertEquals(20, settings.cycleDelay);
        assertEquals(1, settings.placeDelay);
        assertEquals(9, settings.guardSlot);
        assertEquals(0, settings.switchBackDelay);
        assertEquals(3, settings.jitter);

        settings.enabled = true;
        settings.favorite = true;
        settings.mode = AnchorMacroSettings.Mode.DOUBLE;
        settings.ifUnguarded = AnchorMacroSettings.Unguarded.CANCEL;
        settings.doubleAnchor = true;
        settings.switchBack = true;
        store.save(config);

        AnchorMacroSettings loaded = store.load().anchorMacro;
        assertFalse(loaded.enabled);
        assertTrue(loaded.favorite);
        assertEquals(AnchorMacroSettings.Mode.DOUBLE, loaded.mode);
        assertEquals(AnchorMacroSettings.Unguarded.CANCEL, loaded.ifUnguarded);
        assertTrue(loaded.doubleAnchor);
        assertTrue(loaded.safeAnchor);
        assertTrue(loaded.switchBack);
        assertEquals(20, loaded.cycleDelay);
        assertEquals(3, loaded.jitter);
    }

    @Test void triggerbotSettingsPersistWithSessionOnlyActivation() throws IOException {
        Path file = directory.resolve("donut.json");
        Files.writeString(file, """
                {"triggerbot":{"enabled":true,"crits":"UNKNOWN","reaction":-5,"jitter":8,
                  "players":false,"hostiles":true,"holdAttack":true,"swords":false}}
                """);
        ConfigStore store = new ConfigStore(file);
        ClientConfig config = store.load();
        TriggerbotSettings settings = config.triggerbot;
        assertFalse(settings.enabled);
        assertEquals(TriggerbotSettings.Crits.SMART, settings.crits);
        assertEquals(0, settings.reaction);
        assertEquals(4, settings.jitter);
        assertFalse(settings.players);
        assertTrue(settings.hostiles);
        assertTrue(settings.holdAttack);
        assertFalse(settings.swords);

        settings.enabled = true;
        settings.favorite = true;
        settings.crits = TriggerbotSettings.Crits.ONLY;
        store.save(config);

        TriggerbotSettings loaded = store.load().triggerbot;
        assertFalse(loaded.enabled);
        assertTrue(loaded.favorite);
        assertEquals(TriggerbotSettings.Crits.ONLY, loaded.crits);
        assertEquals(0, loaded.reaction);
        assertEquals(4, loaded.jitter);
        assertTrue(loaded.hostiles);
        assertTrue(loaded.holdAttack);
        assertFalse(loaded.swords);
    }

    @Test void aimAssistSettingsPersistAndClamp() throws IOException {
        Path file = directory.resolve("donut.json");
        Files.writeString(file, """
                {"aimAssist":{"enabled":true,"targetMode":"UNKNOWN","aimPart":"INVALID",
                  "range":-4,"fov":540,"speed":0,"axes":false,"throughWalls":true}}
                """);
        ConfigStore store = new ConfigStore(file);
        ClientConfig config = store.load();
        AimAssistSettings settings = config.aimAssist;
        assertFalse(settings.enabled);
        assertEquals(AimAssistSettings.TargetMode.FOV, settings.targetMode);
        assertEquals(AimAssistSettings.AimPart.TORSO, settings.aimPart);
        assertEquals(0, settings.range);
        assertEquals(360, settings.fov);
        assertEquals(0.01, settings.speed);
        assertTrue(settings.throughWalls);
        assertFalse(settings.axes);

        settings.enabled = true;
        settings.favorite = true;
        settings.aimPart = AimAssistSettings.AimPart.LEGS;
        settings.targetMode = AimAssistSettings.TargetMode.CLOSEST;
        settings.everything = true;
        store.save(config);

        AimAssistSettings saved = store.load().aimAssist;
        assertFalse(saved.enabled);
        assertTrue(saved.favorite && saved.everything && saved.throughWalls);
        assertFalse(saved.axes);
        assertEquals(AimAssistSettings.AimPart.LEGS, saved.aimPart);
        assertEquals(AimAssistSettings.TargetMode.CLOSEST, saved.targetMode);
        assertEquals(0.01, saved.speed);
    }

    @Test void playerEspSettingsClampAndPersist() throws IOException {
        Path file = directory.resolve("donut.json");
        ConfigStore store = new ConfigStore(file);
        Files.writeString(file, "{\"playerEsp\":null}");
        var defaults = store.load().playerEsp;
        assertFalse(defaults.enabled);
        assertTrue(defaults.throughWalls);
        assertEquals(128, defaults.range);

        Files.writeString(file, """
                {"playerEsp":{"enabled":true,"favorite":true,"throughWalls":false,
                  "range":999,"color":-65536,"opacity":-1}}
                """);
        var config = store.load();
        assertEquals(256, config.playerEsp.range);
        assertEquals(0xFF0000, config.playerEsp.color);
        assertEquals(0, config.playerEsp.opacity);

        config.playerEsp.range = 80;
        config.playerEsp.opacity = 192;
        store.save(config);
        var saved = store.load().playerEsp;
        assertTrue(saved.enabled && saved.favorite);
        assertFalse(saved.throughWalls);
        assertEquals(80, saved.range);
        assertEquals(0xFF0000, saved.color);
        assertEquals(192, saved.opacity);
    }

    @Test void timeChangerSettingsClampAndPersist() throws IOException {
        Path file = directory.resolve("donut.json");
        ConfigStore store = new ConfigStore(file);
        Files.writeString(file, "{\"timeChanger\":null}");
        var defaults = store.load().timeChanger;
        assertFalse(defaults.enabled);
        assertEquals(6_000, defaults.time);

        Files.writeString(file, "{\"timeChanger\":{\"enabled\":true,\"favorite\":true,\"time\":50000}}");
        var config = store.load();
        assertEquals(23_999, config.timeChanger.time);
        config.timeChanger.time = -1;
        config.timeChanger.sanitize();
        assertEquals(0, config.timeChanger.time);

        config.timeChanger.time = 18_000;
        store.save(config);
        var saved = store.load().timeChanger;
        assertTrue(saved.enabled && saved.favorite);
        assertEquals(18_000, saved.time);
    }

    private List<Path> backups() throws IOException {
        try (var files = Files.list(directory)) {
            return files.filter(file -> file.getFileName().toString().startsWith("donut.json.invalid-")).toList();
        }
    }

    private static void assertSafeDefaults(ClientConfig config) {
        assertNotNull(config.freecam);
        assertNotNull(config.fullbright);
        assertNotNull(config.chunkFinder);
        assertNotNull(config.crystalAura);
        assertNotNull(config.autoTotem);
        assertNotNull(config.anchorMacro);
        assertNotNull(config.triggerbot);
        assertNotNull(config.aimAssist);
        assertFalse(config.freecam.enabled);
        assertFalse(config.fullbright.enabled);
        assertFalse(config.crystalAura.enabled);
        assertFalse(config.autoTotem.enabled);
        assertFalse(config.anchorMacro.enabled);
        assertFalse(config.triggerbot.enabled);
        assertEquals(AutoTotemSettings.Mode.INVENTORY, config.autoTotem.mode);
        assertEquals(2, config.autoTotem.switchBackDelay);
        assertEquals(AnchorMacroSettings.Mode.SINGLE, config.anchorMacro.mode);
        assertEquals(AnchorMacroSettings.Unguarded.DETONATE, config.anchorMacro.ifUnguarded);
        assertEquals(1, config.anchorMacro.safetySlot);
        assertEquals(9, config.anchorMacro.guardSlot);
        assertEquals(TriggerbotSettings.Crits.SMART, config.triggerbot.crits);
        assertEquals(2, config.triggerbot.reaction);
        assertEquals(1, config.triggerbot.jitter);
        assertTrue(config.triggerbot.players && config.triggerbot.skipFriends && config.triggerbot.skipShielding);
        assertTrue(config.triggerbot.swords && config.triggerbot.axes && config.triggerbot.mace);
        assertFalse(config.triggerbot.hostiles || config.triggerbot.passives || config.triggerbot.invisible);
        assertFalse(config.triggerbot.anyItem || config.triggerbot.holdAttack);
        assertFalse(config.aimAssist.enabled || config.aimAssist.throughWalls || config.aimAssist.everything);
        assertTrue(config.aimAssist.swords && config.aimAssist.axes && config.aimAssist.mace && config.aimAssist.spearsAndTridents);
        assertEquals(3.2, config.aimAssist.range);
        assertEquals(90, config.aimAssist.fov);
        assertEquals(0.18, config.aimAssist.speed);
        assertTrue(config.clickGuiParticles);
        assertEquals(1, config.freecam.speed);
    }
}
