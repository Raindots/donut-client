package dev.donut.client.config;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigStoreTest {
    @TempDir Path directory;

    @Test
    void missingFileLoadsDisabledModulesWithoutCreatingAFile() {
        Path path = directory.resolve("donut.json");

        ClientConfig config = new ConfigStore(path).load();

        assertSafeDefaults(config);
        assertFalse(Files.exists(path));
    }

    @Test
    void settingsSurviveSaveAndReloadAndAnExistingFileCanBeReplaced() throws IOException {
        Path path = directory.resolve("nested/config/donut.json");
        ConfigStore store = new ConfigStore(path);
        store.save(new ClientConfig());
        ClientConfig config = new ClientConfig();
        config.accentColor = 0x27ADC9;
        config.showHud = false;
        config.storage.enabled = true;
        config.storage.favorite = true;
        config.storage.throughWalls = false;
        config.storage.distanceFade = false;
        config.storage.mode = RenderMode.OUTLINE;
        config.storage.range = 182.5;
        config.storage.outlineOpacity = 0.42;
        config.storage.fillOpacity = 0.08;
        config.storage.lineWidth = 3.25;
        config.storage.chests = false;
        config.storage.trappedChests = false;
        config.storage.enderChests = false;
        config.storage.shulkers = false;
        config.storage.droppers = false;
        config.storage.perTypeColors = false;
        config.storage.color = 0x123456;
        config.storage.chestColor = 0xABCDEF;
        config.storage.shulkerColor = 0x654321;
        config.storage.dropperColor = 0xFEDCBA;
        config.spawner.enabled = true;
        config.spawner.trialSpawners = false;
        config.spawner.mode = RenderMode.FILL;
        config.spawner.range = 48;
        config.spawner.color = 0x102030;
        config.debris.enabled = true;
        config.debris.throughWalls = false;
        config.debris.range = 200;
        config.debris.color = 0x7A1FD0;

        store.save(config);
        ClientConfig loaded = new ConfigStore(path).load();

        assertEquals(config.accentColor, loaded.accentColor);
        assertFalse(loaded.showHud);
        assertTrue(loaded.storage.enabled);
        assertTrue(loaded.storage.favorite);
        assertFalse(loaded.storage.throughWalls);
        assertFalse(loaded.storage.distanceFade);
        assertEquals(RenderMode.OUTLINE, loaded.storage.mode);
        assertEquals(182.5, loaded.storage.range);
        assertEquals(0.42, loaded.storage.outlineOpacity);
        assertEquals(0.08, loaded.storage.fillOpacity);
        assertEquals(3.25, loaded.storage.lineWidth);
        assertFalse(loaded.storage.chests);
        assertFalse(loaded.storage.trappedChests);
        assertFalse(loaded.storage.enderChests);
        assertFalse(loaded.storage.shulkers);
        assertFalse(loaded.storage.droppers);
        assertFalse(loaded.storage.perTypeColors);
        assertEquals(0x123456, loaded.storage.color);
        assertEquals(0xABCDEF, loaded.storage.chestColor);
        assertEquals(0x654321, loaded.storage.shulkerColor);
        assertEquals(0xFEDCBA, loaded.storage.dropperColor);
        assertTrue(loaded.spawner.enabled);
        assertFalse(loaded.spawner.trialSpawners);
        assertEquals(RenderMode.FILL, loaded.spawner.mode);
        assertEquals(48, loaded.spawner.range);
        assertEquals(0x102030, loaded.spawner.color);
        assertTrue(loaded.debris.enabled);
        assertFalse(loaded.debris.throughWalls);
        assertEquals(200, loaded.debris.range);
        assertEquals(0x7A1FD0, loaded.debris.color);
        assertFalse(Files.exists(path.resolveSibling("donut.json.tmp")));
        assertTrue(backups(path.getParent()).isEmpty());
    }

    @Test
    void malformedFileIsPreservedBeforeDefaultsAreSaved() throws IOException {
        Path path = directory.resolve("donut.json");
        String damaged = "{\"storage\": {\"enabled\": true, broken json";
        Files.writeString(path, damaged);
        ConfigStore store = new ConfigStore(path);

        ClientConfig recovered = store.load();

        assertSafeDefaults(recovered);
        List<Path> backups = backups(directory);
        assertEquals(1, backups.size());
        assertEquals(damaged, Files.readString(backups.getFirst()));
        assertEquals(damaged, Files.readString(path));

        store.save(recovered);

        assertSafeDefaults(new ConfigStore(path).load());
        assertEquals(damaged, Files.readString(backups.getFirst()));
        assertEquals(1, backups(directory).size());
    }

    @Test
    void jsonNullIsRecoveredAndBackedUp() throws IOException {
        Path path = directory.resolve("donut.json");
        Files.writeString(path, "null");

        assertSafeDefaults(new ConfigStore(path).load());

        List<Path> backups = backups(directory);
        assertEquals(1, backups.size());
        assertEquals("null", Files.readString(backups.getFirst()));
    }

    @Test
    void missingFieldsAndFutureFieldsKeepDefaultsAndKnownSettings() throws IOException {
        Path path = directory.resolve("donut.json");
        Files.writeString(path, """
                {"futureOption":{"value":true},"storage":{"range":64,"chests":false}}
                """);

        ClientConfig loaded = new ConfigStore(path).load();

        assertFalse(loaded.storage.enabled);
        assertFalse(loaded.spawner.enabled);
        assertFalse(loaded.storage.chests);
        assertTrue(loaded.storage.shulkers);
        assertEquals(64, loaded.storage.range);
        assertEquals(0.9, loaded.storage.outlineOpacity);
        assertEquals(RenderMode.BOTH, loaded.storage.mode);
        assertEquals(0xFF779C, loaded.spawner.color);
        assertFalse(loaded.debris.enabled);
        assertEquals(0xA855F7, loaded.debris.color);
        assertTrue(backups(directory).isEmpty());
    }

    @Test
    void editedJsonIsSanitizedWithoutDiscardingValidSettings() throws IOException {
        Path path = directory.resolve("donut.json");
        Files.writeString(path, """
                {
                  "accentColor": -1,
                  "showHud": false,
                  "storage": {
                    "enabled": true,
                    "mode": "REMOVED_MODE",
                    "range": 10000,
                    "outlineOpacity": -5,
                    "fillOpacity": 5,
                    "lineWidth": -2,
                    "color": -1,
                    "droppers": false
                  },
                  "spawner": null
                }
                """);

        ClientConfig loaded = new ConfigStore(path).load();

        assertEquals(0xFFFFFF, loaded.accentColor);
        assertFalse(loaded.showHud);
        assertTrue(loaded.storage.enabled);
        assertFalse(loaded.storage.droppers);
        assertEquals(RenderMode.BOTH, loaded.storage.mode);
        assertEquals(256, loaded.storage.range);
        assertEquals(0, loaded.storage.outlineOpacity);
        assertEquals(1, loaded.storage.fillOpacity);
        assertEquals(0.5, loaded.storage.lineWidth);
        assertEquals(0xFFFFFF, loaded.storage.color);
        assertFalse(loaded.spawner.enabled);
        assertEquals(0xFF779C, loaded.spawner.color);
        assertFalse(loaded.spawner.perTypeColors);
        assertTrue(backups(directory).isEmpty());
    }

    @Test
    void savingNonFiniteValuesProducesReadableFiniteJson() throws IOException {
        Path path = directory.resolve("donut.json");
        ClientConfig config = new ClientConfig();
        config.storage.range = Double.NaN;
        config.storage.outlineOpacity = Double.POSITIVE_INFINITY;
        config.storage.fillOpacity = Double.NEGATIVE_INFINITY;
        config.storage.lineWidth = Double.NaN;
        config.spawner.mode = null;

        assertDoesNotThrow(() -> new ConfigStore(path).save(config));
        ClientConfig loaded = new ConfigStore(path).load();

        assertEquals(96, loaded.storage.range);
        assertEquals(0.9, loaded.storage.outlineOpacity);
        assertEquals(0.16, loaded.storage.fillOpacity);
        assertEquals(1.5, loaded.storage.lineWidth);
        assertEquals(RenderMode.BOTH, loaded.spawner.mode);
        String json = Files.readString(path);
        assertFalse(json.contains("NaN"));
        assertFalse(json.contains("Infinity"));
        assertTrue(backups(directory).isEmpty());
    }

    private static List<Path> backups(Path parent) throws IOException {
        try (var files = Files.list(parent)) {
            return files.filter(path -> path.getFileName().toString().startsWith("donut.json.invalid-")).toList();
        }
    }

    private static void assertSafeDefaults(ClientConfig config) {
        assertNotNull(config.storage);
        assertNotNull(config.spawner);
        assertNotNull(config.debris);
        assertFalse(config.storage.enabled);
        assertFalse(config.spawner.enabled);
        assertFalse(config.debris.enabled);
        assertEquals(RenderMode.BOTH, config.storage.mode);
        assertEquals(RenderMode.BOTH, config.spawner.mode);
        assertEquals(96, config.storage.range);
        assertEquals(0xFF779C, config.spawner.color);
        assertFalse(config.spawner.perTypeColors);
        assertEquals(0xA855F7, config.debris.color);
        assertFalse(config.debris.perTypeColors);
    }
}
