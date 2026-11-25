package dev.donut.client.config;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigProfileStoreTest {
    @TempDir Path directory;

    @Test void storesSnapshotsAndEditsDetailsIndependently() throws IOException {
        Path file = directory.resolve("nested/donut-configs.json");
        ConfigProfileStore store = new ConfigProfileStore(file);
        assertTrue(store.list().isEmpty());
        assertFalse(Files.exists(file));

        ClientConfig config = new ClientConfig();
        config.freecam.speed = 2.5;
        config.crystalAura.enabled = true;
        var bindings = Map.of("key.donut.open_gui", "key.keyboard.f8");
        var first = store.create("  Café  ", " PvP\r\npractice ", 0xACD2FF, config, bindings);
        var other = store.create("Building", "", 0xFFFFFF, config, Map.of());
        config.freecam.speed = 4;

        var reloadedStore = new ConfigProfileStore(file);
        var reloaded = reloadedStore.list().getFirst();
        assertEquals(first.id(), reloaded.id());
        assertEquals("Café", reloaded.name());
        assertEquals("PvP\npractice", reloaded.description());
        assertEquals(0xACD2FF, reloaded.color());
        assertEquals(2.5, reloaded.config().freecam.speed);
        assertFalse(reloaded.config().crystalAura.enabled);
        assertEquals(bindings, reloaded.keybinds());

        var renamed = reloadedStore.update(first.id(), "PvP", "", 0xDF5F20, null, null);
        assertEquals(2.5, renamed.config().freecam.speed);
        assertEquals(bindings, renamed.keybinds());
        var updated = reloadedStore.update(first.id(), renamed.name(), renamed.description(), renamed.color(), config, Map.of());
        assertEquals(4, updated.config().freecam.speed);
        assertTrue(updated.keybinds().isEmpty());

        reloadedStore.delete(first.id());
        assertEquals(other.id(), store.list().getFirst().id());
        assertEquals(1, store.list().size());
    }

    @Test void rejectsInvalidNamesAndDuplicatesWithoutChangingTheLibrary() throws IOException {
        Path file = directory.resolve("donut-configs.json");
        ConfigProfileStore store = new ConfigProfileStore(file);
        var config = new ClientConfig();
        var first = store.create("First", "", 0, config, Map.of());
        var second = store.create("Second", "", 0, config, Map.of());
        String before = Files.readString(file);

        assertThrows(IllegalArgumentException.class, () -> store.create("  ", "", 0, config, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> store.create(" first ", "", 0, config, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> store.update(second.id(), first.name(), "", 0, null, null));
        assertThrows(IllegalArgumentException.class, () -> store.update(first.id(), "First", "x".repeat(281), 0, null, null));
        assertEquals(before, Files.readString(file));
    }

    @Test void preservesMalformedAndUnsupportedLibraries() throws IOException {
        Path file = directory.resolve("donut-configs.json");
        ConfigProfileStore store = new ConfigProfileStore(file);
        for (String contents : new String[]{"{\"version\":1,\"profiles\":[", "{\"version\":2,\"profiles\":[]}"}) {
            Files.writeString(file, contents);
            assertThrows(IOException.class, store::list);
            assertThrows(IOException.class, () -> store.create("Profile", "", 0, new ClientConfig(), Map.of()));
            assertEquals(contents, Files.readString(file));
        }
    }

    @Test void exportsOneProfileAndImportsItWithoutReplacingExistingNames() throws IOException {
        var sender = new ConfigProfileStore(directory.resolve("sender/donut-configs.json"));
        var recipient = new ConfigProfileStore(directory.resolve("recipient/donut-configs.json"));
        var config = new ClientConfig();
        config.crystalAura.placeDelay = 6;
        var mappings = Map.of("key.donut.toggle_crystal_aura", "key.keyboard.f8");
        var profile = sender.create("Café / creative", "Shared HUD settings", 0xA981DE, config, mappings);
        sender.create("Private profile", "", 0, config, Map.of());
        var existing = recipient.create("Café / creative", "Keep my own setup", 0, new ClientConfig(), Map.of());

        Path share = sender.exportProfile(profile.id());
        assertEquals(sender.path().getParent().resolve("exports"), share.getParent());
        assertEquals("Café-creative-" + profile.id() + ".json", share.getFileName().toString());
        assertEquals(1, new ConfigProfileStore(share).list().size());
        assertFalse(Files.readString(share).contains("Private profile"));

        var imported = recipient.importProfiles(share).getFirst();
        assertEquals("Café / creative (2)", imported.name());
        assertEquals("Shared HUD settings", imported.description());
        assertEquals(0xA981DE, imported.color());
        assertEquals(6, imported.config().crystalAura.placeDelay);
        assertEquals(mappings, imported.keybinds());
        assertEquals(existing.id(), recipient.list().getFirst().id());
        assertEquals(2, recipient.list().size());
        assertTrue(recipient.importProfiles(share).isEmpty());
    }

    @Test void importsOlderLibrariesAndKeepsDifferentRevisionsSeparate() throws IOException {
        var sender = new ConfigProfileStore(directory.resolve("sender/donut-configs.json"));
        var recipient = new ConfigProfileStore(directory.resolve("recipient/donut-configs.json"));
        var config = new ClientConfig();
        config.crystalAura.placeDelay = 3;
        var first = sender.create("First", "", 0, config, Map.of());
        sender.create("Second", "", 0, config, Map.of());
        assertEquals(2, recipient.importProfiles(sender.path()).size());

        config.crystalAura.placeDelay = 9;
        sender.update(first.id(), first.name(), first.description(), first.color(), config, Map.of());
        var revision = recipient.importProfiles(sender.path()).getFirst();
        assertNotEquals(first.id(), revision.id());
        assertEquals("First (2)", revision.name());
        assertEquals(9, revision.config().crystalAura.placeDelay);
        assertEquals(3, recipient.list().getFirst().config().crystalAura.placeDelay);
        assertTrue(recipient.importProfiles(sender.path()).isEmpty());
        assertEquals(3, recipient.list().size());
    }

    @Test void refusesMissingOrCorruptShareFilesWithoutChangingTheLibrary() throws IOException {
        var recipient = new ConfigProfileStore(directory.resolve("recipient/donut-configs.json"));
        recipient.create("Existing", "", 0, new ClientConfig(), Map.of());
        String previous = Files.readString(recipient.path());
        Path source = directory.resolve("shared.json");
        assertThrows(IOException.class, () -> recipient.importProfiles(source));

        String invalid = "{\"version\":1,\"profiles\":[null]}";
        Files.writeString(source, invalid);
        assertThrows(IOException.class, () -> recipient.importProfiles(source));
        assertEquals(previous, Files.readString(recipient.path()));
        assertEquals(invalid, Files.readString(source));
    }
}
