package dev.donut.client.config;

import com.google.gson.Gson;
import com.google.gson.Strictness;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;

/** Stores named snapshots independently from the client's active configuration. */
public final class ConfigProfileStore {
    public static final int MAX_NAME_LENGTH = 48;
    public static final int MAX_DESCRIPTION_LENGTH = 280;

    private static final int VERSION = 1;
    private static final Gson JSON = ConfigStore.GSON.newBuilder().setStrictness(Strictness.STRICT).create();
    private static final Object WRITE_LOCK = new Object();

    private final Path path;

    public ConfigProfileStore(Path path) {
        this.path = path.toAbsolutePath().normalize();
    }

    public record Profile(UUID id, String name, String description, int color, ClientConfig config,
                          Map<String, String> keybinds) { }

    private record Library(int version, List<Profile> profiles) { }

    public Path path() {
        return path;
    }

    public List<Profile> list() throws IOException {
        return List.copyOf(read());
    }

    public Profile create(String name, String description, int color, ClientConfig config,
                          Map<String, String> keybinds) throws IOException {
        if (config == null) throw new IllegalArgumentException("Config settings are required.");
        Profile profile = validate(new Profile(UUID.randomUUID(), name, description, color, ConfigStore.snapshot(config), keybinds));
        synchronized (WRITE_LOCK) {
            List<Profile> profiles = read();
            requireUniqueName(profiles, profile);
            profiles.add(profile);
            write(profiles);
        }
        return profile;
    }

    /** Pass null for both snapshot arguments to edit only the profile details. */
    public Profile update(UUID id, String name, String description, int color, ClientConfig config,
                          Map<String, String> keybinds) throws IOException {
        if ((config == null) != (keybinds == null)) {
            throw new IllegalArgumentException("Provide both settings and keybinds to update the snapshot.");
        }
        synchronized (WRITE_LOCK) {
            List<Profile> profiles = read();
            int index = indexOf(profiles, id);
            Profile existing = profiles.get(index);
            Profile profile = validate(new Profile(id, name, description, color,
                    config == null ? existing.config() : ConfigStore.snapshot(config),
                    keybinds == null ? existing.keybinds() : keybinds));
            requireUniqueName(profiles, profile);
            profiles.set(index, profile);
            write(profiles);
            return profile;
        }
    }

    public void delete(UUID id) throws IOException {
        synchronized (WRITE_LOCK) {
            List<Profile> profiles = read();
            profiles.remove(indexOf(profiles, id));
            write(profiles);
        }
    }

    public Path exportProfile(UUID id) throws IOException {
        synchronized (WRITE_LOCK) {
            List<Profile> profiles = read();
            Profile profile = profiles.get(indexOf(profiles, id));
            String filename = profile.name().replaceAll("[^\\p{L}\\p{N}_-]+", "-").replaceAll("^-+|-+$", "");
            if (filename.isEmpty()) filename = "profile";
            Path exported = path.getParent().resolve("exports").resolve(filename + "-" + id + ".json");
            write(exported, List.of(profile));
            return exported;
        }
    }

    /** Merge a shared profile file into this library without replacing any existing profile. */
    public List<Profile> importProfiles(Path source) throws IOException {
        synchronized (WRITE_LOCK) {
            List<Profile> incoming = read(source, false);
            List<Profile> profiles = read();
            var profilesById = new HashMap<UUID, Profile>();
            var names = new TreeSet<String>(String.CASE_INSENSITIVE_ORDER);
            for (Profile profile : profiles) {
                profilesById.put(profile.id(), profile);
                names.add(profile.name());
            }

            var added = new ArrayList<Profile>();
            for (Profile profile : incoming) {
                UUID id = availableImportId(profile, profilesById);
                if (id == null) continue;
                String name = reserveImportName(profile.name(), names);
                Profile copy = new Profile(id, name, profile.description(), profile.color(), profile.config(), profile.keybinds());
                profilesById.put(id, copy);
                profiles.add(copy);
                added.add(copy);
            }
            if (!added.isEmpty()) write(profiles);
            return List.copyOf(added);
        }
    }

    private static UUID availableImportId(Profile incoming, Map<UUID, Profile> profilesById) {
        UUID id = incoming.id();
        Profile existing = profilesById.get(id);
        if (existing == null) return id;
        if (sameSnapshot(incoming, existing)) return null;

        // Give revisions of a shared profile stable separate IDs, so importing the same revision twice is a no-op.
        String snapshot = JSON.toJson(incoming);
        for (int attempt = 0; ; attempt++) {
            id = UUID.nameUUIDFromBytes((snapshot + "\n" + attempt).getBytes(StandardCharsets.UTF_8));
            existing = profilesById.get(id);
            if (existing == null) return id;
            if (sameSnapshot(incoming, existing)) return null;
        }
    }

    private static boolean sameSnapshot(Profile first, Profile second) {
        return first.color() == second.color() && first.description().equals(second.description())
                && first.keybinds().equals(second.keybinds())
                && JSON.toJsonTree(first.config()).equals(JSON.toJsonTree(second.config()));
    }

    private static String reserveImportName(String name, Set<String> names) {
        String candidate = name;
        for (int copy = 2; !names.add(candidate); copy++) {
            String suffix = " (" + copy + ")";
            int end = Math.min(name.length(), MAX_NAME_LENGTH - suffix.length());
            if (end < name.length() && Character.isHighSurrogate(name.charAt(end - 1))) end--;
            candidate = name.substring(0, end).stripTrailing() + suffix;
        }
        return candidate;
    }

    private List<Profile> read() throws IOException {
        return read(path, true);
    }

    private static List<Profile> read(Path file, boolean allowMissing) throws IOException {
        Library library;
        try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            library = JSON.fromJson(reader, Library.class);
        } catch (NoSuchFileException e) {
            if (allowMissing) return new ArrayList<>();
            throw new IOException("Could not find " + file.getFileName() + ". Choose an existing config file.", e);
        } catch (IOException e) {
            throw new IOException("Could not open " + file.getFileName() + ". Check file access.", e);
        } catch (RuntimeException e) {
            throw damagedFile(file, allowMissing, e);
        }

        if (library == null || library.profiles() == null) throw damagedFile(file, allowMissing, null);
        if (library.version() != VERSION) {
            throw new IOException("Unsupported profile format in " + file.getFileName() + ".");
        }

        var ids = new HashSet<UUID>();
        var names = new TreeSet<String>(String.CASE_INSENSITIVE_ORDER);
        var profiles = new ArrayList<Profile>();
        try {
            for (Profile entry : library.profiles()) {
                Profile profile = validate(entry);
                if (!ids.add(profile.id()) || !names.add(profile.name())) {
                    throw new IllegalArgumentException("Duplicate profile name or ID.");
                }
                profile.config().sanitize();
                profiles.add(profile);
            }
        } catch (RuntimeException e) {
            throw damagedFile(file, allowMissing, e);
        }
        return profiles;
    }

    private static IOException damagedFile(Path file, boolean library, Throwable cause) {
        String hint = library ? "Repair or move the file to use configs again." : "Choose a file exported from Donut Configs.";
        return new IOException("Could not read " + file.getFileName() + ". " + hint, cause);
    }

    private void write(List<Profile> profiles) throws IOException {
        write(path, profiles);
    }

    private static void write(Path file, List<Profile> profiles) throws IOException {
        try {
            ConfigStore.writeAtomically(file, JSON.toJson(new Library(VERSION, profiles)));
        } catch (IOException | RuntimeException e) {
            throw new IOException("Could not save " + file.getFileName() + ". Check that the config folder is writable.", e);
        }
    }

    private static Profile validate(Profile profile) {
        if (profile == null || profile.id() == null || profile.config() == null || profile.keybinds() == null) {
            throw new IllegalArgumentException("The config profile is incomplete.");
        }
        String name = Objects.toString(profile.name(), "").strip();
        if (name.isBlank() || name.length() > MAX_NAME_LENGTH || name.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Enter a single-line config name up to " + MAX_NAME_LENGTH + " characters.");
        }
        String description = Objects.toString(profile.description(), "").replace("\r\n", "\n").replace('\r', '\n').strip();
        if (description.length() > MAX_DESCRIPTION_LENGTH) {
            throw new IllegalArgumentException("Use " + MAX_DESCRIPTION_LENGTH + " characters or fewer for the description.");
        }
        if (profile.color() < 0 || profile.color() > 0xFFFFFF) {
            throw new IllegalArgumentException("Choose a valid profile color.");
        }
        var keybinds = new TreeMap<String, String>();
        for (var entry : profile.keybinds().entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank() || entry.getValue() == null || entry.getValue().isBlank()) {
                throw new IllegalArgumentException("Each keybind needs a name and binding.");
            }
            keybinds.put(entry.getKey(), entry.getValue());
        }
        return new Profile(profile.id(), name, description, profile.color(), profile.config(), Collections.unmodifiableMap(keybinds));
    }

    private static int indexOf(List<Profile> profiles, UUID id) {
        for (int i = 0; i < profiles.size(); i++) {
            if (profiles.get(i).id().equals(id)) return i;
        }
        throw new IllegalArgumentException("That config no longer exists. Refresh the profile list.");
    }

    private static void requireUniqueName(List<Profile> profiles, Profile candidate) {
        for (Profile profile : profiles) {
            if (!profile.id().equals(candidate.id()) && profile.name().equalsIgnoreCase(candidate.name())) {
                throw new IllegalArgumentException("A config with that name already exists.");
            }
        }
    }
}
