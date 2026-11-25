package dev.donut.client.combat;

import dev.donut.client.autobuild.AutoBuilderController;
import dev.donut.client.config.ClientConfig;
import dev.donut.client.config.TriggerbotSettings;
import dev.donut.client.freecam.FreecamController;
import dev.donut.client.mixin.KeyMappingAccessor;
import dev.donut.client.spectrebuilder.BuilderPlacement;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.concurrent.ThreadLocalRandom;

public final class TriggerbotController {
    private static final int MIN_ATTACK_GAP = 2;
    private static final int SMART_CRIT_WAIT = 12;
    private static int manualAttackSerial;

    private final ClientConfig config;
    private ClientLevel level;
    private LocalPlayer player;
    private LivingEntity trackedTarget;
    private int seenManualAttack = manualAttackSerial;
    private long tick;
    private long targetReady;
    private long chargeReady = -1;
    private long airborneSince = -1;
    private long lastAttack = -MIN_ATTACK_GAP;

    public TriggerbotController(ClientConfig config) {
        this.config = config;
    }

    public static void noteManualAttack() {
        manualAttackSerial++;
    }

    public void tick(Minecraft client, boolean combatBusy) {
        var settings = config.triggerbot;
        boolean manualAttack = seenManualAttack != manualAttackSerial;
        seenManualAttack = manualAttackSerial;
        if (client.player != player || client.level != level) {
            clearTiming();
            player = client.player;
            level = client.level;
            tick = 0;
            lastAttack = -MIN_ATTACK_GAP;
        }
        tick++;
        if (!settings.enabled || combatBusy || !canAct(client, player)) {
            clearTiming();
            return;
        }

        if (player.getAttackStrengthScale(0.5F) < 1.0F) chargeReady = -1;
        else if (chargeReady < 0) chargeReady = tick + jitter(settings);
        if (player.onGround()) airborneSince = -1;
        else if (airborneSince < 0) airborneSince = tick;

        // Use the vanilla crosshair raycast at the current position after entity movement.
        client.gameRenderer.pick(1.0F);
        if (!(client.hitResult instanceof EntityHitResult hit) || !(hit.getEntity() instanceof LivingEntity target)
                || !isTarget(player, target, settings) || !clearLine(player, hit.getLocation())) {
            trackedTarget = null;
            return;
        }
        if (target != trackedTarget) {
            trackedTarget = target;
            targetReady = tick + settings.reaction + jitter(settings);
        }
        if (manualAttack || ((KeyMappingAccessor) client.options.keyAttack).donut$queuedClicks() > 0
                || client.missTime > 0 || tick < targetReady || chargeReady < 0 || tick < chargeReady
                || tick - lastAttack < MIN_ATTACK_GAP || settings.holdAttack && !client.options.keyAttack.isDown()) return;

        ItemStack held = player.getMainHandItem();
        if (!weaponAllowed(held, settings) || held.has(DataComponents.PIERCING_WEAPON)
                || !held.isItemEnabled(level.enabledFeatures()) || player.cannotAttackWithItem(held, 0)
                || !inReach(player, hit, held) || !critAllows(player, settings.crits)) return;
        if (settings.skipShielding && !disablesShield(held) && shieldBlocks(target, player)) return;

        client.gameMode.attack(player, target);
        player.swing(InteractionHand.MAIN_HAND);
        lastAttack = tick;
        chargeReady = -1;
    }

    public void disconnect() {
        config.triggerbot.enabled = false;
        player = null;
        level = null;
        clearTiming();
    }

    private void clearTiming() {
        trackedTarget = null;
        chargeReady = airborneSince = -1;
    }

    private boolean isTarget(LocalPlayer player, LivingEntity target, TriggerbotSettings settings) {
        if (target == player || target == player.getVehicle() || target instanceof ArmorStand || !target.isAlive()
                || !target.isAttackable() || target.isInvisible() && !settings.invisible) return false;
        if (target instanceof Player other) {
            return settings.players && !other.isCreative() && !other.isSpectator()
                    && (!settings.skipFriends || !player.isAlliedTo(other) && !config.crystalAura.isFriend(other.getGameProfile().name()));
        }
        return target instanceof Enemy ? settings.hostiles : settings.passives;
    }

    private static boolean weaponAllowed(ItemStack held, TriggerbotSettings settings) {
        return settings.anyItem || settings.swords && held.is(ItemTags.SWORDS)
                || settings.axes && held.is(ItemTags.AXES) || settings.mace && held.is(Items.MACE);
    }

    private static boolean inReach(LocalPlayer player, EntityHitResult hit, ItemStack held) {
        var range = held.get(DataComponents.ATTACK_RANGE);
        return range == null ? player.isWithinEntityInteractionRange(hit.getEntity().getBoundingBox(), 0)
                : range.isInRange(player, hit.getLocation());
    }

    private static boolean clearLine(LocalPlayer player, Vec3 point) {
        var eye = player.getEyePosition();
        var block = player.level().clip(new ClipContext(eye, point, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        return block.getType() == HitResult.Type.MISS || eye.distanceToSqr(block.getLocation()) >= eye.distanceToSqr(point) - 0.001;
    }

    private boolean critAllows(LocalPlayer player, TriggerbotSettings.Crits mode) {
        if (mode == TriggerbotSettings.Crits.OFF) return true;
        boolean stance = !player.onGround() && !player.onClimbable() && !player.isInWater()
                && !player.isMobilityRestricted() && !player.isPassenger() && !player.isSprinting();
        boolean critical = stance && player.fallDistance > 0;
        return mode == TriggerbotSettings.Crits.ONLY ? critical
                : critical || !stance || airborneSince >= 0 && tick - airborneSince >= SMART_CRIT_WAIT;
    }

    private static boolean shieldBlocks(LivingEntity target, LocalPlayer attacker) {
        ItemStack blocking = target.getItemBlockingWith();
        if (blocking == null) return false;
        var shield = blocking.get(DataComponents.BLOCKS_ATTACKS);
        if (shield == null) return false;
        var damage = attacker.level().damageSources().playerAttack(attacker);
        if (shield.bypassedBy().map(damage::is).orElse(false)) return false;
        Vec3 towardAttacker = attacker.position().subtract(target.position()).multiply(1, 0, 1).normalize();
        Vec3 facing = target.calculateViewVector(0, target.getYHeadRot());
        return shield.resolveBlockedDamage(damage, 1, Math.acos(Mth.clamp(towardAttacker.dot(facing), -1.0, 1.0))) > 0;
    }

    private static boolean disablesShield(ItemStack held) {
        var weapon = held.get(DataComponents.WEAPON);
        return weapon != null && weapon.disableBlockingForSeconds() > 0;
    }

    private static boolean canAct(Minecraft client, LocalPlayer player) {
        return player != null && client.level != null && client.gameMode != null && client.screen == null
                && !client.isPaused() && client.isWindowActive() && client.getCameraEntity() == player
                && player.isAlive() && !player.isSpectator() && !player.isUsingItem() && !player.isHandsBusy()
                && !client.gameMode.isDestroying() && !FreecamController.enabled() && !AutoBuilderController.get().isRunning()
                && !BuilderPlacement.placing();
    }

    private static int jitter(TriggerbotSettings settings) {
        return settings.jitter > 0 ? ThreadLocalRandom.current().nextInt(settings.jitter + 1) : 0;
    }
}
