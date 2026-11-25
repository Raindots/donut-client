package dev.donut.client.config;

public final class TriggerbotSettings {
    public enum Crits { OFF, SMART, ONLY }

    public transient boolean enabled;
    public boolean favorite;
    public int reaction = 2;
    public int jitter = 1;
    public Crits crits = Crits.SMART;
    public boolean holdAttack;
    public boolean skipShielding = true;
    public boolean players = true;
    public boolean hostiles;
    public boolean passives;
    public boolean invisible;
    public boolean skipFriends = true;
    public boolean anyItem;
    public boolean swords = true;
    public boolean axes = true;
    public boolean mace = true;

    public void sanitize() {
        reaction = Math.clamp(reaction, 0, 10);
        jitter = Math.clamp(jitter, 0, 4);
        if (crits == null) crits = Crits.SMART;
    }
}
