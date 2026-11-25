package dev.donut.client.config;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Resolves Donut's own settings files and copies forward settings from older installs. */
public record DonutConfigPaths(Path active, Path profiles) {
    private static final Logger LOGGER = LoggerFactory.getLogger("Donut/Config");

    public static DonutConfigPaths resolve(Path fabricConfigDirectory) {
        Path directory = fabricConfigDirectory.resolve("donut-client");
        return new DonutConfigPaths(
                resolveFile(fabricConfigDirectory, directory, "donut-client.json"),
                resolveFile(fabricConfigDirectory, directory, "donut-configs.json"));
    }

    private static Path resolveFile(Path legacyDirectory, Path directory, String name) {
        Path legacy = legacyDirectory.resolve(name);
        Path destination = directory.resolve(name);
        if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS) || Files.notExists(legacy, LinkOption.NOFOLLOW_LINKS)) {
            return destination;
        }

        try {
            Files.createDirectories(directory);
            Path staged = Files.createTempFile(directory, "donut-migration-", ".tmp");
            try {
                Files.copy(legacy, staged, StandardCopyOption.REPLACE_EXISTING);
                // A separate client starting at the same time may have created the destination.
                Files.move(staged, destination);
            } finally {
                Files.deleteIfExists(staged);
            }
            return destination;
        } catch (FileAlreadyExistsException e) {
            if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) return destination;
            LOGGER.warn("Could not copy {} into {}. Using the previous file.", legacy, directory, e);
            return legacy;
        } catch (IOException e) {
            LOGGER.warn("Could not copy {} into {}. Using the previous file.", legacy, directory, e);
            return legacy;
        }
    }
}
