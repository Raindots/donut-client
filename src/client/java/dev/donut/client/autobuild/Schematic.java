package dev.donut.client.autobuild;

import java.util.List;
import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.state.BlockState;

/** A validated, non-air schematic with coordinates relative to its minimum corner. */
public record Schematic(String name, List<Block> blocks, BlockPos dimensions, int regionCount,
                        List<String> warnings) {
    public Schematic {
        name = Objects.requireNonNull(name);
        blocks = List.copyOf(blocks);
        dimensions = dimensions.immutable();
        warnings = List.copyOf(warnings);
        if (dimensions.getX() < 1 || dimensions.getY() < 1 || dimensions.getZ() < 1 || regionCount < 1)
            throw new IllegalArgumentException("Schematic dimensions and region count must be positive");
        for (Block block : blocks) {
            BlockPos pos = block.pos();
            if (pos.getX() < 0 || pos.getY() < 0 || pos.getZ() < 0 || pos.getX() >= dimensions.getX()
                    || pos.getY() >= dimensions.getY() || pos.getZ() >= dimensions.getZ() || block.state().isAir())
                throw new IllegalArgumentException("Schematic blocks must be non-air and inside its bounds");
        }
    }

    public record Block(BlockPos pos, BlockState state, CompoundTag blockEntity) {
        public Block {
            pos = pos.immutable();
            Objects.requireNonNull(state);
            blockEntity = blockEntity == null ? null : blockEntity.copy();
        }
    }
}
