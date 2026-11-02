package io.github.bitecheckpz.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Read access to a registry checkout.
 *
 * <pre>
 * registry/mods/&lt;mod&gt;/mod.toml
 * registry/mods/&lt;mod&gt;/releases/&lt;version&gt;.toml        (+ .sig by the author)
 * registry/mods/&lt;mod&gt;/releases/&lt;version&gt;.build.toml  (+ .sig by the registry)
 * registry/mods/&lt;mod&gt;/reviews/&lt;version&gt;/&lt;reviewer&gt;.toml (+ .sig by the reviewer)
 * registry/mods/&lt;mod&gt;/flags/&lt;flag&gt;.toml
 * registry/blocklist.toml
 * keys/registry.pub, keys/authors/&lt;name&gt;.pub, keys/reviewers/&lt;name&gt;.pub, keys/trust.toml
 * keys/accounts.toml (GitHub accounts), keys/revoked.toml (revoked key ids)
 * </pre>
 *
 * <p>All names read from files are validated before they are used to build paths.
 */
public final class Registry {

    /** Names of mods, authors and reviewers. */
    public static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9-]{0,63}");
    /** Version strings. Must not contain path separators. */
    public static final Pattern VERSION = Pattern.compile("[0-9A-Za-z][0-9A-Za-z.+_-]{0,63}");

    private final Path root;

    public Registry(Path root) {
        this.root = root;
    }

    public Path root() {
        return root;
    }

    public Path modsDir() {
        return root.resolve("registry").resolve("mods");
    }

    public Path modDir(String mod) {
        requireName(mod, "mod");
        return modsDir().resolve(mod);
    }

    public Path releaseFile(String mod, String version) {
        requireVersion(version);
        return modDir(mod).resolve("releases").resolve(version + ".toml");
    }

    public Path buildFile(String mod, String version) {
        requireVersion(version);
        return modDir(mod).resolve("releases").resolve(version + ".build.toml");
    }

    public Path reviewsDir(String mod, String version) {
        requireVersion(version);
        return modDir(mod).resolve("reviews").resolve(version);
    }

    public Path flagsDir(String mod) {
        return modDir(mod).resolve("flags");
    }

    public Optional<Keys.PublicKeyInfo> registryKey() {
        return readKey(root.resolve("keys").resolve("registry.pub"));
    }

    /** The author's (or publisher's) key, unless it is missing, unreadable or revoked. */
    public Optional<Keys.PublicKeyInfo> authorKey(String name) {
        if (!NAME.matcher(name).matches()) {
            return Optional.empty();
        }
        return readKey(root.resolve("keys").resolve("authors").resolve(name + ".pub")).filter(k -> !isRevoked(k));
    }

    /** The reviewer's key, unless it is missing, unreadable or revoked. */
    public Optional<Keys.PublicKeyInfo> reviewerKey(String name) {
        if (!NAME.matcher(name).matches()) {
            return Optional.empty();
        }
        return readKey(root.resolve("keys").resolve("reviewers").resolve(name + ".pub")).filter(k -> !isRevoked(k));
    }

    /**
     * Why the key in keys/&lt;folder&gt;/&lt;name&gt;.pub is revoked, if it is ("authors" or "reviewers").
     * Revoked keys are listed by key id in keys/revoked.toml; nothing they signed counts any more.
     */
    public Optional<String> revocation(String folder, String name) {
        if (!NAME.matcher(name).matches() || !List.of("authors", "reviewers").contains(folder)) {
            return Optional.empty();
        }
        return readKey(root.resolve("keys").resolve(folder).resolve(name + ".pub")).flatMap(this::revocation);
    }

    private Optional<String> revocation(Keys.PublicKeyInfo key) {
        Path file = root.resolve("keys").resolve("revoked.toml");
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            Map<String, Object> entry = Toml.table(Toml.parse(file), "revoked." + key.id());
            if (entry.isEmpty()) {
                return Optional.empty();
            }
            String reason = Toml.string(entry, "reason");
            return Optional.of(reason == null || reason.isBlank() ? "revoked" : "revoked: " + reason);
        } catch (IOException | Toml.TomlException e) {
            return Optional.of("revoked.toml cannot be read, so no key is trusted: " + e.getMessage());
        }
    }

    private boolean isRevoked(Keys.PublicKeyInfo key) {
        return revocation(key).isPresent();
    }

    /** GitHub account linked to a key name in keys/accounts.toml ({@code [accounts.<name>] github = "..."}). */
    public Optional<String> githubAccount(String name) throws IOException, Toml.TomlException {
        Path file = root.resolve("keys").resolve("accounts.toml");
        if (!NAME.matcher(name).matches() || !Files.isRegularFile(file)) {
            return Optional.empty();
        }
        return Optional.ofNullable(Toml.string(Toml.table(Toml.parse(file), "accounts." + name), "github"));
    }

    /** All release versions of a mod, oldest first (by {@link #compareVersions}). */
    public List<String> versions(String mod) throws IOException {
        Path releases = modDir(mod).resolve("releases");
        if (!Files.isDirectory(releases)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(releases)) {
            return files.map(p -> p.getFileName().toString())
                    .filter(n -> n.endsWith(".toml") && !n.endsWith(".build.toml"))
                    .map(n -> n.substring(0, n.length() - ".toml".length()))
                    .filter(v -> VERSION.matcher(v).matches())
                    .sorted(Registry::compareVersions).toList();
        }
    }

    /** The release directly before {@code version}, or null if it is the first. */
    public String previousVersion(String mod, String version) throws IOException {
        String previous = null;
        for (String v : versions(mod)) {
            if (compareVersions(v, version) < 0) {
                previous = v;
            }
        }
        return previous;
    }

    /** Compares versions part by part, numbers numerically: 1.10 > 1.9, 2026.10.04 > 2026.9.30. */
    public static int compareVersions(String x, String y) {
        String[] a = x.split("[.+_-]");
        String[] b = y.split("[.+_-]");
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            String p = i < a.length ? a[i] : "";
            String q = i < b.length ? b[i] : "";
            int c = p.matches("\\d{1,18}") && q.matches("\\d{1,18}")
                    ? Long.compare(Long.parseLong(p), Long.parseLong(q))
                    : p.compareTo(q);
            if (c != 0) {
                return c;
            }
        }
        return 0;
    }

    /** Reviewer level from keys/trust.toml: "new" (default), "reviewer" or "trusted". */
    public String reviewerLevel(String name) throws IOException, Toml.TomlException {
        Path trust = root.resolve("keys").resolve("trust.toml");
        if (!Files.isRegularFile(trust)) {
            return "new";
        }
        String level = Toml.string(Toml.table(Toml.parse(trust), "reviewers." + name), "level");
        return level == null ? "new" : level;
    }

    /**
     * How many reviews a release needs, and whether a publisher may review an entry whose author
     * is someone else (start-up only; an author never reviews their own mod).
     */
    public record Policy(int requiredApprovals, int requiredTrusted, boolean publisherMayReview) {
        public static final Policy DEFAULT = new Policy(2, 1, false);
    }

    /**
     * Review policy from registry/policy.toml ({@code required_approvals}, {@code required_trusted},
     * {@code publisher_may_review}). Defaults to 2 approvals incl. 1 trusted and no publisher
     * reviews; values below 1 are raised to 1.
     */
    public Policy policy() throws IOException, Toml.TomlException {
        Path file = root.resolve("registry").resolve("policy.toml");
        if (!Files.isRegularFile(file)) {
            return Policy.DEFAULT;
        }
        Map<String, Object> doc = Toml.parse(file);
        long approvals = doc.get("required_approvals") instanceof Long l ? l : Policy.DEFAULT.requiredApprovals();
        long trusted = doc.get("required_trusted") instanceof Long l ? l : Policy.DEFAULT.requiredTrusted();
        int a = (int) Math.max(1, Math.min(approvals, 10));
        int t = (int) Math.max(1, Math.min(trusted, a));
        return new Policy(a, t, Toml.bool(doc, "publisher_may_review"));
    }

    /** All jar hashes a build record vouches for: jar_sha256 and/or artifact_sha256 = [...]. */
    public static List<String> jarHashes(Map<String, Object> build) {
        List<String> hashes = new ArrayList<>();
        String single = Toml.string(build, "jar_sha256");
        if (Hashing.isSha256Hex(single)) {
            hashes.add(single);
        }
        if (build.get("artifact_sha256") instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof String sha && Hashing.isSha256Hex(sha) && !hashes.contains(sha)) {
                    hashes.add(sha);
                }
            }
        }
        return hashes;
    }

    /** The key name that signs releases: the publisher if set, otherwise the author. */
    public static String signer(Map<String, Object> modInfo) {
        String publisher = Toml.string(modInfo, "publisher");
        return publisher != null ? publisher : Toml.string(modInfo, "author");
    }

    /**
     * Why {@code reviewer} may not review this entry, or null if they may. The author never may;
     * the publisher only if the policy allows it and the author is someone else.
     */
    public static String selfReviewProblem(Map<String, Object> modInfo, String reviewer, Policy policy) {
        String author = Toml.string(modInfo, "author");
        String publisher = Toml.string(modInfo, "publisher");
        if (reviewer.equals(author)) {
            return publisher == null ? "the publisher cannot review their own entry"
                    : "the author cannot review their own mod";
        }
        if (reviewer.equals(publisher) && !policy.publisherMayReview()) {
            return "the publisher cannot review their own entry";
        }
        return null;
    }

    /** Reason a jar hash is on the block list, if it is. */
    public Optional<String> blockReason(String jarSha256) throws IOException, Toml.TomlException {
        Path file = root.resolve("registry").resolve("blocklist.toml");
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        Object reason = Toml.table(Toml.parse(file), "blocked").get(jarSha256);
        return reason == null ? Optional.empty() : Optional.of(String.valueOf(reason));
    }

    /** All build record files in the registry. */
    public List<Path> buildFiles() throws IOException {
        List<Path> result = new ArrayList<>();
        if (!Files.isDirectory(modsDir())) {
            return result;
        }
        try (Stream<Path> mods = Files.list(modsDir())) {
            for (Path mod : mods.filter(Files::isDirectory).sorted().toList()) {
                Path releases = mod.resolve("releases");
                if (!Files.isDirectory(releases)) {
                    continue;
                }
                try (Stream<Path> files = Files.list(releases)) {
                    files.filter(p -> p.getFileName().toString().endsWith(".build.toml"))
                            .sorted()
                            .forEach(result::add);
                }
            }
        }
        return result;
    }

    public Map<String, Object> read(Path file) throws IOException, Toml.TomlException {
        return Toml.parse(file);
    }

    private static Optional<Keys.PublicKeyInfo> readKey(Path file) {
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Keys.readPublic(file));
        } catch (IOException | GeneralSecurityException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    public static void requireName(String value, String what) {
        if (value == null || !NAME.matcher(value).matches()) {
            throw new IllegalArgumentException("invalid " + what + " name: " + value);
        }
    }

    public static void requireVersion(String value) {
        if (value == null || !VERSION.matcher(value).matches()) {
            throw new IllegalArgumentException("invalid version: " + value);
        }
    }
}
