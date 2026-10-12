package dev.donut.client.config;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ChunkFinderSettingsTest {
    @TempDir Path directory;

    @Test
    void defaultsAndOlderConfigsStartDisabledWithAllIndicatorsAvailable() throws IOException {
        Path path = directory.resolve("donut.json");
        assertDefaults(new ConfigStore(path).load().chunkFinder);
        assertFalse(Files.exists(path));
        for (String json : new String[] {"{}", "{\"chunkFinder\":null}"}) {
            Files.writeString(path, json);
            assertDefaults(new ConfigStore(path).load().chunkFinder);
        }
        ClientConfig config = new ClientConfig();
        config.chunkFinder = null;
        config.sanitize();
        assertDefaults(config.chunkFinder);
    }

    @Test
    void boundsClampWithoutChangingIndicatorPreferences() {
        ChunkFinderSettings low = new ChunkFinderSettings();
        low.simulationDistance = Integer.MIN_VALUE;
        low.sensitivity = Integer.MIN_VALUE;
        low.alpha = Integer.MIN_VALUE;
        low.color = 0xAB123456;
        low.enabled = true;
        low.favorite = true;
        low.smartAdjustment = false;
        low.kelp = false;
        low.caveVines = false;
        low.vines = false;
        low.amethyst = false;
        low.bamboo = false;
        low.cocoa = false;
        low.beeNest = false;
        ChunkFinderSettings high = new ChunkFinderSettings();
        high.simulationDistance = Integer.MAX_VALUE;
        high.sensitivity = Integer.MAX_VALUE;
        high.alpha = Integer.MAX_VALUE;
        high.color = -1;

        low.sanitize();
        high.sanitize();

        assertEquals(2, low.simulationDistance);
        assertEquals(1, low.sensitivity);
        assertEquals(0, low.alpha);
        assertEquals(0x123456, low.color);
        assertEquals(16, high.simulationDistance);
        assertEquals(5, high.sensitivity);
        assertEquals(255, high.alpha);
        assertEquals(0xFFFFFF, high.color);
        assertTrue(low.enabled);
        assertTrue(low.favorite);
        assertFalse(low.smartAdjustment);
        assertAllIndicatorsDisabled(low);
    }

    @Test
    void everySettingSurvivesSaveAndReload() {
        ConfigStore store = new ConfigStore(directory.resolve("nested/donut.json"));
        ClientConfig config = new ClientConfig();
        ChunkFinderSettings settings = config.chunkFinder;
        settings.enabled = true;
        settings.favorite = true;
        settings.simulationDistance = 12;
        settings.sensitivity = 5;
        settings.smartAdjustment = false;
        settings.alpha = 173;
        settings.color = 0x27ADC9;
        settings.kelp = false;
        settings.caveVines = false;
        settings.vines = false;
        settings.amethyst = false;
        settings.bamboo = false;
        settings.cocoa = false;
        settings.beeNest = false;

        store.save(config);
        ChunkFinderSettings loaded = store.load().chunkFinder;

        assertTrue(loaded.enabled);
        assertTrue(loaded.favorite);
        assertEquals(12, loaded.simulationDistance);
        assertEquals(5, loaded.sensitivity);
        assertFalse(loaded.smartAdjustment);
        assertEquals(173, loaded.alpha);
        assertEquals(0x27ADC9, loaded.color);
        assertAllIndicatorsDisabled(loaded);
    }

    @Test
    void handEditedSettingsAreSanitizedAndMissingFieldsKeepDefaults() throws IOException {
        Path path = directory.resolve("donut.json");
        Files.writeString(path, """
                {"chunkFinder":{"enabled":true,"simulationDistance":10000,
                  "sensitivity":-5,"alpha":999,"color":-1,"amethyst":false}}
                """);

        ChunkFinderSettings loaded = new ConfigStore(path).load().chunkFinder;

        assertTrue(loaded.enabled);
        assertEquals(16, loaded.simulationDistance);
        assertEquals(1, loaded.sensitivity);
        assertEquals(255, loaded.alpha);
        assertEquals(0xFFFFFF, loaded.color);
        assertFalse(loaded.amethyst);
        assertFalse(loaded.favorite);
        assertTrue(loaded.smartAdjustment);
        assertTrue(loaded.kelp);
        assertTrue(loaded.caveVines);
        assertTrue(loaded.vines);
        assertTrue(loaded.bamboo);
        assertTrue(loaded.cocoa);
        assertTrue(loaded.beeNest);
    }

    private static void assertDefaults(ChunkFinderSettings settings) {
        assertNotNull(settings);
        assertFalse(settings.enabled);
        assertFalse(settings.favorite);
        assertEquals(6, settings.simulationDistance);
        assertEquals(3, settings.sensitivity);
        assertTrue(settings.smartAdjustment);
        assertEquals(80, settings.alpha);
        assertEquals(0xC447FF, settings.color);
        assertTrue(settings.kelp);
        assertTrue(settings.caveVines);
        assertTrue(settings.vines);
        assertTrue(settings.amethyst);
        assertTrue(settings.bamboo);
        assertTrue(settings.cocoa);
        assertTrue(settings.beeNest);
    }

    private static void assertAllIndicatorsDisabled(ChunkFinderSettings settings) {
        assertFalse(settings.kelp);
        assertFalse(settings.caveVines);
        assertFalse(settings.vines);
        assertFalse(settings.amethyst);
        assertFalse(settings.bamboo);
        assertFalse(settings.cocoa);
        assertFalse(settings.beeNest);
    }
}
