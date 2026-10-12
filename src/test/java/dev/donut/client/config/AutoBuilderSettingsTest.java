package dev.donut.client.config;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AutoBuilderSettingsTest {
    @TempDir Path directory;

    @Test void preferencesPersistWithoutSavingBuildActivationOrPosition() throws IOException {
        Path path = directory.resolve("donut.json");
        ClientConfig config = new ClientConfig();
        config.autoBuilder.favorite = true;
        config.autoBuilder.previewOpacity = .53;
        ConfigStore store = new ConfigStore(path);
        store.save(config);
        ClientConfig loaded = store.load();
        assertTrue(loaded.autoBuilder.favorite);
        assertEquals(.53, loaded.autoBuilder.previewOpacity);
        var saved = JsonParser.parseString(Files.readString(path)).getAsJsonObject().getAsJsonObject("autoBuilder");
        assertEquals(2, saved.size(), "Only presentation preferences may persist");
        assertFalse(saved.has("enabled"));
        assertFalse(saved.has("origin"));
    }

    @Test void existingAndNullConfigurationsRestoreUsablePreferences() throws IOException {
        Path path = directory.resolve("donut.json");
        for (String json : new String[]{"{}", "{\"autoBuilder\":null}"}) {
            Files.writeString(path, json);
            AutoBuilderSettings settings = new ConfigStore(path).load().autoBuilder;
            assertNotNull(settings);
            assertFalse(settings.favorite);
            assertEquals(.28, settings.previewOpacity);
        }
    }

    @Test void invalidOpacityCannotBreakSavingOrHideTheGhostCompletely() throws IOException {
        Path path = directory.resolve("donut.json");
        for (double value : new double[]{Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            ClientConfig config = new ClientConfig();
            config.autoBuilder.previewOpacity = value;
            new ConfigStore(path).save(config);
            assertEquals(.28, new ConfigStore(path).load().autoBuilder.previewOpacity);
        }
        Files.writeString(path, "{\"autoBuilder\":{\"previewOpacity\":-12}}");
        assertEquals(.08, new ConfigStore(path).load().autoBuilder.previewOpacity);
        Files.writeString(path, "{\"autoBuilder\":{\"previewOpacity\":12}}");
        assertEquals(.8, new ConfigStore(path).load().autoBuilder.previewOpacity);
    }
}
