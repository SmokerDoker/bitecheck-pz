package io.github.bitecheckpz.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Decides whether a mod jar may be loaded without asking the user.
 *
 * <p>A jar is {@link Status#VERIFIED} only if all of the following hold:
 * <ol>
 *   <li>a build record listing its SHA-256 exists and is signed by the registry key,</li>
 *   <li>the release file named in that record is unchanged and signed by the mod's publisher
 *       (or its author, if the entry has no separate publisher),</li>
 *   <li>enough distinct reviewers (not the author, and not the publisher unless the policy allows
 *       it) approved exactly this jar or this build record with valid signatures, as required by
 *       the registry policy (default: 2 approvals, at least 1 from a "trusted" reviewer),</li>
 *   <li>there is no open critical flag for this hash, and the entry is not pending.</li>
 * </ol>
 *
 * <p>Build records come in three kinds ({@code method}): "rebuild" - CI built the jar from source
 * twice and got identical bytes -, "published" - CI took the jar the author published in the
 * source repository at the release commit, and reviewers checked it against the source - and
 * "workshop" - the jar from the Steam Workshop item, whose files CI rebuilt byte-identical from
 * the source twice.
 */
public final class Verifier {

    public enum Status {
        /** Passed every check: load without asking. */
        VERIFIED,
        /** Not (yet) approved: load only after warning the user. */
        UNVERIFIED,
        /** An open critical flag exists: strong warning, double confirmation. */
        FLAGGED,
        /** Confirmed malicious: never load. */
        BLOCKED,
        /** Registry data does not match its signatures: treat as tampered. */
        INVALID
    }

    public record Result(Status status, String jarSha256, String mod, String version, List<String> messages,
            String method) {
        Result(Status status, String jarSha256, String mod, String version, List<String> messages) {
            this(status, jarSha256, mod, version, messages, null);
        }
    }

    private final Registry registry;
    private final Keys.PublicKeyInfo registryKey;
    /** Releases whose verification is under way as the base of a diff review (cycle guard). */
    private final Set<String> diffBasesInProgress = new HashSet<>();

    /**
     * @param registryKey the trusted registry key. A launcher must pin this key itself and not
     *                    take it from the registry checkout it is verifying.
     */
    public Verifier(Registry registry, Keys.PublicKeyInfo registryKey) {
        this.registry = registry;
        this.registryKey = registryKey;
    }

    public Result verify(Path jar) throws IOException {
        return verifyHash(Hashing.sha256Hex(jar));
    }

    /** Verifies a jar by its SHA-256 (lower-case hex). */
    public Result verifyHash(String sha) throws IOException {
        if (!Hashing.isSha256Hex(sha)) {
            throw new IllegalArgumentException("not a SHA-256 hex digest: " + sha);
        }
        List<String> messages = new ArrayList<>();
        try {
            var blocked = registry.blockReason(sha);
            if (blocked.isPresent()) {
                messages.add("on the block list: " + blocked.get());
                return new Result(Status.BLOCKED, sha, null, null, messages);
            }
        } catch (Toml.TomlException e) {
            messages.add("cannot read block list: " + e.getMessage());
            return new Result(Status.INVALID, sha, null, null, messages);
        }

        List<Result> candidates = new ArrayList<>();
        for (Path buildFile : registry.buildFiles()) {
            Map<String, Object> build;
            try {
                build = registry.read(buildFile);
            } catch (Toml.TomlException e) {
                continue; // unrelated broken file; CI rejects these
            }
            if (Registry.jarHashes(build).contains(sha)) {
                candidates.add(checkCandidate(sha, buildFile, build));
            }
        }

        if (candidates.isEmpty()) {
            messages.add("this file is not in the registry");
            return new Result(Status.UNVERIFIED, sha, null, null, messages);
        }
        // Most severe outcome wins if several records claim the same hash.
        Result worst = candidates.get(0);
        for (Result r : candidates) {
            if (severity(r.status()) > severity(worst.status())) {
                worst = r;
            }
        }
        return worst;
    }

    private static int severity(Status s) {
        return switch (s) {
            case VERIFIED -> 0;
            case UNVERIFIED -> 1;
            case FLAGGED -> 2;
            case INVALID -> 3;
            case BLOCKED -> 4;
        };
    }

    private Result checkCandidate(String sha, Path buildFile, Map<String, Object> build) throws IOException {
        List<String> messages = new ArrayList<>();
        String mod = Toml.string(build, "mod");
        String version = Toml.string(build, "version");
        try {
            Registry.requireName(mod, "mod");
            Registry.requireVersion(version);
        } catch (IllegalArgumentException e) {
            messages.add("build record " + buildFile + ": " + e.getMessage());
            return new Result(Status.INVALID, sha, mod, version, messages);
        }
        if (!buildFile.normalize().equals(registry.buildFile(mod, version).normalize())) {
            messages.add("build record " + buildFile + " is stored under the wrong mod or version");
            return new Result(Status.INVALID, sha, mod, version, messages);
        }

        // 1. Registry signature on the build record.
        Signatures.Check buildSig = Signatures.verify(buildFile, registryKey);
        if (buildSig != Signatures.Check.VALID) {
            messages.add("build record: registry " + buildSig.description());
            return new Result(Status.INVALID, sha, mod, version, messages);
        }
        String method = Optional.ofNullable(Toml.string(build, "method"))
                .filter(m -> m.equals("published") || m.equals("workshop")).orElse("rebuild");
        messages.add(switch (method) {
            case "published" -> "published binary, build record signed by the registry";
            case "workshop" -> "Steam Workshop jar, rebuilt from source by CI, build record signed by the registry";
            default -> "reproducible build record signed by the registry";
        });

        try {
            // 2. Release file unchanged and signed by the author.
            Path releaseFile = registry.releaseFile(mod, version);
            if (!Files.isRegularFile(releaseFile)) {
                messages.add("release file is missing");
                return new Result(Status.INVALID, sha, mod, version, messages);
            }
            if (!Hashing.sha256Hex(releaseFile).equals(Toml.string(build, "release_sha256"))) {
                messages.add("release file was changed after it was built");
                return new Result(Status.INVALID, sha, mod, version, messages);
            }
            Map<String, Object> release = registry.read(releaseFile);
            if (!mod.equals(Toml.string(release, "mod")) || !version.equals(Toml.string(release, "version"))) {
                messages.add("release file names a different mod or version");
                return new Result(Status.INVALID, sha, mod, version, messages);
            }
            Path modFile = registry.modDir(mod).resolve("mod.toml");
            if (!Files.isRegularFile(modFile)) {
                messages.add("mod.toml is missing");
                return new Result(Status.INVALID, sha, mod, version, messages);
            }
            Map<String, Object> modInfo = registry.read(modFile);
            if (Toml.bool(modInfo, "example")) {
                messages.add("this is an example entry and is never verified");
                return new Result(Status.UNVERIFIED, sha, mod, version, messages, method);
            }
            if ("pending".equals(Toml.string(modInfo, "status"))) {
                messages.add("this entry is still pending and not verified yet");
                return new Result(Status.UNVERIFIED, sha, mod, version, messages, method);
            }
            String author = Registry.signer(modInfo);
            var authorKey = author == null || !Registry.NAME.matcher(author).matches()
                    ? Optional.<Keys.PublicKeyInfo>empty() : registry.authorKey(author);
            if (authorKey.isEmpty()) {
                messages.add(registry.revocation("authors", author).map(r -> "key of publisher '" + author + "' " + r)
                        .orElse("no valid key for publisher '" + author + "'"));
                return new Result(Status.INVALID, sha, mod, version, messages);
            }
            Signatures.Check releaseSig = Signatures.verify(releaseFile, authorKey.get());
            if (releaseSig != Signatures.Check.VALID) {
                messages.add("release file: publisher " + releaseSig.description());
                return new Result(Status.INVALID, sha, mod, version, messages);
            }
            messages.add("release signed by '" + author + "'");

            // 3. Flags.
            Status flagStatus = checkFlags(sha, mod, version, messages);
            if (flagStatus != null) {
                return new Result(flagStatus, sha, mod, version, messages, method);
            }

            // 4. Reviews.
            Registry.Policy policy = registry.policy();
            int approvals = 0;
            int trusted = 0;
            Set<String> seen = new HashSet<>();
            Path reviewsDir = registry.reviewsDir(mod, version);
            if (Files.isDirectory(reviewsDir)) {
                List<Path> reviewFiles;
                try (Stream<Path> files = Files.list(reviewsDir)) {
                    reviewFiles = files.filter(p -> p.getFileName().toString().endsWith(".toml")).sorted().toList();
                }
                for (Path reviewFile : reviewFiles) {
                    String fileName = reviewFile.getFileName().toString();
                    String reviewer = fileName.substring(0, fileName.length() - ".toml".length());
                    String problem = checkReview(reviewFile, reviewer, sha, Hashing.sha256Hex(buildFile), mod,
                            version, modInfo, policy);
                    if (problem != null) {
                        messages.add("review by '" + reviewer + "' not counted: " + problem);
                        continue;
                    }
                    if (!seen.add(reviewer)) {
                        continue;
                    }
                    approvals++;
                    String level = registry.reviewerLevel(reviewer);
                    if (level.equals("trusted")) {
                        trusted++;
                    }
                    messages.add("approved by '" + reviewer + "' (" + level + ")");
                }
            }
            if (approvals >= policy.requiredApprovals() && trusted >= policy.requiredTrusted()) {
                return new Result(Status.VERIFIED, sha, mod, version, messages, method);
            }
            messages.add("needs " + policy.requiredApprovals() + " approval(s) incl. " + policy.requiredTrusted()
                    + " trusted reviewer(s), has " + approvals + " (" + trusted + " trusted)");
            return new Result(Status.UNVERIFIED, sha, mod, version, messages, method);
        } catch (Toml.TomlException e) {
            messages.add("cannot read registry file: " + e.getMessage());
            return new Result(Status.INVALID, sha, mod, version, messages);
        }
    }

    /** Returns null if the review counts as an approval, otherwise the reason it does not. */
    private String checkReview(Path file, String reviewer, String sha, String buildSha, String mod, String version,
            Map<String, Object> modInfo, Registry.Policy policy) throws IOException, Toml.TomlException {
        if (!Registry.NAME.matcher(reviewer).matches()) {
            return "invalid reviewer name";
        }
        String selfReview = Registry.selfReviewProblem(modInfo, reviewer, policy);
        if (selfReview != null) {
            return selfReview;
        }
        Map<String, Object> review = registry.read(file);
        if (!reviewer.equals(Toml.string(review, "reviewer"))) {
            return "reviewer field does not match the file name";
        }
        if (!mod.equals(Toml.string(review, "mod")) || !version.equals(Toml.string(review, "version"))) {
            return "review is for a different mod or version";
        }
        // A review covers either one jar (jar_sha256) or a whole build record (build_sha256).
        if (!sha.equals(Toml.string(review, "jar_sha256")) && !buildSha.equals(Toml.string(review, "build_sha256"))) {
            return "review is for a different file";
        }
        if (!"approve".equals(Toml.string(review, "verdict"))) {
            return "verdict is not 'approve'";
        }
        String level = registry.reviewerLevel(reviewer);
        if (!level.equals("reviewer") && !level.equals("trusted")) {
            return "reviewer level is '" + level + "'";
        }
        var key = registry.reviewerKey(reviewer);
        if (key.isEmpty()) {
            return registry.revocation("reviewers", reviewer).map(r -> "reviewer key " + r)
                    .orElse("no valid key for this reviewer");
        }
        Signatures.Check sig = Signatures.verify(file, key.get());
        if (sig != Signatures.Check.VALID) {
            return sig.description();
        }
        if ("diff".equals(Toml.string(review, "scope"))) {
            return diffBaseProblem(review, mod, version);
        }
        return null;
    }

    /**
     * A diff review only checked the changes since an earlier release. It counts only against the
     * release directly before this one, at that release's commit, and only if that release is verified.
     */
    private String diffBaseProblem(Map<String, Object> review, String mod, String version)
            throws IOException, Toml.TomlException {
        String previous = registry.previousVersion(mod, version);
        String base = Toml.string(review, "base_version");
        if (previous == null) {
            return "diff review, but there is no earlier release";
        }
        if (!previous.equals(base)) {
            return "diff review against '" + base + "', not the previous release " + previous;
        }
        String baseCommit = Toml.string(registry.read(registry.releaseFile(mod, previous)), "commit");
        if (baseCommit == null || !baseCommit.equals(Toml.string(review, "base_commit"))) {
            return "diff review base commit is not the commit of release " + previous;
        }
        Path baseBuild = registry.buildFile(mod, previous);
        List<String> baseJars = Files.isRegularFile(baseBuild) ? Registry.jarHashes(registry.read(baseBuild)) : List.of();
        if (baseJars.isEmpty()) {
            return "previous release " + previous + " has no build record";
        }
        // The same jar can sit in several build records; never follow a chain of diff reviews in a circle.
        String key = mod + "@" + previous;
        if (!diffBasesInProgress.add(key)) {
            return "diff reviews of " + mod + " refer to each other in a circle";
        }
        try {
            // Check exactly the previous release's build record, not every record that lists the same jar.
            Map<String, Object> build = registry.read(baseBuild);
            for (String jar : baseJars) {
                if (registry.blockReason(jar).isPresent() || checkCandidate(jar, baseBuild, build).status() != Status.VERIFIED) {
                    return "previous release " + previous + " is not verified, so a full review is needed";
                }
            }
        } finally {
            diffBasesInProgress.remove(key);
        }
        return null;
    }

    private Status checkFlags(String sha, String mod, String version, List<String> messages)
            throws IOException, Toml.TomlException {
        Path flagsDir = registry.flagsDir(mod);
        if (!Files.isDirectory(flagsDir)) {
            return null;
        }
        List<Path> flagFiles;
        try (Stream<Path> files = Files.list(flagsDir)) {
            flagFiles = files.filter(p -> p.getFileName().toString().endsWith(".toml")).sorted().toList();
        }
        Status result = null;
        for (Path flagFile : flagFiles) {
            Map<String, Object> flag = registry.read(flagFile);
            // A flag names one jar, or without jar_sha256 every jar of its version.
            String flaggedJar = Toml.string(flag, "jar_sha256");
            if (flaggedJar == null ? !version.equals(Toml.string(flag, "version")) : !sha.equals(flaggedJar)) {
                continue;
            }
            String severity = Toml.string(flag, "severity");
            String status = Toml.string(flag, "status");
            String description = Toml.string(flag, "description");
            if ("confirmed".equals(status)) {
                messages.add("confirmed malicious: " + description);
                return Status.BLOCKED;
            }
            if ("open".equals(status)) {
                messages.add("open " + severity + " flag: " + description);
                if ("critical".equals(severity)) {
                    result = Status.FLAGGED;
                }
            }
        }
        return result;
    }
}
