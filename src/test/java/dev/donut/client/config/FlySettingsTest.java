package dev.donut.client.config;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FlySettingsTest {
    @TempDir Path directory;

    @Test
    void savedFavoritesSurviveButActivationNeverPersists() throws IOException {
        Path path = directory.resolve("donut.json");
        ConfigStore store = new ConfigStore(path);
        ClientConfig config = new ClientConfig();
        config.fly.enabled = true;
        config.fly.favorite = true;

        store.save(config);

        var json = JsonParser.parseString(Files.readString(path)).getAsJsonObject().getAsJsonObject("fly");
        assertFalse(json.has("enabled"));
        assertTrue(json.get("favorite").getAsBoolean());
        assertTrue(config.fly.enabled, "Saving preferences must not switch off live flight");
        ClientConfig loaded = store.load();
        assertFalse(loaded.fly.enabled);
        assertTrue(loaded.fly.favorite);
    }

    @Test
    void handEditedActivationIsIgnoredWhenLoading() throws IOException {
        Path path = directory.resolve("donut.json");
        Files.writeString(path, """
                {"fly":{"enabled":true,"favorite":true}}
                """);

        ClientConfig loaded = new ConfigStore(path).load();

        assertFalse(loaded.fly.enabled, "Joining a session must require enabling flight explicitly");
        assertTrue(loaded.fly.favorite);
    }

    @Test
    void missingFileAndOlderOrNullSettingsRestoreDisabledDefaults() throws IOException {
        Path path = directory.resolve("donut.json");
        ConfigStore store = new ConfigStore(path);
        assertDisabledDefaults(store.load());
        assertFalse(Files.exists(path));
        for (String json : new String[] {"{}", "{\"fly\":null}", "{\"fly\":{}}"}) {
            Files.writeString(path, json);
            assertDisabledDefaults(store.load());
        }
    }

    private static void assertDisabledDefaults(ClientConfig config) {
        assertNotNull(config.fly);
        assertFalse(config.fly.enabled);
        assertFalse(config.fly.favorite);
    }
}
