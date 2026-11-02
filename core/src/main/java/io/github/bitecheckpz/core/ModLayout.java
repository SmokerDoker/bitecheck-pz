package io.github.bitecheckpz.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Checks that the jars of a "published" release sit where Project Zomboid Build 42 and ZombieBuddy
 * will load them, so reviewers do not spend time on a mod that never runs.
 *
 * <p>Build 42 loads a mod from a version folder ({@code 42/}, {@code 42.13/}, ...) next to
 * {@code common/}; {@code mod.info} is in the version folder or in {@code common/}. ZombieBuddy
 * loads the jars that {@code mod.info} names in {@code javaJarFile}, {@code javaJarFile1}, ...
 * relative to those folders. Jars in older version folders (e.g. {@code 41/}) are for Build 41
 * and are ignored, as long as the release also has jars for Build 42.
 */
public final class ModLayout {

    private static final Pattern VERSION_DIR = Pattern.compile("\\d+(\\.\\d+)*");
    private static final Pattern JAR_KEY = Pattern.compile("javaJarFile\\d*");
    private static final int MIN_BUILD = 42;

    private ModLayout() {
    }

    /** Problems with the layout of {@code artifacts} (paths relative to {@code checkout}); empty if it loads. */
    public static List<String> problems(Path checkout, List<String> artifacts) throws IOException {
        List<String> problems = new ArrayList<>();
        int forBuild42 = 0;
        for (String artifact : artifacts) {
            int media = ("/" + artifact).indexOf("/media/java/");
            if (media < 1) {
                problems.add(artifact + ": not in <version folder>/media/java/");
                continue;
            }
            String folderPath = artifact.substring(0, media - 1);
            String inFolder = artifact.substring(media);
            Path folder = checkout.resolve(folderPath);
            String folderName = folder.getFileName().toString();
            Path modRoot = folder.getParent() == null ? checkout : folder.getParent();
            String root = checkout.relativize(modRoot).toString().replace('\\', '/');
            String rootPrefix = root.isEmpty() ? "" : root + "/";
            if (!folderName.equals("common") && !VERSION_DIR.matcher(folderName).matches()) {
                problems.add(artifact + ": '" + folderName + "' is neither a version folder (42, 42.13, ...) nor common");
                continue;
            }
            if (!folderName.equals("common") && major(folderName) < MIN_BUILD) {
                continue; // a jar for an older build, not loaded by Build 42
            }
            forBuild42++;
            List<Path> versions = versionFolders(modRoot);
            if (versions.isEmpty()) {
                problems.add(artifact + ": no version folder " + rootPrefix + "42/ (or 42.x/), Build 42 will not load the mod");
                continue;
            }
            List<Path> modInfos = new ArrayList<>(versions);
            modInfos.add(modRoot.resolve("common"));
            modInfos.replaceAll(d -> d.resolve("mod.info"));
            modInfos.removeIf(f -> !Files.isRegularFile(f));
            if (modInfos.isEmpty()) {
                problems.add(artifact + ": no mod.info in " + rootPrefix + "common/ or " + rootPrefix
                        + "42/, Build 42 will not load the mod");
                continue;
            }
            Set<String> named = new TreeSet<>();
            for (Path modInfo : modInfos) {
                named.addAll(jarFiles(modInfo));
            }
            if (!named.contains(inFolder.toLowerCase(Locale.ROOT))) {
                problems.add(artifact + ": not named in mod.info (javaJarFile), ZombieBuddy will not load it");
            }
        }
        if (forBuild42 == 0 && problems.isEmpty()) {
            problems.add("no jar for Build 42 (only older version folders)");
        }
        return problems;
    }

    /** Version folders of Build 42 or newer directly inside a mod folder. */
    private static List<Path> versionFolders(Path modRoot) throws IOException {
        if (!Files.isDirectory(modRoot)) {
            return List.of();
        }
        try (Stream<Path> dirs = Files.list(modRoot)) {
            return dirs.filter(Files::isDirectory)
                    .filter(d -> VERSION_DIR.matcher(d.getFileName().toString()).matches())
                    .filter(d -> major(d.getFileName().toString()) >= MIN_BUILD).sorted().toList();
        }
    }

    /** The jar paths in mod.info, normalized to "media/java/...", lower case (the game ignores case on Windows). */
    private static List<String> jarFiles(Path modInfo) throws IOException {
        List<String> jars = new ArrayList<>();
        for (String line : Files.readAllLines(modInfo, StandardCharsets.UTF_8)) {
            int eq = line.indexOf('=');
            if (eq > 0 && JAR_KEY.matcher(line.substring(0, eq).strip()).matches()) {
                jars.add(line.substring(eq + 1).strip().replace('\\', '/').replaceFirst("^/+", "")
                        .toLowerCase(Locale.ROOT));
            }
        }
        return jars;
    }

    private static int major(String versionFolder) {
        try {
            return Integer.parseInt(versionFolder.split("\\.")[0]);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
