package dev.donut.client.combat;

import dev.donut.client.autobuild.AutoBuilderController;
import dev.donut.client.config.AnchorMacroSettings;
import dev.donut.client.config.ClientConfig;
import dev.donut.client.freecam.FreecamController;
import dev.donut.client.mixin.BuilderClientLevelAccessor;
import dev.donut.client.spectrebuilder.BuilderPlacement;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RespawnAnchorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.concurrent.ThreadLocalRandom;

public final class AnchorMacroController {
    private enum Step {
        IDLE, PLACE, WAIT_PLACE, SELECT_CHARGE, CHARGE, WAIT_CHARGE, SELECT_GUARD, PLACE_GUARD,
        WAIT_GUARD, SELECT_DETONATE, DETONATE, WAIT_CLEAR, NEXT_CYCLE, SWITCH_BACK
    }

    private static AnchorMacroController instance;

    private final ClientConfig config;
    private LocalPlayer player;
    private ClientLevel level;
    private Step step = Step.IDLE;
    private BlockPos anchor;
    private long tick;
    private long readyAt;
    private long lastUse = -1;
    private int previousSlot = -1;
    private int ownedSlot = -1;
    private int cycles;
    private boolean fastRefill;
    private boolean totemBusy;
    private boolean pendingRestore;
    private AnchorGuard.Spot guard;
    private Block guardBlock;
    private Look savedLook;
    private Look guardLook;
    private Pending pending;

    public AnchorMacroController(ClientConfig config) {
        this.config = config;
        instance = this;
    }

    public static boolean consumeUse(Minecraft client) {
        return instance != null && instance.request(client);
    }

    public boolean ownsInteraction() {
        return step != Step.IDLE || lastUse == tick;
    }

    private boolean request(Minecraft client) {
        var user = client.player;
        if (!config.anchorMacro.enabled || totemBusy || !canAct(client, user)) return false;
        if (step != Step.IDLE) return true;
        if (!(client.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) return false;

        var world = client.level;
        var inventory = user.getInventory();
        BlockState clicked = world.getBlockState(hit.getBlockPos());
        boolean existing = clicked.is(Blocks.RESPAWN_ANCHOR);
        boolean glowstone = find(inventory, Items.GLOWSTONE) >= 0;
        if (existing ? charge(clicked) == 0 && !glowstone
                : !user.getMainHandItem().is(Items.RESPAWN_ANCHOR) || !glowstone) return false;

        BlockPos target = existing ? hit.getBlockPos() : placementTarget(user, hit);
        if (target == null || !canExplode(world, target)) return false;
        player = user;
        level = world;
        anchor = target.immutable();
        previousSlot = inventory.getSelectedSlot();
        if (existing) targetExisting(clicked);
        else schedule(Step.PLACE, 0);
        // A held Use input starts one sequence. The next press can start another.
        client.options.keyUse.setDown(false);
        return true;
    }

    public void tick(Minecraft client, boolean inventoryBusy) {
        tick++;
        totemBusy = inventoryBusy;
        if (step == Step.IDLE) return;
        if (client.player != player || client.level != level || !player.isAlive()) {
            reset();
            return;
        }
        if (inventoryBusy) {
            restoreView();
            pendingRestore = true;
            return;
        }
        if (player.getInventory().getSelectedSlot() != expectedSlot()) {
            restoreView();
            reset();
            return;
        }
        if (pendingRestore || !config.anchorMacro.enabled || !canAct(client, player) || !canExplode(level, anchor)) {
            stop();
            return;
        }

        for (int i = 0; i < 5 && step != Step.IDLE && tick >= readyAt; i++) {
            Step previous = step;
            advance(client);
            if (step == previous || lastUse == tick) break;
        }
    }

    public void disconnect(Minecraft client) {
        if (client.player == player && player != null) stop();
        else reset();
        totemBusy = false;
        config.anchorMacro.enabled = false;
    }

    private void advance(Minecraft client) {
        var settings = config.anchorMacro;
        var inventory = player.getInventory();
        switch (step) {
            case PLACE -> place(client);
            case WAIT_PLACE -> awaitBlock(Step.SELECT_CHARGE, settings.switchDelay);
            case SELECT_CHARGE -> select(find(inventory, Items.GLOWSTONE), Step.CHARGE, settings.chargeDelay);
            case CHARGE -> chargeAnchor(client);
            case WAIT_CHARGE -> awaitBlock(afterCharge(), settings.switchDelay);
            case SELECT_GUARD -> selectGuard();
            case PLACE_GUARD -> placeGuard(client);
            case WAIT_GUARD -> awaitGuard();
            case SELECT_DETONATE -> {
                int anchorSlot = find(inventory, Items.RESPAWN_ANCHOR);
                fastRefill = cycles == 0 && settings.mode == AnchorMacroSettings.Mode.DOUBLE && settings.doubleAnchor
                        && anchorSlot >= 0 && find(inventory, Items.GLOWSTONE) >= 0;
                select(fastRefill ? anchorSlot : safetySlot(inventory), Step.DETONATE, settings.explodeDelay);
            }
            case DETONATE -> detonate(client);
            case WAIT_CLEAR -> {
                if (confirmed()) {
                    pending = null;
                    if (settings.mode == AnchorMacroSettings.Mode.DOUBLE && cycles < 2) {
                        schedule(Step.NEXT_CYCLE, fastRefill ? 0 : delay(settings.cycleDelay));
                    } else {
                        finish();
                    }
                } else if (tick >= pending.deadline) finish();
            }
            case NEXT_CYCLE -> nextCycle();
            case SWITCH_BACK -> stop();
            case IDLE -> { }
        }
    }

    private void place(Minecraft client) {
        BlockHitResult hit = crosshair(player);
        if (hit == null || !player.getMainHandItem().is(Items.RESPAWN_ANCHOR)
                || !anchor.equals(placementTarget(player, hit)) || find(player.getInventory(), Items.GLOWSTONE) < 0) {
            finish();
            return;
        }
        use(client, hit, anchor, Step.WAIT_PLACE, Blocks.RESPAWN_ANCHOR);
    }

    private void chargeAnchor(Minecraft client) {
        BlockHitResult hit = anchorHit();
        if (hit == null) {
            finish();
        } else if (charge(level.getBlockState(anchor)) > 0) {
            schedule(afterCharge(), delay(config.anchorMacro.switchDelay));
        } else if (player.getMainHandItem().is(Items.GLOWSTONE)) {
            use(client, hit, anchor, Step.WAIT_CHARGE, Blocks.RESPAWN_ANCHOR);
        } else {
            finish();
        }
    }

    private void detonate(Minecraft client) {
        BlockHitResult hit = anchorHit();
        if (hit == null || charge(level.getBlockState(anchor)) == 0 || player.getMainHandItem().is(Items.GLOWSTONE)) {
            finish();
            return;
        }
        cycles++;
        use(client, hit, anchor, Step.WAIT_CLEAR, null);
    }

    private void nextCycle() {
        BlockHitResult hit = crosshair(player);
        if (hit == null) {
            finish();
            return;
        }
        BlockState aimed = level.getBlockState(hit.getBlockPos());
        boolean existing = aimed.is(Blocks.RESPAWN_ANCHOR);
        BlockPos target = existing ? hit.getBlockPos() : placementTarget(player, hit);
        if (target == null || !canExplode(level, target)) {
            finish();
            return;
        }
        anchor = target.immutable();
        if (existing) targetExisting(aimed);
        else select(find(player.getInventory(), Items.RESPAWN_ANCHOR), Step.PLACE, config.anchorMacro.placeDelay);
    }

    private void selectGuard() {
        BlockHitResult hit = anchorHit();
        if (hit == null) {
            finish();
            return;
        }
        guard = AnchorGuard.find(level, player, anchor, hit.getLocation());
        if (guard != null && guard.existing()) {
            finishGuard(true);
            return;
        }
        int slot = guard == null ? -1 : guardSlot(player.getInventory());
        if (slot < 0) {
            finishGuard(false);
            return;
        }
        guardBlock = ((BlockItem) player.getInventory().getItem(slot).getItem()).getBlock();
        select(slot, Step.PLACE_GUARD, config.anchorMacro.placeDelay);
    }

    private void placeGuard(Minecraft client) {
        if (guardLook == null) {
            if (anchorHit() == null) {
                finish();
                return;
            }
            savedLook = Look.of(player);
            guardLook = Look.toward(player, guard.aim());
            guardLook.apply(player);
            readyAt = tick + 1;
            return;
        }
        if (!guardLook.matches(player)) {
            finish();
            return;
        }
        var held = player.getMainHandItem().getItem();
        BlockHitResult hit = AnchorGuard.currentHit(player, guard);
        if (hit == null || !(held instanceof BlockItem blockItem) || blockItem.getBlock() != guardBlock) {
            finishGuard(false);
            return;
        }
        use(client, hit, guard.position(), Step.WAIT_GUARD, guardBlock);
    }

    private void awaitGuard() {
        if (!guardLook.matches(player)) finish();
        else if (confirmed()) finishGuard(true);
        else if (tick >= pending.deadline) finishGuard(false);
    }

    private void finishGuard(boolean cover) {
        boolean lookedAway = savedLook != null;
        restoreView();
        guard = null;
        pending = null;
        if (cover || config.anchorMacro.ifUnguarded == AnchorMacroSettings.Unguarded.DETONATE) {
            schedule(Step.SELECT_DETONATE, lookedAway ? 1 : 0);
        } else {
            finish();
        }
    }

    private void restoreView() {
        if (savedLook != null && guardLook.matches(player)) savedLook.apply(player);
        savedLook = guardLook = null;
    }

    private void use(Minecraft client, BlockHitResult hit, BlockPos target, Step waiting, Block expected) {
        var prediction = ((BuilderClientLevelAccessor) level).donut$predictionHandler();
        int before = prediction.currentSequence();
        var result = client.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit);
        lastUse = tick;
        if (result instanceof InteractionResult.Success success && success.swingSource() == InteractionResult.SwingSource.CLIENT) {
            player.swing(InteractionHand.MAIN_HAND);
        }
        int sequence = prediction.currentSequence();
        if (sequence == before) {
            if (waiting == Step.WAIT_GUARD) finishGuard(false);
            else finish();
            return;
        }
        var info = client.getConnection() == null ? null : client.getConnection().getPlayerInfo(player.getUUID());
        int latency = info == null ? 0 : info.getLatency();
        pending = new Pending(target.immutable(), sequence, tick + Math.clamp(12 + latency / 25, 12, 40), expected);
        schedule(waiting, 1);
    }

    public static void blockUpdated(BlockPos pos, BlockState state) {
        if (instance != null && instance.pending != null && instance.pending.position.equals(pos)) {
            instance.pending.reported = state;
        }
    }

    public static void acknowledged(int sequence) {
        if (instance != null && instance.pending != null && sequence >= instance.pending.sequence) {
            instance.pending.acknowledged = true;
        }
    }

    private boolean confirmed() {
        BlockState current = level.getBlockState(pending.position);
        if (!expected(current)) return false;
        return pending.reported != null ? expected(pending.reported) : pending.acknowledged && step != Step.WAIT_GUARD;
    }

    private boolean expected(BlockState state) {
        return switch (step) {
            case WAIT_PLACE, WAIT_GUARD -> state.is(pending.expected);
            case WAIT_CHARGE -> charge(state) > 0;
            case WAIT_CLEAR -> !state.is(Blocks.RESPAWN_ANCHOR);
            default -> false;
        };
    }

    private void awaitBlock(Step next, int wait) {
        if (confirmed()) {
            pending = null;
            schedule(next, delay(wait));
        } else if (tick >= pending.deadline) finish();
    }

    private void targetExisting(BlockState state) {
        schedule(charge(state) > 0 ? afterCharge() : Step.SELECT_CHARGE, 0);
    }

    private Step afterCharge() {
        return config.anchorMacro.safeAnchor ? Step.SELECT_GUARD : Step.SELECT_DETONATE;
    }

    private void select(int slot, Step next, int wait) {
        if (slot < 0) {
            finish();
        } else if (player.getInventory().getSelectedSlot() == slot) {
            schedule(next, 0);
        } else {
            player.getInventory().setSelectedSlot(slot);
            ownedSlot = slot;
            schedule(next, Math.max(1, delay(wait)));
        }
    }

    private void finish() {
        restoreView();
        pending = null;
        if (shouldRestore()) schedule(Step.SWITCH_BACK, delay(config.anchorMacro.switchBackDelay));
        else reset();
    }

    private void stop() {
        restoreView();
        if (shouldRestore()) player.getInventory().setSelectedSlot(previousSlot);
        reset();
    }

    private boolean shouldRestore() {
        return config.anchorMacro.switchBack && ownedSlot >= 0 && ownedSlot != previousSlot
                && player.getInventory().getSelectedSlot() == ownedSlot;
    }

    private int expectedSlot() {
        return ownedSlot >= 0 ? ownedSlot : previousSlot;
    }

    private void reset() {
        step = Step.IDLE;
        player = null;
        level = null;
        anchor = null;
        previousSlot = ownedSlot = -1;
        cycles = 0;
        fastRefill = pendingRestore = false;
        guard = null;
        guardBlock = null;
        savedLook = guardLook = null;
        pending = null;
    }

    private void schedule(Step next, int wait) {
        step = next;
        readyAt = tick + wait;
    }

    private int delay(int ticks) {
        int jitter = config.anchorMacro.jitter;
        return ticks + (jitter > 0 ? ThreadLocalRandom.current().nextInt(jitter + 1) : 0);
    }

    private BlockHitResult anchorHit() {
        BlockHitResult hit = crosshair(player);
        return hit != null && hit.getBlockPos().equals(anchor) && level.getBlockState(anchor).is(Blocks.RESPAWN_ANCHOR) ? hit : null;
    }

    private static BlockHitResult crosshair(LocalPlayer player) {
        if (!(player.pick(player.blockInteractionRange(), 1, false) instanceof BlockHitResult hit)
                || hit.getType() != HitResult.Type.BLOCK) return null;
        Vec3 eye = player.getEyePosition();
        Vec3 direction = hit.getLocation().subtract(eye);
        Vec3 end = eye.add(direction.normalize().scale(Math.min(player.entityInteractionRange(), direction.length())));
        var entity = ProjectileUtil.getEntityHitResult(player, eye, end, player.getBoundingBox().expandTowards(end.subtract(eye)).inflate(1),
                candidate -> !candidate.isSpectator() && candidate.isPickable(), eye.distanceToSqr(end));
        return entity == null ? hit : null;
    }

    private static boolean canAct(Minecraft client, LocalPlayer player) {
        return player != null && client.level != null && client.gameMode != null && client.screen == null && !client.isPaused()
                && client.isWindowActive() && player.isAlive() && !player.isSpectator() && client.getCameraEntity() == player
                && !player.isSecondaryUseActive() && !player.isHandsBusy() && !player.isUsingItem() && !client.gameMode.isDestroying()
                && !player.getOffhandItem().is(Items.GLOWSTONE) && !FreecamController.enabled()
                && !AutoBuilderController.get().isRunning() && !BuilderPlacement.placing();
    }

    private static boolean canExplode(ClientLevel world, BlockPos target) {
        return world.dimension() != Level.NETHER && world.getWorldBorder().isWithinBounds(target)
                && !world.environmentAttributes().getValue(EnvironmentAttributes.RESPAWN_ANCHOR_WORKS, target);
    }

    private static BlockPos placementTarget(LocalPlayer player, BlockHitResult hit) {
        var world = player.level();
        if (world.getBlockState(hit.getBlockPos()).getMenuProvider(world, hit.getBlockPos()) != null) return null;
        var context = new BlockPlaceContext(player, InteractionHand.MAIN_HAND, new ItemStack(Items.RESPAWN_ANCHOR), hit);
        return context.canPlace() ? context.getClickedPos() : null;
    }

    private static int charge(BlockState state) {
        return state.is(Blocks.RESPAWN_ANCHOR) ? state.getValue(RespawnAnchorBlock.CHARGE) : 0;
    }

    private int safetySlot(Inventory inventory) {
        int configured = config.anchorMacro.safetySlot - 1;
        if (!inventory.getItem(configured).is(Items.GLOWSTONE)) return configured;
        int totem = find(inventory, Items.TOTEM_OF_UNDYING);
        if (totem >= 0) return totem;
        for (int slot = 0; slot < Inventory.getSelectionSize(); slot++) {
            if (!inventory.getItem(slot).is(Items.GLOWSTONE)) return slot;
        }
        return -1;
    }

    private int guardSlot(Inventory inventory) {
        int obsidian = find(inventory, Items.OBSIDIAN);
        if (obsidian >= 0) return obsidian;
        int configured = config.anchorMacro.guardSlot - 1;
        var stack = inventory.getItem(configured);
        if (!stack.is(Items.GLOWSTONE) && AnchorGuard.isGuardBlock(stack)) return configured;
        int glowstone = find(inventory, Items.GLOWSTONE);
        if (glowstone < 0 || !AnchorGuard.isGuardBlock(inventory.getItem(glowstone))) return -1;
        int reserved = cycles == 0 && config.anchorMacro.mode == AnchorMacroSettings.Mode.DOUBLE ? 1 : 0;
        int available = 0;
        for (int slot = 0; slot < Inventory.getSelectionSize(); slot++) {
            var item = inventory.getItem(slot);
            if (item.is(Items.GLOWSTONE)) available += item.getCount();
        }
        return available > reserved ? glowstone : -1;
    }

    private static int find(Inventory inventory, Item item) {
        for (int slot = 0; slot < Inventory.getSelectionSize(); slot++) {
            if (inventory.getItem(slot).is(item)) return slot;
        }
        return -1;
    }

    private static final class Pending {
        final BlockPos position;
        final int sequence;
        final long deadline;
        final Block expected;
        BlockState reported;
        boolean acknowledged;

        Pending(BlockPos position, int sequence, long deadline, Block expected) {
            this.position = position;
            this.sequence = sequence;
            this.deadline = deadline;
            this.expected = expected;
        }
    }

    private record Look(float yaw, float pitch) {
        static Look of(LocalPlayer player) { return new Look(player.getYRot(), player.getXRot()); }

        static Look toward(LocalPlayer player, Vec3 aim) {
            Vec3 delta = aim.subtract(player.getEyePosition());
            float yaw = (float) Math.toDegrees(Math.atan2(-delta.x, delta.z));
            float pitch = (float) -Math.toDegrees(Math.atan2(delta.y, delta.horizontalDistance()));
            return new Look(player.getYRot() + Mth.wrapDegrees(yaw - player.getYRot()), pitch);
        }

        boolean matches(LocalPlayer player) {
            return Math.abs(Mth.wrapDegrees(player.getYRot() - yaw)) < 0.5F && Math.abs(player.getXRot() - pitch) < 0.5F;
        }

        void apply(LocalPlayer player) { player.setYRot(yaw); player.setXRot(pitch); }
    }
}
