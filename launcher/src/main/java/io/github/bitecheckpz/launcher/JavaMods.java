package io.github.bitecheckpz.launcher;

import io.github.bitecheckpz.core.Hashing;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Finds installed Java mods: every mod folder whose mod.info (in the folder itself, in
 * {@code common/} or in a version folder like {@code 42/}) declares a {@code javaJarFile}.
 *
 * <p>All jars inside such a mod are collected, not only the one ZombieBuddy will pick for the
 * current version. BiteCheck only ever pre-approves verified hashes, so including extra jars is
 * harmless and avoids re-implementing ZombieBuddy's version selection.
 */
public final class JavaMods {

    /** ZombieBuddy itself is loaded as an agent, not as a mod; the user installed it on purpose. */
    static final String ZOMBIE_BUDDY_PACKAGE = "me.zed_0xff.zombie_buddy";

    private static final int MAX_DEPTH = 8;

    public record Jar(Path path, String sha256) {
    }

    public record JavaMod(String id, String name, Path folder, String workshopId, String source, List<Jar> jars) {
    }

    private JavaMods() {
    }

    /** Scans all mod roots of the platform. Mods with the same folder are reported once. */
    public static List<JavaMod> discover(Platform platform) throws IOException {
        List<Root> roots = new ArrayList<>();
        roots.add(new Root(platform.zomboidDir().resolve("mods"), "local", false));
        Path localWorkshop = platform.zomboidDir().resolve("Workshop");
        if (Files.isDirectory(localWorkshop)) {
            for (Path item : list(localWorkshop)) {
                roots.add(new Root(item.resolve("Contents/mods"), "local workshop", false));
            }
        }
        for (Path content : platform.workshopContentDirs()) {
            for (Path item : list(content)) {
                roots.add(new Root(item.resolve("mods"), "workshop", true));
            }
        }
        Map<Path, JavaMod> mods = new LinkedHashMap<>();
        for (Root root : roots) {
            if (!Files.isDirectory(root.dir)) {
                continue;
            }
            for (Path modFolder : list(root.dir)) {
                if (!Files.isDirectory(modFolder)) {
                    continue;
                }
                Path key = modFolder.toAbsolutePath().normalize();
                if (mods.containsKey(key)) {
                    continue;
                }
                String workshopId = root.workshop ? root.dir.getParent().getFileName().toString() : null;
                JavaMod mod = inspect(modFolder, workshopId, root.label);
                if (mod != null) {
                    mods.put(key, mod);
                }
            }
        }
        return new ArrayList<>(mods.values());
    }

    private record Root(Path dir, String label, boolean workshop) {
    }

    /** Returns the Java mod in this folder, or null if it has no javaJarFile. */
    static JavaMod inspect(Path modFolder, String workshopId, String source) throws IOException {
        List<Path> infos = new ArrayList<>();
        Path direct = modFolder.resolve("mod.info");
        if (Files.isRegularFile(direct)) {
            infos.add(direct);
        }
        for (Path sub : list(modFolder)) {
            Path info = sub.resolve("mod.info");
            if (Files.isDirectory(sub) && Files.isRegularFile(info)) {
                infos.add(info);
            }
        }
        String id = null;
        String name = null;
        boolean java = false;
        for (Path info : infos) {
            Map<String, String> fields = readModInfo(info);
            boolean hasJar = fields.keySet().stream().anyMatch(k -> k.startsWith("javajarfile"));
            if (!hasJar) {
                continue;
            }
            if (ZOMBIE_BUDDY_PACKAGE.equals(fields.get("javapkgname"))) {
                return null;
            }
            java = true;
            if (id == null) {
                id = fields.get("id");
            }
            if (name == null) {
                name = fields.get("name");
            }
        }
        if (!java) {
            return null;
        }
        List<Jar> jars = new ArrayList<>();
        try (Stream<Path> files = Files.walk(modFolder, MAX_DEPTH)) {
            for (Path p : files.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
                    .sorted().toList()) {
                jars.add(new Jar(p, Hashing.sha256Hex(p)));
            }
        }
        if (jars.isEmpty()) {
            return null;
        }
        String fallback = modFolder.getFileName().toString();
        return new JavaMod(id != null ? id : fallback, name != null ? name : fallback, modFolder, workshopId,
                source, jars);
    }

    /** Reads mod.info as lower-case keys; the first value of a key wins (like ZombieBuddy). */
    static Map<String, String> readModInfo(Path info) throws IOException {
        Map<String, String> fields = new LinkedHashMap<>();
        for (String line : Files.readAllLines(info, StandardCharsets.ISO_8859_1)) {
            int eq = line.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String key = line.substring(0, eq).strip().toLowerCase(Locale.ROOT);
            fields.putIfAbsent(key, line.substring(eq + 1).strip());
        }
        return fields;
    }

    private static List<Path> list(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> s = Files.list(dir)) {
            return s.sorted().toList();
        }
    }
}
