package dev.donut.client.fly;

import dev.donut.client.config.ClientConfig;
import dev.donut.client.config.FlySettings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.protocol.game.ClientboundPlayerAbilitiesPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Abilities;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;

/** Uses vanilla flight controls on any connection, granting local authority only on a hosted world. */
public final class FlyController {
    private static FlyController instance;
    private final ClientConfig config;
    private volatile Session session;
    // Accessed exclusively on the integrated server thread, including the player-save hook.
    private Session serverSession;

    public FlyController(ClientConfig config) {
        this.config = config;
        instance = this;
    }

    public static FlyController getInstance() { return instance; }
    public static boolean enabled() { return instance != null && instance.isActive(); }
    public FlySettings settings() { return config.fly; }

    public boolean isActive() {
        Session current = session;
        Minecraft client = Minecraft.getInstance();
        return current != null && !current.closed && current.matches(client);
    }

    public void setEnabled(boolean enabled) {
        if (!enabled) { disable(); return; }
        if (isActive()) { settings().enabled = true; return; }
        disable();
        Minecraft client = Minecraft.getInstance();
        IntegratedServer server = client.getSingleplayerServer();
        if (client.player == null || client.level == null || client.gameMode == null) return;
        if (!client.player.isAlive() || client.player.isRemoved()) return;
        Session created = new Session(client, server);
        session = created;
        settings().enabled = true;
        if (server == null) {
            created.applyClient(client);
        } else {
            // Capture the local server baseline before the client can send a flying=true packet.
            server.execute(() -> serverTick(server));
        }
    }

    public void disable() {
        Session previous = session;
        session = null;
        settings().enabled = false;
        if (previous == null) return;
        previous.closed = true;
        previous.restoreClient(Minecraft.getInstance());
        if (previous.server != null) previous.server.execute(() -> serverTick(previous.server));
    }

    /** Disconnect can arrive on Netty; only close this connection's session until the client can clean up. */
    public void clientDisconnected(ClientPacketListener handler, Minecraft client) {
        Session captured = session;
        if (captured == null || captured.player.connection != handler) return;
        captured.disconnecting = true;
        captured.closed = true;
        client.execute(() -> {
            if (session == captured) disable();
        });
    }

    public void tick(Minecraft client) {
        Session current = session;
        if (current != null && (current.closed || !current.matches(client))) { disable(); return; }
        if (settings().enabled != (current != null)) setEnabled(settings().enabled);
        current = session;
        if (current != null && !current.closed && current.serverReady) current.applyClient(client);
    }

    /** Retain the remote server's latest flight permissions for restoration when the module stops. */
    public static void onServerAbilities(ClientboundPlayerAbilitiesPacket packet) {
        FlyController controller = instance;
        Session current = controller == null ? null : controller.session;
        if (current == null || current.closed || current.server != null) return;
        Minecraft client = Minecraft.getInstance();
        if (!current.matches(client)) return;
        current.clientMode = client.gameMode.getPlayerMode();
        current.clientFlags = new FlightFlags(packet.canFly(), packet.isFlying());
    }

    /** Runs before hosted-world gameplay; no remote server or other player is modified. */
    public void serverTick(MinecraftServer server) {
        Session desired = session;
        if (desired != null && (desired.closed || desired.server != server)) desired = null;
        if (serverSession != null && serverSession.server == server && serverSession != desired) {
            serverSession.restoreServer();
            serverSession = null;
        }
        if (desired == null) return;
        ServerPlayer player = server.getPlayerList().getPlayer(desired.player.getUUID());
        if (player == null || !player.isAlive() || player.isRemoved()
                || desired.serverPlayer != null && desired.serverPlayer != player) {
            desired.closed = true;
            if (serverSession != null) { serverSession.restoreServer(); serverSession = null; }
            return;
        }
        if (serverSession == null) {
            desired.serverPlayer = player;
            desired.serverMode = player.gameMode();
            desired.serverFlags = FlightFlags.of(player.getAbilities());
            serverSession = desired;
        }
        desired.applyServer();
        desired.serverReady = true;
    }

    /** Fabric may dispatch disconnect from a network thread: restore on the server thread. */
    public void serverDisconnected(MinecraftServer server, ServerPlayer player) {
        Session current = session;
        if (current != null && current.server == server && current.player.getUUID().equals(player.getUUID())) current.closed = true;
        server.execute(() -> serverTick(server));
    }

    public void serverStopping(MinecraftServer server) {
        Session current = session;
        if (current != null && current.server == server) current.closed = true;
        if (serverSession != null && serverSession.server == server) {
            serverSession.restoreServer();
            serverSession = null;
        }
    }

    /** Keep temporary flight out of autosaves and logout saves, without mutating live abilities. */
    public static Abilities.Packed abilitiesForSave(Player player, Abilities abilities) {
        FlyController controller = instance;
        Session current = controller == null ? null : controller.serverSession;
        if (current == null || current.serverPlayer != player) return abilities.pack();
        Abilities saved = new Abilities();
        saved.apply(abilities.pack());
        current.serverBaseline().restore(saved);
        return saved.pack();
    }

    private record FlightFlags(boolean mayfly, boolean flying) {
        static FlightFlags of(Abilities abilities) { return new FlightFlags(abilities.mayfly, abilities.flying); }
        static FlightFlags forMode(GameType mode) {
            Abilities abilities = new Abilities();
            mode.updatePlayerAbilities(abilities);
            return of(abilities);
        }
        void restore(Abilities abilities) { abilities.mayfly = mayfly; abilities.flying = flying; }
    }

    private static final class Session {
        final IntegratedServer server;
        final LocalPlayer player;
        final ClientLevel level;
        volatile boolean disconnecting;
        volatile boolean closed;
        volatile boolean serverReady;
        GameType clientMode;
        FlightFlags clientFlags;
        ServerPlayer serverPlayer;
        GameType serverMode;
        FlightFlags serverFlags;

        Session(Minecraft client, IntegratedServer server) {
            this.server = server;
            serverReady = server == null;
            player = client.player;
            level = client.level;
            clientMode = client.gameMode.getPlayerMode();
            clientFlags = FlightFlags.of(player.getAbilities());
        }

        boolean matches(Minecraft client) {
            return client.player == player && client.level == level && client.gameMode != null
                    && client.getSingleplayerServer() == server
                    && player.isAlive() && !player.isRemoved();
        }

        void applyClient(Minecraft client) {
            GameType mode = client.gameMode.getPlayerMode();
            if (clientMode != mode) { clientMode = mode; clientFlags = FlightFlags.forMode(mode); }
            Abilities abilities = player.getAbilities();
            boolean changed = !abilities.mayfly || !abilities.flying;
            abilities.mayfly = true;
            abilities.flying = true;
            if (changed) player.onUpdateAbilities();
        }

        void restoreClient(Minecraft client) {
            // Do not send a previous world's abilities through a new player connection.
            if (client.player != player || client.level != level || client.gameMode == null) return;
            GameType mode = client.gameMode.getPlayerMode();
            (clientMode == mode ? clientFlags : FlightFlags.forMode(mode)).restore(player.getAbilities());
            if (!disconnecting) player.onUpdateAbilities();
        }

        FlightFlags serverBaseline() {
            GameType mode = serverPlayer.gameMode();
            return serverMode == mode ? serverFlags : FlightFlags.forMode(mode);
        }

        void applyServer() {
            GameType mode = serverPlayer.gameMode();
            if (serverMode != mode) { serverMode = mode; serverFlags = FlightFlags.forMode(mode); }
            Abilities abilities = serverPlayer.getAbilities();
            boolean changed = !abilities.mayfly || !abilities.flying;
            abilities.mayfly = true;
            abilities.flying = true;
            if (changed) serverPlayer.onUpdateAbilities();
        }

        void restoreServer() {
            if (serverPlayer == null) return;
            serverBaseline().restore(serverPlayer.getAbilities());
            serverPlayer.onUpdateAbilities();
        }
    }
}
