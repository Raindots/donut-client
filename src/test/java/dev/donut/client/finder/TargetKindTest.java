package dev.donut.client.finder;

import dev.donut.client.config.FinderSettings;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TargetKindTest {
    @Test
    void allTargetKindsHaveValidDisplayNames() {
        for (TargetKind kind : TargetKind.values()) {
            assertNotNull(kind.displayName());
            assertFalse(kind.displayName().isBlank());
        }
    }

    @Test
    void hopperInclusionMatchesSettings() {
        FinderSettings settings = new FinderSettings();
        settings.hoppers = true;
        assertTrue(TargetKind.HOPPER.included(settings));

        settings.hoppers = false;
        assertFalse(TargetKind.HOPPER.included(settings));
    }

    @Test
    void spawnerKindsAreProperlyIdentified() {
        assertTrue(TargetKind.SPAWNER.isSpawner());
        assertTrue(TargetKind.TRIAL_SPAWNER.isSpawner());
        assertFalse(TargetKind.CHEST.isSpawner());
        assertFalse(TargetKind.HOPPER.isSpawner());
        assertFalse(TargetKind.SHULKER.isSpawner());
    }
}
