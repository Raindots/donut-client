package dev.donut.client.chunkfinder;

import dev.donut.client.config.ChunkFinderSettings;
import dev.donut.client.config.ClientConfig;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.BambooStalkBlock;
import net.minecraft.world.level.block.BeehiveBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CocoaBlock;
import net.minecraft.world.level.block.GrowingPlantHeadBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/** Incrementally reads loaded chunk palettes and blocks without requesting chunks or packets. */
public final class ChunkFinderScanner {
    public static final int MAX_TARGETS = 256;
    public static final int MAX_BLOCKS_PER_TICK = 4096;
    private static final int MAX_SECTIONS_PER_TICK = 64;
    private static final int MAX_CHUNKS_PER_TICK = 32;
    private static final int MAX_SCORE_PER_KIND = 24;
    private static final long WORK_NANOS_PER_TICK = 3_000_000L;

    private final ClientConfig config;
    private final Map<Long, Detection> detections = new HashMap<>();
    private final Set<Long> visitedThisPass = new HashSet<>();
    private List<FoundChunk> targets = List.of();
    private List<ChunkPos> scanOrder = List.of();
    private ClientLevel lastLevel;
    private ScanSettings lastSettings;
    private ScanWork active;
    private int centerX;
    private int centerZ;
    private int cursor;
    private long completedPasses;
    private long scannedChunks;

    public ChunkFinderScanner(ClientConfig config) { this.config = config; }
    public List<FoundChunk> snapshot() { return targets; }
    /** Complete coverage passes through the current scan area, including unloaded coordinates. */
    public long completedPasses() { return completedPasses; }
    public long scannedChunkCount() { return scannedChunks; }

    public static int thresholdForSensitivity(int sensitivity) {
        return 14 - 2 * Math.clamp(sensitivity, 1, 5);
    }

    public void clear() {
        detections.clear();
        visitedThisPass.clear();
        targets = List.of();
        scanOrder = List.of();
        lastLevel = null;
        lastSettings = null;
        active = null;
        cursor = 0;
        completedPasses = 0;
        scannedChunks = 0;
    }

    public void tick(Minecraft client) {
        if (client.level == null || client.player == null || !config.chunkFinder.enabled) {
            clear();
            return;
        }
        ScanSettings settings = ScanSettings.of(config.chunkFinder);
        if (!settings.hasFilters()) { clear(); return; }
        int playerX = client.player.chunkPosition().x;
        int playerZ = client.player.chunkPosition().z;
        if (client.level != lastLevel || !settings.equals(lastSettings)) {
            clear();
            lastLevel = client.level;
            lastSettings = settings;
            rebuildOrder(playerX, playerZ, settings.radius());
        } else if (playerX != centerX || playerZ != centerZ) {
            rebuildOrder(playerX, playerZ, settings.radius());
        }

        boolean dirty = detections.entrySet().removeIf(entry -> {
            Detection detection = entry.getValue();
            FoundChunk result = detection.result();
            return !inRange(result.chunkX(), result.chunkZ(), settings.radius())
                    || loadedChunk(client.level, result.chunkX(), result.chunkZ()) != detection.chunk();
        });
        if (active != null && loadedChunk(client.level, active.pos.x, active.pos.z) != active.chunk) {
            active = null;
        }

        int blocksLeft = MAX_BLOCKS_PER_TICK;
        int sectionsLeft = MAX_SECTIONS_PER_TICK;
        int chunksLeft = MAX_CHUNKS_PER_TICK;
        long deadline = System.nanoTime() + WORK_NANOS_PER_TICK;
        while (blocksLeft > 0 && sectionsLeft > 0 && chunksLeft > 0) {
            if (active == null) {
                if (cursor >= scanOrder.size()) {
                    completedPasses++;
                    visitedThisPass.clear();
                    rebuildOrder(centerX, centerZ, settings.radius());
                    break;
                }
                ChunkPos next = scanOrder.get(cursor++);
                visitedThisPass.add(next.toLong());
                chunksLeft--;
                LevelChunk chunk = loadedChunk(client.level, next.x, next.z);
                if (chunk == null) continue;
                active = new ScanWork(chunk, next);
            }
            var sections = active.chunk.getSections();
            if (active.sectionIndex >= sections.length) {
                dirty |= finishScan(settings);
                scannedChunks++;
                active = null;
                continue;
            }
            var section = sections[active.sectionIndex];
            if (!active.sectionStarted) {
                sectionsLeft--;
                if (section.hasOnlyAir() || !section.maybeHas(settings::isCandidate)) {
                    active.sectionIndex++;
                    if (System.nanoTime() >= deadline) break;
                    continue;
                }
                active.sectionStarted = true;
            }
            int start = active.blockIndex;
            int end = start + Math.min(blocksLeft, 4096 - start);
            while (active.blockIndex < end) {
                int index = active.blockIndex;
                int x = index & 15;
                int z = (index >>> 4) & 15;
                int y = (index >>> 8) & 15;
                BlockState state = section.getBlockState(x, y, z);
                if (settings.isCandidate(state)) {
                    int worldY = active.chunk.getMinY() + active.sectionIndex * 16 + y;
                    active.observe(state, x, worldY, z, settings);
                }
                active.blockIndex++;
                if ((active.blockIndex & 127) == 0 && System.nanoTime() >= deadline) break;
            }
            blocksLeft -= active.blockIndex - start;
            if (active.blockIndex == 4096) {
                active.sectionIndex++;
                active.blockIndex = 0;
                active.sectionStarted = false;
            }
            if (System.nanoTime() >= deadline) break;
        }
        if (dirty) publish();
    }

    private static LevelChunk loadedChunk(ClientLevel level, int x, int z) {
        return level.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false);
    }

    private void rebuildOrder(int x, int z, int radius) {
        centerX = x;
        centerZ = z;
        cursor = 0;
        if (active != null && !inRange(active.pos.x, active.pos.z, radius)) active = null;
        visitedThisPass.removeIf(key -> !inRange(ChunkPos.getX(key), ChunkPos.getZ(key), radius));
        List<ChunkPos> order = new ArrayList<>((radius * 2 + 1) * (radius * 2 + 1));
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                ChunkPos pos = new ChunkPos(x + dx, z + dz);
                if (!visitedThisPass.contains(pos.toLong())) order.add(pos);
            }
        }
        order.sort(Comparator.comparingLong(pos -> distanceSquared(pos.x, pos.z)));
        scanOrder = List.copyOf(order);
        publish();
    }

    private boolean inRange(int x, int z, int radius) {
        return Math.abs((long) x - centerX) <= radius && Math.abs((long) z - centerZ) <= radius;
    }

    private long distanceSquared(int x, int z) {
        long dx = (long) x - centerX;
        long dz = (long) z - centerZ;
        return dx * dx + dz * dz;
    }

    private boolean finishScan(ScanSettings settings) {
        long key = active.pos.toLong();
        FoundChunk found = active.result(settings);
        if (found.score() < thresholdForSensitivity(settings.sensitivity())) {
            return detections.remove(key) != null;
        }
        if (!detections.containsKey(key) && detections.size() >= MAX_TARGETS) {
            var farthest = detections.entrySet().stream().max(Comparator.comparingLong(entry -> {
                FoundChunk result = entry.getValue().result();
                return distanceSquared(result.chunkX(), result.chunkZ());
            })).orElseThrow();
            FoundChunk previous = farthest.getValue().result();
            if (distanceSquared(found.chunkX(), found.chunkZ())
                    >= distanceSquared(previous.chunkX(), previous.chunkZ())) return false;
            detections.remove(farthest.getKey());
        }
        Detection previous = detections.put(key, new Detection(active.chunk, found));
        return previous == null || !found.equals(previous.result());
    }

    private void publish() {
        List<FoundChunk> found = new ArrayList<>(detections.size());
        for (Detection detection : detections.values()) found.add(detection.result());
        found.sort(Comparator.comparingLong((FoundChunk result) -> distanceSquared(result.chunkX(), result.chunkZ()))
                .thenComparing(Comparator.comparingInt(FoundChunk::score).reversed())
                .thenComparingInt(FoundChunk::chunkX).thenComparingInt(FoundChunk::chunkZ));
        targets = List.copyOf(found);
    }

    private record Detection(LevelChunk chunk, FoundChunk result) {}

    private record ScanSettings(int radius, int sensitivity, boolean smart, boolean kelp,
                                boolean caveVines, boolean vines, boolean amethyst,
                                boolean bamboo, boolean cocoa, boolean beeNest) {
        static ScanSettings of(ChunkFinderSettings settings) {
            return new ScanSettings(Math.clamp(settings.simulationDistance, 2, 16),
                    Math.clamp(settings.sensitivity, 1, 5), settings.smartAdjustment,
                    settings.kelp, settings.caveVines, settings.vines, settings.amethyst,
                    settings.bamboo, settings.cocoa, settings.beeNest);
        }

        boolean hasFilters() { return kelp || caveVines || vines || amethyst || bamboo || cocoa || beeNest; }

        boolean isCandidate(BlockState state) {
            return (kelp && state.is(Blocks.KELP))
                    || (caveVines && state.is(Blocks.CAVE_VINES))
                    || (vines && state.is(Blocks.VINE))
                    || (amethyst && (state.is(Blocks.AMETHYST_BLOCK) || state.is(Blocks.BUDDING_AMETHYST)
                        || state.is(Blocks.SMALL_AMETHYST_BUD) || state.is(Blocks.MEDIUM_AMETHYST_BUD)
                        || state.is(Blocks.LARGE_AMETHYST_BUD) || state.is(Blocks.AMETHYST_CLUSTER)))
                    || (bamboo && state.is(Blocks.BAMBOO))
                    || (cocoa && state.is(Blocks.COCOA))
                    || (beeNest && state.is(Blocks.BEE_NEST));
        }
    }

    private static final class ScanWork {
        final LevelChunk chunk;
        final ChunkPos pos;
        final int[] points = new int[EvidenceKind.values().length];
        final int[] lowest = new int[EvidenceKind.values().length];
        final int[] highest = new int[EvidenceKind.values().length];
        final BitSet evidenceHeights = new BitSet();
        final BlockPos.MutableBlockPos neighbor = new BlockPos.MutableBlockPos();
        int sectionIndex;
        int blockIndex;
        boolean sectionStarted;
        boolean amethystGrowth;

        ScanWork(LevelChunk chunk, ChunkPos pos) { this.chunk = chunk; this.pos = pos; }

        FoundChunk result(ScanSettings settings) {
            int total = 0;
            int minY = Integer.MAX_VALUE;
            int maxY = Integer.MIN_VALUE;
            EnumSet<EvidenceKind> kinds = EnumSet.noneOf(EvidenceKind.class);
            for (EvidenceKind kind : EvidenceKind.values()) {
                int index = kind.ordinal();
                if (points[index] == 0) continue;
                int score = points[index];
                // Maximum sensitivity also accepts the first growth stage of a lone bud.
                if (kind == EvidenceKind.AMETHYST && settings.sensitivity() == 5 && amethystGrowth) {
                    score = Math.max(score, thresholdForSensitivity(5));
                }
                total += score;
                minY = Math.min(minY, lowest[index]);
                maxY = Math.max(maxY, highest[index]);
                kinds.add(kind);
            }
            List<FoundChunk.Layer> layers = new ArrayList<>();
            int chunkMinY = chunk.getMinY();
            for (int start = evidenceHeights.nextSetBit(0); start >= 0;) {
                int end = evidenceHeights.nextClearBit(start) - 1;
                layers.add(new FoundChunk.Layer(chunkMinY + start, chunkMinY + end));
                start = evidenceHeights.nextSetBit(end + 1);
            }
            return new FoundChunk(pos.x, pos.z, minY, maxY, total, kinds, layers);
        }

        void observe(BlockState state, int x, int y, int z, ScanSettings settings) {
            EvidenceKind kind;
            int strength;
            if (state.is(Blocks.KELP)) {
                kind = EvidenceKind.KELP;
                int age = state.getValue(GrowingPlantHeadBlock.AGE);
                // Generated kelp tips start at ages 20..23.
                strength = age < 20 || age == 25 ? 3 : age == 24 ? 1 : settings.smart() ? 0 : 1;
            } else if (state.is(Blocks.CAVE_VINES)) {
                kind = EvidenceKind.CAVE_VINES;
                // Ages 23..25 and berries both occur during lush-cave generation.
                strength = state.getValue(GrowingPlantHeadBlock.AGE) < 23 ? 3 : settings.smart() ? 0 : 1;
            } else if (state.is(Blocks.VINE)) {
                kind = EvidenceKind.VINES;
                strength = hasConstructedSupport(x, y, z) ? 3 : settings.smart() ? 0 : 1;
            } else if (state.is(Blocks.BAMBOO)) {
                kind = EvidenceKind.BAMBOO;
                strength = state.getValue(BambooStalkBlock.AGE) == 0 ? 3 : settings.smart() ? 0 : 1;
            } else if (state.is(Blocks.COCOA)) {
                kind = EvidenceKind.COCOA;
                int age = state.getValue(CocoaBlock.AGE);
                strength = age == 2 ? 3 : age == 1 ? 1 : settings.smart() ? 0 : 1;
            } else if (state.is(Blocks.BEE_NEST)) {
                kind = EvidenceKind.BEE_NEST;
                int honey = state.getValue(BeehiveBlock.HONEY_LEVEL);
                strength = honey > 0 ? honey + 1 : settings.smart() ? 0 : 1;
            } else {
                kind = EvidenceKind.AMETHYST;
                if (!state.is(Blocks.AMETHYST_BLOCK) && !state.is(Blocks.BUDDING_AMETHYST)) amethystGrowth = true;
                strength = state.is(Blocks.AMETHYST_CLUSTER) ? 4
                        : state.is(Blocks.LARGE_AMETHYST_BUD) ? 3
                        : state.is(Blocks.MEDIUM_AMETHYST_BUD) ? 2
                        : state.is(Blocks.SMALL_AMETHYST_BUD) ? 1 : settings.smart() ? 0 : 1;
            }
            if (strength == 0) return;
            evidenceHeights.set(y - chunk.getMinY());
            int index = kind.ordinal();
            if (points[index] == 0) {
                lowest[index] = y;
                highest[index] = y;
            }
            points[index] = Math.min(MAX_SCORE_PER_KIND, points[index] + strength);
            lowest[index] = Math.min(lowest[index], y);
            highest[index] = Math.max(highest[index], y);
        }

        private boolean hasConstructedSupport(int x, int y, int z) {
            // Read only this chunk; unknown support across an unloaded boundary is not evidence.
            return (x > 0 && constructed(chunk.getBlockState(neighbor.set(pos.getMinBlockX() + x - 1, y, pos.getMinBlockZ() + z))))
                    || (x < 15 && constructed(chunk.getBlockState(neighbor.set(pos.getMinBlockX() + x + 1, y, pos.getMinBlockZ() + z))))
                    || (z > 0 && constructed(chunk.getBlockState(neighbor.set(pos.getMinBlockX() + x, y, pos.getMinBlockZ() + z - 1))))
                    || (z < 15 && constructed(chunk.getBlockState(neighbor.set(pos.getMinBlockX() + x, y, pos.getMinBlockZ() + z + 1))));
        }

        private static boolean constructed(BlockState state) {
            return state.is(BlockTags.PLANKS) || state.is(Blocks.BRICKS) || state.is(Blocks.STONE_BRICKS)
                    || state.is(Blocks.GLASS) || state.is(Blocks.SMOOTH_STONE)
                    || state.is(Blocks.POLISHED_ANDESITE) || state.is(Blocks.POLISHED_DIORITE)
                    || state.is(Blocks.POLISHED_GRANITE);
        }
    }
}
