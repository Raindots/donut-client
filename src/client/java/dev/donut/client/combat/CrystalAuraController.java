package dev.donut.client.combat;

import dev.donut.client.config.ClientConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;

public final class CrystalAuraController {
    private static CrystalAuraController instance;

    private final ClientConfig config;
    private final CrystalAuraManual manual = new CrystalAuraManual();
    private LocalPlayer player;
    private ClientLevel level;
    private long tick;
    private boolean ownsInteraction;

    private CrystalAuraController(ClientConfig config) {
        this.config = config;
    }

    public static CrystalAuraController install(ClientConfig config) {
        instance = new CrystalAuraController(config);
        return instance;
    }

    public static boolean consumeUse(Minecraft client) {
        return instance != null && instance.config.crystalAura.enabled && instance.manual.requestUse(client);
    }

    public void tick(Minecraft client, boolean combatBusy) {
        ownsInteraction = false;
        var settings = config.crystalAura;
        if (client.player != player || client.level != level) {
            clearSession();
            player = client.player;
            level = client.level;
        }
        if (player == null || level == null || client.gameMode == null) return;

        tick++;
        if (combatBusy) {
            manual.clearUseRequest();
            return;
        }
        if (settings.enabled) {
            ownsInteraction = manual.tick(client, settings, tick);
        } else {
            manual.reset();
        }
    }

    public void disconnect() {
        config.crystalAura.enabled = false;
        clearSession();
    }

    public boolean ownsInteraction() {
        return ownsInteraction;
    }

    private void clearSession() {
        player = null;
        level = null;
        tick = 0;
        ownsInteraction = false;
        manual.reset();
    }
}
