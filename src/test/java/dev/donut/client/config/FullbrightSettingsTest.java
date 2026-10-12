package dev.donut.client.config;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FullbrightSettingsTest {
    @TempDir Path directory;

    @Test
    void missingAndOlderConfigurationsRestoreDisabledDefaults() throws IOException {
        Path path = directory.resolve("donut.json");
        ConfigStore store = new ConfigStore(path);
        assertDefaults(store.load().fullbright);
        assertFalse(Files.exists(path));

        for (String json : new String[] {"{}", "{\"fullbright\":null}"}) {
            Files.writeString(path, json);
            assertDefaults(store.load().fullbright);
        }

        ClientConfig config = new ClientConfig();
        config.fullbright = null;
        config.sanitize();
        assertDefaults(config.fullbright);
    }

    @Test
    void activationAndFavoriteSurviveSaveAndReload() {
        ConfigStore store = new ConfigStore(directory.resolve("nested/donut.json"));
        ClientConfig config = new ClientConfig();
        config.fullbright.enabled = true;
        config.fullbright.favorite = true;
        config.chunkFinder.sensitivity = 5;

        store.save(config);
        ClientConfig loaded = store.load();

        assertTrue(loaded.fullbright.enabled);
        assertTrue(loaded.fullbright.favorite);
        assertEquals(5, loaded.chunkFinder.sensitivity);
        assertTrue(config.fullbright.enabled, "Saving must not disable the live module");

        loaded.fullbright.enabled = false;
        loaded.fullbright.favorite = false;
        store.save(loaded);
        assertDefaults(store.load().fullbright);
    }

    private static void assertDefaults(FullbrightSettings settings) {
        assertNotNull(settings);
        assertFalse(settings.enabled);
        assertFalse(settings.favorite);
    }
}
