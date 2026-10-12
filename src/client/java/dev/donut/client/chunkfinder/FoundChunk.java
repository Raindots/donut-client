package dev.donut.client.chunkfinder;

import java.util.List;
import java.util.Set;

/** A heuristic match, not proof of player activity. Y bounds include observed evidence only. */
public record FoundChunk(int chunkX, int chunkZ, int minY, int maxY, int score,
                         Set<EvidenceKind> kinds, List<Layer> layers) {
    public FoundChunk {
        kinds = Set.copyOf(kinds);
        layers = List.copyOf(layers);
    }

    public FoundChunk(int chunkX, int chunkZ, int minY, int maxY, int score,
                      Set<EvidenceKind> kinds) {
        this(chunkX, chunkZ, minY, maxY, score, kinds,
                minY <= maxY ? List.of(new Layer(minY, maxY)) : List.of());
    }

    /** Inclusive bounds of a contiguous band of observed evidence blocks. */
    public record Layer(int minY, int maxY) {
        public Layer {
            if (minY > maxY) throw new IllegalArgumentException("Layer bounds are reversed");
        }
    }
}
