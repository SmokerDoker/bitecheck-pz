package io.github.bitecheckpz.launcher;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds Steam, Project Zomboid and the mod folders on Windows, macOS and Linux.
 * Every location can be overridden in {@code ~/.bitecheck/config.toml}.
 */
public final class Platform {

    public static final String PZ_APP_ID = "108600";

    public enum Os {
        WINDOWS, MACOS, LINUX
    }

    private static final Pattern VDF_PATH = Pattern.compile("\"path\"\\s+\"((?:[^\"\\\\]|\\\\.)*)\"");

    private final Os os;
    private final Path home;
    private final Config config;

    public Platform(Config config) {
        this(detectOs(), Path.of(System.getProperty("user.home")), config);
    }

    Platform(Os os, Path home, Config config) {
        this.os = os;
        this.home = home;
        this.config = config;
    }

    public static Os detectOs() {
        String name = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (name.contains("win")) {
            return Os.WINDOWS;
        }
        if (name.contains("mac") || name.contains("darwin")) {
            return Os.MACOS;
        }
        return Os.LINUX;
    }

    public Os os() {
        return os;
    }

    public Path home() {
        return home;
    }

    /** BiteCheck's own folder: keys, cache, state, config. */
    public Path biteCheckDir() {
        return home.resolve(".bitecheck");
    }

    /** Project Zomboid's user folder (saves, local mods). */
    public Path zomboidDir() {
        return config.path("zomboid_dir").orElse(home.resolve("Zomboid"));
    }

    /** ZombieBuddy's config folder holding mod_approvals.json. */
    public Path zombieBuddyDir() {
        return config.path("zombiebuddy_dir").orElse(home.resolve(".zombie_buddy"));
    }

    /** Steam installation folders that exist on this machine. */
    public List<Path> steamRoots() {
        Set<Path> candidates = new LinkedHashSet<>();
        config.path("steam_dir").ifPresent(candidates::add);
        switch (os) {
            case WINDOWS -> {
                windowsRegistrySteamPath().ifPresent(candidates::add);
                candidates.add(Path.of(envOr("ProgramFiles(x86)", "C:\\Program Files (x86)"), "Steam"));
                candidates.add(Path.of(envOr("ProgramFiles", "C:\\Program Files"), "Steam"));
            }
            case MACOS -> candidates.add(home.resolve("Library/Application Support/Steam"));
            case LINUX -> {
                candidates.add(home.resolve(".steam/steam"));
                candidates.add(home.resolve(".local/share/Steam"));
                candidates.add(home.resolve(".var/app/com.valvesoftware.Steam/.local/share/Steam"));
                candidates.add(home.resolve("snap/steam/common/.local/share/Steam"));
            }
        }
        List<Path> result = new ArrayList<>();
        Set<Path> seen = new LinkedHashSet<>();
        for (Path p : candidates) {
            if (Files.isDirectory(p.resolve("steamapps")) && seen.add(realPath(p))) {
                result.add(p);
            }
        }
        return result;
    }

    /** All Steam library folders (each contains a steamapps folder). */
    public List<Path> steamLibraries() {
        Set<Path> seen = new LinkedHashSet<>();
        List<Path> result = new ArrayList<>();
        for (Path root : steamRoots()) {
            addLibrary(root, seen, result);
            Path vdf = root.resolve("steamapps/libraryfolders.vdf");
            if (Files.isRegularFile(vdf)) {
                try {
                    for (Path lib : parseLibraryFolders(Files.readString(vdf, StandardCharsets.UTF_8))) {
                        addLibrary(lib, seen, result);
                    }
                } catch (IOException e) {
                    // unreadable vdf: keep what we have
                }
            }
        }
        return result;
    }

    private static void addLibrary(Path lib, Set<Path> seen, List<Path> result) {
        if (Files.isDirectory(lib.resolve("steamapps")) && seen.add(realPath(lib))) {
            result.add(lib);
        }
    }

    static List<Path> parseLibraryFolders(String vdf) {
        List<Path> paths = new ArrayList<>();
        Matcher m = VDF_PATH.matcher(vdf);
        while (m.find()) {
            paths.add(Path.of(m.group(1).replace("\\\\", "\\")));
        }
        return paths;
    }

    /** Project Zomboid's install folder. */
    public Optional<Path> gameDir() {
        Optional<Path> configured = config.path("game_dir");
        if (configured.isPresent()) {
            return configured;
        }
        for (Path lib : steamLibraries()) {
            Path dir = lib.resolve("steamapps/common/ProjectZomboid");
            if (Files.isDirectory(dir)) {
                return Optional.of(dir);
            }
        }
        return Optional.empty();
    }

    /** Steam Workshop content folders for Project Zomboid. */
    public List<Path> workshopContentDirs() {
        List<Path> result = new ArrayList<>();
        config.path("workshop_dir").ifPresent(result::add);
        for (Path lib : steamLibraries()) {
            Path dir = lib.resolve("steamapps/workshop/content/" + PZ_APP_ID);
            if (Files.isDirectory(dir)) {
                result.add(dir);
            }
        }
        return result;
    }

    private Optional<Path> windowsRegistrySteamPath() {
        try {
            Process p = new ProcessBuilder("reg", "query", "HKCU\\Software\\Valve\\Steam", "/v", "SteamPath")
                    .redirectErrorStream(true).start();
            String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!p.waitFor(5, TimeUnit.SECONDS)) {
                p.destroy();
                return Optional.empty();
            }
            for (String line : output.split("\\R")) {
                int idx = line.indexOf("REG_SZ");
                if (line.contains("SteamPath") && idx >= 0) {
                    return Optional.of(Path.of(line.substring(idx + "REG_SZ".length()).strip()));
                }
            }
        } catch (IOException e) {
            // reg.exe not available
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return Optional.empty();
    }

    private static String envOr(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static Path realPath(Path p) {
        try {
            return p.toRealPath();
        } catch (IOException e) {
            return p.toAbsolutePath().normalize();
        }
    }
}
