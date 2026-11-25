package dev.donut.client.gametest;

import dev.donut.client.combat.AutoTotemController;
import dev.donut.client.config.AutoTotemSettings;
import dev.donut.client.config.ClientConfig;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.function.Predicate;

/** Checks server-authoritative transfers through both Auto Totem modes. */
public final class AutoTotemGameTest implements FabricClientGameTest {
    private static final int RESERVE_INDEX = 4;

    @Override
    public void runTest(ClientGameTestContext context) {
        var config = new ClientConfig();
        var controller = new AutoTotemController(config);
        try (var world = context.worldBuilder().create()) {
            TestServerContext server = world.getServer();
            server.runCommand("gamemode survival @a");
            context.waitFor(client -> client.player != null && !client.player.isCreative());
            try {
                inventoryMode(context, server, controller, config.autoTotem);
                hotbarMode(context, server, controller, config.autoTotem);
            } finally {
                context.runOnClient(controller::disconnect);
            }
            require(context.computeOnClient(client -> !config.autoTotem.enabled && !controller.ownsInventory()),
                    "Disconnect retained Auto Totem activation or inventory ownership");
        }
    }

    private static void inventoryMode(ClientGameTestContext context, TestServerContext server,
            AutoTotemController controller, AutoTotemSettings settings) {
        server.runCommand("item replace entity @a inventory.0 with minecraft:totem_of_undying");
        server.runCommand("item replace entity @a inventory.1 with minecraft:totem_of_undying");
        server.runCommand("item replace entity @a hotbar.4 with minecraft:iron_sword");
        server.runCommand("item replace entity @a weapon.offhand with minecraft:shield");
        context.waitFor(client -> client.player.getOffhandItem().is(Items.SHIELD)
                && client.player.getInventory().getItem(9).is(Items.TOTEM_OF_UNDYING)
                && client.player.getInventory().getItem(10).is(Items.TOTEM_OF_UNDYING)
                && client.player.getInventory().getItem(RESERVE_INDEX).is(Items.IRON_SWORD));

        context.runOnClient(client -> settings.enabled = true);
        advance(context, controller, 6);
        require(serverHas(server, player -> player.getOffhandItem().is(Items.SHIELD)),
                "Auto Totem replaced the offhand before it was armed");
        require(context.computeOnClient(client -> client.screen == null), "Unarmed Auto Totem opened the inventory");

        arm(context, server, controller);
        server.runCommand("item replace entity @a weapon.offhand with minecraft:shield");
        context.waitFor(client -> client.player.getOffhandItem().is(Items.SHIELD));
        context.runOnClient(client -> {
            settings.hotbarRefill = true;
            settings.hotbarSlot = RESERVE_INDEX + 1;
            settings.reactionDelay = 2;
            settings.closeDelay = 2;
        });
        advance(context, controller, 2);
        require(context.computeOnClient(client -> client.screen == null), "Inventory opened before the reaction delay elapsed");
        advance(context, controller, 1);
        require(context.computeOnClient(client -> client.screen instanceof InventoryScreen && controller.ownsInventory()),
                "Auto Totem did not open and take ownership of its inventory screen");

        until(context, server, controller, player -> player.getOffhandItem().is(Items.TOTEM_OF_UNDYING)
                && player.getInventory().getItem(RESERVE_INDEX).is(Items.TOTEM_OF_UNDYING),
                "Server did not receive the offhand and reserve totems");
        require(serverHas(server, player -> player.getInventory().getItem(9).is(Items.SHIELD)
                        && player.getInventory().getItem(10).is(Items.IRON_SWORD)),
                "Inventory swaps did not preserve both displaced items in their source slots");
        advance(context, controller, 16);
        require(context.computeOnClient(client -> client.screen == null && !controller.ownsInventory()),
                "Auto Totem did not close and release its own inventory");

        server.runCommand("item replace entity @a inventory.2 with minecraft:totem_of_undying");
        server.runCommand("item replace entity @a weapon.offhand with minecraft:air");
        context.waitFor(client -> client.player.getOffhandItem().isEmpty()
                && client.player.getInventory().getItem(11).is(Items.TOTEM_OF_UNDYING));
        var manualScreen = context.computeOnClient(client -> {
            var inventory = new InventoryScreen(client.player);
            client.setScreen(inventory);
            inventory.getMenu().setCarried(new ItemStack(Items.STICK));
            return inventory;
        });
        advance(context, controller, 12);
        require(serverHas(server, player -> player.getOffhandItem().isEmpty()),
                "Inventory mode swapped items while the cursor was carrying a stack");
        require(context.computeOnClient(client -> client.screen == manualScreen
                        && manualScreen.getMenu().getCarried().is(Items.STICK)),
                "Auto Totem changed the player's inventory screen or carried stack");

        context.runOnClient(client -> manualScreen.getMenu().setCarried(ItemStack.EMPTY));
        until(context, server, controller, player -> player.getOffhandItem().is(Items.TOTEM_OF_UNDYING),
                "Inventory mode did not refill after clearing the cursor");
        advance(context, controller, 16);
        require(context.computeOnClient(client -> client.screen == manualScreen),
                "Auto Totem closed the player's own inventory screen");
        context.runOnClient(client -> client.player.closeContainer());
        context.waitFor(client -> client.screen == null);
    }

    private static void hotbarMode(ClientGameTestContext context, TestServerContext server,
            AutoTotemController controller, AutoTotemSettings settings) {
        context.runOnClient(client -> settings.enabled = false);
        advance(context, controller, 1);
        server.runCommand("item replace entity @a hotbar.0 with minecraft:diamond_sword");
        server.runCommand("item replace entity @a hotbar.4 with minecraft:air");
        server.runCommand("item replace entity @a inventory.3 with minecraft:totem_of_undying");
        server.runCommand("item replace entity @a weapon.offhand with minecraft:totem_of_undying");
        context.waitFor(client -> client.player.getOffhandItem().is(Items.TOTEM_OF_UNDYING)
                && client.player.getInventory().getItem(0).is(Items.DIAMOND_SWORD)
                && client.player.getInventory().getItem(12).is(Items.TOTEM_OF_UNDYING)
                && client.player.getInventory().getItem(RESERVE_INDEX).isEmpty());
        context.runOnClient(client -> {
            settings.mode = AutoTotemSettings.Mode.HOTBAR_SWAP;
            settings.enabled = true;
            settings.reactionDelay = 0;
            client.player.getInventory().setSelectedSlot(0);
        });
        advance(context, controller, 1);

        server.runCommand("item replace entity @a weapon.offhand with minecraft:shield");
        context.waitFor(client -> client.player.getOffhandItem().is(Items.SHIELD));
        advance(context, controller, 6);
        require(serverHas(server, player -> player.getOffhandItem().is(Items.SHIELD)
                        && player.getInventory().getItem(12).is(Items.TOTEM_OF_UNDYING)),
                "Hotbar Swap took a spare outside the hotbar");

        server.runCommand("item replace entity @a hotbar.4 with minecraft:totem_of_undying");
        context.waitFor(client -> client.player.getInventory().getItem(RESERVE_INDEX).is(Items.TOTEM_OF_UNDYING));
        advance(context, controller, 1);
        require(context.computeOnClient(client -> client.screen == null && controller.ownsInventory()
                        && client.player.getInventory().getSelectedSlot() == RESERVE_INDEX),
                "Hotbar Swap did not select the spare totem while leaving the screen closed");
        until(context, server, controller, player -> player.getOffhandItem().is(Items.TOTEM_OF_UNDYING),
                "Server did not receive the Hotbar Swap offhand transfer");
        advance(context, controller, 16);
        require(serverHas(server, player -> player.getInventory().getItem(RESERVE_INDEX).is(Items.SHIELD)
                        && player.getInventory().getSelectedSlot() == 0),
                "Hotbar Swap did not preserve the offhand item and restore the original slot on the server");
        require(context.computeOnClient(client -> client.screen == null && !controller.ownsInventory()
                        && client.player.getInventory().getSelectedSlot() == 0),
                "Hotbar Swap did not finish and release inventory ownership");

        server.runCommand("item replace entity @a hotbar.4 with minecraft:totem_of_undying");
        context.waitFor(client -> client.player.getInventory().getItem(RESERVE_INDEX).is(Items.TOTEM_OF_UNDYING));
        server.runOnServer(mc -> {
            var player = mc.getPlayerList().getPlayers().getFirst();
            player.hurtServer(mc.overworld(), player.damageSources().generic(), 40);
            require(player.isAlive() && player.getOffhandItem().isEmpty(),
                    "The damage fixture did not consume the player's offhand totem");
        });
        context.waitFor(client -> client.player.isAlive() && client.player.getOffhandItem().isEmpty());
        until(context, server, controller, player -> player.isAlive() && player.getOffhandItem().is(Items.TOTEM_OF_UNDYING),
                "Auto Totem did not refill after a real Totem of Undying use");
    }

    private static void arm(ClientGameTestContext context, TestServerContext server, AutoTotemController controller) {
        server.runCommand("item replace entity @a weapon.offhand with minecraft:totem_of_undying");
        context.waitFor(client -> client.player.getOffhandItem().is(Items.TOTEM_OF_UNDYING));
        advance(context, controller, 1);
    }

    private static boolean serverHas(TestServerContext server, Predicate<ServerPlayer> condition) {
        return server.computeOnServer(mc -> condition.test(mc.getPlayerList().getPlayers().getFirst()));
    }

    private static void until(ClientGameTestContext context, TestServerContext server, AutoTotemController controller,
            Predicate<ServerPlayer> condition, String failure) {
        for (int tick = 0; tick < 50 && !serverHas(server, condition); tick++) advance(context, controller, 1);
        require(serverHas(server, condition), failure);
    }

    private static void advance(ClientGameTestContext context, AutoTotemController controller, int ticks) {
        for (int tick = 0; tick < ticks; tick++) {
            context.runOnClient(client -> {
                boolean active = client.isWindowActive();
                client.setWindowActive(true);
                try {
                    controller.tick(client);
                } finally {
                    client.setWindowActive(active);
                }
            });
            context.waitTick();
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
