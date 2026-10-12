package dev.donut.client.gametest;

import com.mojang.blaze3d.platform.InputConstants;
import dev.donut.client.fly.FlyController;
import dev.donut.client.gui.DonutScreen;
import dev.donut.client.input.ModuleKeybinds;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Properties;
import java.util.function.Function;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.minecraft.client.CameraType;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Options;
import net.minecraft.world.entity.player.Abilities;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Uses a real dedicated-server socket connection; its allow-flight fixture permits vanilla movement. */
public final class FlyMultiplayerGameTest implements FabricClientGameTest {
    private static final Logger LOGGER = LoggerFactory.getLogger("Donut/FlyMultiplayerGameTest");
    private static final int MODULE = 6;
    private static final int TEST_KEY = GLFW.GLFW_KEY_F8;

    @Override
    public void runTest(ClientGameTestContext context) {
        Path eulaFile = Path.of("eula.txt").toAbsolutePath();
        if (!acceptedEula(eulaFile)) {
            LOGGER.info("Skipping FlyMultiplayerGameTest: eula=true is not accepted in {}. "
                    + "After manually accepting Minecraft's EULA, create or edit this file with eula=true "
                    + "and run ./gradlew runClientGameTest -x deleteGameTestRunDir from the project root. "
                    + "Normal runs delete the runtime directory and its EULA flag.", eulaFile);
            return;
        }
        FlyController controller = FlyController.getInstance();
        require(controller != null, "The production Fly controller was not initialized");
        int originalScale = context.computeOnClient(client -> client.options.guiScale().get());
        CameraType originalCamera = context.computeOnClient(client -> client.options.getCameraType());
        String originalModuleKey = context.computeOnClient(client -> ModuleKeybinds.mapping(MODULE).saveString());
        KeyMapping openGui = context.computeOnClient(client -> Arrays.stream(client.options.keyMappings)
                .filter(key -> key.getName().equals("key.donut.open_gui")).findFirst().orElseThrow());
        String originalOpenKey = context.computeOnClient(client -> openGui.saveString());
        context.runOnClient(client -> {
            controller.disable();
            openGui.setKey(InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_RIGHT_SHIFT));
            KeyMapping.resetMapping();
            client.options.guiScale().set(1);
            client.options.setCameraType(CameraType.FIRST_PERSON);
            client.resizeDisplay();
        });
        context.getInput().resizeWindow(1280, 800);

        Properties properties = new Properties();
        properties.setProperty("allow-flight", "true");
        properties.setProperty("online-mode", "false");
        properties.setProperty("server-ip", "127.0.0.1");
        properties.setProperty("server-port", Integer.toString(availablePort()));
        properties.setProperty("max-players", "1");
        properties.setProperty("level-name", "fly-multiplayer-test-world");

        try {
            try (var server = context.worldBuilder().createServer(properties)) {
                try (var connection = server.connect()) {
                    server.runCommand("gamemode survival @a");
                    server.runCommand("time set noon");
                    server.runCommand("fill -30 64 -30 30 64 30 minecraft:smooth_stone");
                    server.runCommand("tp @a 0.5 65 8.5 180 0");
                    context.waitTicks(8);
                    connection.getClientWorld().waitForChunksRender();
                    context.waitFor(client -> client.player != null && client.player.onGround() && client.screen == null);
                    context.runOnClient(client -> require(client.getSingleplayerServer() == null,
                            "The multiplayer fixture accidentally uses an integrated server"));
                    AbilityState originalClient = clientAbilities(context);
                    AbilityState originalServer = serverAbilities(server);
                    require(!originalClient.mayfly() && !originalServer.mayfly(),
                            "The survival multiplayer fixture already authorizes flight");

                    // The production Blatant card and shortcut must both work on a remote connection.
                    context.waitTicks(4);
                    context.getInput().pressKey(openGui);
                    context.waitForScreen(DonutScreen.class);
                    GuiTestInput.settle(context);
                    GuiTestInput.clickWithFade(context, "tab3", "contentProgress");
                    require(GuiTestInput.visible(context, "module6"), "Fly is missing from multiplayer Blatant modules");
                    GuiTestInput.click(context, "keybind6");
                    context.getInput().pressKey(TEST_KEY);
                    context.runOnClient(client -> require(ModuleKeybinds.mapping(MODULE).saveString()
                                    .equals(InputConstants.Type.KEYSYM.getOrCreate(TEST_KEY).getName())
                                    && !controller.isActive(),
                            "The multiplayer Fly shortcut was not captured without activating it"));
                    GuiTestInput.click(context, "enable6");
                    assertClientFlying(context, controller);
                    context.waitTicks(4);
                    originalServer.assertSame(serverAbilities(server), "Server after client-only activation");
                    assertSurvival(context, server);
                    context.takeScreenshot(TestScreenshotOptions.of("fly-multiplayer-01-blatant-active")
                            .withSize(1280, 800));
                    GuiTestInput.closeAnimated(context);
                    context.waitTicks(4);

                    // The server separately grants permission before testing accepted movement.
                    // Its real S2C packet also supplies a new baseline while the module is active.
                    server.runOnServer(mc -> {
                        var player = mc.getPlayerList().getPlayers().getFirst();
                        player.getAbilities().mayfly = true;
                        player.getAbilities().flying = false;
                        player.getAbilities().setFlyingSpeed(.065f);
                        player.onUpdateAbilities();
                    });
                    context.waitFor(client -> Math.abs(client.player.getAbilities().getFlyingSpeed() - .065f) < .00001);
                    assertClientFlying(context, controller);
                    context.waitTicks(4);
                    AbilityState authorizedClient = clientAbilities(context);
                    AbilityState authorizedServer = serverAbilities(server);
                    require(authorizedServer.mayfly(),
                            "The dedicated-server permission fixture did not authorize vanilla flight");

                    Vec3 initialClient = context.computeOnClient(client -> client.player.position());
                    Vec3 initialServer = serverPosition(server);
                    move(context, options -> options.keyJump, 10);
                    Vec3 raisedClient = context.computeOnClient(client -> client.player.position());
                    Vec3 raisedServer = serverPosition(server);
                    require(raisedClient.y > initialClient.y + .5 && raisedServer.y > initialServer.y + .5,
                            "The allow-flight server did not accept vanilla upward multiplayer movement");
                    move(context, options -> options.keyUp, 10);
                    require(context.computeOnClient(client -> client.player.position().distanceTo(raisedClient)) > .5
                                    && serverPosition(server).distanceTo(raisedServer) > .5,
                            "Forward input did not move the client and dedicated-server player");
                    double beforeDescent = context.computeOnClient(client -> client.player.getY());
                    move(context, options -> options.keyShift, 4);
                    require(context.computeOnClient(client -> client.player.getY()) < beforeDescent - .2,
                            "Sneak did not descend through vanilla multiplayer flight input");
                    Vec3 clientPosition = context.computeOnClient(client -> client.player.position());
                    require(clientPosition.distanceTo(serverPosition(server)) < 1,
                            "The dedicated server corrected the permitted vanilla flight movement");
                    AbilityState movedServer = serverAbilities(server);
                    require(movedServer.mayfly() && movedServer.flying(),
                            "The authorized server did not accept normal vanilla flight ability packets");
                    authorizedServer.assertUnrelated(movedServer, "Server after authorized vanilla flight movement");
                    authorizedClient.assertUnrelated(clientAbilities(context), "Flying multiplayer client");
                    assertSurvival(context, server);

                    context.getInput().pressKey(TEST_KEY);
                    context.waitFor(client -> !controller.isActive());
                    context.waitTicks(5);
                    context.runOnClient(client -> require(client.player.getAbilities().mayfly
                                    && !client.player.getAbilities().flying
                                    && Math.abs(client.player.getAbilities().getFlyingSpeed() - .065f) < .00001,
                            "Disabling Fly did not restore the latest remote server's granted abilities"));
                    require(serverAbilities(server).mayfly() && !serverAbilities(server).flying(),
                            "Normal disable packets did not leave the authorized server player nonflying");

                    context.getInput().pressKey(TEST_KEY);
                    assertClientFlying(context, controller);
                    // Then the remote server revokes permission; disabling must restore that newer baseline.
                    server.runOnServer(mc -> {
                        var player = mc.getPlayerList().getPlayers().getFirst();
                        player.getAbilities().mayfly = false;
                        player.getAbilities().flying = false;
                        player.getAbilities().setFlyingSpeed(.075f);
                        player.onUpdateAbilities();
                    });
                    context.waitFor(client -> Math.abs(client.player.getAbilities().getFlyingSpeed() - .075f) < .00001);
                    assertClientFlying(context, controller);
                    context.getInput().pressKey(TEST_KEY);
                    context.waitFor(client -> !controller.isActive());
                    context.waitTicks(5);
                    context.runOnClient(client -> require(!client.player.getAbilities().mayfly
                                    && !client.player.getAbilities().flying
                                    && Math.abs(client.player.getAbilities().getFlyingSpeed() - .075f) < .00001,
                            "Disabling Fly restored stale permissions after the remote server revoked flight"));
                    require(!serverAbilities(server).mayfly() && !serverAbilities(server).flying(),
                            "Client-side Fly changed the dedicated server's revoked flight permissions");
                    assertSurvival(context, server);

                    context.getInput().pressKey(TEST_KEY);
                    assertClientFlying(context, controller);
                    // The connection closes with Fly active to exercise the actual disconnect event.
                }
                context.runOnClient(client -> require(!controller.isActive() && !controller.settings().enabled,
                        "Remote disconnect did not clear Fly activation"));
                context.waitTicks(4);

                try (var connection = server.connect()) {
                    context.waitTicks(6);
                    context.runOnClient(client -> require(client.getSingleplayerServer() == null
                                    && !controller.isActive() && !controller.settings().enabled
                                    && !client.player.getAbilities().mayfly && !client.player.getAbilities().flying,
                            "Reconnecting to the dedicated server reapplied flight or stale ability flags"));
                    require(!serverAbilities(server).mayfly() && !serverAbilities(server).flying(),
                            "Client Fly leaked temporary permissions into the remote server's saved player");
                }
            }
        } finally {
            context.getInput().releaseKey(TEST_KEY);
            context.getInput().releaseKey(options -> options.keyUp);
            context.getInput().releaseKey(options -> options.keyJump);
            context.getInput().releaseKey(options -> options.keyShift);
            context.runOnClient(client -> {
                controller.disable();
                ModuleKeybinds.mapping(MODULE).setKey(InputConstants.getKey(originalModuleKey));
                openGui.setKey(InputConstants.getKey(originalOpenKey));
                KeyMapping.resetMapping();
                client.options.guiScale().set(originalScale);
                client.options.setCameraType(originalCamera);
                client.options.save();
                client.resizeDisplay();
            });
        }
    }

    private static void assertClientFlying(ClientGameTestContext context, FlyController controller) {
        context.waitFor(client -> controller.isActive() && client.player.getAbilities().mayfly
                && client.player.getAbilities().flying);
    }

    private static void assertSurvival(ClientGameTestContext context, TestServerContext server) {
        context.runOnClient(client -> require(client.gameMode.getPlayerMode() == GameType.SURVIVAL,
                "Fly changed the remote client's game mode"));
        server.runOnServer(mc -> require(mc.getPlayerList().getPlayers().getFirst().gameMode() == GameType.SURVIVAL,
                "Fly changed the dedicated server player's game mode"));
    }

    private static Vec3 serverPosition(TestServerContext server) {
        return server.computeOnServer(mc -> mc.getPlayerList().getPlayers().getFirst().position());
    }

    private static AbilityState clientAbilities(ClientGameTestContext context) {
        return context.computeOnClient(client -> AbilityState.of(client.player.getAbilities()));
    }

    private static AbilityState serverAbilities(TestServerContext server) {
        return server.computeOnServer(mc -> AbilityState.of(mc.getPlayerList().getPlayers().getFirst().getAbilities()));
    }

    private static void move(ClientGameTestContext context, Function<Options, KeyMapping> key, int ticks) {
        context.getInput().holdKey(key);
        context.waitTicks(ticks);
        context.getInput().releaseKey(key);
        context.waitTicks(3);
    }

    private static int availablePort() {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            return socket.getLocalPort();
        } catch (IOException error) {
            throw new AssertionError("Cannot reserve a local dedicated-server test port", error);
        }
    }

    private static boolean acceptedEula(Path eulaFile) {
        Properties properties = new Properties();
        try (var reader = Files.newBufferedReader(eulaFile)) {
            properties.load(reader);
            return Boolean.parseBoolean(properties.getProperty("eula", "false"));
        } catch (IOException error) {
            LOGGER.debug("Cannot read the dedicated-server EULA flag in {}", eulaFile, error);
            return false;
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private record AbilityState(boolean mayfly, boolean flying, boolean invulnerable, boolean instabuild,
                                boolean mayBuild, float flyingSpeed, float walkingSpeed) {
        static AbilityState of(Abilities abilities) {
            return new AbilityState(abilities.mayfly, abilities.flying, abilities.invulnerable,
                    abilities.instabuild, abilities.mayBuild, abilities.getFlyingSpeed(), abilities.getWalkingSpeed());
        }

        void assertSame(AbilityState actual, String label) {
            require(equals(actual), label + " abilities changed: before=" + this + ", after=" + actual);
        }

        void assertUnrelated(AbilityState actual, String label) {
            require(invulnerable == actual.invulnerable && instabuild == actual.instabuild
                            && mayBuild == actual.mayBuild && flyingSpeed == actual.flyingSpeed
                            && walkingSpeed == actual.walkingSpeed,
                    label + " unrelated abilities or vanilla speeds changed");
        }
    }
}
