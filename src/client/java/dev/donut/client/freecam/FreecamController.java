package dev.donut.client.freecam;

import dev.donut.client.config.ClientConfig;
import dev.donut.client.autobuild.AutoBuilderController;
import dev.donut.client.config.FreecamSettings;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

/** Camera-only flight. Never writes a player transform, abilities, gamemode or movement packet. */
public final class FreecamController {
    private static FreecamController instance;
    private final ClientConfig config;
    private final ClientInput neutralInput = new ClientInput() {
        @Override public void tick() { keyPresses = Input.EMPTY; moveVector = Vec2.ZERO; }
        // Vanilla auto-jump can call this after input.tick(). It must stay neutral too.
        @Override public void makeJump() { }
    };
    private LocalPlayer player;
    private ClientLevel level;
    private ClientInput savedInput;
    private boolean active;
    private Vec3 position = Vec3.ZERO;
    private Vec3 previousPosition = Vec3.ZERO;
    private float yaw;
    private float pitch;

    public FreecamController(ClientConfig config) {
        this.config = config;
        instance = this;
    }

    public static FreecamController getInstance() { return instance; }
    public static boolean enabled() { return instance != null && instance.isActive(); }
    public FreecamSettings settings() { return config.freecam; }
    public Vec3 position() { return position; }
    public float yaw() { return yaw; }
    public float pitch() { return pitch; }

    public boolean isActive() {
        Minecraft client = Minecraft.getInstance();
        return active && client.player == player && client.level == level
                && player != null && player.isAlive() && !player.isRemoved()
                && client.getCameraEntity() == player;
    }

    public void setEnabled(boolean enabled) {
        if (!enabled) { disable(); return; }
        if (isActive()) { settings().enabled = true; return; }
        disable();
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null || !client.player.isAlive()
                || client.getCameraEntity() != client.player) return;
        // Releasing a charged bow/trident would be a gameplay action. Let the user finish first.
        if (client.player.isUsingItem()) {
            client.player.displayClientMessage(Component.literal("Release the item before enabling Freecam."), true);
            return;
        }
        // Return builder steering before Freecam saves the player's normal input.
        if (AutoBuilderController.get().isRunning()) AutoBuilderController.get().pause();
        settings().sanitize();
        player = client.player;
        level = client.level;
        savedInput = player.input;
        neutralInput.tick();
        player.input = neutralInput;
        position = player.getEyePosition();
        previousPosition = position;
        yaw = player.getYRot();
        pitch = player.getXRot();
        if (client.gameMode != null) client.gameMode.stopDestroyBlock();
        suppressActions(client);
        active = true;
        settings().enabled = true;
    }

    public void disable() {
        if (player != null && player.input == neutralInput && savedInput != null) player.input = savedInput;
        if (active) suppressActions(Minecraft.getInstance());
        active = false;
        settings().enabled = false;
        player = null;
        level = null;
        savedInput = null;
    }

    public void tick(Minecraft client) {
        if (active && !isActive()) { disable(); return; }
        if (settings().enabled != active) setEnabled(settings().enabled);
        if (!isActive()) return;
        suppressActions(client);
        previousPosition = position;
        if (client.screen != null || client.isPaused()) return;
        var options = client.options;
        double forward = axis(options.keyUp, options.keyDown);
        double strafe = axis(options.keyRight, options.keyLeft);
        double vertical = axis(options.keyJump, options.keyShift);
        Vec3 look = Vec3.directionFromRotation(pitch, yaw);
        double radians = Math.toRadians(yaw);
        Vec3 motion = look.scale(forward).add(-Math.cos(radians) * strafe, vertical, -Math.sin(radians) * strafe);
        if (motion.lengthSqr() > 1.0e-8) {
            double speed = settings().speed * (options.keySprint.isDown() ? settings().boostMultiplier : 1);
            Vec3 next = position.add(motion.normalize().scale(speed));
            // Keep all render matrices finite and within Minecraft's supported horizontal world bounds.
            position = new Vec3(Math.clamp(next.x, -29_999_984, 29_999_984),
                    Math.clamp(next.y, -2048, 4096), Math.clamp(next.z, -29_999_984, 29_999_984));
        }
    }

    public Vec3 interpolatedPosition(float partialTick) {
        return previousPosition.lerp(position, Math.clamp(partialTick, 0, 1));
    }

    /** Arguments have already been sensitivity-scaled by Minecraft's mouse handler. */
    public void turn(double dx, double dy) {
        if (!isActive() || Minecraft.getInstance().screen != null) return;
        yaw = net.minecraft.util.Mth.wrapDegrees(yaw + (float) (dx * 0.15));
        pitch = Math.clamp(pitch + (float) (dy * 0.15), -90, 90);
    }

    private static double axis(KeyMapping positive, KeyMapping negative) {
        return (positive.isDown() ? 1 : 0) - (negative.isDown() ? 1 : 0);
    }

    /** Drain action clicks as well as held states so they cannot replay after exiting Freecam. */
    public void suppressActions(Minecraft client) {
        var options = client.options;
        release(options.keyAttack);
        release(options.keyUse);
        release(options.keyPickItem);
        release(options.keyDrop);
        release(options.keySwapOffhand);
        for (KeyMapping slot : options.keyHotbarSlots) release(slot);
    }

    private static void release(KeyMapping key) {
        key.setDown(false);
        while (key.consumeClick()) { }
    }
}
