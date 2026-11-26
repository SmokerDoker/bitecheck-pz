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
        return writeBuildRecord(releaseFile, jars, builders, null);
    }

    /**
     * @param hardFindings the scanner's hard rules found in the jars ({@code hard_findings}); CI
     *                     always passes them (an empty list when there are none), null leaves the
     *                     field out (records from before this field)
     */
    public static Path writeBuildRecord(Path releaseFile, List<Path> jars, List<String> builders, List<String> hardFindings)
            throws IOException, Toml.TomlException {
        return writeBuildRecord(releaseFile, jars, builders, hardFindings, null);
    }

    /**
     * @param scanReport the scanner's full report on the jars; written next to the record as
     *                   {@code <version>.scan.txt}, its SHA-256 as {@code scan_sha256} in the record,
     *                   so a review (which signs the record) co-signs the report the reviewer saw
     */
    public static Path writeBuildRecord(Path releaseFile, List<Path> jars, List<String> builders,
            List<String> hardFindings, String scanReport) throws IOException, Toml.TomlException {
        String scanSha = null;
        if (scanReport != null) {
            Path report = releaseFile.resolveSibling(Toml.string(Toml.parse(releaseFile), "version") + ".scan.txt");
            writeNew(report, scanReport);
            scanSha = Hashing.sha256Hex(report);
        }
        return writeBuildRecordOnly(releaseFile, jars, builders, hardFindings, scanSha);
    }

    private static Path writeBuildRecordOnly(Path releaseFile, List<Path> jars, List<String> builders,
            List<String> hardFindings, String scanSha) throws IOException, Toml.TomlException {
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
        if (scanSha != null) {
            out.append("scan_sha256 = ").append(Toml.quote(scanSha)).append('\n');
        }
        if (hardFindings != null) {
            // the scanner's hard findings in any of the jars: such a release is verified only with a
            // signed exception from a trusted reviewer (see Verifier)
            out.append("hard_findings = ").append(array(List.copyOf(new java.util.TreeSet<>(hardFindings)))).append('\n');
        }
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
        return writeReview(releaseFile, jar, reviewer, verdict, scope, notes, null);
    }

    /**
     * @param changedPercent diff reviews: how much of the source changed since the base release
     *                       ({@code changed_percent}); above {@link #MAX_DIFF_PERCENT} a diff
     *                       review does not count (the update needs a full review)
     */
    public static Path writeReview(Path releaseFile, Path jar, String reviewer, String verdict, String scope,
            String notes, Integer changedPercent) throws IOException, Toml.TomlException {
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
            if (changedPercent != null) {
                out.append("changed_percent = ").append(changedPercent).append('\n');
            }
        }
        out.append("reviewed_at = ").append(Toml.quote(Instant.now().truncatedTo(ChronoUnit.SECONDS).toString()))
                .append('\n');
        out.append("notes = ").append(Toml.quote(notes == null ? "" : notes)).append('\n');
        Files.createDirectories(target.getParent());
        writeNew(target, out.toString());
        return target;
    }

    /**
     * Writes {@code exceptions/<version>/<reviewer>.toml}: a trusted reviewer accepts the scanner's
     * hard findings ({@code rules}) of the release's build record, which must exist, with a reason.
     * Sign the file afterwards with the reviewer's key.
     */
    public static Path writeException(Path releaseFile, String reviewer, List<String> rules, String reason)
            throws IOException, Toml.TomlException {
        Registry.requireName(reviewer, "reviewer");
        if (rules.isEmpty() || rules.stream().anyMatch(r -> !r.matches("[a-z][a-z-]{0,30}"))) {
            throw new IllegalArgumentException("name the accepted rules, e.g. unsafe,process");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("say why the findings are acceptable");
        }
        Map<String, Object> release = Toml.parse(releaseFile);
        String mod = Toml.string(release, "mod");
        String version = Toml.string(release, "version");
        Registry.requireName(mod, "mod");
        Registry.requireVersion(version);
        Path buildFile = releaseFile.resolveSibling(version + ".build.toml");
        if (!Files.isRegularFile(buildFile)) {
            throw new IOException("no build record " + buildFile.getFileName() + " yet");
        }
        Path modDir = releaseFile.toAbsolutePath().getParent().getParent();
        Path target = modDir.resolve("exceptions").resolve(version).resolve(reviewer + ".toml");
        StringBuilder out = new StringBuilder();
        out.append("# A trusted reviewer accepts the scanner's hard findings of this release.\n");
        out.append("mod = ").append(Toml.quote(mod)).append('\n');
        out.append("version = ").append(Toml.quote(version)).append('\n');
        out.append("build_sha256 = ").append(Toml.quote(Hashing.sha256Hex(buildFile))).append('\n');
        out.append("reviewer = ").append(Toml.quote(reviewer)).append('\n');
        out.append("rules = ").append(array(List.copyOf(new java.util.TreeSet<>(rules)))).append('\n');
        out.append("reason = ").append(Toml.quote(reason.strip())).append('\n');
        Files.createDirectories(target.getParent());
        writeNew(target, out.toString());
        return target;
    }

    /** A diff review counts only if at most this share of the source changed since the base release. */
    public static final int MAX_DIFF_PERCENT = 30;

    private static void writeNew(Path target, String content) throws IOException {
        if (Files.exists(target)) {
            throw new FileAlreadyExistsException(target.toString(), null, "already exists");
        }
        Files.writeString(target, content, StandardCharsets.UTF_8);
    }
}
