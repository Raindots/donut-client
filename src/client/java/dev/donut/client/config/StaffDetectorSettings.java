package dev.donut.client.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Staff identity is scoped to a server and bound to an observed UUID before detection. */
public final class StaffDetectorSettings {
    public static final int MAX_SERVERS = 64, MAX_STAFF = 128;
    public boolean enabled, favorite;
    public boolean showHud = true, chatAlerts = true, spectatorAlerts = true, invisibilityAlerts = true;
    public boolean vanishAlerts = true;
    public boolean completionChecks, rosterAlerts;
    public int nearbyRange = 64, completionIntervalSeconds = 10, requiredSamples = 3;
    public int evidenceSeconds = 30, alertCooldownSeconds = 30, maxHudRows = 4;
    public Map<String, List<Member>> rosters = new LinkedHashMap<>();

    public static final class Member {
        public String uuid = "", name = "";
        public Member() { }
        public Member(String uuid, String name) { this.uuid = uuid; this.name = name; }
        public UUID id() { return parseUuid(uuid); }
        public String label() { return name.isEmpty() ? uuid : name; }
    }

    public static UUID parseUuid(String value) {
        if (value == null || !value.matches("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) return null;
        return UUID.fromString(value);
    }
    public static boolean validName(String value) { return value != null && value.matches("[A-Za-z0-9_]{1,16}"); }
    public static String serverKey(String server) {
        if (server == null || server.isBlank()) return "singleplayer";
        String value = server.trim().toLowerCase(Locale.ROOT);
        return value.length() > 255 ? value.substring(0, 255) : value;
    }
    public List<Member> roster(String server) { return rosters.getOrDefault(serverKey(server), List.of()); }

    /** Accept a username awaiting observation or an exact UUID. No external profile lookup. */
    public boolean add(String server, String input) {
        String value = input == null ? "" : input.trim();
        UUID uuid = parseUuid(value);
        if (uuid == null && !validName(value)) throw new IllegalArgumentException("Enter a Minecraft username or a full UUID.");
        String key = serverKey(server);
        if (!rosters.containsKey(key) && rosters.size() >= MAX_SERVERS)
            throw new IllegalArgumentException("The saved server limit has been reached.");
        List<Member> members = rosters.computeIfAbsent(key, ignored -> new ArrayList<>());
        if (members.stream().anyMatch(member -> uuid != null ? uuid.equals(member.id()) : value.equalsIgnoreCase(member.name))) return false;
        if (members.size() >= MAX_STAFF) throw new IllegalArgumentException("The staff list is full for this server.");
        members.add(new Member(uuid == null ? "" : uuid.toString(), uuid == null ? value : ""));
        return true;
    }
    public void remove(String server, Member member) {
        String key = serverKey(server);
        List<Member> members = rosters.get(key);
        if (members != null) { members.remove(member); if (members.isEmpty()) rosters.remove(key); }
    }
    public void sanitize() {
        nearbyRange = Math.clamp(nearbyRange, 8, 256);
        completionIntervalSeconds = Math.clamp(completionIntervalSeconds, 6, 120);
        requiredSamples = Math.clamp(requiredSamples, 2, 10);
        evidenceSeconds = Math.clamp(evidenceSeconds, 10, 180);
        alertCooldownSeconds = Math.clamp(alertCooldownSeconds, 10, 300);
        maxHudRows = Math.clamp(maxHudRows, 1, 8);
        Map<String, List<Member>> clean = new LinkedHashMap<>();
        if (rosters != null) for (var entry : rosters.entrySet()) {
            if (clean.size() >= MAX_SERVERS) break;
            if (entry.getKey() == null || entry.getValue() == null) continue;
            List<Member> members = clean.computeIfAbsent(serverKey(entry.getKey()), ignored -> new ArrayList<>());
            for (Member member : entry.getValue()) {
                if (members.size() >= MAX_STAFF) break;
                if (member == null) continue;
                UUID uuid = member.id();
                String name = member.name == null ? "" : member.name.trim();
                if (!validName(name)) name = "";
                if (uuid == null && name.isEmpty()) continue;
                final String candidate = name;
                if (members.stream().anyMatch(existing -> uuid != null && uuid.equals(existing.id())
                        || !candidate.isEmpty() && candidate.equalsIgnoreCase(existing.name))) continue;
                // Retain object identity: an open editor may hold a reference to this member.
                member.uuid = uuid == null ? "" : uuid.toString(); member.name = name;
                members.add(member);
            }
        }
        clean.values().removeIf(List::isEmpty);
        rosters = clean;
    }
}
