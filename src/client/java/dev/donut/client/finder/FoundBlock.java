package dev.donut.client.finder;

import net.minecraft.core.BlockPos;

public record FoundBlock(
        BlockPos pos,
        TargetKind kind,
        int chunkX,
        int chunkZ,
        double distanceSquared
) {
    public FoundBlock(BlockPos pos, TargetKind kind) {
        this(pos, kind, pos.getX() >> 4, pos.getZ() >> 4, 0.0);
    }
}
