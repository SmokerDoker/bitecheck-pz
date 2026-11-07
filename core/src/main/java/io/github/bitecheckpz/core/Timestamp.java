package io.github.bitecheckpz.core;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Map;

/**
 * A small signed statement that the published index is still current, so the index itself only has
 * to be signed again when the registry changes (or once a month):
 *
 * <pre>
 * format = 1
 * index_sha256 = "&lt;SHA-256 of index.toml&gt;"
 * issued_at = "2026-11-07T03:17:00Z"
 * </pre>
 *
 * <p>CI signs it daily with the timestamp key, which lives in an environment without a required
 * reviewer. That key can only keep an index that the registry key signed fresh, and only up to
 * {@link Index#MAX_CONFIRMED_AGE} after it was generated; it cannot approve or unblock any mod.
 */
public record Timestamp(String indexSha256, Instant issuedAt) {

    public static final int FORMAT = 1;

    public static String generate(String indexSha256, Instant now) {
        if (!Hashing.isSha256Hex(indexSha256)) {
            throw new IllegalArgumentException("not a SHA-256: " + indexSha256);
        }
        return "# BiteCheck timestamp - the index with this hash is still current. Signed by the timestamp key.\n"
                + "format = " + FORMAT + "\n"
                + "index_sha256 = " + Toml.quote(indexSha256) + "\n"
                + "issued_at = " + Toml.quote(now.truncatedTo(ChronoUnit.SECONDS).toString()) + "\n";
    }

    public static Timestamp parse(Path file) throws IOException, Toml.TomlException {
        Map<String, Object> doc = Toml.parse(file);
        Object format = doc.get("format");
        if (!(format instanceof Long f) || f != FORMAT) {
            throw new IOException(file + ": unsupported timestamp format " + format);
        }
        String sha = Toml.string(doc, "index_sha256");
        if (sha == null || !Hashing.isSha256Hex(sha)) {
            throw new IOException(file + ": invalid index_sha256");
        }
        try {
            return new Timestamp(sha, Instant.parse(String.valueOf(Toml.string(doc, "issued_at"))));
        } catch (DateTimeParseException e) {
            throw new IOException(file + ": invalid issued_at");
        }
    }
}
