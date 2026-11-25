package dev.donut.client.combat;

import dev.donut.client.autobuild.AutoBuilderController;
import dev.donut.client.config.AimAssistSettings;
import dev.donut.client.config.ClientConfig;
import dev.donut.client.freecam.FreecamController;
import dev.donut.client.spectrebuilder.BuilderPlacement;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

public final class AimAssistController {
    private static final double RETAIN_RANGE_MARGIN = 1.0;
    private static final double DEAD_ZONE = 0.45;
    private static final double REFERENCE_FPS = 60.0;
    private static final double MAX_FRAME_SECONDS = 0.05;
    private static final double TURN_INPUT_TO_DEGREES = 0.15;
    private static AimAssistController instance;

    private final ClientConfig config;
    private ClientLevel level;
    private LocalPlayer player;
    private Player target;
    private long lastFrame;
    private boolean combatBusy;

    private AimAssistController(ClientConfig config) {
        this.config = config;
    }

    public static AimAssistController install(ClientConfig config) {
        instance = new AimAssistController(config);
        return instance;
    }

    public static void renderFrame(float partialTick) {
        if (instance != null) instance.update(Minecraft.getInstance(), partialTick);
    }

    public void setCombatBusy(boolean busy) {
        combatBusy = busy;
    }

    public void preferTarget(Minecraft client, Player opponent) {
        if (!config.aimAssist.enabled || client.player == null || client.level == null) return;
        updateWorld(client);
        target = opponent;
    }

    public void disconnect() {
        config.aimAssist.enabled = false;
        combatBusy = false;
        level = null;
        player = null;
        clearTracking();
    }

    private void update(Minecraft client, float partialTick) {
        updateWorld(client);
        var settings = config.aimAssist;
        if (!settings.enabled || level == null || player == null || !player.isAlive() || player.isSpectator()) {
            clearTracking();
            return;
        }
        if (combatBusy || FreecamController.enabled() || AutoBuilderController.get().isRunning()
                || BuilderPlacement.placing() || client.getCameraEntity() != player
                || client.screen != null || client.isPaused() || !client.isWindowActive()) {
            lastFrame = 0;
            return;
        }
        if (player.isUsingItem() || client.options.keyUse.isDown()
                || GLFW.glfwGetMouseButton(client.getWindow().handle(), GLFW.GLFW_MOUSE_BUTTON_RIGHT) == GLFW.GLFW_PRESS
                || !allowsItem(player.getMainHandItem(), settings) || settings.range <= 0) {
            clearTracking();
            return;
        }

        long now = System.nanoTime();
        double seconds = lastFrame == 0 ? 1 / REFERENCE_FPS : Math.clamp((now - lastFrame) / 1_000_000_000.0, 0, MAX_FRAME_SECONDS);
        lastFrame = now;
        Vec3 eye = player.getEyePosition(partialTick);

        double retainRange = settings.range + RETAIN_RANGE_MARGIN;
        AimDelta aim = eligible(target, retainRange * retainRange) ? aimDelta(target, eye, partialTick, settings) : null;
        if (aim == null) aim = acquire(eye, partialTick, settings);
        if (aim == null || aim.angle() <= DEAD_ZONE) return;

        double nearTarget = Math.pow(Math.min(aim.angle() / 12.0, 1.0), 2.2);
        double fractionAt60Fps = settings.speed * 0.18 * nearTarget;
        double fraction = -Math.expm1(Math.log1p(-fractionAt60Fps) * REFERENCE_FPS * seconds);
        player.turn(aim.yaw() * fraction / TURN_INPUT_TO_DEGREES, aim.pitch() * fraction / TURN_INPUT_TO_DEGREES);
    }

    private AimDelta acquire(Vec3 eye, float partialTick, AimAssistSettings settings) {
        target = null;
        AimDelta best = null;
        double bestScore = Double.POSITIVE_INFINITY;
        double rangeSquared = settings.range * settings.range;
        for (Player candidate : level.players()) {
            if (!eligible(candidate, rangeSquared)) continue;
            AimDelta aim = aimDelta(candidate, eye, partialTick, settings);
            if (aim == null) continue;
            double score = settings.targetMode == AimAssistSettings.TargetMode.FOV ? aim.angle() : player.distanceToSqr(candidate);
            if (score < bestScore) {
                best = aim;
                target = candidate;
                bestScore = score;
            }
        }
        return best;
    }

    private boolean eligible(Player candidate, double rangeSquared) {
        return candidate != null && candidate != player && candidate.level() == level && !candidate.isRemoved()
                && candidate.isAlive() && player.distanceToSqr(candidate) <= rangeSquared;
    }

    private AimDelta aimDelta(Player target, Vec3 eye, float partialTick, AimAssistSettings settings) {
        double heightFraction = switch (settings.aimPart) {
            case HEAD -> 0.83;
            case TORSO -> 0.56;
            case LEGS -> 0.22;
        };
        Vec3 point = target.getPosition(partialTick).add(0, target.getBbHeight() * heightFraction, 0);
        Vec3 offset = point.subtract(eye);
        if (offset.lengthSqr() < 1.0E-8) return null;
        float yaw = Mth.wrapDegrees((float) Math.toDegrees(Math.atan2(-offset.x, offset.z)) - player.getYRot());
        float pitch = (float) -Math.toDegrees(Math.atan2(offset.y, offset.horizontalDistance())) - player.getXRot();
        double angle = Math.hypot(yaw, pitch);
        if (angle > settings.fov * 0.5) return null;
        if (!settings.throughWalls && level.clip(new ClipContext(eye, point, ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE, player)).getType() != HitResult.Type.MISS) return null;
        return new AimDelta(yaw, pitch, angle);
    }

    private void updateWorld(Minecraft client) {
        if (client.player != player || client.level != level) {
            player = client.player;
            level = client.level;
            clearTracking();
        }
    }

    private void clearTracking() {
        target = null;
        lastFrame = 0;
    }

    private static boolean allowsItem(ItemStack item, AimAssistSettings settings) {
        return settings.everything || settings.swords && item.is(ItemTags.SWORDS)
                || settings.axes && item.is(ItemTags.AXES) || settings.mace && item.is(Items.MACE)
                || settings.spearsAndTridents && (item.is(ItemTags.SPEARS) || item.is(Items.TRIDENT));
    }

    private record AimDelta(float yaw, float pitch, double angle) {}
}
