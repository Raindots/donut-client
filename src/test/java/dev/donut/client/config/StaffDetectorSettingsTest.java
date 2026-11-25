package dev.donut.client.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class StaffDetectorSettingsTest {
    @TempDir Path directory;
    private static final String UUID = "c458951b-707e-486b-a35a-d46ddf1411da";

    @Test void missingAndNullPreferencesUseConservativeDefaults() throws Exception {
        Path file = directory.resolve("settings.json");
        for (String json : List.of("{}", "{\"staffDetector\":null}")) {
            Files.writeString(file, json); var value = new ConfigStore(file).load().staffDetector;
            assertFalse(value.enabled || value.completionChecks || value.rosterAlerts);
            assertTrue(value.showHud && value.chatAlerts && value.spectatorAlerts && value.invisibilityAlerts);
            assertTrue(value.vanishAlerts);
            assertTrue(value.rosters.isEmpty()); assertEquals(3, value.requiredSamples);
        }
    }
    @Test void serverScopedIdentitiesAndAllPreferencesPersist() {
        var config = new ClientConfig(); var value = config.staffDetector;
        value.enabled = value.favorite = value.completionChecks = value.rosterAlerts = true;
        value.showHud = value.chatAlerts = value.spectatorAlerts = value.invisibilityAlerts = false;
        value.vanishAlerts = false;
        value.nearbyRange = 120; value.completionIntervalSeconds = 20; value.requiredSamples = 4;
        value.evidenceSeconds = 50; value.alertCooldownSeconds = 60; value.maxHudRows = 6;
        assertTrue(value.add(" Example.Org:25565 ", "KnownStaff"));
        assertFalse(value.add("example.org:25565", "knownstaff"));
        assertTrue(value.add("second.example", UUID));
        var store = new ConfigStore(directory.resolve("settings.json")); store.save(config);
        var loaded = store.load().staffDetector;
        assertTrue(loaded.enabled && loaded.favorite && loaded.completionChecks && loaded.rosterAlerts);
        assertFalse(loaded.showHud || loaded.chatAlerts || loaded.spectatorAlerts || loaded.invisibilityAlerts);
        assertFalse(loaded.vanishAlerts);
        assertEquals("KnownStaff", loaded.roster("EXAMPLE.ORG:25565").getFirst().name);
        assertEquals(UUID, loaded.roster("second.example").getFirst().uuid);
        assertTrue(loaded.roster("third.example").isEmpty());
        assertEquals(120, loaded.nearbyRange); assertEquals(20, loaded.completionIntervalSeconds);
        assertEquals(4, loaded.requiredSamples); assertEquals(50, loaded.evidenceSeconds);
        assertEquals(60, loaded.alertCooldownSeconds); assertEquals(6, loaded.maxHudRows);
    }
    @Test void invalidMembersDuplicatesAndNumericBoundsAreSanitizedWithoutRebindingUuid() {
        var value = new StaffDetectorSettings();
        value.nearbyRange = 999; value.requiredSamples = -1; value.completionIntervalSeconds = 0;
        value.evidenceSeconds = 0; value.alertCooldownSeconds = 999; value.maxHudRows = 999;
        value.rosters = new LinkedHashMap<>();
        value.rosters.put(" EXAMPLE.ORG ", new ArrayList<>(java.util.Arrays.asList(null,
                new StaffDetectorSettings.Member("invalid", "bad name"),
                new StaffDetectorSettings.Member(UUID.toUpperCase(), " KnownStaff "),
                new StaffDetectorSettings.Member(UUID, "Duplicate"),
                new StaffDetectorSettings.Member("", "knownstaff"))));
        value.sanitize();
        assertEquals(1, value.roster("example.org").size());
        assertEquals(UUID, value.roster("example.org").getFirst().uuid);
        assertEquals("KnownStaff", value.roster("example.org").getFirst().name);
        assertEquals(256, value.nearbyRange); assertEquals(2, value.requiredSamples);
        assertEquals(6, value.completionIntervalSeconds); assertEquals(10, value.evidenceSeconds);
        assertEquals(300, value.alertCooldownSeconds); assertEquals(8, value.maxHudRows);
        value.rosters = null; value.sanitize(); assertTrue(value.rosters.isEmpty());
    }
    @Test void boundedListsAndStrictUuidParsingRejectMalformedInput() {
        var value = new StaffDetectorSettings();
        assertNull(StaffDetectorSettings.parseUuid("1-2-3-4-5"));
        for (String invalid : List.of("", "with spaces", "a".repeat(17), "minecraft:admin", "1-2-3-4-5"))
            assertThrows(IllegalArgumentException.class, () -> value.add("example.org", invalid));
        for (int i = 0; i < StaffDetectorSettings.MAX_STAFF; i++) assertTrue(value.add("example.org", "Staff" + i));
        assertThrows(IllegalArgumentException.class, () -> value.add("example.org", "OneMore"));
        var first = value.roster("example.org").getFirst(); value.sanitize(); value.remove("example.org", first);
        assertEquals(StaffDetectorSettings.MAX_STAFF - 1, value.roster("example.org").size());
    }
}
