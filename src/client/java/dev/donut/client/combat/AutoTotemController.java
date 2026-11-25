package dev.donut.client.combat;

import dev.donut.client.autobuild.AutoBuilderController;
import dev.donut.client.config.AutoTotemSettings;
import dev.donut.client.config.ClientConfig;
import dev.donut.client.freecam.FreecamController;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.Items;

import java.util.concurrent.ThreadLocalRandom;

public final class AutoTotemController {
    private static final int MAX_INVENTORY_SWAPS = 2;
    private static final int RETRY_COOLDOWN = 4;

    private enum Step { IDLE, INVENTORY_SWAP, INVENTORY_CLOSE, HOTBAR_SWAP, HOTBAR_RETURN }

    private final ClientConfig config;
    private LocalPlayer player;
    private ClientLevel level;
    private AutoTotemSettings.Mode activeMode;
    private Step step = Step.IDLE;
    private InventoryScreen openedInventory;
    private boolean armed;
    private boolean inventoryBusy;
    private long tick;
    private long reactionAt = -1;
    private long readyAt;
    private long nextPass;
    private long confirmUntil;
    private int swaps;
    private int previousSlot = -1;
    private int totemSlot = -1;

    public AutoTotemController(ClientConfig config) {
        this.config = config;
    }

    public boolean ownsInventory() {
        return inventoryBusy;
    }

    public void tick(Minecraft client) {
        var settings = config.autoTotem;
        inventoryBusy = false;
        if (client.player != player || client.level != level) {
            reset();
            player = client.player;
            level = client.level;
        }
        if (!settings.enabled || player == null || !player.isAlive() || player.isSpectator()) {
            cancel(client);
            armed = false;
            confirmUntil = 0;
            nextPass = 0;
            return;
        }
        if (client.gameMode == null || level == null || client.getConnection() == null) return;
        tick++;

        if (hasOffhandTotem()) {
            armed = true;
            confirmUntil = 0;
        }
        if (FreecamController.enabled() || AutoBuilderController.get().isRunning()) {
            cancel(client);
            return;
        }
        if (step != Step.IDLE && activeMode != settings.mode) cancel(client);
        if (!client.isWindowActive() || client.isPaused()) {
            inventoryBusy = step != Step.IDLE;
            return;
        }

        if (step == Step.IDLE) tryStart(client, settings);
        if (step == Step.IDLE) return;
        inventoryBusy = true;
        if (tick < readyAt) return;

        switch (step) {
            case INVENTORY_SWAP -> inventorySwap(client, settings);
            case HOTBAR_SWAP -> hotbarSwap(client, settings);
            case INVENTORY_CLOSE, HOTBAR_RETURN, IDLE -> { }
        }
        if (step == Step.INVENTORY_CLOSE && tick >= readyAt) finish(client);
        if (step == Step.HOTBAR_RETURN && tick >= readyAt && client.screen == null) finish(client);
    }

    public void disconnect(Minecraft client) {
        if (client.player == player) cancel(client);
        config.autoTotem.enabled = false;
        reset();
    }

    private void tryStart(Minecraft client, AutoTotemSettings settings) {
        boolean hotbar = settings.mode == AutoTotemSettings.Mode.HOTBAR_SWAP;
        boolean canStart = hotbar ? client.screen == null && needsOffhand() && hotbarTotem() >= 0
                : !player.isCreative() && player.containerMenu == player.inventoryMenu
                        && (client.screen == null || client.screen instanceof InventoryScreen)
                        && player.inventoryMenu.getCarried().isEmpty() && inventoryAction(settings) != null;
        if (!canStart || tick < confirmUntil) {
            reactionAt = -1;
            return;
        }
        if (hotbar) inventoryBusy = true;
        if (reactionAt < 0) reactionAt = tick + delay(settings.reactionDelay, settings);
        if (tick < reactionAt || tick < nextPass) return;

        activeMode = settings.mode;
        if (hotbar) {
            var inventory = player.getInventory();
            previousSlot = inventory.getSelectedSlot();
            totemSlot = hotbarTotem();
            inventory.setSelectedSlot(totemSlot);
            schedule(Step.HOTBAR_SWAP, settings.swapDelay, settings);
        } else {
            if (client.screen == null) {
                openedInventory = new InventoryScreen(player);
                client.setScreen(openedInventory);
            }
            schedule(Step.INVENTORY_SWAP, settings.swapDelay, settings);
        }
    }

    private void inventorySwap(Minecraft client, AutoTotemSettings settings) {
        if (!(client.screen instanceof InventoryScreen screen) || screen.getMenu() != player.inventoryMenu
                || player.containerMenu != player.inventoryMenu) {
            finish(client);
            return;
        }

        var menu = player.inventoryMenu;
        var action = inventoryAction(settings);
        if (action != null && menu.getCarried().isEmpty()) {
            int source = action.source();
            int menuSlot = Inventory.isHotbarSlot(source) ? InventoryMenu.USE_ROW_SLOT_START + source : source;
            client.gameMode.handleInventoryMouseClick(menu.containerId, menuSlot, action.button(), ClickType.SWAP, player);
            swaps++;
            if (swaps < MAX_INVENTORY_SWAPS && inventoryAction(settings) != null) {
                schedule(Step.INVENTORY_SWAP, settings.swapDelay, settings);
                return;
            }
        }
        schedule(Step.INVENTORY_CLOSE, settings.closeDelay, settings);
    }

    private InventoryAction inventoryAction(AutoTotemSettings settings) {
        var inventory = player.getInventory();
        int reserve = settings.hotbarRefill ? settings.hotbarSlot - 1 : -1;
        boolean refillOffhand = needsOffhand();
        boolean refillReserve = reserve >= 0 && !inventory.getItem(reserve).is(Items.TOTEM_OF_UNDYING);
        if (!refillOffhand && !refillReserve) return null;
        int main = mainInventoryTotem(inventory);
        if (refillOffhand) {
            if (main >= 0) return new InventoryAction(main, Inventory.SLOT_OFFHAND);
            for (int slot = 0; slot < Inventory.getSelectionSize(); slot++) {
                if (slot != reserve && inventory.getItem(slot).is(Items.TOTEM_OF_UNDYING)) {
                    return new InventoryAction(slot, Inventory.SLOT_OFFHAND);
                }
            }
            if (reserve >= 0 && inventory.getItem(reserve).is(Items.TOTEM_OF_UNDYING)) {
                return new InventoryAction(reserve, Inventory.SLOT_OFFHAND);
            }
        }
        if (refillReserve && main >= 0) {
            return new InventoryAction(main, reserve);
        }
        return null;
    }

    private void hotbarSwap(Minecraft client, AutoTotemSettings settings) {
        if (client.screen != null) return;
        var inventory = player.getInventory();
        if (!needsOffhand() || inventory.getSelectedSlot() != totemSlot || !player.getMainHandItem().is(Items.TOTEM_OF_UNDYING)) {
            finish(client);
            return;
        }

        // The slot has been selected for at least one tick, allowing vanilla to synchronize it before swapping.
        var connection = client.getConnection();
        connection.send(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND,
                BlockPos.ZERO, Direction.DOWN));
        var info = connection.getPlayerInfo(player.getUUID());
        int latency = info == null ? 0 : info.getLatency();
        confirmUntil = tick + Math.clamp(latency / 25 + 4, 10, 40);
        schedule(Step.HOTBAR_RETURN, settings.switchBackDelay, settings);
    }

    private void finish(Minecraft client) {
        cancel(client);
        nextPass = tick + RETRY_COOLDOWN;
    }

    private void cancel(Minecraft client) {
        if (player != null) {
            if (openedInventory != null && client.screen == openedInventory
                    && player.inventoryMenu.getCarried().isEmpty()) player.closeContainer();
            var inventory = player.getInventory();
            if (previousSlot >= 0 && inventory.getSelectedSlot() == totemSlot) inventory.setSelectedSlot(previousSlot);
        }
        clearSequence();
    }

    private void reset() {
        player = null;
        level = null;
        armed = false;
        inventoryBusy = false;
        tick = nextPass = confirmUntil = 0;
        clearSequence();
    }

    private void clearSequence() {
        step = Step.IDLE;
        activeMode = null;
        openedInventory = null;
        reactionAt = -1;
        swaps = 0;
        previousSlot = totemSlot = -1;
    }

    private boolean needsOffhand() {
        return armed && !hasOffhandTotem();
    }

    private boolean hasOffhandTotem() {
        return player.getOffhandItem().is(Items.TOTEM_OF_UNDYING);
    }

    private int hotbarTotem() {
        var inventory = player.getInventory();
        if (player.getMainHandItem().is(Items.TOTEM_OF_UNDYING)) return inventory.getSelectedSlot();
        for (int slot = 0; slot < Inventory.getSelectionSize(); slot++) {
            if (inventory.getItem(slot).is(Items.TOTEM_OF_UNDYING)) return slot;
        }
        return -1;
    }

    private static int mainInventoryTotem(Inventory inventory) {
        for (int slot = Inventory.getSelectionSize(); slot < Inventory.INVENTORY_SIZE; slot++) {
            if (inventory.getItem(slot).is(Items.TOTEM_OF_UNDYING)) return slot;
        }
        return -1;
    }

    private void schedule(Step next, int ticks, AutoTotemSettings settings) {
        step = next;
        readyAt = tick + delay(ticks, settings);
    }

    private static int delay(int ticks, AutoTotemSettings settings) {
        return ticks + (settings.jitter > 0 ? ThreadLocalRandom.current().nextInt(settings.jitter + 1) : 0);
    }

    private record InventoryAction(int source, int button) {}
}
