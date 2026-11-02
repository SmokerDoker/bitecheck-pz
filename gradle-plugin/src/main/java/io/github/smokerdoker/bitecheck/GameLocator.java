package io.github.smokerdoker.bitecheck;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds the game classes of Project Zomboid Build 42. All game code (and its libraries) is in one jar,
 * {@code projectzomboid.jar}; only its place differs between the game client and the dedicated server.
 */
final class GameLocator {

    /** Where projectzomboid.jar is, relative to an install directory. Matched ignoring case. */
    static final List<String> JAR_LOCATIONS = List.of(
            "projectzomboid.jar",                                   // game client: Windows, Linux with Proton
            "java/projectzomboid.jar",                              // dedicated server (Steam app 380870)
            "projectzomboid/projectzomboid.jar",                    // game client: native Linux depot
            "Project Zomboid.app/Contents/Java/projectzomboid.jar", // game client: macOS
            "Contents/Java/projectzomboid.jar");                    // macOS, install dir = the .app

    /** Install folder names below steamapps/common: the game (108600) and the dedicated server (380870). */
    static final List<String> STEAM_APP_DIRS = List.of("ProjectZomboid", "Project Zomboid Dedicated Server");

    private static final Pattern VDF_PATH = Pattern.compile("\"path\"\\s+\"((?:[^\"\\\\]|\\\\.)*)\"");

    private GameLocator() {
    }

    /** projectzomboid.jar inside an install directory, if there is one. */
    static Optional<Path> gameJar(Path installDir) {
        for (String location : JAR_LOCATIONS) {
            Optional<Path> jar = resolveIgnoringCase(installDir, location);
            if (jar.isPresent() && Files.isRegularFile(jar.get())) {
                return jar;
            }
        }
        return Optional.empty();
    }

    /** Default Steam directories on this kind of computer, existing or not. */
    static List<Path> steamRoots(String osName, Path home, Map<String, String> env) {
        String os = osName.toLowerCase(Locale.ROOT);
        List<Path> roots = new ArrayList<>();
        if (os.contains("win")) {
            String x86 = env.get("ProgramFiles(x86)");
            if (x86 != null && !x86.isBlank()) {
                roots.add(Path.of(x86, "Steam"));
            }
            roots.add(Path.of("C:\\Program Files (x86)\\Steam"));
            roots.add(Path.of("C:\\Program Files\\Steam"));
        } else if (os.contains("mac") || os.contains("darwin")) {
            roots.add(home.resolve("Library/Application Support/Steam"));
        } else {
            roots.add(home.resolve(".steam/steam"));
            roots.add(home.resolve(".local/share/Steam"));
            roots.add(home.resolve(".steam/debian-installation"));
            roots.add(home.resolve(".var/app/com.valvesoftware.Steam/.local/share/Steam"));
            roots.add(home.resolve("snap/steam/common/.local/share/Steam"));
            roots.add(home.resolve("Steam")); // SteamCMD's default
        }
        return roots;
    }

    /** The Steam root plus every library listed in its steamapps/libraryfolders.vdf. */
    static List<Path> libraries(Path steamRoot) {
        Set<Path> result = new LinkedHashSet<>();
        result.add(steamRoot);
        Path vdf = steamRoot.resolve("steamapps").resolve("libraryfolders.vdf");
        if (Files.isRegularFile(vdf)) {
            try {
                result.addAll(parseLibraryFolders(Files.readString(vdf, StandardCharsets.UTF_8)));
            } catch (IOException | RuntimeException e) {
                // an unreadable library list only means fewer places to look
            }
        }
        return new ArrayList<>(result);
    }

    static List<Path> parseLibraryFolders(String vdf) {
        List<Path> paths = new ArrayList<>();
        Matcher m = VDF_PATH.matcher(vdf);
        while (m.find()) {
            String path = m.group(1).replace("\\\\", "\\").replace("\\\"", "\"");
            if (!path.isBlank()) {
                paths.add(Path.of(path));
            }
        }
        return paths;
    }

    /** Every install directory that is searched automatically, in order. */
    static List<Path> candidates(String osName, Path home, Map<String, String> env) {
        Set<Path> result = new LinkedHashSet<>();
        for (Path root : steamRoots(osName, home, env)) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            for (Path library : libraries(root)) {
                for (String app : STEAM_APP_DIRS) {
                    result.add(library.resolve("steamapps").resolve("common").resolve(app));
                }
            }
        }
        return new ArrayList<>(result);
    }

    /** The first automatically found install that contains the game jar. */
    static Optional<Path> autodetect(String osName, Path home, Map<String, String> env) {
        for (Path dir : candidates(osName, home, env)) {
            if (gameJar(dir).isPresent()) {
                return Optional.of(dir);
            }
        }
        return Optional.empty();
    }

    private static Optional<Path> resolveIgnoringCase(Path dir, String relative) {
        Path current = dir;
        for (String part : relative.split("/")) {
            Path exact = current.resolve(part);
            if (Files.exists(exact)) {
                current = exact;
                continue;
            }
            Path match = null;
            if (Files.isDirectory(current)) {
                try (DirectoryStream<Path> entries = Files.newDirectoryStream(current)) {
                    for (Path entry : entries) {
                        if (entry.getFileName().toString().equalsIgnoreCase(part)) {
                            match = entry;
                            break;
                        }
                    }
                } catch (IOException e) {
                    return Optional.empty();
                }
            }
            if (match == null) {
                return Optional.empty();
            }
            current = match;
        }
        return Optional.of(current);
    }
}
