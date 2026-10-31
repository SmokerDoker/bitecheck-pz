package io.github.bitecheckpz.launcher;

import io.github.bitecheckpz.core.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads and edits ZombieBuddy's {@code mod_approvals.json} (format version 2).
 *
 * <pre>
 * { "formatVersion": 2,
 *   "mods": [ { "id": "…", "workshop_id": 123, "jar_hash": "…", "decision": true, "time": "…" } ] }
 * </pre>
 *
 * <p>ZombieBuddy looks decisions up by {@code jar_hash}: {@code true} loads the jar without asking,
 * {@code false} blocks it, no entry means ZombieBuddy asks the player. Everything BiteCheck does not
 * touch (other entries, unknown fields) is written back unchanged.
 */
public final class ZombieBuddyApprovals {

    public static final String FILE_NAME = "mod_approvals.json";
    static final int SUPPORTED_FORMAT = 2;

    private final Path file;
    private final Map<String, Object> root;
    private boolean dirty;

    private ZombieBuddyApprovals(Path file, Map<String, Object> root) {
        this.file = file;
        this.root = root;
    }

    @SuppressWarnings("unchecked")
    public static ZombieBuddyApprovals load(Path zombieBuddyDir) throws IOException {
        Path file = zombieBuddyDir.resolve(FILE_NAME);
        Map<String, Object> root;
        if (Files.isRegularFile(file) && Files.size(file) > 0) {
            Object parsed;
            try {
                parsed = Json.parse(Files.readString(file, StandardCharsets.UTF_8));
            } catch (Json.JsonException e) {
                throw new IOException(file + " is not valid JSON (" + e.getMessage() + "); not changing it");
            }
            if (!(parsed instanceof Map)) {
                throw new IOException(file + " has an unexpected structure; not changing it");
            }
            root = (Map<String, Object>) parsed;
            Object format = root.get("formatVersion");
            if (!(format instanceof Json.Num n) || !n.text().equals(Integer.toString(SUPPORTED_FORMAT))) {
                throw new IOException(file + " uses ZombieBuddy approval format " + format
                        + ", BiteCheck supports " + SUPPORTED_FORMAT + "; please update BiteCheck");
            }
            if (!(root.get("mods") instanceof List)) {
                root.put("mods", new ArrayList<>());
            }
        } else {
            root = new LinkedHashMap<>();
            root.put("formatVersion", Json.Num.of(SUPPORTED_FORMAT));
            root.put("mods", new ArrayList<>());
        }
        return new ZombieBuddyApprovals(file, root);
    }

    public Path file() {
        return file;
    }

    @SuppressWarnings("unchecked")
    private List<Object> entries() {
        return (List<Object>) root.get("mods");
    }

    /** The stored decision for a jar hash: TRUE, FALSE or null (ZombieBuddy will ask). */
    @SuppressWarnings("unchecked")
    public Boolean decision(String sha256) {
        Boolean result = null;
        for (Object o : entries()) {
            if (o instanceof Map<?, ?> m && sha256.equals(((Map<String, Object>) m).get("jar_hash"))) {
                Object d = m.get("decision");
                if (Boolean.FALSE.equals(d)) {
                    return Boolean.FALSE; // any stored denial wins
                }
                if (Boolean.TRUE.equals(d)) {
                    result = Boolean.TRUE;
                }
            }
        }
        return result;
    }

    /** Stores a decision for a jar hash, replacing existing decisions for that hash. */
    @SuppressWarnings("unchecked")
    public void set(String modId, String workshopId, String sha256, boolean decision) {
        boolean found = false;
        for (Object o : entries()) {
            if (o instanceof Map<?, ?> m && sha256.equals(m.get("jar_hash"))) {
                Map<String, Object> entry = (Map<String, Object>) m;
                if (!Boolean.valueOf(decision).equals(entry.get("decision"))) {
                    entry.put("decision", decision);
                    entry.put("time", now());
                    dirty = true;
                }
                found = true;
            }
        }
        if (!found) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", modId == null ? "" : modId);
            if (workshopId != null && workshopId.matches("[0-9]{1,19}")) {
                entry.put("workshop_id", new Json.Num(workshopId));
            }
            entry.put("jar_hash", sha256);
            entry.put("decision", decision);
            entry.put("time", now());
            entries().add(entry);
            dirty = true;
        }
    }

    /** Removes all decisions for a jar hash, so ZombieBuddy asks the player again. */
    public void remove(String sha256) {
        Iterator<Object> it = entries().iterator();
        while (it.hasNext()) {
            if (it.next() instanceof Map<?, ?> m && sha256.equals(m.get("jar_hash"))) {
                it.remove();
                dirty = true;
            }
        }
    }

    public boolean isDirty() {
        return dirty;
    }

    /** Writes the file atomically. Keeps a one-time backup of the original next to it. */
    public void save() throws IOException {
        if (!dirty) {
            return;
        }
        Files.createDirectories(file.getParent());
        Path backup = file.resolveSibling(FILE_NAME + ".bitecheck-backup");
        if (Files.isRegularFile(file) && !Files.exists(backup)) {
            Files.copy(file, backup);
        }
        Path tmp = file.resolveSibling(FILE_NAME + ".bitecheck-tmp");
        Files.writeString(tmp, Json.write(root), StandardCharsets.UTF_8);
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
        dirty = false;
    }

    private static String now() {
        return Instant.now().truncatedTo(ChronoUnit.SECONDS).toString();
    }
}
