package dev.donut.client.combat;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

final class AnchorGuard {
    private static final int MIN_BLOCKED_SAMPLES = 7;
    private static final double FACE_INSET = 0.002;
    private static final Direction[] SUPPORT_FACES = {
        Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST, Direction.DOWN
    };

    private AnchorGuard() {}

    static Spot find(ClientLevel level, LocalPlayer player, BlockPos anchor, Vec3 anchorAim) {
        Vec3 eye = player.getEyePosition();
        Vec3 blast = Vec3.atCenterOf(anchor);
        AABB body = player.getBoundingBox();
        BlockPos feet = player.blockPosition();
        Spot best = null;
        int bestShade = MIN_BLOCKED_SAMPLES - 1;
        double bestDistance = Double.POSITIVE_INFINITY;

        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                if (x == 0 && z == 0) continue;
                BlockPos position = feet.offset(x, 0, z);
                AABB box = new AABB(position);
                if (position.equals(anchor) || box.intersects(body) || box.clip(eye, anchorAim).isPresent()) continue;

                int shade = shade(box, body, blast);
                double distance = player.position().distanceToSqr(box.getCenter());
                if (shade < MIN_BLOCKED_SAMPLES || shade < bestShade || shade == bestShade && distance >= bestDistance) continue;

                BlockState state = level.getBlockState(position);
                Spot spot = state.isCollisionShapeFullBlock(level, position)
                        ? new Spot(position, null, null, null)
                        : placement(level, player, position, state);
                if (spot != null) {
                    best = spot;
                    bestShade = shade;
                    bestDistance = distance;
                }
            }
        }
        return best;
    }

    static boolean isGuardBlock(ItemStack stack) {
        if (!(stack.getItem() instanceof BlockItem item)) return false;
        var block = item.getBlock();
        return !(block instanceof EntityBlock) && !(block instanceof FallingBlock)
                && block != Blocks.RESPAWN_ANCHOR && block != Blocks.TNT
                && block.defaultBlockState().isCollisionShapeFullBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
    }

    static BlockHitResult currentHit(LocalPlayer player, Spot spot) {
        ItemStack stack = player.getMainHandItem();
        if (spot.existing() || !isGuardBlock(stack)) return null;
        Level level = player.level();
        if (!canFill(level, player, spot.position(), level.getBlockState(spot.position()))
                || spot.face() != null && !canUseSupport(level, spot.support(), spot.face())) return null;
        Vec3 eye = player.getEyePosition();
        Vec3 end = eye.add(player.getLookAngle().scale(player.blockInteractionRange()));
        BlockHitResult hit = trace(player, end);
        return validHit(player, spot, hit, stack) ? hit : null;
    }

    private static Spot placement(ClientLevel level, LocalPlayer player, BlockPos position, BlockState state) {
        if (!canFill(level, player, position, state)) return null;

        ItemStack sample = new ItemStack(Items.OBSIDIAN);
        var targetOutline = state.getShape(level, position);
        if (!targetOutline.isEmpty()) {
            Spot direct = new Spot(position, targetOutline.bounds().move(position).getCenter(), position, null);
            if (canAimAt(player, direct, sample)) return direct;
        }

        for (Direction face : SUPPORT_FACES) {
            BlockPos support = position.relative(face.getOpposite());
            if (!canUseSupport(level, support, face)) continue;
            var outline = level.getBlockState(support).getShape(level, support);
            if (outline.isEmpty()) continue;

            AABB box = outline.bounds().move(support);
            Direction.Axis axis = face.getAxis();
            boolean positive = face.getAxisDirection() == Direction.AxisDirection.POSITIVE;
            double coordinate = positive ? box.max(axis) - FACE_INSET : box.min(axis) + FACE_INSET;
            Spot supported = new Spot(position, box.getCenter().with(axis, coordinate), support, face);
            if (canAimAt(player, supported, sample)) return supported;
        }
        return null;
    }

    private static boolean canFill(Level level, LocalPlayer player, BlockPos position, BlockState state) {
        return state.canBeReplaced() && level.getWorldBorder().isWithinBounds(position)
                && level.isUnobstructed(Blocks.OBSIDIAN.defaultBlockState(), position, CollisionContext.of(player));
    }

    private static boolean canUseSupport(Level level, BlockPos position, Direction face) {
        BlockState state = level.getBlockState(position);
        return !state.canBeReplaced() && !state.hasBlockEntity() && !state.is(Blocks.RESPAWN_ANCHOR)
                && !state.is(Blocks.NOTE_BLOCK) && !state.is(Blocks.DRAGON_EGG)
                && state.getMenuProvider(level, position) == null && state.isFaceSturdy(level, position, face);
    }

    private static boolean canAimAt(LocalPlayer player, Spot spot, ItemStack stack) {
        double reach = player.blockInteractionRange();
        return player.getEyePosition().distanceToSqr(spot.aim()) <= reach * reach
                && validHit(player, spot, trace(player, spot.aim()), stack);
    }

    private static boolean validHit(LocalPlayer player, Spot spot, BlockHitResult hit, ItemStack stack) {
        if (hit.getType() != HitResult.Type.BLOCK || !spot.support().equals(hit.getBlockPos())
                || spot.face() != null && spot.face() != hit.getDirection()) return false;
        var context = new BlockPlaceContext(player, InteractionHand.MAIN_HAND, stack, hit);
        if (!context.canPlace() || !context.getClickedPos().equals(spot.position())) return false;

        Vec3 eye = player.getEyePosition();
        Vec3 direction = hit.getLocation().subtract(eye);
        AABB search = player.getBoundingBox().expandTowards(direction).inflate(1);
        return ProjectileUtil.getEntityHitResult(player, eye, hit.getLocation(), search,
                entity -> !entity.isSpectator() && entity.isPickable(), direction.lengthSqr()) == null;
    }

    private static BlockHitResult trace(LocalPlayer player, Vec3 end) {
        return player.level().clip(new ClipContext(player.getEyePosition(), end,
                ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
    }

    private static int shade(AABB cover, AABB body, Vec3 blast) {
        // Sample the body at 3 positions across each horizontal axis and 5 heights.
        int blocked = 0;
        for (int x = 0; x < 3; x++) {
            for (int y = 0; y < 5; y++) {
                for (int z = 0; z < 3; z++) {
                    Vec3 sample = new Vec3(Mth.lerp(x / 2.0, body.minX, body.maxX),
                            Mth.lerp(y / 4.0, body.minY, body.maxY), Mth.lerp(z / 2.0, body.minZ, body.maxZ));
                    if (cover.clip(blast, sample).isPresent()) blocked++;
                }
            }
        }
        return blocked;
    }

    record Spot(BlockPos position, Vec3 aim, BlockPos support, Direction face) {
        boolean existing() { return support == null; }
    }
}
