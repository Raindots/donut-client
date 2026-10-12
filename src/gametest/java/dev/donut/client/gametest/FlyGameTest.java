package dev.donut.client.gametest;

import com.mojang.blaze3d.platform.InputConstants;
import dev.donut.client.config.ClientConfig;
import dev.donut.client.config.ConfigStore;
import dev.donut.client.fly.FlyController;
import dev.donut.client.gui.DonutScreen;
import dev.donut.client.input.ModuleKeybinds;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.function.Function;
import javax.imageio.ImageIO;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.CameraType;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Options;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.entity.player.Abilities;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

/** Exercises vanilla flight with the production module and an authoritative integrated server. */
public final class FlyGameTest implements FabricClientGameTest {
    private static final int MODULE = 6;
    private static final int TEST_KEY = GLFW.GLFW_KEY_F7;

    @Override
    public void runTest(ClientGameTestContext context) {
        FlyController controller = FlyController.getInstance();
        require(controller != null, "The production Fly controller was not initialized");
        ConfigStore store = new ConfigStore(FabricLoader.getInstance().getConfigDir().resolve("donut-client.json"));
        ClientConfig originalConfig = store.load();
        int originalScale = context.computeOnClient(client -> client.options.guiScale().get());
        boolean originalHideGui = context.computeOnClient(client -> client.options.hideGui);
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
            client.options.hideGui = false;
            client.options.setCameraType(CameraType.FIRST_PERSON);
            client.resizeDisplay();
        });
        context.getInput().resizeWindow(1280, 800);

        try {
            try (var world = context.worldBuilder().create()) {
                TestServerContext server = world.getServer();
                server.runCommand("gamemode survival @a");
                server.runCommand("time set noon");
                server.runCommand("fill -30 64 -30 30 64 30 minecraft:smooth_stone");
                server.runCommand("spawnpoint @a 0 65 8");
                server.runCommand("tp @a 0.5 65 8.5 180 0");
                context.waitTicks(8);
                world.getClientWorld().waitForChunksRender();
                context.waitFor(client -> client.player != null && client.player.onGround());
                FlightAbilities survivalClient = clientAbilities(context);
                FlightAbilities survivalServer = serverAbilities(server);
                require(!survivalClient.mayfly() && !survivalServer.mayfly(),
                        "The survival fixture already allows flight");

                ClientConfig[] screenConfig = new ClientConfig[1];
                try {
                    openGui(context, openGui);
                    screenConfig[0] = context.computeOnClient(client -> screenConfig((DonutScreen) client.screen));
                    require(!GuiTestInput.visible(context, "module6"), "Fly appeared in the Visual category");
                    if (!store.load().showHud) {
                        GuiTestInput.clickWithFade(context, "tab2", "contentProgress");
                        GuiTestInput.click(context, "hud");
                    }
                    GuiTestInput.clickWithFade(context, "tab3", "contentProgress");
                    require(GuiTestInput.visible(context, "module6"), "Fly is missing from Blatant");
                    GuiTestInput.assertExpansionReverses(context, MODULE);
                    GuiTestInput.rightClick(context, "module6");
                    context.runOnClient(client -> require(!FlyController.enabled() && !controller.isActive(),
                            "Opening Fly settings activated flight"));
                    if (store.load().fly.favorite) GuiTestInput.click(context, "favorite6");
                    GuiTestInput.click(context, "favorite6");
                    GuiTestInput.clickWithFade(context, "tab1", "contentProgress");
                    require(store.load().fly.favorite && GuiTestInput.visible(context, "module6"),
                            "Fly did not appear in Favorites or persist its favorite preference");
                    GuiTestInput.clickWithFade(context, "tab3", "contentProgress");
                    GuiTestInput.click(context, "keybind6");
                    context.getInput().pressKey(TEST_KEY);
                    context.runOnClient(client -> {
                        KeyMapping mapping = ModuleKeybinds.mapping(MODULE);
                        require(mapping.getName().equals("key.donut.toggle_fly"), "Fly has the wrong Controls entry");
                        require(mapping.saveString().equals(InputConstants.Type.KEYSYM.getOrCreate(TEST_KEY).getName()),
                                "The Fly card did not capture its shortcut");
                        require(!controller.isActive(), "Capturing the Fly shortcut activated flight");
                    });
                    GuiTestInput.click(context, "enable6");
                    context.waitFor(client -> controller.isActive());
                    require(context.computeOnClient(client -> controller.settings().enabled) && !store.load().fly.enabled,
                            "The Fly switch did not keep active flight transient");
                    assertFlying(context, server);
                    GuiTestInput.click(context, "favorite6");
                    require(context.computeOnClient(client -> controller.isActive() && controller.settings().enabled)
                                    && !store.load().fly.enabled,
                            "Saving a favorite preference stopped active Fly or persisted temporary activation");
                    screenshot(context, "fly-01-blatant-active");
                    GuiTestInput.click(context, "enable6");
                    context.waitFor(client -> !controller.isActive());
                    GuiTestInput.closeAnimated(context);
                    context.waitTicks(4);
                    long hudOff = whiteHudPixels(screenshot(context, "fly-02-hud-off"));

                    // Exercise the real keyboard route, including a held key and a repeated GLFW click.
                    context.getInput().holdKey(TEST_KEY);
                    context.waitTicks(6);
                    require(context.computeOnClient(client -> controller.isActive() && controller.settings().enabled)
                                    && !store.load().fly.enabled,
                            "The production Fly shortcut did not enable the module");
                    context.runOnClient(client -> KeyMapping.click(InputConstants.Type.KEYSYM.getOrCreate(TEST_KEY)));
                    context.waitTicks(2);
                    context.runOnClient(client -> require(controller.isActive(),
                            "A repeated held Fly shortcut toggled flight again"));
                    context.getInput().releaseKey(TEST_KEY);
                    assertFlying(context, server);
                    long hudOn = whiteHudPixels(screenshot(context, "fly-03-hud-on"));
                    require(hudOn > hudOff + 12, "The active Fly module did not add rendered HUD text");

                    Vec3 clientStart = context.computeOnClient(client -> client.player.position());
                    Vec3 serverStart = server.computeOnServer(mc -> mc.getPlayerList().getPlayers().getFirst().position());
                    move(context, options -> options.keyUp, 12);
                    Vec3 clientMoved = context.computeOnClient(client -> client.player.position());
                    Vec3 serverMoved = server.computeOnServer(mc -> mc.getPlayerList().getPlayers().getFirst().position());
                    require(clientMoved.distanceTo(clientStart) > .5 && serverMoved.distanceTo(serverStart) > .5,
                            "Forward flight did not move both the real client and server player");
                    require(clientMoved.distanceTo(serverMoved) < 1,
                            "The server rejected or corrected survival flight movement");
                    context.runOnClient(client -> require(client.getCameraEntity() == client.player
                                    && !client.gameRenderer.getMainCamera().isDetached(),
                            "Fly detached the camera instead of moving the actual player"));

                    double beforeRise = context.computeOnClient(client -> client.player.getY());
                    move(context, options -> options.keyJump, 12);
                    double afterRise = context.computeOnClient(client -> client.player.getY());
                    require(afterRise > beforeRise + .7, "Jump did not raise the flying player");
                    move(context, options -> options.keyShift, 6);
                    double afterDescent = context.computeOnClient(client -> client.player.getY());
                    require(afterDescent < afterRise - .3, "Sneak did not lower the flying player");
                    move(context, options -> options.keyJump, 16);
                    context.waitTicks(8);
                    double hoverY = context.computeOnClient(client -> client.player.getY());
                    context.waitTicks(10);
                    require(Math.abs(context.computeOnClient(client -> client.player.getY()) - hoverY) < .1,
                            "The player fell while Fly was active without movement input");
                    assertFlying(context, server);
                    survivalClient.assertOtherAbilities(clientAbilities(context), "Survival client");
                    survivalServer.assertOtherAbilities(serverAbilities(server), "Survival server");
                    // Exercise the actual player-save mixin, then read its compressed NBT from disk.
                    server.runOnServer(mc -> {
                        var player = mc.getPlayerList().getPlayers().getFirst();
                        mc.getPlayerList().saveAll();
                        Path savedPlayer = mc.getWorldPath(LevelResource.PLAYER_DATA_DIR)
                                .resolve(player.getUUID() + ".dat");
                        try {
                            var abilities = NbtIo.readCompressed(savedPlayer, NbtAccounter.unlimitedHeap())
                                    .getCompound("abilities").orElseThrow();
                            require(!abilities.getBoolean("mayfly").orElseThrow()
                                            && !abilities.getBoolean("flying").orElseThrow(),
                                    "Autosaving while Fly is active persisted temporary survival flight");
                            require(player.getAbilities().mayfly && player.getAbilities().flying,
                                    "Saving player data revoked live flight abilities");
                        } catch (IOException error) {
                            throw new AssertionError("Cannot inspect saved survival flight abilities", error);
                        }
                    });
                    screenshot(context, "fly-04-survival-hover");

                    context.getInput().pressKey(TEST_KEY);
                    context.waitFor(client -> !controller.isActive());
                    context.waitTicks(6);
                    survivalClient.assertSame(clientAbilities(context), "Disabled survival client");
                    survivalServer.assertSame(serverAbilities(server), "Disabled survival server");
                    require(context.computeOnClient(client -> client.player.getY()) < hoverY - .4,
                            "Disabling Fly did not restore gravity");
                    context.waitFor(client -> client.player.onGround());

                    // A different game mode supplies a fresh vanilla baseline for eventual restoration.
                    context.runOnClient(client -> controller.setEnabled(true));
                    assertFlying(context, server);
                    server.runCommand("gamemode creative @a");
                    context.waitFor(client -> client.player.getAbilities().instabuild);
                    assertFlying(context, server);
                    context.runOnClient(client -> controller.disable());
                    context.waitTicks(6);
                    require(clientAbilities(context).mayfly() && !clientAbilities(context).flying()
                                    && serverAbilities(server).mayfly() && !serverAbilities(server).flying(),
                            "A survival-to-creative transition did not restore creative's default flight abilities");

                    assertPreserved(context, server, controller, "Creative while grounded", false);
                    // A player already flying in creative must retain that state and their vanilla speeds.
                    server.runCommand("tp @a 0.5 75 8.5 180 0");
                    server.runOnServer(mc -> {
                        var player = mc.getPlayerList().getPlayers().getFirst();
                        player.getAbilities().flying = true;
                        player.onUpdateAbilities();
                    });
                    context.waitFor(client -> client.player.getY() > 70 && client.player.getAbilities().flying);
                    assertPreserved(context, server, controller, "Creative", true);
                    server.runCommand("gamemode spectator @a");
                    context.waitFor(client -> client.player.isSpectator());
                    assertPreserved(context, server, controller, "Spectator", true);

                    context.runOnClient(client -> controller.setEnabled(true));
                    assertFlying(context, server);
                    server.runCommand("gamemode survival @a");
                    context.waitFor(client -> !client.player.isSpectator() && !client.player.getAbilities().instabuild);
                    assertFlying(context, server);
                    context.runOnClient(client -> controller.disable());
                    context.waitTicks(6);
                    survivalClient.assertSame(clientAbilities(context), "Spectator-to-survival client");
                    survivalServer.assertSame(serverAbilities(server), "Spectator-to-survival server");

                    context.runOnClient(client -> controller.setEnabled(true));
                    assertFlying(context, server);
                    server.runCommand("kill @a");
                    context.waitFor(client -> !controller.isActive() && !controller.settings().enabled);
                    context.waitTicks(4);
                    survivalServer.assertSame(serverAbilities(server), "Dead survival player");
                    context.runOnClient(client -> client.player.respawn());
                    context.waitFor(client -> client.player != null && client.player.isAlive());
                    context.setScreen(() -> null);
                    context.waitTicks(6);
                    context.runOnClient(client -> require(!controller.isActive() && !controller.settings().enabled,
                            "Respawning reapplied flight to the new player"));
                    survivalClient.assertSame(clientAbilities(context), "Respawned survival client");
                    survivalServer.assertSame(serverAbilities(server), "Respawned survival server");
                } finally {
                    context.getInput().releaseKey(TEST_KEY);
                    context.setScreen(() -> null);
                    context.runOnClient(client -> {
                        controller.disable();
                        controller.settings().favorite = originalConfig.fly.favorite;
                        if (screenConfig[0] != null) {
                            screenConfig[0].showHud = originalConfig.showHud;
                            store.save(screenConfig[0]);
                        }
                    });
                }

                // Leave flight active deliberately: the real disconnect event must revoke the module.
                context.runOnClient(client -> controller.setEnabled(true));
                assertFlying(context, server);
            }
            context.runOnClient(client -> require(!controller.isActive() && !FlyController.enabled(),
                    "Disconnect did not leave Fly disabled"));
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
                client.options.hideGui = originalHideGui;
                client.options.setCameraType(originalCamera);
                client.options.save();
                client.resizeDisplay();
            });
        }
    }

    private static void assertPreserved(ClientGameTestContext context, TestServerContext server,
                                        FlyController controller, String mode, boolean expectedFlying) {
        context.waitTicks(4);
        FlightAbilities clientBefore = clientAbilities(context);
        FlightAbilities serverBefore = serverAbilities(server);
        require(clientBefore.flying() == expectedFlying && serverBefore.flying() == expectedFlying,
                mode + " fixture does not have its expected existing flight state");
        context.runOnClient(client -> controller.setEnabled(true));
        assertFlying(context, server);
        context.runOnClient(client -> controller.disable());
        context.waitTicks(6);
        clientBefore.assertSame(clientAbilities(context), mode + " client");
        serverBefore.assertSame(serverAbilities(server), mode + " server");
    }

    private static void assertFlying(ClientGameTestContext context, TestServerContext server) {
        context.waitFor(client -> client.player.getAbilities().mayfly && client.player.getAbilities().flying);
        context.waitTicks(5);
        FlightAbilities authoritative = serverAbilities(server);
        require(authoritative.mayfly() && authoritative.flying(),
                "The integrated server did not authorize Fly's vanilla flight abilities");
    }

    private static void move(ClientGameTestContext context, Function<Options, KeyMapping> key, int ticks) {
        context.getInput().holdKey(key);
        context.waitTicks(ticks);
        context.getInput().releaseKey(key);
        context.waitTicks(3);
    }

    private static void openGui(ClientGameTestContext context, KeyMapping openGui) {
        context.waitForScreen(null);
        context.waitTicks(4);
        context.getInput().pressKey(openGui);
        context.waitForScreen(DonutScreen.class);
        GuiTestInput.settle(context);
    }

    private static ClientConfig screenConfig(DonutScreen screen) {
        try {
            Field field = DonutScreen.class.getDeclaredField("config");
            field.setAccessible(true);
            return (ClientConfig) field.get(screen);
        } catch (ReflectiveOperationException error) {
            throw new AssertionError("Cannot retain the production screen configuration for test cleanup", error);
        }
    }

    private static FlightAbilities clientAbilities(ClientGameTestContext context) {
        return context.computeOnClient(client -> FlightAbilities.of(client.player.getAbilities()));
    }

    private static FlightAbilities serverAbilities(TestServerContext server) {
        return server.computeOnServer(mc -> FlightAbilities.of(mc.getPlayerList().getPlayers().getFirst().getAbilities()));
    }

    private static Path screenshot(ClientGameTestContext context, String name) {
        return context.takeScreenshot(TestScreenshotOptions.of(name).withSize(1280, 800));
    }

    private static long whiteHudPixels(Path screenshot) {
        try {
            BufferedImage image = ImageIO.read(screenshot.toFile());
            require(image != null, "Cannot decode the Fly HUD screenshot");
            long count = 0;
            for (int y = 8; y < 125; y++) {
                for (int x = image.getWidth() - 48; x < image.getWidth() - 8; x++) {
                    int pixel = image.getRGB(x, y);
                    int red = (pixel >> 16) & 255, green = (pixel >> 8) & 255, blue = pixel & 255;
                    if (red > 190 && green > 190 && blue > 190
                            && Math.abs(red - green) < 20 && Math.abs(red - blue) < 20) count++;
                }
            }
            return count;
        } catch (IOException error) {
            throw new AssertionError("Cannot inspect rendered Fly HUD pixels", error);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private record FlightAbilities(boolean mayfly, boolean flying, boolean invulnerable, boolean instabuild,
                                   boolean mayBuild, float flyingSpeed, float walkingSpeed) {
        static FlightAbilities of(Abilities abilities) {
            return new FlightAbilities(abilities.mayfly, abilities.flying, abilities.invulnerable,
                    abilities.instabuild, abilities.mayBuild, abilities.getFlyingSpeed(), abilities.getWalkingSpeed());
        }

        void assertSame(FlightAbilities actual, String label) {
            require(equals(actual), label + " abilities were not restored: before=" + this + ", after=" + actual);
        }

        void assertOtherAbilities(FlightAbilities actual, String label) {
            require(invulnerable == actual.invulnerable && instabuild == actual.instabuild
                            && mayBuild == actual.mayBuild && flyingSpeed == actual.flyingSpeed
                            && walkingSpeed == actual.walkingSpeed,
                    label + " unrelated abilities or vanilla movement speeds changed during flight");
        }
    }
}
