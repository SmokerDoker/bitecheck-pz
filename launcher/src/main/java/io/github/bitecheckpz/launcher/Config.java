package io.github.bitecheckpz.launcher;

import io.github.bitecheckpz.core.Toml;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

/**
 * Optional settings from {@code config.toml} in the BiteCheck folder. Every key is optional.
 * Relative paths are relative to the folder of the config file.
 *
 * <pre>
 * index_url = "https://…/index.toml"     # or a file path, for testing
 * registry_key = "/path/to/registry.pub" # replaces the key built into the launcher (testing)
 * zomboid_dir = "…"                      # instead of ~/Zomboid
 * zombiebuddy_dir = "…"                  # instead of ~/.zombie_buddy
 * steam_dir = "…", game_dir = "…", workshop_dir = "…"
 * </pre>
 */
public final class Config {

    private final Map<String, Object> values;
    private final Path file;

    private Config(Path file, Map<String, Object> values) {
        this.file = file;
        this.values = values;
    }

    public static Config empty() {
        return new Config(null, Map.of());
    }

    public static Config load(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            return new Config(file, Map.of());
        }
        try {
            return new Config(file, Toml.parse(file));
        } catch (Toml.TomlException e) {
            throw new IOException("invalid config: " + e.getMessage(), e);
        }
    }

    public Optional<String> string(String key) {
        String value = Toml.string(values, key);
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(value);
    }

    public Optional<Path> path(String key) {
        return string(key).map(this::resolve);
    }

    /** A URL, or a file path (relative to the config folder) turned into a file: URL. */
    public Optional<URI> url(String key) {
        return string(key).map(v -> v.contains("://") ? URI.create(v) : resolve(v).toUri());
    }

    private Path resolve(String value) {
        Path p = Path.of(value);
        if (!p.isAbsolute() && file != null && file.toAbsolutePath().getParent() != null) {
            return file.toAbsolutePath().getParent().resolve(p).normalize();
        }
        return p;
    }

    public Path file() {
        return file;
    }
}
