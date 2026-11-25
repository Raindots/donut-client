package dev.donut.client.staff;

import com.mojang.authlib.GameProfile;
import dev.donut.client.config.ClientConfig;
import dev.donut.client.config.StaffDetectorSettings;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundCommandSuggestionsPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ServerboundCommandSuggestionPacket;
import net.minecraft.world.level.GameType;

/** Packet callbacks run after vanilla's thread handoff. All model/world access stays on the client thread. */
public final class StaffDetectorController {
    public static final int MAX_PENDING = 64;
    private static StaffDetectorController instance;
    private final ClientConfig config;
    private final Runnable save;
    private StaffDetectorSettings settings;
    private StaffDetectorEngine engine;
    private ClientPacketListener connection;
    private ClientLevel level;
    private String server = "singleplayer";
    private boolean active;
    private int ticks, requestSequence, pendingRequest;
    private long started, nextQuery, queryDeadline, lastChat = -1, dropped;
    private long modeReports, spectatorReports, tabHides, removalReports;
    private String queryStatus = "Completion checks are off.";
    private sealed interface Observation permits Roster, Completions, GameModes { }
    private record Roster(List<StaffDetectorEngine.Profile> profiles) implements Observation { }
    private record Completions(List<String> names) implements Observation { }
    private record Mode(UUID id, boolean spectator) { }
    private record GameModes(List<Mode> reports) implements Observation { }
    private final ArrayDeque<Observation> pending = new ArrayDeque<>();
    private boolean overflowed;
    private final ArrayDeque<StaffDetectorEngine.Evidence> alerts = new ArrayDeque<>();

    public StaffDetectorController(ClientConfig config, Runnable save) {
        this.config = config; this.save = save; this.settings = config.staffDetector;
        settings.sanitize(); engine = new StaffDetectorEngine(settings, System::nanoTime); instance = this;
    }
    public static StaffDetectorController get() { return instance; }
    public boolean uses(ClientConfig candidate) { return candidate == config; }
    public StaffDetectorSettings settings() { return settings; }
    public static String serverKey(Minecraft mc) {
        var data = mc.getCurrentServer();
        return StaffDetectorSettings.serverKey(data == null ? "singleplayer" : data.ip);
    }
    private boolean sync(Minecraft mc) {
        if (settings != config.staffDetector) {
            disconnect(); settings = config.staffDetector; settings.sanitize();
            engine = new StaffDetectorEngine(settings, System::nanoTime);
        }
        if (!settings.enabled || mc.level == null || mc.player == null || mc.getConnection() == null) {
            if (active) disconnect(); return false;
        }
        if (!active || connection != mc.getConnection() || level != mc.level || !Objects.equals(server, serverKey(mc))) {
            disconnect(); connection = mc.getConnection(); level = mc.level; server = serverKey(mc);
            active = true; started = System.nanoTime(); nextQuery = started + seconds(8);
        }
        engine.watch(members(mc));
        return true;
    }
    private List<StaffDetectorSettings.Member> members(Minecraft mc) {
        return settings.roster(server).stream().filter(member -> !mc.player.getUUID().equals(member.id())).toList();
    }
    private void bindIdentities(Minecraft mc) {
        boolean changed = false;
        for (var member : settings.roster(server)) {
            var info = member.id() == null ? connection.getPlayerInfoIgnoreCase(member.name) : connection.getPlayerInfo(member.id());
            GameProfile profile = info == null ? null : info.getProfile();
            if (profile == null) for (var player : level.players()) {
                if (member.id() != null ? member.id().equals(player.getUUID()) : member.name.equalsIgnoreCase(player.getGameProfile().name())) {
                    profile = player.getGameProfile(); break;
                }
            }
            if (profile == null) profile = historicalProfile(member);
            if (profile != null && (!profile.id().toString().equals(member.uuid) || !profile.name().equals(member.name))) {
                member.uuid = profile.id().toString(); member.name = profile.name(); changed = true;
            }
        }
        if (changed) save.run();
        engine.watch(members(mc));
    }
    private GameProfile historicalProfile(StaffDetectorSettings.Member member) {
        // Historical profiles can establish identity, never current online state or game mode.
        GameProfile match = null;
        for (var info : connection.getSeenPlayers().values()) {
            var profile = info.getProfile();
            if (member.id() != null ? member.id().equals(profile.id()) : member.name.equalsIgnoreCase(profile.name())) {
                if (match != null && !match.id().equals(profile.id())) return null;
                match = profile;
            }
        }
        return match;
    }
    private void rememberProfiles(Minecraft mc, ClientboundPlayerInfoUpdatePacket packet) {
        boolean changed = false;
        var roster = config.staffDetector.roster(serverKey(mc));
        for (var entry : packet.newEntries()) {
            GameProfile profile = entry.profile();
            if (profile == null || !StaffDetectorSettings.validName(profile.name())) continue;
            for (var member : roster) {
                boolean matches = member.id() == null ? member.name.equalsIgnoreCase(profile.name()) : member.id().equals(profile.id());
                if (matches && (!profile.id().toString().equals(member.uuid) || !profile.name().equals(member.name))) {
                    member.uuid = profile.id().toString(); member.name = profile.name(); changed = true;
                }
            }
        }
        if (changed) save.run();
    }
    private void queueRoster(Minecraft mc) {
        bindIdentities(mc);
        var listed = new HashSet<java.util.UUID>();
        for (var info : connection.getListedOnlinePlayers()) listed.add(info.getProfile().id());
        List<StaffDetectorEngine.Profile> snapshot = new ArrayList<>();
        for (var member : members(mc)) {
            if (member.id() == null) continue;
            var info = connection.getPlayerInfo(member.id());
            if (info != null) snapshot.add(new StaffDetectorEngine.Profile(member.id(), info.getProfile().name(),
                    listed.contains(member.id()), info.getGameMode() == GameType.SPECTATOR));
        }
        queue(new Roster(List.copyOf(snapshot)));
    }
    private void queue(Observation observation) {
        if (pending.size() == MAX_PENDING) { pending.removeFirst(); dropped++; overflowed = true; }
        pending.addLast(observation);
    }
    public static void rosterApplied(ClientPacketListener source) {
        var self = instance; var mc = Minecraft.getInstance();
        if (self == null || !mc.isSameThread() || source != mc.getConnection() || !self.sync(mc)) return;
        self.queueRoster(mc);
    }
    public static void rosterUpdated(ClientPacketListener source, ClientboundPlayerInfoUpdatePacket packet) {
        var self = instance; var mc = Minecraft.getInstance();
        if (self == null || !mc.isSameThread() || source != mc.getConnection() || mc.player == null) return;
        // Bind configured identities when first seen, even if detection is currently disabled.
        self.rememberProfiles(mc, packet);
        if (!self.sync(mc)) return;
        var actions = packet.actions();
        boolean modeUpdate = actions.contains(ClientboundPlayerInfoUpdatePacket.Action.UPDATE_GAME_MODE);
        boolean listingUpdate = actions.contains(ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LISTED);
        if (!modeUpdate && !listingUpdate && !actions.contains(ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER)) return;
        self.queueRoster(mc);
        var watched = new HashSet<UUID>();
        for (var member : self.members(mc)) if (member.id() != null) watched.add(member.id());
        List<Mode> reports = new ArrayList<>();
        for (var entry : packet.entries()) {
            if (!watched.contains(entry.profileId())) continue;
            if (listingUpdate && !entry.listed()) self.tabHides++;
            if (modeUpdate && entry.gameMode() != null) {
                boolean spectator = entry.gameMode() == GameType.SPECTATOR;
                reports.add(new Mode(entry.profileId(), spectator));
                self.modeReports++; if (spectator) self.spectatorReports++;
            }
        }
        if (!reports.isEmpty()) self.queue(new GameModes(List.copyOf(reports)));
    }
    public static void rosterRemoved(ClientPacketListener source, ClientboundPlayerInfoRemovePacket packet) {
        var self = instance; var mc = Minecraft.getInstance();
        if (self == null || !mc.isSameThread() || source != mc.getConnection() || !self.sync(mc)) return;
        for (var member : self.members(mc)) if (member.id() != null && packet.profileIds().contains(member.id())) self.removalReports++;
        self.queueRoster(mc);
    }
    public static void suggestionsApplied(ClientPacketListener source, ClientboundCommandSuggestionsPacket packet) {
        var self = instance; var mc = Minecraft.getInstance();
        if (self == null || !mc.isSameThread() || source != mc.getConnection() || !self.sync(mc)
                || self.pendingRequest == 0 || packet.id() != self.pendingRequest) return;
        self.pendingRequest = 0;
        if (!self.settings.completionChecks || System.nanoTime() > self.queryDeadline) {
            self.engine.clearCompletions(); return;
        }
        // Compare against the latest tab state, including packets received since the request.
        self.queueRoster(mc);
        self.queue(new Completions(packet.toSuggestions().getList().stream().limit(4096).map(suggestion -> suggestion.getText()).toList()));
        self.queryStatus = "Matched response received; repeated discrepancies remain uncertain.";
    }
    private void drainRoster() {
        if (overflowed) { engine.clearCompletions(); overflowed = false; }
        for (int count = 0; count < MAX_PENDING && !pending.isEmpty(); count++) {
            var observation = pending.removeFirst();
            if (observation instanceof Roster roster) engine.roster(roster.profiles());
            else if (observation instanceof Completions completions) engine.completions(completions.names());
            else if (observation instanceof GameModes modes) for (Mode report : modes.reports())
                engine.gameModeReport(report.id(), report.spectator());
        }
    }
    public void tick(Minecraft mc) {
        if (!sync(mc)) return;
        ticks++;
        if (ticks % 10 == 1) queueRoster(mc);
        drainRoster();
        if (ticks % 5 == 1) {
            List<StaffDetectorEngine.Body> bodies = new ArrayList<>();
            for (var player : level.players()) if (player != mc.player)
                bodies.add(new StaffDetectorEngine.Body(player.getUUID(), Math.sqrt(player.distanceToSqr(mc.player)), player.isInvisible()));
            engine.bodies(bodies);
        }
        long now = System.nanoTime();
        query(mc, now); engine.tick();
        for (var event : engine.drainNotifications()) {
            if (alerts.size() == StaffDetectorEngine.MAX_NOTIFICATIONS) alerts.removeFirst();
            alerts.addLast(event);
        }
        if (!settings.chatAlerts) alerts.clear();
        if (settings.chatAlerts && mc.screen == null && !mc.options.hideGui && (lastChat < 0 || now - lastChat >= seconds(2))) {
            while (!alerts.isEmpty()) {
                var event = alerts.removeFirst();
                if (!engine.alertRelevant(event)) continue;
                String label = config.nameHider.enabled ? "Staff member" : event.name();
                mc.gui.getChat().addMessage(Component.literal("[Staff Detector] " + label + ": " + event.detail()));
                lastChat = now; break;
            }
        }
    }
    private void query(Minecraft mc, long now) {
        if (!settings.completionChecks) {
            pendingRequest = 0; engine.clearCompletions(); queryStatus = "Completion checks are off."; return;
        }
        if (pendingRequest != 0 && now > queryDeadline) {
            pendingRequest = 0; engine.clearCompletions(); queryStatus = "Completion request timed out; no inference made.";
        }
        if (pendingRequest != 0 || now < nextQuery || now - started < seconds(8) || mc.screen != null) return;
        nextQuery = now + seconds(settings.completionIntervalSeconds);
        if (engine.views().isEmpty()) { queryStatus = "Add staff and observe their UUIDs before checking completions."; return; }
        if (connection.getCommands().getRoot().getChild("msg") == null) {
            engine.clearCompletions(); queryStatus = "/msg is unavailable; no completion request sent."; return;
        }
        // Vanilla request IDs are non-negative. A dedicated negative range avoids its in-flight UI requests.
        requestSequence = (requestSequence + 1) & 0x3FFFFFFF;
        pendingRequest = Integer.MIN_VALUE + requestSequence;
        queryDeadline = now + seconds(3);
        connection.send(new ServerboundCommandSuggestionPacket(pendingRequest, "/msg "));
        queryStatus = "Waiting for a matched /msg completion response.";
    }
    public List<StaffDetectorEngine.View> views() { return active ? engine.views() : List.of(); }
    public List<StaffDetectorEngine.Evidence> history() { return engine.history(); }
    public long droppedSnapshots() { return dropped; }
    public int pendingSnapshots() { return pending.size(); }
    public String queryStatus() { return queryStatus; }
    public String diagnostics() {
        if (!active) return "Detector inactive. Join the server and enable it to collect reports.";
        long unresolved = settings.roster(server).stream().filter(member -> member.id() == null).count();
        return "Unresolved names: " + unresolved + " | Spectator reports: " + spectatorReports
                + " | All mode reports: " + modeReports + " | Tab hides: " + tabHides + " | Removals: " + removalReports;
    }
    public String status() {
        if (!active) return "Enable in a world to observe staff. Hidden presence is unknown.";
        int count = settings.roster(server).size(), bound = engine.views().size();
        return count == 0 ? "Add staff for this server. Hidden presence is unknown."
                : bound + " UUIDs tracked / " + count + " entries. Hidden presence is unknown.";
    }
    public void disconnect() {
        pending.clear(); alerts.clear(); engine.clear(); overflowed = false; active = false; connection = null; level = null;
        ticks = pendingRequest = 0; lastChat = -1; started = nextQuery = queryDeadline = 0;
        modeReports = spectatorReports = tabHides = removalReports = 0;
        queryStatus = "No active completion request.";
    }
    private static long seconds(int value) { return value * 1_000_000_000L; }
}
