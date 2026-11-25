package dev.donut.client.combat;

import dev.donut.client.autobuild.AutoBuilderController;
import dev.donut.client.config.CrystalAuraSettings;
import dev.donut.client.freecam.FreecamController;
import dev.donut.client.spectrebuilder.BuilderPlacement;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Repeats crystal interactions at the player's aim using the held Use input. */
final class CrystalAuraManual {
    private boolean useRequested;
    private Placement placement;
    private EndCrystal lastAttacked;
    private long retryBreakAt;
    private long nextPlace;
    private long nextBreak;

    boolean requestUse(Minecraft client) {
        if (aimedTarget(client) == null) return false;
        useRequested = true;
        return true;
    }

    boolean tick(Minecraft client, CrystalAuraSettings settings, long tick) {
        boolean using = useRequested || client.options.keyUse.isDown();
        clearUseRequest();
        if (!using) {
            if (placement != null && placement.handIsEmpty(client.player)) placement = null;
            return false;
        }
        if (placement != null) {
            if (crystalAt(client.level, placement.base()) != null) placement = placement.confirmed();
            else if (tick >= placement.retryAt()) placement = null;
        }
        Aim aim = aimedTarget(client);
        if (aim == null) return false;
        if (aim.crystal() != null) breakCrystal(client, settings, tick, aim);
        else if (aim.hand() != null) placeCrystal(client, settings, tick, aim);
        return true;
    }

    void clearUseRequest() {
        useRequested = false;
    }

    void reset() {
        clearUseRequest();
        placement = null;
        lastAttacked = null;
        retryBreakAt = nextPlace = nextBreak = 0;
    }

    private Aim aimedTarget(Minecraft client) {
        LocalPlayer player = client.player;
        if (!canAct(client, player)) return null;
        InteractionHand hand = crystalHand(player);
        // A depleted stack can finish its last placement while Use is still held.
        if (hand == null && (placement == null || !client.options.keyUse.isDown() || !placement.handIsEmpty(player))) {
            return null;
        }
        HitResult hit = player.raycastHitResult(1.0F, player);
        Aim aim;
        if (hit instanceof EntityHitResult entity && entity.getEntity() instanceof EndCrystal crystal && crystal.isAlive()) {
            aim = new Aim(null, crystal, hand);
        } else if (hit instanceof BlockHitResult block && block.getType() == HitResult.Type.BLOCK
                && isCrystalBase(client.level.getBlockState(block.getBlockPos()))) {
            aim = new Aim(block, crystalAt(client.level, block.getBlockPos()), hand);
        } else {
            return null;
        }
        return hand != null || placement.base().equals(aim.base()) ? aim : null;
    }

    private void placeCrystal(Minecraft client, CrystalAuraSettings settings, long tick, Aim aim) {
        BlockPos base = aim.base();
        BlockPos above = base.above();
        var level = client.level;
        if (tick < nextPlace || placement != null && tick < placement.retryAt()
                || level.isOutsideBuildHeight(above) || !level.getWorldBorder().isWithinBounds(base)
                || !level.isEmptyBlock(above) || !level.getEntities((Entity) null, crystalSpace(base)).isEmpty()) return;

        LocalPlayer player = client.player;
        var result = client.gameMode.useItemOn(player, aim.hand(), aim.block());
        if (result instanceof InteractionResult.Success success && success.swingSource() == InteractionResult.SwingSource.CLIENT) {
            player.swing(aim.hand());
        }
        nextPlace = tick + settings.placeDelay;
        long retryAt = tick + confirmationTicks(client);
        if (result.consumesAction()) {
            placement = new Placement(base, aim.hand(), player.getInventory().getSelectedSlot(), retryAt);
        } else {
            nextPlace = Math.max(nextPlace, retryAt);
        }
    }

    private void breakCrystal(Minecraft client, CrystalAuraSettings settings, long tick, Aim aim) {
        EndCrystal crystal = aim.crystal();
        LocalPlayer player = client.player;
        if (tick < nextBreak || crystal == lastAttacked && tick < retryBreakAt
                || !canBreakWithMainHand(player)) return;
        // When the crosshair still hits the base, restrict the break to its own visible crystal.
        if (aim.block() != null && !isCrystalVisible(player, crystal)) return;

        client.gameMode.attack(player, crystal);
        player.swing(InteractionHand.MAIN_HAND);
        lastAttacked = crystal;
        retryBreakAt = tick + confirmationTicks(client);
        nextBreak = tick + settings.breakDelay;
    }

    private static boolean canAct(Minecraft client, LocalPlayer player) {
        return player != null && client.level != null && client.gameMode != null && client.getCameraEntity() == player
                && client.screen == null && !client.isPaused() && client.isWindowActive() && player.isAlive()
                && !player.isSpectator() && !player.isHandsBusy() && !player.isUsingItem() && !client.gameMode.isDestroying()
                && !client.options.keyAttack.isDown() && !FreecamController.enabled()
                && !AutoBuilderController.get().isRunning() && !BuilderPlacement.placing();
    }

    private static InteractionHand crystalHand(LocalPlayer player) {
        if (player.getMainHandItem().is(Items.END_CRYSTAL)) return InteractionHand.MAIN_HAND;
        return player.getOffhandItem().is(Items.END_CRYSTAL) ? InteractionHand.OFF_HAND : null;
    }

    private static EndCrystal crystalAt(ClientLevel level, BlockPos base) {
        BlockPos above = base.above();
        var crystals = level.getEntitiesOfClass(EndCrystal.class, crystalSpace(base),
                crystal -> crystal.isAlive() && crystal.blockPosition().equals(above));
        return crystals.isEmpty() ? null : crystals.getFirst();
    }

    private static boolean isCrystalBase(BlockState state) {
        return state.is(Blocks.OBSIDIAN) || state.is(Blocks.BEDROCK);
    }

    private static AABB crystalSpace(BlockPos base) {
        return new AABB(base.above()).expandTowards(0, 1, 0);
    }

    private static int confirmationTicks(Minecraft client) {
        var connection = client.getConnection();
        var info = connection == null ? null : connection.getPlayerInfo(client.player.getUUID());
        int latency = info == null ? 0 : Math.max(0, info.getLatency());
        return Math.clamp(3 + latency / 50, 3, 20);
    }

    private static boolean canBreakWithMainHand(LocalPlayer player) {
        var weakness = player.getEffect(MobEffects.WEAKNESS);
        if (weakness == null || player.isCreative()) return true;
        var modifiers = player.getMainHandItem().getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY);
        double attack = modifiers.compute(Attributes.ATTACK_DAMAGE,
                player.getAttributeBaseValue(Attributes.ATTACK_DAMAGE), EquipmentSlot.MAINHAND);
        var strength = player.getEffect(MobEffects.STRENGTH);
        if (strength != null) attack += 3 * (strength.getAmplifier() + 1);
        return attack > 4 * (weakness.getAmplifier() + 1);
    }

    private static boolean isCrystalVisible(LocalPlayer player, EndCrystal crystal) {
        Vec3 eye = player.getEyePosition();
        double reach = player.entityInteractionRange();
        AABB box = crystal.getBoundingBox();
        if (box.distanceToSqr(eye) > reach * reach) return false;
        Vec3 center = box.getCenter();
        // The center often sits behind a block face while an edge of the crystal is exposed.
        Vec3[] samples = {center, new Vec3(center.x, box.maxY - 0.15, center.z), new Vec3(center.x, box.minY + 0.15, center.z),
                new Vec3(box.minX + 0.15, center.y, center.z), new Vec3(box.maxX - 0.15, center.y, center.z),
                new Vec3(center.x, center.y, box.minZ + 0.15), new Vec3(center.x, center.y, box.maxZ - 0.15)};
        for (Vec3 sample : samples) {
            Vec3 end = eye.add(sample.subtract(eye).normalize().scale(reach));
            Vec3 visibleEnd = player.level().clip(
                    new ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player)).getLocation();
            if (!box.contains(eye) && box.clip(eye, visibleEnd).isEmpty()) continue;
            AABB search = player.getBoundingBox().expandTowards(visibleEnd.subtract(eye)).inflate(1.0);
            EntityHitResult hit = ProjectileUtil.getEntityHitResult(player, eye, visibleEnd, search,
                    entity -> !entity.isSpectator() && entity.isPickable(), eye.distanceToSqr(visibleEnd));
            if (hit != null && hit.getEntity() == crystal) return true;
        }
        return false;
    }

    private record Aim(BlockHitResult block, EndCrystal crystal, InteractionHand hand) {
        BlockPos base() {
            return block == null ? crystal.blockPosition().below() : block.getBlockPos();
        }
    }

    private record Placement(BlockPos base, InteractionHand hand, int slot, long retryAt) {
        Placement confirmed() {
            return retryAt == 0 ? this : new Placement(base, hand, slot, 0);
        }

        boolean handIsEmpty(LocalPlayer player) {
            return player.getItemInHand(hand).isEmpty()
                    && (hand == InteractionHand.OFF_HAND || player.getInventory().getSelectedSlot() == slot);
        }
    }
}
