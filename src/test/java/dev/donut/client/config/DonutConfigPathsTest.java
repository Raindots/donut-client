package dev.donut.client.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DonutConfigPathsTest {
    @TempDir Path directory;

    @Test void freshInstallUsesDedicatedPaths() {
        var paths = DonutConfigPaths.resolve(directory);
        assertEquals(directory.resolve("donut-client/donut-client.json"), paths.active());
        assertEquals(directory.resolve("donut-client/donut-configs.json"), paths.profiles());
        assertFalse(Files.exists(directory.resolve("donut-client.json")));
        assertFalse(Files.exists(directory.resolve("donut-configs.json")));
    }

    @Test void copiesBothLegacyFilesAndPreservesExistingDestinations() throws IOException {
        Path active = Files.writeString(directory.resolve("donut-client.json"), "{\"accentColor\":123}");
        Path profiles = Files.writeString(directory.resolve("donut-configs.json"), "{\"version\":1,\"profiles\":[]}");

        var paths = DonutConfigPaths.resolve(directory);
        assertEquals(Files.readString(active), Files.readString(paths.active()));
        assertEquals(Files.readString(profiles), Files.readString(paths.profiles()));

        Files.writeString(paths.active(), "{\"accentColor\":456}");
        Files.writeString(paths.profiles(), "{ \"version\": 1, \"profiles\": [] }");
        var reopened = DonutConfigPaths.resolve(directory);
        assertEquals(456, new ConfigStore(reopened.active()).load().accentColor);
        assertEquals("{ \"version\": 1, \"profiles\": [] }", Files.readString(reopened.profiles()));
        assertEquals(123, new ConfigStore(active).load().accentColor);
        assertEquals("{\"version\":1,\"profiles\":[]}", Files.readString(profiles));
    }

    @Test void usesLegacyFileIfTheDedicatedFolderIsBlocked() throws IOException {
        Path legacy = Files.writeString(directory.resolve("donut-client.json"), "{\"accentColor\":789}");
        Files.writeString(directory.resolve("donut-client"), "This file blocks directory creation.");

        var paths = DonutConfigPaths.resolve(directory);
        assertEquals(legacy, paths.active());
        assertEquals(789, new ConfigStore(paths.active()).load().accentColor);
        assertEquals(directory.resolve("donut-client/donut-configs.json"), paths.profiles());
    }
}
