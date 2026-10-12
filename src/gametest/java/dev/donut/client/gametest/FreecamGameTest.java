package dev.donut.client.gametest;

import dev.donut.client.config.ClientConfig;
import dev.donut.client.freecam.FreecamController;
import dev.donut.client.gui.DonutScreen;
import java.util.function.Function;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.minecraft.client.CameraType;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Options;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec3;

/** Verifies the production singleton moves only its camera, retaining the actual client/server player. */
public final class FreecamGameTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        FreecamController controller = FreecamController.getInstance();
        require(controller != null, "The production Freecam controller was not initialized");
        double originalSpeed = controller.settings().speed;
        double originalBoost = controller.settings().boostMultiplier;
        context.runOnClient(client -> {
            controller.setEnabled(false);
            controller.settings().speed = 1;
            controller.settings().boostMultiplier = 3;
            client.options.guiScale().set(1);
            client.options.setCameraType(CameraType.FIRST_PERSON);
        });
        context.getInput().resizeWindow(1280, 800);

        try {
          try (var world = context.worldBuilder().create()) {
            TestServerContext server = world.getServer();
            server.runCommand("gamemode creative @a");
            server.runCommand("time set noon");
            server.runCommand("fill -15 64 -15 15 64 15 minecraft:smooth_stone");
            server.runCommand("setblock -2 65 0 minecraft:chest");
            server.runCommand("setblock 0 65 0 minecraft:purple_shulker_box");
            server.runCommand("setblock 2 65 0 minecraft:spawner");
            server.runCommand("tp @a 0.5 65 8.5 180 10");
            context.waitTicks(10);
            world.getClientWorld().waitForChunksRender();
            context.waitFor(client -> client.player != null && client.player.onGround()
                    && Math.abs(client.player.getY() - 65) < 0.001);
            context.waitTicks(3);

            Pose clientBody = context.computeOnClient(client -> Pose.of(client.player));
            Pose serverBody = server.computeOnServer(mcServer -> Pose.of(mcServer.getPlayerList().getPlayers().getFirst()));
            var originalInput = context.computeOnClient(client -> client.player.input);

            // Share just the actual controller settings with the screen, leaving finder preferences untouched.
            ClientConfig screenConfig = new ClientConfig();
            screenConfig.freecam = controller.settings();
            context.setScreen(() -> new DonutScreen(screenConfig, () -> { }));
            context.waitTicks(2);
            GuiTestInput.click(context, "enable2");
            context.waitFor(client -> controller.isActive());
            screenshot(context, "donut-freecam-01-modules");
            GuiTestInput.rightClick(context, "module2");
            GuiTestInput.click(context, "m2.freecamSpeed"); // Camera-speed slider midpoint.
            context.runOnClient(client -> require(Math.abs(controller.settings().speed - 2.6) < 0.001,
                    "The Freecam speed slider did not update the controller settings"));
            GuiTestInput.click(context, "m2.freecamBoost"); // Sprint-boost slider midpoint.
            context.runOnClient(client -> require(Math.abs(controller.settings().boostMultiplier - 5.5) < 0.001,
                    "The Freecam boost slider did not update the controller settings"));
            screenshot(context, "donut-freecam-02-settings");
            context.runOnClient(client -> {
                controller.settings().speed = 1;
                controller.settings().boostMultiplier = 3;
                client.screen.onClose();
            });
            context.waitForScreen(null);
            context.waitTicks(2);
            context.runOnClient(client -> {
                require(client.player.input != originalInput, "Freecam did not install neutral player input");
                require(client.player.input.keyPresses.equals(Input.EMPTY), "Player movement input is not neutral");
                require(client.getCameraEntity() == client.player, "Freecam replaced the real camera entity");
                require(client.gameRenderer.getMainCamera().isDetached(), "Freecam camera was not detached");
                require(client.gameRenderer.getMainCamera().position().distanceTo(controller.position()) < 0.001,
                        "Rendered camera did not use the controller position");
            });

            double ordinary = move(context, controller, options -> options.keyUp, 6);
            require(ordinary > 3, "Forward input did not move the virtual camera");
            assertBodyUnchanged(context, server, clientBody, serverBody);

            context.getInput().holdKey(options -> options.keySprint);
            double boosted = move(context, controller, options -> options.keyUp, 6);
            context.getInput().releaseKey(options -> options.keySprint);
            context.waitTick();
            require(boosted > ordinary * 2.5 && boosted < ordinary * 3.5,
                    "Sprint boost did not apply the configured multiplier");
            assertBodyUnchanged(context, server, clientBody, serverBody);

            double beforeRise = context.computeOnClient(client -> controller.position().y);
            move(context, controller, options -> options.keyJump, 4);
            double afterRise = context.computeOnClient(client -> controller.position().y);
            require(afterRise > beforeRise + 2, "Jump input did not raise the virtual camera");
            move(context, controller, options -> options.keyShift, 4);
            double afterDescent = context.computeOnClient(client -> controller.position().y);
            require(afterDescent < afterRise - 2, "Sneak input did not lower the virtual camera");
            assertBodyUnchanged(context, server, clientBody, serverBody);

            // Also exercise MouseHandler's real input route, rather than testing only controller.turn().
            context.getInput().moveCursor(1, 0); // Clear vanilla's first-move guard after grabbing the mouse.
            context.waitTick();
            float beforeMouseYaw = context.computeOnClient(client -> controller.yaw());
            context.getInput().moveCursor(24, -12);
            context.waitTicks(2);
            context.runOnClient(client -> require(Math.abs(wrappedDegrees(controller.yaw() - beforeMouseYaw)) > 0.01,
                    "MouseHandler input did not reach the free camera"));
            assertBodyUnchanged(context, server, clientBody, serverBody);

            context.runOnClient(client -> {
                float yaw = controller.yaw();
                float pitch = controller.pitch();
                controller.turn(80, -30);
                require(Math.abs(wrappedDegrees(controller.yaw() - yaw) - 12) < 0.001,
                        "Mouse yaw did not rotate the virtual camera");
                require(Math.abs(controller.pitch() - pitch + 4.5) < 0.001,
                        "Mouse pitch did not rotate the virtual camera");
            });
            context.waitTicks(2);
            assertBodyUnchanged(context, server, clientBody, serverBody);
            // Move above the fixture and look back at the stationary body for a useful visual check.
            move(context, controller, options -> options.keyJump, 8);
            context.runOnClient(client -> {
                Vec3 toBody = client.player.getEyePosition().subtract(controller.position());
                double desiredYaw = Math.toDegrees(Math.atan2(-toBody.x, toBody.z));
                double desiredPitch = Math.toDegrees(Math.atan2(-toBody.y, Math.hypot(toBody.x, toBody.z)));
                controller.turn((desiredYaw - controller.yaw()) / 0.15, (desiredPitch - controller.pitch()) / 0.15);
            });
            context.waitTicks(2);
            assertBodyUnchanged(context, server, clientBody, serverBody);
            screenshot(context, "donut-freecam-03-detached-camera");

            // Queued world interactions must be cleared before they can run or replay after disabling.
            context.runOnClient(client -> {
                KeyMapping[] interactions = {client.options.keyAttack, client.options.keyUse,
                        client.options.keyDrop, client.options.keySwapOffhand};
                for (KeyMapping key : interactions) {
                    key.setDown(true);
                    KeyMapping.click(key.getDefaultKey());
                }
                controller.tick(client);
                for (KeyMapping key : interactions) {
                    require(!key.isDown(), "Freecam retained a held world interaction: " + key.getName());
                    require(!key.consumeClick(), "Freecam retained a queued world interaction: " + key.getName());
                }
            });
            context.waitTicks(2);
            assertBodyUnchanged(context, server, clientBody, serverBody);

            context.runOnClient(client -> controller.setEnabled(false));
            context.waitTicks(2);
            context.runOnClient(client -> {
                require(!controller.isActive(), "Freecam did not disable");
                require(!controller.settings().enabled, "Freecam enabled flag was not reset");
                require(client.player.input == originalInput, "Disabling Freecam did not restore the original input");
                require(client.getCameraEntity() == client.player, "Disabling Freecam changed the camera entity");
                require(!client.gameRenderer.getMainCamera().isDetached(), "Camera remained detached after disabling");
                require(client.gameRenderer.getMainCamera().position().distanceTo(client.player.getEyePosition()) < 0.15,
                        "Camera did not return to the player");
            });
            assertBodyUnchanged(context, server, clientBody, serverBody);
            screenshot(context, "donut-freecam-04-returned-to-player");

            // A real server correction remains authoritative while the camera is elsewhere.
            context.runOnClient(client -> controller.setEnabled(true));
            context.waitFor(client -> controller.isActive());
            server.runCommand("tp @a 3.5 65 8.5 90 0");
            context.waitFor(client -> Math.abs(client.player.getX() - 3.5) < 0.001);
            context.waitTicks(3);
            Pose correctedClient = context.computeOnClient(client -> Pose.of(client.player));
            Pose correctedServer = server.computeOnServer(mcServer -> Pose.of(mcServer.getPlayerList().getPlayers().getFirst()));
            context.runOnClient(client -> controller.setEnabled(false));
            context.waitTicks(2);
            assertBodyUnchanged(context, server, correctedClient, correctedServer);
            require(correctedClient.position().distanceTo(clientBody.position()) > 2,
                    "Server correction fixture did not move the actual player");

            // Leave Freecam enabled deliberately: the production disconnect hook must clean it up.
            context.runOnClient(client -> controller.setEnabled(true));
            context.waitFor(client -> controller.isActive());
          }
          context.runOnClient(client -> require(!controller.isActive() && !controller.settings().enabled,
                  "Disconnect did not leave Freecam disabled"));
        } finally {
            context.getInput().releaseKey(options -> options.keyUp);
            context.getInput().releaseKey(options -> options.keySprint);
            context.getInput().releaseKey(options -> options.keyJump);
            context.getInput().releaseKey(options -> options.keyShift);
            context.runOnClient(client -> {
                controller.setEnabled(false);
                controller.settings().speed = originalSpeed;
                controller.settings().boostMultiplier = originalBoost;
            });
        }
    }

    private static double move(ClientGameTestContext context, FreecamController controller,
                               Function<Options, KeyMapping> key, int ticks) {
        Vec3 start = context.computeOnClient(client -> controller.position());
        context.getInput().holdKey(key);
        context.waitTicks(ticks);
        context.getInput().releaseKey(key);
        context.waitTick();
        return context.computeOnClient(client -> controller.position().distanceTo(start));
    }

    private static void assertBodyUnchanged(ClientGameTestContext context, TestServerContext server,
                                            Pose clientBody, Pose serverBody) {
        context.runOnClient(client -> {
            require(client.getCameraEntity() == client.player, "Virtual movement replaced the player's camera entity");
            clientBody.assertUnchanged(client.player, "Client player");
        });
        server.runOnServer(mcServer -> serverBody.assertUnchanged(mcServer.getPlayerList().getPlayers().getFirst(),
                "Server player"));
    }

    private static float wrappedDegrees(float degrees) {
        float wrapped = degrees % 360;
        if (wrapped >= 180) wrapped -= 360;
        if (wrapped < -180) wrapped += 360;
        return wrapped;
    }

    private static void screenshot(ClientGameTestContext context, String name) {
        context.takeScreenshot(TestScreenshotOptions.of(name).withSize(1280, 800));
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private record Pose(Vec3 position, float yaw, float pitch) {
        static Pose of(Entity player) {
            return new Pose(player.position(), player.getYRot(), player.getXRot());
        }

        void assertUnchanged(Entity player, String label) {
            require(position.distanceTo(player.position()) < 0.00001, label + " moved during Freecam");
            require(Math.abs(wrappedDegrees(player.getYRot() - yaw)) < 0.001, label + " yaw changed during Freecam");
            require(Math.abs(player.getXRot() - pitch) < 0.001, label + " pitch changed during Freecam");
        }
    }
}
