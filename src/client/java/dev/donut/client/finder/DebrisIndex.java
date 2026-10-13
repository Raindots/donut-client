package dev.donut.client.finder;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.Vec3;

/**
 * Ancient debris has no block entity, so loaded chunk sections are searched directly.
 * Results are cached per chunk and scans are spread over ticks, nearest chunks first.
 */
final class DebrisIndex {
    private static final int CHUNKS_PER_TICK = 8;
    /** Indexed chunks are rescanned so debris revealed by block updates appears without reloading. */
    private static final int REFRESH_TICKS = 40;
    private static final Predicate<BlockState> DEBRIS = state -> state.is(Blocks.ANCIENT_DEBRIS);

    private final Map<Long, Scan> scans = new HashMap<>();
    private int[] offsets = new int[0];
    private double offsetsRange = -1;
    private long tick;

    void clear() {
        scans.clear();
        tick = 0;
    }

    void tick(ClientLevel level, ChunkPos center, double range) {
        tick++;
        if (range != offsetsRange) {
            offsets = offsets(range);
            offsetsRange = range;
        }
        // Newly loaded chunks take priority over refreshing chunks that are already indexed.
        int budget = scan(level, center, CHUNKS_PER_TICK, false);
        if (budget > 0) scan(level, center, budget, true);
        if (tick % 20 == 0) prune(level, center, range);
    }

    void collect(Vec3 origin, double range, List<FoundBlock> found) {
        double rangeSqr = range * range;
        for (Scan scan : scans.values()) {
            for (FoundBlock block : scan.debris) {
                if (block.pos().distToCenterSqr(origin) <= rangeSqr) found.add(block);
            }
        }
    }

    private int scan(ClientLevel level, ChunkPos center, int budget, boolean refresh) {
        for (int i = 0; i < offsets.length && budget > 0; i += 2) {
            int x = center.x + offsets[i], z = center.z + offsets[i + 1];
            LevelChunk chunk = level.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false);
            if (chunk == null) continue;
            long key = ChunkPos.asLong(x, z);
            Scan previous = scans.get(key);
            // A reloaded chunk is a new object, even when it reuses the same position.
            boolean indexed = previous != null && previous.chunk == chunk;
            if (refresh ? !indexed || tick - previous.tick < REFRESH_TICKS : indexed) continue;
            scans.put(key, new Scan(chunk, tick, find(chunk)));
            budget--;
        }
        return budget;
    }

    private void prune(ClientLevel level, ChunkPos center, double range) {
        scans.entrySet().removeIf(entry -> {
            int x = ChunkPos.getX(entry.getKey()), z = ChunkPos.getZ(entry.getKey());
            return !withinRange(x - center.x, z - center.z, range)
                    || level.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false) != entry.getValue().chunk;
        });
    }

    private static List<FoundBlock> find(LevelChunk chunk) {
        List<FoundBlock> found = new ArrayList<>();
        LevelChunkSection[] sections = chunk.getSections();
        int minX = chunk.getPos().getMinBlockX(), minZ = chunk.getPos().getMinBlockZ();
        for (int index = 0; index < sections.length; index++) {
            LevelChunkSection section = sections[index];
            // The palette check skips almost every section without reading its blocks.
            if (section.hasOnlyAir() || !section.maybeHas(DEBRIS)) continue;
            int minY = SectionPos.sectionToBlockCoord(chunk.getSectionYFromSectionIndex(index));
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        if (DEBRIS.test(section.getBlockState(x, y, z))) {
                            found.add(new FoundBlock(new BlockPos(minX + x, minY + y, minZ + z), TargetKind.ANCIENT_DEBRIS));
                        }
                    }
                }
            }
        }
        return found.isEmpty() ? List.of() : List.copyOf(found);
    }

    /** Chunk offsets within range, nearest first, packed as dx/dz pairs. */
    private static int[] offsets(double range) {
        int radius = (int) Math.ceil(range / 16.0);
        List<int[]> within = new ArrayList<>();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (withinRange(dx, dz, range)) within.add(new int[] {dx, dz});
            }
        }
        within.sort(Comparator.comparingInt(offset -> offset[0] * offset[0] + offset[1] * offset[1]));
        int[] packed = new int[within.size() * 2];
        for (int i = 0; i < within.size(); i++) {
            packed[i * 2] = within.get(i)[0];
            packed[i * 2 + 1] = within.get(i)[1];
        }
        return packed;
    }

    /** The player may stand anywhere in the center chunk, so measure to the nearest possible edge. */
    private static boolean withinRange(int dx, int dz, double range) {
        double x = Math.max(Math.abs(dx) - 1, 0) * 16.0, z = Math.max(Math.abs(dz) - 1, 0) * 16.0;
        return x * x + z * z <= range * range;
    }

    private record Scan(LevelChunk chunk, long tick, List<FoundBlock> debris) { }
}
