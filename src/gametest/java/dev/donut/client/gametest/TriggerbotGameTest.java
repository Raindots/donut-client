package dev.donut.client.gametest;

import dev.donut.client.combat.TriggerbotController;
import dev.donut.client.config.ClientConfig;
import dev.donut.client.config.TriggerbotSettings;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Objects;

/** Measures vanilla cooldown and server-authoritative damage against a live crosshair target. */
public final class TriggerbotGameTest implements FabricClientGameTest {
    private static final Vec3 AIM = new Vec3(0.5, 66, 1.25);
    private static final BlockPos FLOOR = new BlockPos(0, 64, 3);

    @Override
    public void runTest(ClientGameTestContext context) {
        var config = new ClientConfig();
        var settings = config.triggerbot;
        var triggerbot = new TriggerbotController(config);
        try (var world = context.worldBuilder().create()) {
            var server = world.getServer();
            int target = prepare(context, server);
            try {
                verifyAcquisition(context, server, triggerbot, settings, target);
                verifyCooldownAndHold(context, server, triggerbot, settings, target);
            } finally {
                context.runOnClient(client -> triggerbot.disconnect());
            }
        }
    }

    private static int prepare(ClientGameTestContext context, TestServerContext server) {
        server.runCommand("gamemode survival @a");
        server.runCommand("fill -4 65 -4 4 68 6 minecraft:air");
        server.runCommand("fill -4 64 -4 4 64 6 minecraft:obsidian");
        server.runCommand("tp @a 0.5 65 3.5 180 0");
        server.runCommand("item replace entity @a hotbar.0 with minecraft:iron_sword");
        context.waitFor(client -> client.player.onGround() && client.level.getBlockState(FLOOR).is(Blocks.OBSIDIAN)
                && client.player.getInventory().getItem(0).is(Items.IRON_SWORD));
        context.runOnClient(client -> client.player.getInventory().setSelectedSlot(0));

        return server.computeOnServer(mc -> {
            var level = mc.overworld();
            var cow = Objects.requireNonNull(EntityType.COW.create(level, EntitySpawnReason.COMMAND));
            cow.setPos(0.5, 65, 1.25);
            cow.setNoAi(true);
            cow.getAttribute(Attributes.MAX_HEALTH).setBaseValue(60);
            cow.getAttribute(Attributes.KNOCKBACK_RESISTANCE).setBaseValue(1);
            cow.setHealth(cow.getMaxHealth());
            require(level.addFreshEntity(cow), "Could not spawn the Triggerbot target");
            return cow.getId();
        });
    }

    private static void verifyAcquisition(ClientGameTestContext context, TestServerContext server,
            TriggerbotController triggerbot, TriggerbotSettings settings, int target) {
        aimAt(context, AIM, target);
        float initialHealth = health(server, target);
        assertNoSwing(context, triggerbot, 8, false, "Triggerbot attacked while disabled");

        context.runOnClient(client -> {
            settings.enabled = true;
            settings.reaction = 6;
            settings.jitter = 0;
        });
        assertNoSwing(context, triggerbot, 9, false, "Triggerbot attacked a passive mob with Passives disabled");

        context.runOnClient(client -> settings.passives = true);
        assertNoSwing(context, triggerbot, 9, true, "Triggerbot attacked while another combat module owned the interaction");
        aimAt(context, new Vec3(2.5, 66, 3.5), -1);
        assertNoSwing(context, triggerbot, 9, false, "Triggerbot attacked away from the crosshair");
        require(health(server, target) == initialHealth, "The target took damage before the acquisition test");

        aimAt(context, AIM, target);
        assertNoSwing(context, triggerbot, 3, false, "Triggerbot ignored the reaction delay");
        awaitSwing(context, triggerbot, false, "Triggerbot did not swing at the acquired passive target");
        awaitDamage(context, server, target, initialHealth);
    }

    private static void verifyCooldownAndHold(ClientGameTestContext context, TestServerContext server,
            TriggerbotController triggerbot, TriggerbotSettings settings, int target) {
        float healthAfterFirstHit = health(server, target);
        context.runOnClient(client -> {
            settings.reaction = 0;
            client.player.resetAttackStrengthTicker();
        });
        for (int i = 0; i < 4; i++) {
            var sample = step(context, triggerbot, false, false);
            require(sample.before() < 1, "The cooldown fixture reached full charge too early");
            require(!sample.swung(), "Triggerbot attacked before the weapon fully recharged");
        }

        context.runOnClient(client -> settings.holdAttack = true);
        context.waitFor(client -> client.player.getAttackStrengthScale(0.5F) >= 1);
        assertNoSwing(context, triggerbot, 6, false, "Hold Attack acted while the Attack binding was released");
        require(health(server, target) == healthAfterFirstHit, "The target took damage while the attack was gated");

        context.runOnClient(client -> settings.crits = TriggerbotSettings.Crits.ONLY);
        require(context.computeOnClient(client -> client.player.onGround()), "The critical-hit fixture must be grounded");
        for (int i = 0; i < 6; i++) {
            require(!step(context, triggerbot, false, true).swung(), "Crits Only allowed a grounded swing");
        }

        context.runOnClient(client -> settings.crits = TriggerbotSettings.Crits.SMART);
        awaitSwing(context, triggerbot, true, "Holding the Attack binding did not trigger a charged grounded swing");
        awaitDamage(context, server, target, healthAfterFirstHit);
    }

    private static void aimAt(ClientGameTestContext context, Vec3 point, int target) {
        context.runOnClient(client -> {
            Vec3 delta = point.subtract(client.player.getEyePosition());
            float yaw = (float) Math.toDegrees(Math.atan2(-delta.x, delta.z));
            float pitch = (float) -Math.toDegrees(Math.atan2(delta.y, delta.horizontalDistance()));
            client.player.setYRot(yaw);
            client.player.yRotO = yaw;
            client.player.setXRot(pitch);
            client.player.xRotO = pitch;
        });
        context.waitFor(client -> pointsAt(client.hitResult instanceof EntityHitResult hit ? hit : null, target));
    }

    private static boolean pointsAt(EntityHitResult hit, int target) {
        return target < 0 ? hit == null : hit != null && hit.getEntity().getId() == target;
    }

    private static void assertNoSwing(ClientGameTestContext context, TriggerbotController triggerbot,
            int ticks, boolean busy, String failure) {
        for (int i = 0; i < ticks; i++) require(!step(context, triggerbot, busy, false).swung(), failure);
    }

    private static void awaitSwing(ClientGameTestContext context, TriggerbotController triggerbot,
            boolean held, String failure) {
        for (int i = 0; i < 40; i++) {
            if (step(context, triggerbot, false, held).swung()) return;
        }
        throw new AssertionError(failure);
    }

    private static TickSample step(ClientGameTestContext context, TriggerbotController triggerbot, boolean busy, boolean held) {
        TickSample sample = context.computeOnClient(client -> {
            boolean active = client.isWindowActive();
            boolean previousHold = client.options.keyAttack.isDown();
            client.setWindowActive(true);
            client.options.keyAttack.setDown(held);
            try {
                float before = client.player.getAttackStrengthScale(0.5F);
                triggerbot.tick(client, busy);
                return new TickSample(before, client.player.getAttackStrengthScale(0.5F));
            } finally {
                client.options.keyAttack.setDown(previousHold);
                client.setWindowActive(active);
            }
        });
        context.waitTick();
        return sample;
    }

    private static float health(TestServerContext server, int target) {
        return server.computeOnServer(mc -> ((LivingEntity) Objects.requireNonNull(mc.overworld().getEntity(target))).getHealth());
    }

    private static void awaitDamage(ClientGameTestContext context, TestServerContext server, int target, float previousHealth) {
        for (int i = 0; i < 20; i++) {
            if (health(server, target) < previousHealth) return;
            context.waitTick();
        }
        throw new AssertionError("Triggerbot's swing did not damage the target on the server");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private record TickSample(float before, float after) {
        boolean swung() {
            return after < before;
        }
    }
}
