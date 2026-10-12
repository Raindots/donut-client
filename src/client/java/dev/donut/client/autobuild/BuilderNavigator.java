package dev.donut.client.autobuild;

import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.*;

/** Walks using vanilla player input. Does not teleport, fly, mine, or modify the world. */
public final class BuilderNavigator {
    public enum Result { MOVING, ARRIVED, NO_PATH, STUCK }
    private LocalPlayer owner;
    private ClientInput saved;
    private final SteeringInput steering = new SteeringInput();
    private List<BuilderPathfinder.Cell> route = List.of();
    private int index, stalled;
    private Vec3 lastPosition;
    private BlockPos target;
    private final Set<BuilderPathfinder.Cell> rejectedGoals = new HashSet<>();

    private static final class SteeringInput extends ClientInput {
        boolean forward, jump, sneak;
        @Override public void tick() {
            keyPresses = new Input(forward, false, false, false, jump, sneak, false);
            moveVector = forward ? new Vec2(0, 1) : Vec2.ZERO;
        }
        @Override public void makeJump() { if (forward) super.makeJump(); }
    }

    public void hold(Minecraft client, boolean sneak) {
        if (client.player == null) return;
        if (owner != client.player) {
            release(); owner = client.player; saved = owner.input; owner.input = steering;
        }
        steering.forward = false; steering.jump = false; steering.sneak = sneak; steering.tick();
    }

    public void release() {
        if (owner != null && owner.input == steering && saved != null) owner.input = saved;
        owner = null; saved = null; steering.forward = false; steering.jump = false; steering.sneak = false;
        route = List.of(); index = 0; stalled = 0; lastPosition = null;
    }
    public void replan() { route = List.of(); index = 0; stalled = 0; lastPosition = null; }
    public void reset() { release(); target = null; rejectedGoals.clear(); }
    public void rejectCurrent(Minecraft client) {
        if (client.player != null) rejectedGoals.add(cell(client.player.blockPosition()));
        replan();
    }
    public int routeLength() { return Math.max(0, route.size() - index); }

    public Result moveTo(Minecraft client, BlockPos destination) {
        if (client.level == null || client.player == null) return Result.NO_PATH;
        hold(client, false);
        if (!destination.equals(target)) { target = destination.immutable(); rejectedGoals.clear(); replan(); }
        BuilderPathfinder.Cell feet = cell(client.player.blockPosition());
        if (goal(client, feet, destination)) return Result.ARRIVED;
        if (route.isEmpty() || index >= route.size()) {
            BuilderPathfinder.Terrain terrain = new BuilderPathfinder.Terrain() {
                @Override public boolean standable(BuilderPathfinder.Cell at) {
                    BlockPos pos = pos(at);
                    if (!MaterialTracker.isLoaded(client.level,pos) || !MaterialTracker.isLoaded(client.level,pos.above(2))) return false;
                    var ground = client.level.getBlockState(pos.below());
                    if (ground.is(Blocks.MAGMA_BLOCK) || ground.is(Blocks.CAMPFIRE) || ground.is(Blocks.SOUL_CAMPFIRE)
                            || ground.is(Blocks.CACTUS) || ground.is(Blocks.POWDER_SNOW)
                            || !ground.isCollisionShapeFullBlock(client.level, pos.below())) return false;
                    return headroom(at);
                }
                @Override public boolean headroom(BuilderPathfinder.Cell at) {
                    BlockPos pos = pos(at);
                    if (!MaterialTracker.isLoaded(client.level,pos) || !client.level.getFluidState(pos).isEmpty()
                            || !client.level.getFluidState(pos.above()).isEmpty()) return false;
                    var state = client.level.getBlockState(pos);
                    if (state.is(Blocks.FIRE) || state.is(Blocks.SOUL_FIRE) || state.is(Blocks.SWEET_BERRY_BUSH)
                            || state.is(Blocks.POWDER_SNOW)) return false;
                    return client.level.noCollision(client.player, new AABB(at.x()+.2, at.y()+.01, at.z()+.2,
                            at.x()+.8, at.y()+1.8, at.z()+.8));
                }
            };
            route = BuilderPathfinder.find(feet, cell(destination), terrain, at -> goal(client, at, destination), 6000, 96);
            index = 0;
            if (route.isEmpty()) return Result.NO_PATH;
        }
        BuilderPathfinder.Cell next = route.get(index);
        Vec3 aim = new Vec3(next.x()+.5, next.y(), next.z()+.5);
        if (client.player.position().distanceToSqr(aim) < .16) {
            index++;
            if (index >= route.size()) { replan(); return Result.MOVING; }
            next = route.get(index); aim = new Vec3(next.x()+.5, next.y(), next.z()+.5);
        }
        Vec3 delta = aim.subtract(client.player.position());
        client.player.setYRot((float)Math.toDegrees(Math.atan2(-delta.x, delta.z)));
        client.player.setXRot(10);
        steering.forward = true;
        steering.jump = next.y() > client.player.getY()+.25 && client.player.onGround();
        steering.tick();
        if (lastPosition != null && client.player.position().distanceToSqr(lastPosition) < .0004) stalled++;
        else stalled = 0;
        lastPosition = client.player.position();
        if (stalled > 30) { rejectCurrent(client); return Result.STUCK; }
        return Result.MOVING;
    }

    private boolean goal(Minecraft client, BuilderPathfinder.Cell at, BlockPos destination) {
        if (rejectedGoals.contains(at)) return false;
        Vec3 eye = new Vec3(at.x()+.5, at.y()+client.player.getEyeHeight(), at.z()+.5);
        // A block cannot be placed into the walking player's body.
        if (at.x()==destination.getX() && at.z()==destination.getZ()
                && at.y()<=destination.getY() && at.y()+1.8>destination.getY()) return false;
        // When the only usable neighbor is below, the top placement face must be viewed from above.
        // Center visibility alone would repeatedly choose ground cells below an elevated support column.
        if (client.level.getBlockState(destination).canBeReplaced() && eye.y<=destination.getY()+.01) {
            boolean alternativeFace=false;
            for (var direction:net.minecraft.core.Direction.values()) {
                if (direction==net.minecraft.core.Direction.DOWN) continue;
                BlockPos neighbor=destination.relative(direction);
                if (!MaterialTracker.isLoaded(client.level,neighbor)) continue;
                var state=client.level.getBlockState(neighbor);
                if (!state.canBeReplaced() && !state.getCollisionShape(client.level,neighbor).isEmpty()) { alternativeFace=true; break; }
            }
            if (!alternativeFace) return false;
        }
        double reach = Math.min(4.25, client.player.blockInteractionRange() - .25);
        if (eye.distanceToSqr(Vec3.atCenterOf(destination)) > reach * reach) return false;
        BlockHitResult hit = client.level.clip(new ClipContext(eye, Vec3.atCenterOf(destination),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, client.player));
        return hit.getType() == HitResult.Type.MISS || hit.getBlockPos().equals(destination);
    }
    private static BuilderPathfinder.Cell cell(BlockPos p) { return new BuilderPathfinder.Cell(p.getX(),p.getY(),p.getZ()); }
    private static BlockPos pos(BuilderPathfinder.Cell p) { return new BlockPos(p.x(),p.y(),p.z()); }
}
