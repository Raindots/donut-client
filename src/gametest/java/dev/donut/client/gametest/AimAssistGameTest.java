package dev.donut.client.gametest;

import com.mojang.authlib.GameProfile;
import com.mojang.blaze3d.platform.InputConstants;
import dev.donut.client.config.AimAssistSettings;
import dev.donut.client.config.ClientConfig;
import dev.donut.client.gui.DonutScreen;
import dev.donut.client.input.ModuleKeybinds;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import org.lwjgl.glfw.GLFW;

import java.util.UUID;

/** Checks visible rotation through the normal renderer and settings through native GUI input. */
public final class AimAssistGameTest implements FabricClientGameTest {
    private static final int FOV_TARGET = 2_000_000_120;
    private static final int NEAR_TARGET = 2_000_000_121;
    private static final BlockPos WALL = new BlockPos(0, 66, 5);

    @Override
    public void runTest(ClientGameTestContext context) {
        try (var world = context.worldBuilder().create()) {
            TestServerContext server = world.getServer();
            prepare(context, server);
            verifyInspector(context);
            ClientConfig config = productionConfig(context);
            AimAssistSettings original = config.aimAssist;
            AimAssistSettings settings = new AimAssistSettings();
            settings.speed = 1;
            context.runOnClient(client -> {
                config.aimAssist = settings;
                client.setWindowActive(true);
                addTarget(client, FOV_TARGET, "AimForward", 1.0, 6.4);
                addTarget(client, NEAR_TARGET, "AimNearby", -0.45, 4.9);
            });
            try {
                verifyTracking(context, settings);
                verifyVisibilityAndInput(context, server, settings);
            } finally {
                context.runOnClient(client -> {
                    config.aimAssist = original;
                    client.options.keyUse.setDown(false);
                    if (client.screen instanceof InventoryScreen) client.player.closeContainer();
                    client.level.removeEntity(FOV_TARGET, Entity.RemovalReason.DISCARDED);
                    client.level.removeEntity(NEAR_TARGET, Entity.RemovalReason.DISCARDED);
                });
            }
        }
    }

    private static void prepare(ClientGameTestContext context, TestServerContext server) {
        server.runCommand("fill -4 65 0 4 68 9 minecraft:air");
        server.runCommand("fill -4 64 0 4 64 9 minecraft:smooth_stone");
        server.runCommand("tp @a 0.5 65 3.5 0 0");
        server.runCommand("item replace entity @a hotbar.0 with minecraft:iron_sword");
        server.runCommand("item replace entity @a hotbar.1 with minecraft:iron_pickaxe");
        server.runCommand("item replace entity @a hotbar.2 with minecraft:air");
        context.waitFor(client -> client.player.onGround() && client.player.getInventory().getItem(0).is(Items.IRON_SWORD)
                && client.player.getInventory().getItem(1).is(Items.IRON_PICKAXE));
        context.runOnClient(client -> client.player.getInventory().setSelectedSlot(0));
    }

    private static void verifyInspector(ClientGameTestContext context) {
        var isolated = new ClientConfig();
        int module = ModuleKeybinds.AIM_ASSIST;
        String prefix = "m" + module + ".";
        int originalScale = context.computeOnClient(client -> client.options.guiScale().get());
        String originalBinding = context.computeOnClient(client -> ModuleKeybinds.mapping(module).saveString());
        context.getInput().resizeWindow(1280, 800);
        context.runOnClient(client -> {
            client.options.guiScale().set(1);
            client.resizeDisplay();
        });
        try {
            GuiTestInput.openAnimated(context, () -> new DonutScreen(isolated, () -> {}));
            GuiTestInput.click(context, "tab7");
            GuiTestInput.click(context, "module" + module);
            require(!GuiTestInput.visible(context, "module2"), "A non-combat tile appeared under Combat");
            GuiTestInput.click(context, "enable" + module);
            GuiTestInput.click(context, prefix + "targetMode");
            GuiTestInput.click(context, prefix + "aimPart");
            GuiTestInput.click(context, prefix + "throughWalls");
            GuiTestInput.click(context, prefix + "everything");
            require(isolated.aimAssist.enabled && isolated.aimAssist.throughWalls && isolated.aimAssist.everything,
                    "Aim Assist toggles did not update the settings");
            require(isolated.aimAssist.targetMode == AimAssistSettings.TargetMode.CLOSEST
                            && isolated.aimAssist.aimPart == AimAssistSettings.AimPart.LEGS,
                    "Aim Assist mode and part controls did not update the settings");
            require(!GuiTestInput.visible(context, prefix + "swords"), "Everything left weapon filters visible");
            GuiTestInput.click(context, "keybind" + module);
            context.getInput().pressKey(GLFW.GLFW_KEY_F8);
            require(context.computeOnClient(client -> ModuleKeybinds.mapping(module).saveString().equals("key.keyboard.f8")),
                    "Aim Assist keybind did not update Minecraft Controls");
            GuiTestInput.click(context, "favorite" + module);
            GuiTestInput.click(context, "tab1");
            require(GuiTestInput.visible(context, "module" + module), "Aim Assist did not appear in Favorites");
            GuiTestInput.click(context, "tab5");
            GuiTestInput.click(context, "search");
            GuiTestInput.type(context, "aimassist");
            require(GuiTestInput.visible(context, "module" + module), "Searching aimassist did not find the tile");
            GuiTestInput.closeAnimated(context);
        } finally {
            context.runOnClient(client -> {
                ModuleKeybinds.setKey(client, module, InputConstants.getKey(originalBinding));
                client.options.guiScale().set(originalScale);
                client.resizeDisplay();
            });
        }
    }

    private static ClientConfig productionConfig(ClientGameTestContext context) {
        context.waitTicks(2);
        KeyMapping open = context.computeOnClient(client -> KeyMapping.get("key.donut.open_gui"));
        context.getInput().pressKey(open);
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

    private static void verifyTracking(ClientGameTestContext context, AimAssistSettings settings) {
        context.runOnClient(AimAssistGameTest::centerView);
        requireStill(context, "Disabled Aim Assist moved the view");
        context.runOnClient(client -> settings.enabled = true);
        context.waitFor(client -> client.player.getYRot() < -0.25 && client.player.getXRot() > 0.1);

        context.runOnClient(client -> {
            settings.targetMode = AimAssistSettings.TargetMode.CLOSEST;
            moveTarget((RemotePlayer) client.level.getEntity(FOV_TARGET), 1.0, 7.2);
            centerView(client);
        });
        context.waitFor(client -> client.player.getYRot() < -0.25);
        context.runOnClient(client -> {
            moveTarget((RemotePlayer) client.level.getEntity(FOV_TARGET), 1.0, 8.3);
            centerView(client);
        });
        context.waitFor(client -> client.player.getYRot() > 0.25);

        context.runOnClient(client -> {
            var attacked = (RemotePlayer) client.level.getEntity(FOV_TARGET);
            moveTarget(attacked, 1.0, 6.4);
            centerView(client);
            var result = AttackEntityCallback.EVENT.invoker().interact(client.player, client.level, InteractionHand.MAIN_HAND, attacked, null);
            require(result == InteractionResult.PASS, "Aim Assist consumed a normal attack callback");
        });
        context.waitFor(client -> client.player.getYRot() < -0.25);
    }

    private static void verifyVisibilityAndInput(ClientGameTestContext context, TestServerContext server, AimAssistSettings settings) {
        server.runCommand("setblock 0 66 5 minecraft:obsidian");
        context.waitFor(client -> client.level.getBlockState(WALL).is(Blocks.OBSIDIAN));
        context.runOnClient(AimAssistGameTest::centerView);
        context.waitFor(client -> client.player.getYRot() > 0.25);
        context.runOnClient(client -> {
            client.level.removeEntity(NEAR_TARGET, Entity.RemovalReason.DISCARDED);
            centerView(client);
        });
        requireStill(context, "Aim Assist tracked an occluded player with Through Walls disabled");
        context.runOnClient(client -> settings.throughWalls = true);
        context.waitFor(client -> client.player.getYRot() < -0.25);

        context.runOnClient(client -> {
            settings.fov = 10;
            centerView(client);
        });
        requireStill(context, "Aim Assist ignored its FOV limit");
        context.runOnClient(client -> {
            settings.fov = 90;
            client.player.getInventory().setSelectedSlot(1);
            centerView(client);
        });
        requireStill(context, "The axe filter incorrectly accepted a pickaxe");
        context.runOnClient(client -> {
            settings.everything = true;
            client.player.getInventory().setSelectedSlot(2);
        });
        context.waitFor(client -> client.player.getYRot() < -0.25);

        context.runOnClient(client -> {
            client.options.keyUse.setDown(true);
            centerView(client);
        });
        requireStill(context, "Aim Assist steered while Use was held");
        context.runOnClient(client -> {
            client.options.keyUse.setDown(false);
            client.setScreen(new InventoryScreen(client.player));
            centerView(client);
        });
        requireStill(context, "Aim Assist steered while the inventory was open");
        context.runOnClient(client -> client.player.closeContainer());
        context.waitFor(client -> client.player.getYRot() < -0.25);
    }

    private static void addTarget(Minecraft client, int id, String name, double x, double z) {
        var target = new RemotePlayer(client.level, new GameProfile(UUID.randomUUID(), name));
        target.setId(id);
        moveTarget(target, x, z);
        client.level.addEntity(target);
    }

    private static void moveTarget(RemotePlayer target, double x, double z) {
        target.setPos(x, 65, z);
        target.setOldPosAndRot();
    }

    private static void centerView(Minecraft client) {
        client.player.setYRot(0);
        client.player.setXRot(0);
        client.player.setOldPosAndRot();
    }

    private static void requireStill(ClientGameTestContext context, String message) {
        context.waitTicks(4);
        require(context.computeOnClient(client -> Math.abs(client.player.getYRot()) < 0.01 && Math.abs(client.player.getXRot()) < 0.01), message);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
