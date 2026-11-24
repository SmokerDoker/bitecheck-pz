package io.github.bitecheckpz.launcher;

import io.github.bitecheckpz.core.Hashing;
import io.github.bitecheckpz.core.Json;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Closes ZombieBuddy's two ways around a per-jar decision, in its {@code config.json}:
 * <ul>
 *   <li>{@code preload_mods}: these jars load at startup by path, without looking at
 *       {@code mod_approvals.json}. BiteCheck removes every entry whose jar it does not allow.</li>
 *   <li>{@code trusted_authors}: a jar signed by a trusted author loads even where
 *       {@code mod_approvals.json} says no. BiteCheck decides per jar, so the list is cleared.</li>
 * </ul>
 * Everything else in the file is written back unchanged; an unreadable file is left alone.
 */
final class ZombieBuddyConfig {

    static final String FILE_NAME = "config.json";

    private ZombieBuddyConfig() {
    }

    /**
     * @param allowed SHA-256 of the jars BiteCheck lets load (verified or allowed by the player)
     * @return notes for the player about what was changed; empty if nothing
     */
    @SuppressWarnings("unchecked")
    static List<String> secure(Path zombieBuddyDir, Set<String> allowed) throws IOException {
        Path file = zombieBuddyDir.resolve(FILE_NAME);
        List<String> notes = new ArrayList<>();
        if (!Files.isRegularFile(file) || Files.size(file) == 0) {
            return notes;
        }
        Object parsed;
        try {
            parsed = Json.parse(Files.readString(file, StandardCharsets.UTF_8));
        } catch (Json.JsonException e) {
            notes.add(file + " is not valid JSON; BiteCheck could not check ZombieBuddy's preload and trusted-author "
                    + "settings.");
            return notes;
        }
        if (!(parsed instanceof Map)) {
            return notes;
        }
        Map<String, Object> root = (Map<String, Object>) parsed;
        boolean changed = false;
        if (root.get("trusted_authors") instanceof List<?> authors && !authors.isEmpty()) {
            root.put("trusted_authors", new ArrayList<>());
            changed = true;
            notes.add("ZombieBuddy's trusted authors were cleared: their jars could load even where you said no. "
                    + "BiteCheck asks per jar instead.");
        }
        if (root.get("preload_mods") instanceof Map<?, ?> preload) {
            for (Iterator<? extends Map.Entry<?, ?>> it = preload.entrySet().iterator(); it.hasNext(); ) {
                Map.Entry<?, ?> e = it.next();
                if (!allowedJar(e.getValue(), allowed)) {
                    it.remove();
                    changed = true;
                    notes.add("Preloading of " + e.getKey() + " was switched off: preloaded jars skip ZombieBuddy's "
                            + "approvals, and BiteCheck does not allow this jar.");
                }
            }
        }
        if (changed) {
            Path tmp = file.resolveSibling(FILE_NAME + ".tmp");
            Files.writeString(tmp, Json.write(root), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
        return notes;
    }

    /** A preload entry {@code {"infPath": "...", "jarPath": "..."}} whose jar BiteCheck allows. */
    private static boolean allowedJar(Object entry, Set<String> allowed) {
        if (!(entry instanceof Map<?, ?> m) || !(m.get("jarPath") instanceof String jarPath)) {
            return false;
        }
        try {
            Path jar = Path.of(jarPath);
            return Files.isRegularFile(jar) && allowed.contains(Hashing.sha256Hex(jar));
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }
}
