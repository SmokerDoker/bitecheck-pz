package io.github.bitecheckpz.launcher;

import io.github.bitecheckpz.core.Hashing;
import io.github.bitecheckpz.core.Keys;
import io.github.bitecheckpz.core.Signatures;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Compares BiteCheck's own program file with the signed checksums of its release: every release
 * lists the SHA-256 of BiteCheck.jar in SHA256SUMS.txt, signed with the release key. If the jar
 * differs, BiteCheck repairs itself by installing the same release again (see {@link SelfUpdate}).
 *
 * <p>This catches a damaged or changed installation. A program written to attack BiteCheck can
 * change this check too, so it is no protection against malware already on the computer.
 */
final class SelfCheck {

    static final String JAR_NAME = "BiteCheck.jar";

    enum Outcome {
        /** The jar is the one of the signed release. */
        OK,
        /** The jar differs from the signed release. */
        CHANGED,
        /** Not checked: a dev build, offline, turned off, or the release lists no jar hash. */
        UNKNOWN
    }

    record Result(Outcome outcome, String version, String expected, String actual) {

        static Result unknown() {
            return new Result(Outcome.UNKNOWN, null, null, null);
        }
    }

    private SelfCheck() {
    }

    /** The jar this launcher runs from; empty if it does not run from a jar (dev build). */
    static Optional<Path> ownJar() {
        try {
            Path p = Path.of(SelfCheck.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            return Files.isRegularFile(p) && p.getFileName().toString().endsWith(".jar") ? Optional.of(p) : Optional.empty();
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /**
     * Checks the running jar against its release. A match is remembered in {@code cacheDir}, so the
     * checksums are only downloaded again when the jar or the version changes.
     */
    static Result check(Config config, Path cacheDir) {
        Optional<String> version = UpdateCheck.current();
        Optional<Path> jar = ownJar();
        if (version.isEmpty() || jar.isEmpty() || "off".equals(config.string("update_check").orElse(""))) {
            return Result.unknown();
        }
        try {
            Optional<Keys.PublicKeyInfo> key = IndexClient.releaseKey(config);
            if (key.isEmpty()) {
                return Result.unknown();
            }
            String actual = Hashing.sha256Hex(jar.get());
            Path remembered = cacheDir.resolve("selfcheck-" + version.get());
            if (Files.isRegularFile(remembered) && Files.readString(remembered, StandardCharsets.UTF_8).strip().equals(actual)) {
                return new Result(Outcome.OK, version.get(), actual, actual);
            }
            Path dir = Files.createDirectories(cacheDir.resolve("selfcheck"));
            Path sums = dir.resolve(SelfUpdate.SUMS);
            String base = SelfUpdate.DOWNLOAD_BASE + "v" + version.get() + "/";
            SelfUpdate.fetch(URI.create(base + SelfUpdate.SUMS), sums);
            SelfUpdate.fetch(URI.create(base + SelfUpdate.SUMS + ".sig"), Signatures.signaturePath(sums));
            if (Signatures.verify(sums, key.get()) != Signatures.Check.VALID) {
                return Result.unknown();
            }
            Result result = compare(Files.readAllLines(sums, StandardCharsets.UTF_8), version.get(), actual);
            if (result.outcome() == Outcome.OK) {
                Files.writeString(remembered, actual, StandardCharsets.UTF_8);
            }
            return result;
        } catch (IOException | RuntimeException e) {
            return Result.unknown();
        }
    }

    /** Compares with the jar hash in already verified checksums; UNKNOWN if they list none (older releases). */
    static Result compare(List<String> verifiedSums, String version, String actual) {
        return SelfUpdate.expectedHash(verifiedSums, JAR_NAME)
                .map(expected -> new Result(expected.equals(actual) ? Outcome.OK : Outcome.CHANGED, version, expected, actual))
                .orElse(Result.unknown());
    }

    /** The note for the player when the jar was changed. */
    static String note(Result r) {
        return "BiteCheck's own program file differs from the signed release " + r.version() + " (expected SHA-256 "
                + r.expected() + "). BiteCheck installs this release again to repair itself. If that keeps "
                + "happening, something on this computer changes BiteCheck: check it with an antivirus program.";
    }
}
