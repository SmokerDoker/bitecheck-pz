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
        return writeBuildRecord(releaseFile, List.of(jar), builders);
    }

    /**
     * Writes the build record for one or more jars. For a "published" release, pass the jars in
     * the order of the release's {@code artifacts} list; they are recorded with their paths. For a
     * "workshop" release, pass the Workshop item's jars in the order of {@code workshop_artifacts},
     * after checking that they contain exactly what CI built.
     */
    public static Path writeBuildRecord(Path releaseFile, List<Path> jars, List<String> builders)
            throws IOException, Toml.TomlException {
        Map<String, Object> release = Toml.parse(releaseFile);
        String mod = Toml.string(release, "mod");
        String version = Toml.string(release, "version");
        Registry.requireName(mod, "mod");
        Registry.requireVersion(version);
        if (!releaseFile.getFileName().toString().equals(version + ".toml")) {
            throw new IllegalArgumentException("release file must be named " + version + ".toml");
        }
        Map<String, Object> build = Toml.table(release, "build");
        String type = Toml.string(build, "type");
        boolean workshop = "workshop".equals(type);
        boolean published = "published".equals(type) || workshop;
        List<String> paths = workshop ? Releases.workshopArtifacts(release) : Releases.artifacts(release);
        if (jars.isEmpty() || (published && jars.size() != paths.size()) || (!published && jars.size() != 1)) {
            throw new IllegalArgumentException(published
                    ? "pass one jar per artifact (" + paths.size() + ") in the release's order"
                    : "pass exactly one jar");
        }
        StringBuilder out = new StringBuilder();
        out.append("# Build record written by CI. Signed by the registry key.\n");
        out.append("mod = ").append(Toml.quote(mod)).append('\n');
        out.append("version = ").append(Toml.quote(version)).append('\n');
        out.append("method = ").append(Toml.quote(workshop ? "workshop" : published ? "published" : "rebuild"))
                .append('\n');
        out.append("release_sha256 = ").append(Toml.quote(Hashing.sha256Hex(releaseFile))).append('\n');
        if (published) {
            out.append("artifact_paths = ").append(array(paths)).append('\n');
            List<String> hashes = new java.util.ArrayList<>();
            for (Path jar : jars) {
                hashes.add(Hashing.sha256Hex(jar));
            }
            out.append("artifact_sha256 = ").append(array(hashes)).append('\n');
        } else {
            out.append("jar_sha256 = ").append(Toml.quote(Hashing.sha256Hex(jars.get(0)))).append('\n');
        }
        out.append("built_at = ").append(Toml.quote(Instant.now().truncatedTo(ChronoUnit.SECONDS).toString()))
                .append('\n');
        out.append("builders = ").append(array(builders)).append('\n');
        Path target = releaseFile.resolveSibling(version + ".build.toml");
        writeNew(target, out.toString());
        return target;
    }

    private static String array(List<String> values) {
        StringBuilder out = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            out.append(i == 0 ? "" : ", ").append(Toml.quote(values.get(i)));
        }
        return out.append(']').toString();
    }

    /**
     * Writes {@code reviews/<version>/<reviewer>.toml}. With a jar, the review covers that jar;
     * with {@code jar == null}, it covers the release's build record (all its jars), which must
     * exist already. A {@code "diff"} review records the previous release as its base. Sign the
     * file afterwards.
     */
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
        if (jar != null) {
            out.append("jar_sha256 = ").append(Toml.quote(Hashing.sha256Hex(jar))).append('\n');
        } else {
            Path buildFile = releaseFile.resolveSibling(version + ".build.toml");
            if (!Files.isRegularFile(buildFile)) {
                throw new IOException("no build record " + buildFile.getFileName() + " to review yet");
            }
            out.append("build_sha256 = ").append(Toml.quote(Hashing.sha256Hex(buildFile))).append('\n');
        }
        out.append("reviewer = ").append(Toml.quote(reviewer)).append('\n');
        out.append("verdict = ").append(Toml.quote(verdict)).append('\n');
        out.append("scope = ").append(Toml.quote(scope)).append('\n');
        if (scope.equals("diff")) {
            // A diff review checks the changes since the release directly before this one.
            Registry registry = new Registry(modDir.getParent().getParent().getParent());
            String base = registry.previousVersion(mod, version);
            if (base == null) {
                throw new IllegalArgumentException(mod + " " + version + " is the first release: a full review is needed");
            }
            String baseCommit = Toml.string(Toml.parse(registry.releaseFile(mod, base)), "commit");
            out.append("base_version = ").append(Toml.quote(base)).append('\n');
            out.append("base_commit = ").append(Toml.quote(String.valueOf(baseCommit))).append('\n');
        }
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
