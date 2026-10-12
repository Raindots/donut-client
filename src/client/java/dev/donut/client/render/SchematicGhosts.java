package dev.donut.client.render;

import dev.donut.client.autobuild.AutoBuilderController;
import dev.donut.client.autobuild.PlacementTransform;
import dev.donut.client.autobuild.Schematic;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;

/** Index geometry off-thread; extraction visits nearby columns instead of entire large schematics. */
final class SchematicGhosts {
    record Ghost(BlockPos pos, BlockState state) { }
    private record Index(Schematic schematic, PlacementTransform transform, Map<Long, List<Ghost>> columns) { }
    private CompletableFuture<Index> pending;
    private Index current;
    private Schematic requested;
    private PlacementTransform requestedTransform;
    private final AtomicInteger generation = new AtomicInteger();

    List<Ghost> nearby(Vec3 camera) {
        AutoBuilderController builder = AutoBuilderController.get();
        Schematic schematic = builder.schematic();
        if (!builder.previewVisible() || schematic == null) {
            current = null;
            requested = null;
            if (pending != null) { generation.incrementAndGet(); pending.cancel(false); }
            pending = null;
            return List.of();
        }
        PlacementTransform transform = builder.transform();
        if (schematic != requested || !transform.equals(requestedTransform)) {
            if (pending != null) pending.cancel(false);
            requested = schematic;
            requestedTransform = transform;
            current = null;
            int request = generation.incrementAndGet();
            pending = CompletableFuture.supplyAsync(() -> index(schematic, transform, request));
        }
        if (pending != null && pending.isDone()) {
            if (!pending.isCompletedExceptionally()) current = pending.join();
            pending = null;
        }
        if (current == null || current.schematic != schematic || !current.transform.equals(transform)) return List.of();
        int cx = ((int) Math.floor(camera.x)) >> 4;
        int cz = ((int) Math.floor(camera.z)) >> 4;
        List<Ghost> visible = new ArrayList<>();
        // Near columns first so the geometry budget cannot hide nearby placement targets.
        List<ChunkPos> columns = new ArrayList<>(169);
        for (int x = cx - 6; x <= cx + 6; x++)
            for (int z = cz - 6; z <= cz + 6; z++) columns.add(new ChunkPos(x, z));
        columns.sort(Comparator.comparingInt(p -> (p.x - cx) * (p.x - cx) + (p.z - cz) * (p.z - cz)));
        for (ChunkPos column : columns) {
            for (Ghost ghost : current.columns.getOrDefault(column.toLong(), List.of())) {
                if (Vec3.atCenterOf(ghost.pos).distanceToSqr(camera) > 96 * 96) continue;
                visible.add(ghost);
                if (visible.size() >= 8192) return visible;
            }
        }
        return visible;
    }

    private Index index(Schematic schematic, PlacementTransform transform, int request) {
        Map<Long, List<Ghost>> columns = new HashMap<>();
        for (Schematic.Block block : schematic.blocks()) {
            if (request != generation.get()) throw new CancellationException();
            BlockPos pos = transform.apply(block.pos());
            columns.computeIfAbsent(ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4), key -> new ArrayList<>())
                    .add(new Ghost(pos, transform.apply(block.state())));
        }
        return new Index(schematic, transform, columns);
    }
}
