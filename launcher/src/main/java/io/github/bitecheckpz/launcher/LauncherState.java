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
 *   <li>the player's answers about mods that are not verified: allowed ({@code allowed_anyway}) or
 *       not ({@code refused}),</li>
 *   <li>the last version of each jar the player trusted, to tell an update from a jar that changed
 *       without one,</li>
 *   <li>the newest index it accepted, so an older index cannot be replayed.</li>
 * </ul>
 */
public final class LauncherState {

    private final Path file;
    private final Map<String, String> autoApproved = new TreeMap<>();
    private final Map<String, String> allowedAnyway = new TreeMap<>();
    private final Map<String, String> refused = new TreeMap<>();
    private final Map<String, Trusted> trusted = new TreeMap<>();
    private Instant newestIndex;
    private boolean tampered;

    /** Holds the key for the check value of the player's answers, next to state.toml. */
    static final String KEY_FILE = "state.key";

    /**
     * The last trusted version of a jar (verified, or allowed by the player).
     *
     * @param jar          where the jar sits, e.g. {@code ZBBetterFPS/42/media/java/ZBBetterFPS.jar}
     * @param version      the jar's Implementation-Version, or null
     * @param steamUpdated when Steam last updated the Workshop item (Unix time), or null
     */
    public record Trusted(String jar, String sha256, String version, String steamUpdated) {
    }

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
        copyHashes(Toml.table(doc, "refused"), state.refused);
        for (Map.Entry<String, Object> e : Toml.table(doc, "trusted").entrySet()) {
            if (e.getValue() instanceof Map<?, ?> raw) {
                @SuppressWarnings("unchecked")
                Map<String, Object> t = (Map<String, Object>) raw;
                String jar = Toml.string(t, "jar");
                String sha = Toml.string(t, "sha256");
                if (jar != null && sha != null && Hashing.isSha256Hex(sha)) {
                    state.trusted.put(jar, new Trusted(jar, sha, Toml.string(t, "version"),
                            Toml.string(t, "steam_updated")));
                }
            }
        }
        String newest = Toml.string(doc, "newest_index");
        if (newest != null) {
            try {
                state.newestIndex = Instant.parse(newest);
            } catch (DateTimeParseException e) {
                // ignore a broken value; the next accepted index fixes it
            }
        }
        // Answers changed outside BiteCheck are not used: the player is asked again. A file from
        // before check values (no key yet) is accepted once and gets one when it is saved.
        Path keyFile = file.resolveSibling(KEY_FILE);
        if (Files.isRegularFile(keyFile)) {
            String expected = state.checkValue(Files.readAllBytes(keyFile));
            if (!expected.equals(Toml.string(doc, "check"))) {
                state.tampered = !state.allowedAnyway.isEmpty() || !state.refused.isEmpty() || !state.trusted.isEmpty();
                state.allowedAnyway.clear();
                state.refused.clear();
                state.trusted.clear();
            }
        }
        return state;
    }

    /** True if the saved answers were changed outside BiteCheck; they were not loaded. */
    public boolean tampered() {
        return tampered;
    }

    /** HMAC-SHA256 over the player's answers and trusted versions, in a fixed order. */
    private String checkValue(byte[] key) throws IOException {
        StringBuilder text = new StringBuilder("bitecheck-state-1\n");
        allowedAnyway.forEach((k, v) -> text.append("allowed\t").append(k).append('\t').append(v).append('\n'));
        refused.forEach((k, v) -> text.append("refused\t").append(k).append('\t').append(v).append('\n'));
        trusted.values().forEach(t -> text.append("trusted\t").append(t.jar()).append('\t').append(t.sha256())
                .append('\t').append(t.version()).append('\t').append(t.steamUpdated()).append('\n'));
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(key, "HmacSHA256"));
            return java.util.HexFormat.of().formatHex(mac.doFinal(text.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException e) {
            throw new IOException("cannot compute the state check value: " + e.getMessage(), e);
        }
    }

    private byte[] key() throws IOException {
        Path keyFile = file.resolveSibling(KEY_FILE);
        if (Files.isRegularFile(keyFile)) {
            return Files.readAllBytes(keyFile);
        }
        byte[] key = new byte[32];
        new java.security.SecureRandom().nextBytes(key);
        Files.createDirectories(keyFile.getParent());
        Files.write(keyFile, key);
        return key;
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

    /** Jars the player said no to; BiteCheck does not ask about them again. */
    public Map<String, String> refused() {
        return refused;
    }

    /** By {@link Trusted#jar()}. */
    public Map<String, Trusted> trusted() {
        return trusted;
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
        out.append("# Check value of the answers below; BiteCheck ignores answers changed outside BiteCheck.\n");
        out.append("check = ").append(Toml.quote(checkValue(key()))).append('\n');
        out.append("\n[auto_approved]\n");
        autoApproved.forEach((k, v) -> out.append(k).append(" = ").append(Toml.quote(v)).append('\n'));
        out.append("\n[allowed_anyway]\n");
        allowedAnyway.forEach((k, v) -> out.append(k).append(" = ").append(Toml.quote(v)).append('\n'));
        out.append("\n[refused]\n");
        refused.forEach((k, v) -> out.append(k).append(" = ").append(Toml.quote(v)).append('\n'));
        for (Trusted t : trusted.values()) {
            // a bare key derived from the path; the path itself is in the table
            out.append("\n[trusted.j").append(Hashing.sha256Hex(t.jar().getBytes(StandardCharsets.UTF_8)), 0, 16)
                    .append("]\n");
            out.append("jar = ").append(Toml.quote(t.jar())).append('\n');
            out.append("sha256 = ").append(Toml.quote(t.sha256())).append('\n');
            if (t.version() != null) {
                out.append("version = ").append(Toml.quote(t.version())).append('\n');
            }
            if (t.steamUpdated() != null) {
                out.append("steam_updated = ").append(Toml.quote(t.steamUpdated())).append('\n');
            }
        }
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, out.toString(), StandardCharsets.UTF_8);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
    }
}
