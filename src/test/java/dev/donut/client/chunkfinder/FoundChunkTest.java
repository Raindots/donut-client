package dev.donut.client.chunkfinder;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class FoundChunkTest {
    @Test
    void snapshotKeepsSeparateImmutableEvidenceLayers() {
        var kinds = EnumSet.of(EvidenceKind.AMETHYST);
        var layers = new ArrayList<>(List.of(new FoundChunk.Layer(-32, -31), new FoundChunk.Layer(65, 65)));
        var found = new FoundChunk(1, 0, -32, 65, 12, kinds, layers);
        kinds.clear();
        layers.clear();

        assertEquals(Set.of(EvidenceKind.AMETHYST), found.kinds());
        assertEquals(List.of(new FoundChunk.Layer(-32, -31), new FoundChunk.Layer(65, 65)), found.layers());
        assertThrows(UnsupportedOperationException.class, () -> found.layers().clear());
        assertThrows(UnsupportedOperationException.class, () -> found.kinds().clear());
    }

    @Test
    void compatibilityConstructorHandlesNegativeAndEmptyBounds() {
        var negative = new FoundChunk(-1, 0, -63, -63, 8, Set.of(EvidenceKind.AMETHYST));
        assertEquals(List.of(new FoundChunk.Layer(-63, -63)), negative.layers());
        var empty = new FoundChunk(0, 0, Integer.MAX_VALUE, Integer.MIN_VALUE, 0, Set.of());
        assertTrue(empty.layers().isEmpty());
    }

    @Test
    void layerRejectsReversedBounds() {
        assertThrows(IllegalArgumentException.class, () -> new FoundChunk.Layer(65, -32));
    }
}
