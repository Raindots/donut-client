package dev.donut.client.config;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class FinderSettingsTest {
    @Test
    void finiteValuesAreClampedAtBothEnds() {
        FinderSettings low = new FinderSettings();
        low.range = -1;
        low.outlineOpacity = -0.1;
        low.fillOpacity = -0.1;
        low.lineWidth = 0;
        FinderSettings high = new FinderSettings();
        high.range = 1000;
        high.outlineOpacity = 2;
        high.fillOpacity = 2;
        high.lineWidth = 20;

        low.sanitize();
        high.sanitize();

        assertEquals(16, low.range);
        assertEquals(0, low.outlineOpacity);
        assertEquals(0, low.fillOpacity);
        assertEquals(0.5, low.lineWidth);
        assertEquals(256, high.range);
        assertEquals(1, high.outlineOpacity);
        assertEquals(1, high.fillOpacity);
        assertEquals(5, high.lineWidth);
    }

    @Test
    void everyNonFiniteInputUsesFiniteDefaults() {
        for (double badValue : new double[] {Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY}) {
            FinderSettings settings = new FinderSettings();
            settings.range = badValue;
            settings.outlineOpacity = badValue;
            settings.fillOpacity = badValue;
            settings.lineWidth = badValue;

            settings.sanitize();

            assertEquals(96, settings.range, "range: " + badValue);
            assertEquals(0.9, settings.outlineOpacity, "outline opacity: " + badValue);
            assertEquals(0.16, settings.fillOpacity, "fill opacity: " + badValue);
            assertEquals(1.5, settings.lineWidth, "line width: " + badValue);
        }
    }

    @Test
    void nullModeAndColorAlphaAreRepairedWithoutChangingPreferences() {
        FinderSettings settings = new FinderSettings();
        settings.enabled = true;
        settings.favorite = true;
        settings.mode = null;
        settings.throughWalls = false;
        settings.chests = false;
        settings.color = 0xAB123456;
        settings.chestColor = 0xFFABCDEF;
        settings.shulkerColor = 0x80654321;
        settings.dropperColor = -1;

        settings.sanitize();

        assertEquals(RenderMode.BOTH, settings.mode);
        assertEquals(0x123456, settings.color);
        assertEquals(0xABCDEF, settings.chestColor);
        assertEquals(0x654321, settings.shulkerColor);
        assertEquals(0xFFFFFF, settings.dropperColor);
        assertTrue(settings.enabled);
        assertTrue(settings.favorite);
        assertFalse(settings.throughWalls);
        assertFalse(settings.chests);
    }

    @Test
    void nullModulesRestoreDistinctDisabledDefaults() {
        ClientConfig config = new ClientConfig();
        config.storage = null;
        config.spawner = null;
        config.debris = null;
        config.accentColor = 0xAB27ADC9;

        config.sanitize();

        assertNotNull(config.storage);
        assertNotNull(config.spawner);
        assertNotNull(config.debris);
        assertNotSame(config.storage, config.spawner);
        assertNotSame(config.spawner, config.debris);
        assertFalse(config.storage.enabled);
        assertFalse(config.spawner.enabled);
        assertFalse(config.debris.enabled);
        assertTrue(config.storage.perTypeColors);
        assertFalse(config.spawner.perTypeColors);
        assertEquals(0x4C86FF, config.storage.color);
        assertEquals(0xFF779C, config.spawner.color);
        assertEquals(0xA855F7, config.debris.color);
        assertEquals(0x27ADC9, config.accentColor);
    }
}
