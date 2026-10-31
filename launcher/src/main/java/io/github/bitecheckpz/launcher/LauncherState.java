package io.github.bitecheckpz.launcher;

import io.github.bitecheckpz.core.Hashing;
import io.github.bitecheckpz.core.Toml;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * What BiteCheck remembers between runs, in {@code ~/.bitecheck/state.toml}:
 * <ul>
 *   <li>which approvals it wrote into ZombieBuddy's file itself, so it can take them back when a
 *       mod loses its verification,</li>
 *   <li>flagged or suspicious mods the player decided to allow anyway,</li>
 *   <li>the newest index it accepted, so an older index cannot be replayed.</li>
 * </ul>
 */
public final class LauncherState {

    private final Path file;
    private final Map<String, String> autoApproved = new TreeMap<>();
    private final Map<String, String> allowedAnyway = new TreeMap<>();
    private Instant newestIndex;

    private LauncherState(Path file) {
        this.file = file;
    }

    public static LauncherState load(Path file) throws IOException {
        LauncherState state = new LauncherState(file);
        if (!Files.isRegularFile(file)) {
            return state;
        }
        Map<String, Object> doc;
        try {
            doc = Toml.parse(file);
        } catch (Toml.TomlException e) {
            throw new IOException("invalid state file: " + e.getMessage(), e);
        }
        copyHashes(Toml.table(doc, "auto_approved"), state.autoApproved);
        copyHashes(Toml.table(doc, "allowed_anyway"), state.allowedAnyway);
        String newest = Toml.string(doc, "newest_index");
        if (newest != null) {
            try {
                state.newestIndex = Instant.parse(newest);
            } catch (DateTimeParseException e) {
                // ignore a broken value; the next accepted index fixes it
            }
        }
        return state;
    }

    private static void copyHashes(Map<String, Object> from, Map<String, String> to) {
        for (Map.Entry<String, Object> e : from.entrySet()) {
            if (Hashing.isSha256Hex(e.getKey())) {
                to.put(e.getKey(), String.valueOf(e.getValue()));
            }
        }
    }

    public Map<String, String> autoApproved() {
        return autoApproved;
    }

    public Map<String, String> allowedAnyway() {
        return allowedAnyway;
    }

    public Optional<Instant> newestIndex() {
        return Optional.ofNullable(newestIndex);
    }

    public void setNewestIndex(Instant generatedAt) {
        if (newestIndex == null || generatedAt.isAfter(newestIndex)) {
            newestIndex = generatedAt;
        }
    }

    public void save() throws IOException {
        StringBuilder out = new StringBuilder("# BiteCheck launcher state. Safe to delete.\n");
        if (newestIndex != null) {
            out.append("newest_index = ").append(Toml.quote(newestIndex.toString())).append('\n');
        }
        out.append("\n[auto_approved]\n");
        autoApproved.forEach((k, v) -> out.append(k).append(" = ").append(Toml.quote(v)).append('\n'));
        out.append("\n[allowed_anyway]\n");
        allowedAnyway.forEach((k, v) -> out.append(k).append(" = ").append(Toml.quote(v)).append('\n'));
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, out.toString(), StandardCharsets.UTF_8);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
    }
}
