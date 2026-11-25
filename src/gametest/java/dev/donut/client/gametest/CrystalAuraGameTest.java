package dev.donut.client.gametest;

import dev.donut.client.config.ClientConfig;
import dev.donut.client.config.CrystalAuraSettings;
import dev.donut.client.gui.DonutScreen;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.minecraft.client.KeyMapping;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Exercises server-authoritative crystal interactions with the player's aim and Use input. */
public final class CrystalAuraGameTest implements FabricClientGameTest {
    private static final BlockPos MANUAL_BASE = new BlockPos(0, 65, 0);
    private static final AABB ARENA = new AABB(-6, 64, -5, 7, 70, 8);

    @Override
    public void runTest(ClientGameTestContext context) {
        try (var world = context.worldBuilder().create()) {
            verifyManual(context, world.getServer());
        }
    }

    private static void verifyManual(ClientGameTestContext context, TestServerContext server) {
        server.runCommand("gamemode survival @a");
        server.runCommand("fill -6 64 -5 6 68 7 minecraft:air");
        server.runCommand("fill -6 64 -5 6 64 7 minecraft:bedrock");
        server.runCommand("setblock 0 65 0 minecraft:obsidian");
        server.runCommand("tp @a 0.5 65 3.5 90 0");
        server.runCommand("item replace entity @a hotbar.0 with minecraft:end_crystal 3");
        server.runCommand("item replace entity @a hotbar.1 with minecraft:obsidian 2");
        server.runOnServer(mc -> {
            var player = mc.getPlayerList().getPlayers().getFirst();
            player.getAbilities().invulnerable = true;
            player.getAttribute(Attributes.EXPLOSION_KNOCKBACK_RESISTANCE).setBaseValue(1);
        });
        context.waitFor(client -> client.player.onGround() && client.player.getInventory().getItem(0).getCount() == 3
                && client.level.getBlockState(MANUAL_BASE).is(Blocks.OBSIDIAN));

        ClientConfig config = productionConfig(context);
        var original = config.crystalAura;
        var settings = new CrystalAuraSettings();
        settings.enabled = true;
        context.runOnClient(client -> {
            config.crystalAura = settings;
            client.setWindowActive(true);
            client.player.getInventory().setSelectedSlot(0);
        });
        try {
            Vec3 baseSide = new Vec3(0.5, 65.2, 0.9);
            aim(context, baseSide);
            context.waitFor(client -> client.hitResult instanceof BlockHitResult hit
                    && hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(MANUAL_BASE));
            View view = context.computeOnClient(client -> new View(client.player.getYRot(), client.player.getXRot()));
            context.waitTicks(5);
            require(crystalCount(server) == 0, "Manual placed a crystal with Use released");

            context.getInput().holdKey(options -> options.keyUse);
            awaitManualCycle(context, server, InteractionHand.MAIN_HAND);
            context.getInput().releaseKey(options -> options.keyUse);
            view.verify(context);

            server.runCommand("item replace entity @a hotbar.0 with minecraft:end_crystal 2");
            context.waitFor(client -> client.player.getMainHandItem().getCount() == 2);
            context.waitTicks(6);
            require(crystalCount(server) == 0, "Manual kept placing after Use was released");

            server.runCommand("summon minecraft:end_crystal 0.5 66 0.5");
            aim(context, new Vec3(0.5, 67, 0.5));
            context.waitFor(client -> client.hitResult instanceof EntityHitResult hit && hit.getEntity() instanceof EndCrystal);
            context.getInput().holdKey(options -> options.keyUse);
            for (int i = 0; i < 30 && crystalCount(server) != 0; i++) context.waitTick();
            context.getInput().releaseKey(options -> options.keyUse);
            require(crystalCount(server) == 0, "Manual did not break the crystal under the crosshair");
            require(server.computeOnServer(mc -> mc.getPlayerList().getPlayers().getFirst().getMainHandItem().getCount()) == 2,
                    "Manual placed a crystal while aiming above the base");

            server.runCommand("setblock 0 65 0 minecraft:bedrock");
            server.runCommand("item replace entity @a hotbar.0 with minecraft:diamond_sword");
            server.runCommand("item replace entity @a weapon.offhand with minecraft:end_crystal");
            context.waitFor(client -> client.level.getBlockState(MANUAL_BASE).is(Blocks.BEDROCK)
                    && client.player.getMainHandItem().is(Items.DIAMOND_SWORD)
                    && client.player.getOffhandItem().is(Items.END_CRYSTAL));
            aim(context, baseSide);
            context.getInput().holdKey(options -> options.keyUse);
            awaitManualCycle(context, server, InteractionHand.OFF_HAND);
            context.getInput().releaseKey(options -> options.keyUse);
            view.verify(context);
        } finally {
            context.getInput().releaseKey(options -> options.keyUse);
            context.runOnClient(client -> config.crystalAura = original);
            server.runOnServer(mc -> mc.getPlayerList().getPlayers().getFirst()
                    .getAttribute(Attributes.EXPLOSION_KNOCKBACK_RESISTANCE).setBaseValue(0));
        }
    }

    private static ClientConfig productionConfig(ClientGameTestContext context) {
        KeyMapping key = context.computeOnClient(client -> KeyMapping.get("key.donut.open_gui"));
        context.waitTicks(2);
        context.getInput().pressKey(key);
        context.waitForScreen(DonutScreen.class);
        var config = context.computeOnClient(client -> {
            try {
                var field = DonutScreen.class.getDeclaredField("config");
                field.setAccessible(true);
                return (ClientConfig) field.get(client.screen);
            } catch (ReflectiveOperationException error) {
                throw new AssertionError(error);
            }
        });
        GuiTestInput.closeAnimated(context);
        return config;
    }

    private static void aim(ClientGameTestContext context, Vec3 point) {
        context.runOnClient(client -> {
            var player = client.player;
            Vec3 delta = point.subtract(player.getEyePosition());
            player.setYRot((float) Math.toDegrees(Math.atan2(-delta.x, delta.z)));
            player.setXRot((float) -Math.toDegrees(Math.atan2(delta.y, delta.horizontalDistance())));
            player.setOldPosAndRot();
        });
    }

    private static void awaitManualCycle(ClientGameTestContext context, TestServerContext server, InteractionHand hand) {
        for (int i = 0; i < 60; i++) {
            if (server.computeOnServer(mc -> mc.getPlayerList().getPlayers().getFirst().getItemInHand(hand).isEmpty()
                    && mc.overworld().getEntitiesOfClass(EndCrystal.class, ARENA, Entity::isAlive).isEmpty())) return;
            context.waitTick();
        }
        throw new AssertionError("Manual did not consume and break all crystals from " + hand);
    }

    private record View(float yaw, float pitch) {
        void verify(ClientGameTestContext context) {
            require(context.computeOnClient(client -> Math.abs(client.player.getYRot() - yaw) < 0.01
                            && Math.abs(client.player.getXRot() - pitch) < 0.01
                            && client.player.getInventory().getSelectedSlot() == 0),
                    "Manual changed view rotation or the selected slot");
        }
    }

    private static int crystalCount(TestServerContext server) {
        return server.computeOnServer(mc -> mc.overworld().getEntitiesOfClass(EndCrystal.class, ARENA, Entity::isAlive).size());
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
