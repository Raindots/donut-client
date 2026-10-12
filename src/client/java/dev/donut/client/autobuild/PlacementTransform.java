package dev.donut.client.autobuild;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;

/** Mirror local X, rotate clockwise around local zero, then translate to the world. */
public record PlacementTransform(BlockPos origin, int quarterTurns, boolean mirrorX) {
    public PlacementTransform {
        origin = origin.immutable();
        quarterTurns = Math.floorMod(quarterTurns, 4);
    }

    public BlockPos apply(BlockPos local) {
        int x = mirrorX ? -local.getX() : local.getX();
        int z = local.getZ();
        return switch (quarterTurns) {
            case 1 -> origin.offset(-z, local.getY(), x);
            case 2 -> origin.offset(-x, local.getY(), -z);
            case 3 -> origin.offset(z, local.getY(), -x);
            default -> origin.offset(x, local.getY(), z);
        };
    }

    public BlockState apply(BlockState state) {
        if (mirrorX) state = state.mirror(Mirror.FRONT_BACK);
        return state.rotate(switch (quarterTurns) {
            case 1 -> Rotation.CLOCKWISE_90;
            case 2 -> Rotation.CLOCKWISE_180;
            case 3 -> Rotation.COUNTERCLOCKWISE_90;
            default -> Rotation.NONE;
        });
    }
}
