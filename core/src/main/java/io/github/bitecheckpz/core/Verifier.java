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
 *   <li>a build record for its SHA-256 exists and is signed by the registry key,</li>
 *   <li>the release file named in that record is unchanged and signed by the mod's author,</li>
 *   <li>at least {@link #REQUIRED_APPROVALS} distinct reviewers (not the author) approved exactly
 *       this hash with valid signatures, at least one of them with level "trusted",</li>
 *   <li>there is no open critical flag for this hash.</li>
 * </ol>
 */
public final class Verifier {

    public static final int REQUIRED_APPROVALS = 2;

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

    public record Result(Status status, String jarSha256, String mod, String version, List<String> messages) {
    }

    private final Registry registry;
    private final Keys.PublicKeyInfo registryKey;

    /**
     * @param registryKey the trusted registry key. A launcher must pin this key itself and not
     *                    take it from the registry checkout it is verifying.
     */
    public Verifier(Registry registry, Keys.PublicKeyInfo registryKey) {
        this.registry = registry;
        this.registryKey = registryKey;
    }

    public Result verify(Path jar) throws IOException {
        String sha = Hashing.sha256Hex(jar);
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
            if (sha.equals(Toml.string(build, "jar_sha256"))) {
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
        messages.add("reproducible build record signed by the registry");

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
                return new Result(Status.UNVERIFIED, sha, mod, version, messages);
            }
            String author = Toml.string(modInfo, "author");
            var authorKey = author == null ? Optional.<Keys.PublicKeyInfo>empty() : registry.authorKey(author);
            if (authorKey.isEmpty()) {
                messages.add("no valid key for author '" + author + "'");
                return new Result(Status.INVALID, sha, mod, version, messages);
            }
            Signatures.Check releaseSig = Signatures.verify(releaseFile, authorKey.get());
            if (releaseSig != Signatures.Check.VALID) {
                messages.add("release file: author " + releaseSig.description());
                return new Result(Status.INVALID, sha, mod, version, messages);
            }
            messages.add("release signed by author '" + author + "'");

            // 3. Flags.
            Status flagStatus = checkFlags(sha, mod, messages);
            if (flagStatus != null) {
                return new Result(flagStatus, sha, mod, version, messages);
            }

            // 4. Reviews.
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
                    String problem = checkReview(reviewFile, reviewer, sha, mod, version, author);
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
            if (approvals >= REQUIRED_APPROVALS && trusted >= 1) {
                return new Result(Status.VERIFIED, sha, mod, version, messages);
            }
            messages.add("needs " + REQUIRED_APPROVALS + " approvals incl. 1 trusted reviewer, has "
                    + approvals + " (" + trusted + " trusted)");
            return new Result(Status.UNVERIFIED, sha, mod, version, messages);
        } catch (Toml.TomlException e) {
            messages.add("cannot read registry file: " + e.getMessage());
            return new Result(Status.INVALID, sha, mod, version, messages);
        }
    }

    /** Returns null if the review counts as an approval, otherwise the reason it does not. */
    private String checkReview(Path file, String reviewer, String sha, String mod, String version, String author)
            throws IOException, Toml.TomlException {
        if (!Registry.NAME.matcher(reviewer).matches()) {
            return "invalid reviewer name";
        }
        if (reviewer.equals(author)) {
            return "authors cannot review their own mod";
        }
        Map<String, Object> review = registry.read(file);
        if (!reviewer.equals(Toml.string(review, "reviewer"))) {
            return "reviewer field does not match the file name";
        }
        if (!mod.equals(Toml.string(review, "mod")) || !version.equals(Toml.string(review, "version"))) {
            return "review is for a different mod or version";
        }
        if (!sha.equals(Toml.string(review, "jar_sha256"))) {
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
            return "no valid key for this reviewer";
        }
        Signatures.Check sig = Signatures.verify(file, key.get());
        if (sig != Signatures.Check.VALID) {
            return sig.description();
        }
        return null;
    }

    private Status checkFlags(String sha, String mod, List<String> messages) throws IOException, Toml.TomlException {
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
            if (!sha.equals(Toml.string(flag, "jar_sha256"))) {
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
