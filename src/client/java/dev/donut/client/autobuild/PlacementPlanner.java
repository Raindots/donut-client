package dev.donut.client.autobuild;

import dev.donut.client.mixin.BlockItemAccessor;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.core.*;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.*;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.*;
import net.minecraft.world.phys.*;

/** Evaluates the same placement context as vanilla before sending an interaction. */
public final class PlacementPlanner {
    public record Plan(BlockHitResult hit, BlockState prediction, float yaw, float pitch, boolean configure) { }
    private static final Set<String> DERIVED = Set.of("powered", "power", "lit", "locked", "in_wall", "snowy",
            "distance", "north", "east", "south", "west", "up", "down");
    private PlacementPlanner() { }

    public static boolean compatible(BlockState desired, BlockState predicted) {
        if (predicted == null || desired.getBlock() != predicted.getBlock()) return false;
        for (Property<?> property : desired.getProperties()) {
            if (DERIVED.contains(property.getName())) continue;
            if (property == BlockStateProperties.STAIRS_SHAPE || property == BlockStateProperties.CHEST_TYPE) continue;
            if (isConfigurable(desired, property)) continue;
            if (property == SlabBlock.TYPE && desired.getValue(SlabBlock.TYPE) == SlabType.DOUBLE) continue;
            if (isStackCount(property) && (Integer)predicted.getValue(property) <= (Integer)desired.getValue(property)) continue;
            if (!desired.getValue(property).equals(predicted.getValue(property))) return false;
        }
        return true;
    }

    private static boolean isStackCount(Property<?> property) {
        return property == BlockStateProperties.LAYERS || property == BlockStateProperties.CANDLES
                || property == BlockStateProperties.PICKLES || property == BlockStateProperties.EGGS
                || property == BlockStateProperties.FLOWER_AMOUNT || property == BlockStateProperties.SEGMENT_AMOUNT;
    }
    private static boolean isConfigurable(BlockState state, Property<?> property) {
        return (state.getBlock() instanceof RepeaterBlock && property == BlockStateProperties.DELAY)
                || (state.getBlock() instanceof ComparatorBlock && property == BlockStateProperties.MODE_COMPARATOR)
                || (state.getBlock() instanceof NoteBlock && property == BlockStateProperties.NOTE);
    }
    public static boolean needsConfiguration(BlockState expected, BlockState actual) {
        if (expected.getBlock() != actual.getBlock()) return false;
        for (Property<?> property : expected.getProperties()) {
            if (isConfigurable(expected, property) && !expected.getValue(property).equals(actual.getValue(property))) return true;
        }
        return false;
    }
    public static boolean canAdd(BlockState expected, BlockState actual) {
        if (actual.canBeReplaced()) return true;
        if (expected.getBlock() != actual.getBlock()) return false;
        if (expected.hasProperty(SlabBlock.TYPE)) return expected.getValue(SlabBlock.TYPE) == SlabType.DOUBLE
                && actual.getValue(SlabBlock.TYPE) != SlabType.DOUBLE;
        for (Property<?> property : expected.getProperties()) {
            if (isStackCount(property) && (Integer)expected.getValue(property) > (Integer)actual.getValue(property)) return true;
        }
        return false;
    }

    public static Plan plan(Minecraft client, BlockPos target, BlockState desired, ItemStack stack) {
        if (client.level == null || client.player == null) return null;
        BlockState actual = client.level.getBlockState(target);
        boolean configure = needsConfiguration(desired, actual);
        if (!configure && !(stack.getItem() instanceof BlockItem)) return null;
        List<BlockHitResult> hits = new ArrayList<>();
        if (!actual.isAir() && actual.getBlock() == desired.getBlock()) {
            addFaces(hits, target, actual, client);
        } else {
            for (Direction face : new Direction[]{Direction.UP,Direction.NORTH,Direction.SOUTH,Direction.WEST,Direction.EAST,Direction.DOWN}) {
                BlockPos support = target.relative(face.getOpposite());
                if (!MaterialTracker.isLoaded(client.level,support)) continue;
                BlockState supportState = client.level.getBlockState(support);
                if (supportState.isAir() || supportState.canBeReplaced() || supportState.getCollisionShape(client.level,support).isEmpty()) continue;
                addFace(hits, support, face, supportState, client);
            }
        }
        Vec3 eye = client.player.getEyePosition();
        double reach = client.player.blockInteractionRange() - .05;
        float oldYaw = client.player.getYRot(), oldPitch = client.player.getXRot();
        try {
            for (BlockHitResult hit : hits) {
                Vec3 point = hit.getLocation();
                if (eye.distanceToSqr(point) > reach * reach) continue;
                Vec3 inside = point.subtract(Vec3.atLowerCornerOf(hit.getDirection().getUnitVec3i()).scale(.002));
                BlockHitResult visible = client.level.clip(new ClipContext(eye,inside,ClipContext.Block.OUTLINE,ClipContext.Fluid.NONE,client.player));
                if (visible.getType() != HitResult.Type.BLOCK || !visible.getBlockPos().equals(hit.getBlockPos())
                        || visible.getDirection() != hit.getDirection()) continue;
                Vec3 delta = point.subtract(eye);
                float yaw = (float)Math.toDegrees(Math.atan2(-delta.x,delta.z));
                float pitch = (float)Math.toDegrees(Math.atan2(-delta.y,Math.hypot(delta.x,delta.z)));
                client.player.setYRot(yaw); client.player.setXRot(pitch);
                if (configure) {
                    BlockState predicted = actual;
                    if (actual.getBlock() instanceof RepeaterBlock) predicted = actual.cycle(BlockStateProperties.DELAY);
                    else if (actual.getBlock() instanceof ComparatorBlock) predicted = actual.cycle(BlockStateProperties.MODE_COMPARATOR);
                    else if (actual.getBlock() instanceof NoteBlock) predicted = actual.cycle(BlockStateProperties.NOTE);
                    return new Plan(hit,predicted,yaw,pitch,true);
                }
                BlockItem item = (BlockItem)stack.getItem();
                BlockPlaceContext context = item.updatePlacementContext(new BlockPlaceContext(client.player,InteractionHand.MAIN_HAND,stack,hit));
                if (context == null || !context.getClickedPos().equals(target) || !context.canPlace()) continue;
                BlockState predicted = ((BlockItemAccessor)item).donut$getPlacementState(context);
                if (compatible(desired,predicted)) return new Plan(hit,predicted,yaw,pitch,false);
            }
        } finally { client.player.setYRot(oldYaw); client.player.setXRot(oldPitch); }
        return null;
    }
    private static void addFaces(List<BlockHitResult> hits,BlockPos pos,BlockState state,Minecraft client) {
        for (Direction face : Direction.values()) addFace(hits,pos,face,state,client);
    }
    private static void addFace(List<BlockHitResult> hits,BlockPos pos,Direction face,BlockState state,Minecraft client) {
        var shape = state.getShape(client.level,pos);
        if (shape.isEmpty()) return;
        AABB bounds = shape.bounds();
        for (double level : new double[]{.5,.25,.75}) {
            double x=(bounds.minX+bounds.maxX)/2, y=bounds.minY+(bounds.maxY-bounds.minY)*level,z=(bounds.minZ+bounds.maxZ)/2;
            switch(face) {
                case UP -> y=bounds.maxY; case DOWN -> y=bounds.minY;
                case EAST -> x=bounds.maxX; case WEST -> x=bounds.minX;
                case SOUTH -> z=bounds.maxZ; case NORTH -> z=bounds.minZ;
            }
            hits.add(new BlockHitResult(new Vec3(pos.getX()+x,pos.getY()+y,pos.getZ()+z),face,pos,false));
        }
    }
}
