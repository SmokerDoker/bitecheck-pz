package io.github.bitecheckpz.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

/** Writes registry records: build records (CI) and reviews (reviewers). */
public final class Records {

    private Records() {
    }

    /**
     * Writes {@code <version>.build.toml} next to the release file. The registry signs it
     * afterwards, once two independent builds produced the same jar.
     */
    public static Path writeBuildRecord(Path releaseFile, Path jar, List<String> builders)
            throws IOException, Toml.TomlException {
        Map<String, Object> release = Toml.parse(releaseFile);
        String mod = Toml.string(release, "mod");
        String version = Toml.string(release, "version");
        Registry.requireName(mod, "mod");
        Registry.requireVersion(version);
        if (!releaseFile.getFileName().toString().equals(version + ".toml")) {
            throw new IllegalArgumentException("release file must be named " + version + ".toml");
        }
        StringBuilder out = new StringBuilder();
        out.append("# Build record written by CI. Signed by the registry key.\n");
        out.append("mod = ").append(Toml.quote(mod)).append('\n');
        out.append("version = ").append(Toml.quote(version)).append('\n');
        out.append("release_sha256 = ").append(Toml.quote(Hashing.sha256Hex(releaseFile))).append('\n');
        out.append("jar_sha256 = ").append(Toml.quote(Hashing.sha256Hex(jar))).append('\n');
        out.append("built_at = ").append(Toml.quote(Instant.now().truncatedTo(ChronoUnit.SECONDS).toString()))
                .append('\n');
        out.append("builders = [");
        for (int i = 0; i < builders.size(); i++) {
            out.append(i == 0 ? "" : ", ").append(Toml.quote(builders.get(i)));
        }
        out.append("]\n");
        Path target = releaseFile.resolveSibling(version + ".build.toml");
        writeNew(target, out.toString());
        return target;
    }

    /** Writes {@code reviews/<version>/<reviewer>.toml} for the release. Sign it afterwards. */
    public static Path writeReview(Path releaseFile, Path jar, String reviewer, String verdict, String scope,
            String notes) throws IOException, Toml.TomlException {
        Registry.requireName(reviewer, "reviewer");
        if (!List.of("approve", "reject", "questions").contains(verdict)) {
            throw new IllegalArgumentException("verdict must be approve, reject or questions");
        }
        if (!List.of("full", "diff").contains(scope)) {
            throw new IllegalArgumentException("scope must be full or diff");
        }
        Map<String, Object> release = Toml.parse(releaseFile);
        String mod = Toml.string(release, "mod");
        String version = Toml.string(release, "version");
        Registry.requireName(mod, "mod");
        Registry.requireVersion(version);
        // releases/<version>.toml -> reviews/<version>/<reviewer>.toml
        Path modDir = releaseFile.toAbsolutePath().getParent().getParent();
        Path target = modDir.resolve("reviews").resolve(version).resolve(reviewer + ".toml");
        StringBuilder out = new StringBuilder();
        out.append("mod = ").append(Toml.quote(mod)).append('\n');
        out.append("version = ").append(Toml.quote(version)).append('\n');
        out.append("jar_sha256 = ").append(Toml.quote(Hashing.sha256Hex(jar))).append('\n');
        out.append("reviewer = ").append(Toml.quote(reviewer)).append('\n');
        out.append("verdict = ").append(Toml.quote(verdict)).append('\n');
        out.append("scope = ").append(Toml.quote(scope)).append('\n');
        out.append("reviewed_at = ").append(Toml.quote(Instant.now().truncatedTo(ChronoUnit.SECONDS).toString()))
                .append('\n');
        out.append("notes = ").append(Toml.quote(notes == null ? "" : notes)).append('\n');
        Files.createDirectories(target.getParent());
        writeNew(target, out.toString());
        return target;
    }

    private static void writeNew(Path target, String content) throws IOException {
        if (Files.exists(target)) {
            throw new FileAlreadyExistsException(target.toString(), null, "already exists");
        }
        Files.writeString(target, content, StandardCharsets.UTF_8);
    }
}
