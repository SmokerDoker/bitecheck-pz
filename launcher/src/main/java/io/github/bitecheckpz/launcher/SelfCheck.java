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
        /** Portable: files differed from the signed release zip and were put back ({@code expected} names them). */
        REPAIRED,
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
            if (!isPortable(jar.get()) && Files.isRegularFile(remembered)
                    && Files.readString(remembered, StandardCharsets.UTF_8).strip().equals(actual)) {
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
            List<String> verifiedSums = Files.readAllLines(sums, StandardCharsets.UTF_8);
            if (isPortable(jar.get())) {
                return checkPortable(jar.get().getParent(), verifiedSums, version.get(), base, dir);
            }
            Result result = compare(verifiedSums, version.get(), actual);
            if (result.outcome() == Outcome.OK) {
                Files.writeString(remembered, actual, StandardCharsets.UTF_8);
            }
            return result;
        } catch (IOException | RuntimeException e) {
            return Result.unknown();
        }
    }

    /** The portable zip: the jar sits next to the start scripts, not in an installed package. */
    static boolean isPortable(Path jar) {
        Path folder = jar.getParent();
        return System.getProperty("jpackage.app-path") == null && folder != null
                && (Files.isRegularFile(folder.resolve("BiteCheck.bat")) || Files.isRegularFile(folder.resolve("bitecheck.sh")));
    }

    /**
     * The portable folder against the signed release zip: every file of the zip (jar, start
     * scripts, README, LICENSE) must be unchanged; a changed one is put back from the zip. The
     * running jar cannot be replaced on Windows, so it goes next to it as BiteCheck.jar.repair, which
     * the start scripts put in place before the next start.
     */
    static Result checkPortable(Path folder, List<String> verifiedSums, String version, String base, Path dir)
            throws IOException {
        String name = "BiteCheck-" + version + ".zip";
        Optional<String> zipSha = SelfUpdate.expectedHash(verifiedSums, name);
        if (zipSha.isEmpty()) {
            return Result.unknown();
        }
        Path zip = dir.resolve(name);
        if (!Files.isRegularFile(zip) || !Hashing.sha256Hex(zip).equals(zipSha.get())) {
            SelfUpdate.fetch(URI.create(base + name), zip);
            if (!Hashing.sha256Hex(zip).equals(zipSha.get())) {
                Files.deleteIfExists(zip);
                return Result.unknown();
            }
        }
        List<String> restored = restoreFromZip(folder, zip);
        return restored.isEmpty() ? new Result(Outcome.OK, version, zipSha.get(), zipSha.get())
                : new Result(Outcome.REPAIRED, version, String.join(", ", restored), null);
    }

    /** Puts back every file of the zip's BiteCheck/ folder that differs in {@code folder}; returns their names. */
    static List<String> restoreFromZip(Path folder, Path zip) throws IOException {
        List<String> restored = new java.util.ArrayList<>();
        try (java.util.zip.ZipFile z = new java.util.zip.ZipFile(zip.toFile())) {
            var entries = z.entries();
            while (entries.hasMoreElements()) {
                java.util.zip.ZipEntry e = entries.nextElement();
                String entryName = e.getName();
                if (e.isDirectory() || !entryName.startsWith("BiteCheck/") || entryName.indexOf('/', "BiteCheck/".length()) >= 0) {
                    continue;
                }
                String file = entryName.substring("BiteCheck/".length());
                if (file.isEmpty() || file.contains("..")) {
                    continue;
                }
                byte[] expected;
                try (var in = z.getInputStream(e)) {
                    expected = in.readAllBytes();
                }
                Path local = folder.resolve(file);
                if (Files.isRegularFile(local) && java.util.Arrays.equals(Files.readAllBytes(local), expected)) {
                    continue;
                }
                Path target = file.equals(JAR_NAME) ? folder.resolve(JAR_NAME + ".repair") : local;
                Files.write(target, expected);
                if (file.endsWith(".sh") || file.endsWith(".command")) {
                    target.toFile().setExecutable(true, false);
                }
                restored.add(file);
            }
        }
        return restored;
    }

    /** Compares with the jar hash in already verified checksums; UNKNOWN if they list none (older releases). */
    static Result compare(List<String> verifiedSums, String version, String actual) {
        return SelfUpdate.expectedHash(verifiedSums, JAR_NAME)
                .map(expected -> new Result(expected.equals(actual) ? Outcome.OK : Outcome.CHANGED, version, expected, actual))
                .orElse(Result.unknown());
    }

    /** The note for the player when the jar was changed (or, portable, files were put back). */
    static String note(Result r) {
        if (r.outcome() == Outcome.REPAIRED) {
            return "These files of BiteCheck differed from the signed release " + r.version() + " and were put back: "
                    + r.expected() + (r.expected().contains(JAR_NAME) ? " (the program file takes effect at the next start)" : "")
                    + ". If that keeps happening, something on this computer changes BiteCheck: check it with an antivirus program.";
        }
        return "BiteCheck's own program file differs from the signed release " + r.version() + " (expected SHA-256 "
                + r.expected() + "). BiteCheck installs this release again to repair itself. If that keeps "
                + "happening, something on this computer changes BiteCheck: check it with an antivirus program.";
    }
}
