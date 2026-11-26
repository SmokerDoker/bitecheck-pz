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

    /** Signed exceptions for the scanner's hard findings of one release, one file per reviewer. */
    public Path exceptionsDir(String mod, String version) {
        requireVersion(version);
        return modDir(mod).resolve("exceptions").resolve(version);
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
        if (malwareAuthors().contains(name)) {
            return Optional.empty(); // revoked: signed a release that is confirmed malware
        }
        return readKey(root.resolve("keys").resolve("authors").resolve(name + ".pub")).filter(k -> !isRevoked(k));
    }

    /** From this many flags marked "abuse", a reporter's open flags no longer warn or block anything. */
    public static final int ABUSE_LIMIT = 2;

    private Map<String, Integer> flagAbuse;

    /** How many of the reporter's flags a maintainer marked as abuse (status = "abuse"). */
    public int flagAbuse(String reporter) {
        if (flagAbuse == null) {
            Map<String, Integer> counts = new java.util.HashMap<>();
            try {
                Path mods = modsDir();
                if (Files.isDirectory(mods)) {
                    List<Path> files;
                    try (java.util.stream.Stream<Path> s = Files.walk(mods, 3)) {
                        files = s.filter(p -> p.getParent() != null && p.getParent().getFileName().toString().equals("flags")
                                && p.toString().endsWith(".toml")).toList();
                    }
                    for (Path f : files) {
                        Map<String, Object> flag = Toml.parse(f);
                        String by = Toml.string(flag, "reported_by");
                        if (by != null && "abuse".equals(Toml.string(flag, "status"))) {
                            counts.merge(by, 1, Integer::sum);
                        }
                    }
                }
            } catch (IOException | Toml.TomlException e) {
                // unreadable flags are reported by Lint
            }
            flagAbuse = counts;
        }
        return flagAbuse.getOrDefault(reporter, 0);
    }

    private java.util.Set<String> malwareAuthors;

    /**
     * Authors whose own release (signed by them, not by a separate publisher) has a confirmed flag:
     * their key counts as revoked, for every mod they signed. A publisher of someone else's mod is
     * not revoked for that author's malware.
     */
    public java.util.Set<String> malwareAuthors() {
        if (malwareAuthors != null) {
            return malwareAuthors;
        }
        java.util.Set<String> out = new java.util.TreeSet<>();
        try {
            Path mods = modsDir();
            if (Files.isDirectory(mods)) {
                List<Path> modDirs;
                try (java.util.stream.Stream<Path> s = Files.list(mods)) {
                    modDirs = s.filter(Files::isDirectory).toList();
                }
                for (Path modDir : modDirs) {
                    Path flags = modDir.resolve("flags");
                    Path modFile = modDir.resolve("mod.toml");
                    if (!Files.isDirectory(flags) || !Files.isRegularFile(modFile)) {
                        continue;
                    }
                    Map<String, Object> modInfo = Toml.parse(modFile);
                    if (Toml.string(modInfo, "publisher") != null) {
                        continue;
                    }
                    List<Path> flagFiles;
                    try (java.util.stream.Stream<Path> s = Files.list(flags)) {
                        flagFiles = s.filter(p -> p.toString().endsWith(".toml")).toList();
                    }
                    for (Path f : flagFiles) {
                        if ("confirmed".equals(Toml.string(Toml.parse(f), "status"))) {
                            String author = Toml.string(modInfo, "author");
                            if (author != null) {
                                out.add(author);
                            }
                        }
                    }
                }
            }
        } catch (IOException | Toml.TomlException e) {
            // unreadable registry files: Lint reports them; nothing is revoked on a guess
        }
        malwareAuthors = out;
        return out;
    }

    /**
     * Every key of the author that still counts: the current one ({@code keys/authors/<name>.pub})
     * and the ones it replaced ({@code keys/authors/<name>.previous/*.pub}, kept so earlier releases
     * stay verified after a key change with {@code bcz rotate-key}). Revoked keys are left out.
     */
    public List<Keys.PublicKeyInfo> authorKeys(String name) {
        List<Keys.PublicKeyInfo> keys = new java.util.ArrayList<>();
        authorKey(name).ifPresent(keys::add);
        if (!NAME.matcher(name).matches() || malwareAuthors().contains(name)) {
            return keys;
        }
        Path previous = root.resolve("keys").resolve("authors").resolve(name + ".previous");
        if (Files.isDirectory(previous)) {
            try (java.util.stream.Stream<Path> files = Files.list(previous)) {
                for (Path f : files.filter(p -> p.getFileName().toString().endsWith(".pub")).sorted().toList()) {
                    readKey(f).filter(k -> !isRevoked(k)).ifPresent(keys::add);
                }
            } catch (IOException e) {
                // unreadable: only the current key counts
            }
        }
        return keys;
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
        if (folder.equals("authors") && malwareAuthors().contains(name)) {
            return Optional.of("revoked: a release they signed is confirmed malware");
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

    /** True if the key is listed in keys/revoked.toml. */
    public boolean isRevokedKey(Keys.PublicKeyInfo key) {
        return isRevoked(key);
    }

    /**
     * The author's keys that may have signed this release file: the current key, and a key it
     * replaced only for the releases that key had signed before the change. {@code bcz rotate-key}
     * lists them in {@code keys/authors/<name>.previous/<key id>.releases} (SHA-256 of each release
     * file, signed by that old key). So a stolen old key cannot sign new releases.
     */
    public List<Keys.PublicKeyInfo> authorKeysFor(String name, Path releaseFile) {
        List<Keys.PublicKeyInfo> keys = new java.util.ArrayList<>();
        authorKey(name).ifPresent(keys::add);
        if (!NAME.matcher(name).matches() || malwareAuthors().contains(name)) {
            return keys;
        }
        Path previous = root.resolve("keys").resolve("authors").resolve(name + ".previous");
        if (!Files.isDirectory(previous)) {
            return keys;
        }
        try {
            String sha = Hashing.sha256Hex(releaseFile);
            List<Path> pubs;
            try (java.util.stream.Stream<Path> files = Files.list(previous)) {
                pubs = files.filter(p -> p.getFileName().toString().endsWith(".pub")).sorted().toList();
            }
            for (Path f : pubs) {
                Optional<Keys.PublicKeyInfo> k = readKey(f).filter(x -> !isRevoked(x));
                String base = f.getFileName().toString();
                Path list = previous.resolve(base.substring(0, base.length() - ".pub".length()) + ".releases");
                if (k.isPresent() && Files.isRegularFile(list) && Signatures.verify(list, k.get()) == Signatures.Check.VALID
                        && Files.readAllLines(list).stream().map(String::strip).anyMatch(sha::equals)) {
                    keys.add(k.get());
                }
            }
        } catch (IOException e) {
            // unreadable: only the current key counts
        }
        return keys;
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
        if (demoted().contains(name)) {
            return "demoted"; // approved a release that turned out to be malware: no approval counts
        }
        Path trust = root.resolve("keys").resolve("trust.toml");
        if (!Files.isRegularFile(trust)) {
            return "new";
        }
        String level = Toml.string(Toml.table(Toml.parse(trust), "reviewers." + name), "level");
        return level == null ? "new" : level;
    }

    /** Who invited the reviewer ({@code invited_by} in keys/trust.toml), or null. */
    public String invitedBy(String name) throws IOException, Toml.TomlException {
        Path trust = root.resolve("keys").resolve("trust.toml");
        if (!Files.isRegularFile(trust)) {
            return null;
        }
        return Toml.string(Toml.table(Toml.parse(trust), "reviewers." + name), "invited_by");
    }

    private java.util.Set<String> demoted;

    /**
     * Reviewers who approved a release that has a confirmed flag (malware): they lose their level,
     * and none of their approvals counts until a maintainer looked at them again. Derived from the
     * files, so nobody has to remember to do it.
     */
    public java.util.Set<String> demoted() throws IOException, Toml.TomlException {
        if (demoted != null) {
            return demoted;
        }
        java.util.Set<String> out = new java.util.TreeSet<>();
        Path mods = modsDir();
        if (Files.isDirectory(mods)) {
            List<Path> modDirs;
            try (java.util.stream.Stream<Path> s = Files.list(mods)) {
                modDirs = s.filter(Files::isDirectory).toList();
            }
            for (Path modDir : modDirs) {
                Path flags = modDir.resolve("flags");
                if (!Files.isDirectory(flags)) {
                    continue;
                }
                List<Path> flagFiles;
                try (java.util.stream.Stream<Path> s = Files.list(flags)) {
                    flagFiles = s.filter(p -> p.toString().endsWith(".toml")).toList();
                }
                for (Path flagFile : flagFiles) {
                    Map<String, Object> flag = Toml.parse(flagFile);
                    String version = Toml.string(flag, "version");
                    if (!"confirmed".equals(Toml.string(flag, "status")) || version == null
                            || !VERSION.matcher(version).matches()) {
                        continue;
                    }
                    Path reviews = modDir.resolve("reviews").resolve(version);
                    if (!Files.isDirectory(reviews)) {
                        continue;
                    }
                    List<Path> reviewFiles;
                    try (java.util.stream.Stream<Path> s = Files.list(reviews)) {
                        reviewFiles = s.filter(p -> p.toString().endsWith(".toml")).toList();
                    }
                    for (Path review : reviewFiles) {
                        if ("approve".equals(Toml.string(Toml.parse(review), "verdict"))) {
                            String file = review.getFileName().toString();
                            out.add(file.substring(0, file.length() - ".toml".length()));
                        }
                    }
                }
            }
        }
        demoted = out;
        return out;
    }

    /**
     * How many reviews a release needs, and whether a publisher may review an entry whose author
     * is someone else (start-up only; an author never reviews their own mod).
     */
    public record Policy(int requiredApprovals, int requiredTrusted, boolean publisherMayReview, boolean newcomersCount) {
        public static final Policy DEFAULT = new Policy(2, 1, false, false);

        public Policy(int requiredApprovals, int requiredTrusted, boolean publisherMayReview) {
            this(requiredApprovals, requiredTrusted, publisherMayReview, false);
        }
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
        return new Policy(a, t, Toml.bool(doc, "publisher_may_review"), Toml.bool(doc, "newcomers_count"));
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
        if (modInfo.get("team") instanceof List<?> team && team.contains(reviewer)) {
            return "a member of the mod's team cannot review it";
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
