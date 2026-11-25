package dev.donut.client.config;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.StringJoiner;
import java.util.regex.Pattern;

public final class CrystalAuraSettings {
    public static final int MAX_FRIENDS_TEXT_LENGTH = 640;
    private static final Pattern FRIEND_SEPARATOR = Pattern.compile("[,\\s]+");
    private static final Pattern PLAYER_NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");

    public transient boolean enabled;
    public boolean favorite;
    public int placeDelay;
    public int breakDelay;
    public String friends = "";

    public boolean isFriend(String playerName) {
        if (friends == null || friends.isEmpty()) return false;
        for (String name : FRIEND_SEPARATOR.split(friends)) {
            if (name.equalsIgnoreCase(playerName)) return true;
        }
        return false;
    }

    public void setFriends(String value) {
        friends = cleanFriends(value);
    }

    public void sanitize() {
        placeDelay = Math.clamp(placeDelay, 0, 20);
        breakDelay = Math.clamp(breakDelay, 0, 20);
        setFriends(friends);
    }

    private static String cleanFriends(String value) {
        if (value == null || value.isBlank()) return "";
        Set<String> seen = new HashSet<>();
        StringJoiner clean = new StringJoiner(", ");
        for (String name : FRIEND_SEPARATOR.split(value)) {
            if (!PLAYER_NAME.matcher(name).matches() || !seen.add(name.toLowerCase(Locale.ROOT))) continue;
            int separatorLength = clean.length() == 0 ? 0 : 2;
            if (clean.length() + separatorLength + name.length() > MAX_FRIENDS_TEXT_LENGTH) break;
            clean.add(name);
        }
        return clean.toString();
    }
}
