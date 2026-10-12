package dev.donut.client.fullbright;

import dev.donut.client.config.ClientConfig;
import net.minecraft.client.Minecraft;

/** Overrides only the lightmap's gamma, leaving the saved brightness option intact. */
public final class FullbrightController implements AutoCloseable {
    public static final double GAMMA = 1000.0;

    private static FullbrightController instance;
    private final ClientConfig config;
    private Minecraft client;
    private boolean previousActive;
    private boolean closed;

    public FullbrightController(ClientConfig config) {
        this.config = config;
        instance = this;
    }

    public static FullbrightController getInstance() { return instance; }

    public boolean isActive() { return !closed && config.fullbright.enabled; }

    public static double effectiveGamma(double originalGamma) {
        return instance != null && instance.isActive() ? GAMMA : originalGamma;
    }

    public void tick(Minecraft client) {
        this.client = client;
        boolean active = isActive();
        if (active == previousActive) return;
        previousActive = active;
        client.gameRenderer.lightTexture().tick();
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        previousActive = false;
        if (client != null) client.gameRenderer.lightTexture().tick();
    }
}
