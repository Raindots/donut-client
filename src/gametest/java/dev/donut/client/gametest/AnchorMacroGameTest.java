package dev.donut.client.gametest;

import dev.donut.client.config.AnchorMacroSettings;
import dev.donut.client.config.ClientConfig;
import dev.donut.client.gui.DonutScreen;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.minecraft.client.KeyMapping;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RespawnAnchorBlock;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Arrays;
import java.util.function.BooleanSupplier;

/** Drives the vanilla Use binding and observes the resulting server block and inventory states. */
public final class AnchorMacroGameTest implements FabricClientGameTest {
    private static final BlockPos SUPPORT = new BlockPos(0, 64, 1);
    private static final BlockPos ANCHOR = SUPPORT.above();
    private static final BlockPos GUARDED_ANCHOR = new BlockPos(0, 65, 0);
    private static final BlockPos GUARD = new BlockPos(0, 65, 2);

    @Override public void runTest(ClientGameTestContext context) {
        try (var world = context.worldBuilder().create()) {
            var server = world.getServer();
            ClientConfig config = productionConfig(context);
            var original = config.anchorMacro;
            var settings = new AnchorMacroSettings();
            settings.enabled = true;
            settings.safetySlot = 3;
            settings.switchBack = true;
            context.runOnClient(client -> config.anchorMacro = settings);
            try {
                prepare(context, server);
                testSingleAndDouble(context, server, settings);
                testGuard(context, server, settings);
            } finally {
                context.runOnClient(client -> config.anchorMacro = original);
            }
        }
    }

    private static ClientConfig productionConfig(ClientGameTestContext context) {
        KeyMapping key = context.computeOnClient(client -> Arrays.stream(client.options.keyMappings)
                .filter(mapping -> mapping.getName().equals("key.donut.open_gui")).findFirst().orElseThrow());
        context.getInput().pressKey(key);
        context.waitForScreen(DonutScreen.class);
        var config = context.computeOnClient(client -> {
            try {
                var field = DonutScreen.class.getDeclaredField("config");
                field.setAccessible(true);
                return (ClientConfig) field.get(client.screen);
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
        });
        GuiTestInput.closeAnimated(context);
        return config;
    }

    private static void prepare(ClientGameTestContext context, TestServerContext server) {
        server.runCommand("gamemode survival @a");
        server.runCommand("fill -5 64 -5 5 64 6 minecraft:obsidian");
        resetArena(context, server);
        server.runOnServer(mc -> {
            var player = mc.getPlayerList().getPlayers().getFirst();
            player.getAbilities().invulnerable = true;
            player.getAttribute(Attributes.EXPLOSION_KNOCKBACK_RESISTANCE).setBaseValue(1);
        });
        server.runCommand("item replace entity @a hotbar.0 with minecraft:respawn_anchor 7");
        server.runCommand("item replace entity @a hotbar.1 with minecraft:glowstone 7");
        server.runCommand("item replace entity @a hotbar.2 with minecraft:iron_sword");
        context.waitFor(client -> client.player.onGround() && client.player.getInventory().getItem(0).is(Items.RESPAWN_ANCHOR)
                && client.player.getInventory().getItem(1).is(Items.GLOWSTONE)
                && client.level.getBlockState(SUPPORT).is(Blocks.OBSIDIAN));
        context.runOnClient(client -> client.player.getInventory().setSelectedSlot(0));
    }

    private static void testSingleAndDouble(ClientGameTestContext context, TestServerContext server, AnchorMacroSettings settings) {
        Vec3 aim = new Vec3(0.5, 64.98, 1.5);
        aimAndUse(context, aim, SUPPORT);
        until(context, () -> consumed(server, 6), "Single mode did not place, charge and detonate one anchor");
        context.waitFor(client -> client.player.getInventory().getSelectedSlot() == 0);

        resetArena(context, server);
        context.runOnClient(client -> settings.mode = AnchorMacroSettings.Mode.DOUBLE);
        aimAndUse(context, aim, SUPPORT);
        until(context, () -> consumed(server, 4), "Double mode did not finish two anchor cycles");
        context.waitFor(client -> client.player.getInventory().getSelectedSlot() == 0);

        resetArena(context, server);
        context.runOnClient(client -> {
            settings.doubleAnchor = true;
            settings.cycleDelay = 20;
        });
        aimAndUse(context, aim, SUPPORT);
        until(context, () -> consumed(server, 2), "Double Anchor did not replace the first detonated anchor");
        context.waitFor(client -> client.player.getInventory().getSelectedSlot() == 0);
    }

    private static boolean consumed(TestServerContext server, int remaining) {
        return server.computeOnServer(mc -> {
            var inventory = mc.getPlayerList().getPlayers().getFirst().getInventory();
            return inventory.getItem(0).getCount() == remaining && inventory.getItem(1).getCount() == remaining
                    && !mc.overworld().getBlockState(ANCHOR).is(Blocks.RESPAWN_ANCHOR);
        });
    }

    private static void testGuard(ClientGameTestContext context, TestServerContext server, AnchorMacroSettings settings) {
        resetArena(context, server);
        context.runOnClient(client -> {
            settings.mode = AnchorMacroSettings.Mode.SINGLE;
            settings.safeAnchor = true;
            settings.ifUnguarded = AnchorMacroSettings.Unguarded.CANCEL;
        });
        server.runCommand("item replace entity @a hotbar.1 with minecraft:air");
        server.runCommand("setblock 0 65 0 minecraft:respawn_anchor[charges=1]");
        context.waitFor(client -> client.player.getInventory().getItem(1).isEmpty()
                && client.level.getBlockState(GUARDED_ANCHOR).is(Blocks.RESPAWN_ANCHOR));
        Vec3 aim = new Vec3(0.5, 65.88, 0.85);
        aimAndUse(context, aim, GUARDED_ANCHOR);
        context.waitTicks(20);
        require(server.computeOnServer(mc -> {
            var state = mc.overworld().getBlockState(GUARDED_ANCHOR);
            return state.is(Blocks.RESPAWN_ANCHOR) && state.getValue(RespawnAnchorBlock.CHARGE) == 1;
        }), "If Unguarded Cancel detonated an anchor without cover");

        server.runCommand("item replace entity @a hotbar.8 with minecraft:obsidian");
        context.waitFor(client -> client.player.getInventory().getItem(8).is(Items.OBSIDIAN));
        aimAndUse(context, aim, GUARDED_ANCHOR);
        until(context, () -> server.computeOnServer(mc -> mc.overworld().getBlockState(GUARD).is(Blocks.OBSIDIAN)
                && !mc.overworld().getBlockState(GUARDED_ANCHOR).is(Blocks.RESPAWN_ANCHOR)),
                "Safe Anchor did not place cover before detonating the charged anchor");
        context.waitFor(client -> client.player.getInventory().getSelectedSlot() == 0);
    }

    private static void aimAndUse(ClientGameTestContext context, Vec3 point, BlockPos expected) {
        context.runOnClient(client -> {
            client.setWindowActive(true);
            Vec3 delta = point.subtract(client.player.getEyePosition());
            client.player.setYRot((float) Math.toDegrees(Math.atan2(-delta.x, delta.z)));
            client.player.setXRot((float) -Math.toDegrees(Math.atan2(delta.y, delta.horizontalDistance())));
        });
        var onTarget = (BooleanSupplier) () -> context.computeOnClient(client -> client.hitResult instanceof BlockHitResult hit
                && hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(expected));
        for (int i = 0; i < 25 && !onTarget.getAsBoolean(); i++) context.waitTick();
        if (!onTarget.getAsBoolean()) throw new AssertionError(context.computeOnClient(client ->
                "Expected crosshair on " + expected + " from " + client.player.position() + " but hit "
                        + (client.hitResult instanceof BlockHitResult hit ? hit.getBlockPos() + " " + hit.getType() : client.hitResult)));
        context.getInput().pressKey(options -> options.keyUse);
    }

    private static void resetArena(ClientGameTestContext context, TestServerContext server) {
        server.runCommand("fill -5 65 -5 5 68 6 minecraft:air");
        server.runCommand("tp @a 0.5 65 3.5 180 0");
        context.waitTicks(3);
        context.waitFor(client -> client.player.onGround() && client.player.position().distanceToSqr(new Vec3(0.5, 65, 3.5)) < 0.01);
    }

    private static void until(ClientGameTestContext context, BooleanSupplier condition, String failure) {
        for (int i = 0; i < 85 && !condition.getAsBoolean(); i++) context.waitTick();
        require(condition.getAsBoolean(), failure);
    }

    private static void require(boolean value, String failure) {
        if (!value) throw new AssertionError(failure);
    }
}
