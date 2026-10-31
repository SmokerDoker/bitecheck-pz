package io.github.bitecheckpz.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Checks a registry checkout for structural problems: valid files, names, required fields and
 * signatures. Runs in CI on every pull request so broken or unsigned files never get merged.
 */
public final class Lint {

    private static final Set<String> LEVELS = Set.of("new", "reviewer", "trusted");
    private static final Set<String> VERDICTS = Set.of("approve", "reject", "questions");
    private static final Set<String> SCOPES = Set.of("full", "diff");
    private static final Set<String> SEVERITIES = Set.of("info", "suspicion", "critical");
    private static final Set<String> FLAG_STATUSES = Set.of("open", "resolved", "confirmed");
    private static final Set<String> MOD_STATUSES = Set.of("active", "suspended", "withdrawn");

    private final Registry registry;
    private final Optional<Keys.PublicKeyInfo> registryKey;
    private final List<String> errors = new ArrayList<>();

    private Lint(Registry registry, Optional<Keys.PublicKeyInfo> registryKey) {
        this.registry = registry;
        this.registryKey = registryKey;
    }

    /** Returns all problems found; an empty list means the registry is fine. */
    public static List<String> check(Registry registry, Optional<Keys.PublicKeyInfo> registryKey) throws IOException {
        Lint lint = new Lint(registry, registryKey);
        lint.run();
        return lint.errors;
    }

    private void error(Path file, String message) {
        Path abs = file.toAbsolutePath().normalize();
        Path root = registry.root().toAbsolutePath().normalize();
        String shown = (abs.startsWith(root) ? root.relativize(abs) : abs).toString().replace('\\', '/');
        errors.add(shown + ": " + message);
    }

    private void run() throws IOException {
        Path root = registry.root().toAbsolutePath().normalize();
        checkKeys(root.resolve("keys"));
        Path blocklist = root.resolve("registry/blocklist.toml");
        if (Files.isRegularFile(blocklist)) {
            Map<String, Object> doc = parse(blocklist);
            if (doc != null) {
                for (String key : Toml.table(doc, "blocked").keySet()) {
                    if (!Hashing.isSha256Hex(key)) {
                        error(blocklist, "'" + key + "' is not a SHA-256 hash");
                    }
                }
            }
        }
        Path mods = root.resolve("registry/mods");
        if (!Files.isDirectory(mods)) {
            return;
        }
        for (Path modDir : list(mods)) {
            if (Files.isDirectory(modDir)) {
                checkMod(modDir);
            } else {
                error(modDir, "unexpected file in registry/mods");
            }
        }
    }

    private void checkKeys(Path keys) throws IOException {
        if (!Files.isDirectory(keys)) {
            return;
        }
        try (Stream<Path> files = Files.walk(keys)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".pub")).toList()) {
                try {
                    Keys.readPublic(f);
                } catch (Exception e) {
                    error(f, "invalid public key: " + e.getMessage());
                }
                String name = f.getFileName().toString().replace(".pub", "");
                if (!f.getParent().equals(keys) && !Registry.NAME.matcher(name).matches()) {
                    error(f, "key file name must be a valid name");
                }
            }
        }
        Path trust = keys.resolve("trust.toml");
        if (Files.isRegularFile(trust)) {
            Map<String, Object> doc = parse(trust);
            if (doc != null) {
                for (Map.Entry<String, Object> e : Toml.table(doc, "reviewers").entrySet()) {
                    if (!Registry.NAME.matcher(e.getKey()).matches()) {
                        error(trust, "invalid reviewer name '" + e.getKey() + "'");
                    }
                    Object level = e.getValue() instanceof Map<?, ?> m ? m.get("level") : null;
                    if (!LEVELS.contains(String.valueOf(level))) {
                        error(trust, e.getKey() + ": level must be new, reviewer or trusted");
                    }
                    if (!Files.isRegularFile(keys.resolve("reviewers").resolve(e.getKey() + ".pub"))) {
                        error(trust, e.getKey() + ": no key in keys/reviewers/");
                    }
                }
            }
        }
    }

    private void checkMod(Path modDir) throws IOException {
        String mod = modDir.getFileName().toString();
        if (!Registry.NAME.matcher(mod).matches()) {
            error(modDir, "mod folder name must be lower case letters, digits and '-'");
            return;
        }
        Path modFile = modDir.resolve("mod.toml");
        Map<String, Object> modInfo = Files.isRegularFile(modFile) ? parse(modFile) : null;
        if (modInfo == null) {
            if (!Files.isRegularFile(modFile)) {
                error(modDir, "mod.toml is missing");
            }
            return;
        }
        boolean example = Toml.bool(modInfo, "example");
        if (!mod.equals(Toml.string(modInfo, "id"))) {
            error(modFile, "id must be \"" + mod + "\"");
        }
        String author = Toml.string(modInfo, "author");
        require(modFile, modInfo, "name");
        require(modFile, modInfo, "license");
        String source = Toml.string(modInfo, "source");
        if (source == null || !source.startsWith("https://")) {
            error(modFile, "source must be an https:// URL to the public source code");
        }
        String status = Toml.string(modInfo, "status");
        if (status != null && !MOD_STATUSES.contains(status)) {
            error(modFile, "status must be active, suspended or withdrawn");
        }
        Optional<Keys.PublicKeyInfo> authorKey = Optional.empty();
        if (author == null || !Registry.NAME.matcher(author).matches()) {
            error(modFile, "author must be a valid name");
        } else if (!example) {
            authorKey = registry.authorKey(author);
            if (authorKey.isEmpty()) {
                error(modFile, "no key keys/authors/" + author + ".pub");
            }
        }

        Path releases = modDir.resolve("releases");
        if (Files.isDirectory(releases)) {
            for (Path f : list(releases)) {
                String name = f.getFileName().toString();
                if (name.endsWith(".sig")) {
                    if (!Files.isRegularFile(f.resolveSibling(name.substring(0, name.length() - 4)))) {
                        error(f, "signature without a signed file");
                    }
                } else if (name.endsWith(".build.toml")) {
                    checkBuild(f, mod);
                } else if (name.endsWith(".toml")) {
                    checkRelease(f, mod, example, authorKey);
                } else {
                    error(f, "unexpected file");
                }
            }
        }
        Path reviews = modDir.resolve("reviews");
        if (Files.isDirectory(reviews)) {
            for (Path versionDir : list(reviews)) {
                for (Path f : list(versionDir)) {
                    if (f.getFileName().toString().endsWith(".toml")) {
                        checkReview(f, mod, versionDir.getFileName().toString(), author);
                    }
                }
            }
        }
        Path flags = modDir.resolve("flags");
        if (Files.isDirectory(flags)) {
            for (Path f : list(flags)) {
                if (f.getFileName().toString().endsWith(".toml")) {
                    checkFlag(f, mod);
                }
            }
        }
    }

    private void checkRelease(Path f, String mod, boolean example, Optional<Keys.PublicKeyInfo> authorKey) {
        Map<String, Object> doc = parse(f);
        if (doc == null) {
            return;
        }
        String version = Toml.string(doc, "version");
        if (version == null || !Registry.VERSION.matcher(version).matches()) {
            error(f, "invalid version");
        } else if (!f.getFileName().toString().equals(version + ".toml")) {
            error(f, "file must be named " + version + ".toml");
        }
        if (!mod.equals(Toml.string(doc, "mod"))) {
            error(f, "mod must be \"" + mod + "\"");
        }
        String commit = Toml.string(doc, "commit");
        if (commit == null || !commit.matches("[0-9a-f]{40}")) {
            error(f, "commit must be a full 40-character git commit hash");
        }
        Map<String, Object> build = Toml.table(doc, "build");
        for (String key : List.of("jdk", "command", "artifact")) {
            if (Toml.string(build, key) == null && !example) {
                error(f, "[build] needs " + key);
            }
        }
        String jdk = Toml.string(build, "jdk");
        if (jdk != null && !jdk.matches("(temurin|zulu|corretto|microsoft|liberica|oracle)-[0-9]{2}")) {
            error(f, "[build] jdk must look like temurin-25 (distribution-majorversion)");
        }
        String command = Toml.string(build, "command");
        if (command != null && (command.contains("\n") || command.contains("\r"))) {
            error(f, "[build] command must be a single line");
        }
        String artifact = Toml.string(build, "artifact");
        if (artifact != null && (artifact.startsWith("/") || artifact.contains("..") || artifact.contains("\\"))) {
            error(f, "[build] artifact must be a relative path inside the project");
        }
        if (!example && authorKey.isPresent()) {
            Signatures.Check sig = Signatures.verify(f, authorKey.get());
            if (sig != Signatures.Check.VALID) {
                error(f, "author signature: " + sig.description());
            }
        }
    }

    private void checkBuild(Path f, String mod) {
        Map<String, Object> doc = parse(f);
        if (doc == null) {
            return;
        }
        if (!mod.equals(Toml.string(doc, "mod"))) {
            error(f, "mod must be \"" + mod + "\"");
        }
        for (String key : List.of("release_sha256", "jar_sha256")) {
            if (!Hashing.isSha256Hex(Toml.string(doc, key))) {
                error(f, key + " must be a SHA-256 hash");
            }
        }
        if (registryKey.isPresent()) {
            Signatures.Check sig = Signatures.verify(f, registryKey.get());
            if (sig != Signatures.Check.VALID) {
                error(f, "registry signature: " + sig.description());
            }
        }
    }

    private void checkReview(Path f, String mod, String version, String author) {
        Map<String, Object> doc = parse(f);
        if (doc == null) {
            return;
        }
        String fileName = f.getFileName().toString();
        String reviewer = fileName.substring(0, fileName.length() - ".toml".length());
        if (!reviewer.equals(Toml.string(doc, "reviewer"))) {
            error(f, "reviewer must match the file name");
        }
        if (reviewer.equals(author)) {
            error(f, "authors cannot review their own mod");
        }
        if (!mod.equals(Toml.string(doc, "mod")) || !version.equals(Toml.string(doc, "version"))) {
            error(f, "mod/version do not match the folder");
        }
        if (!Hashing.isSha256Hex(Toml.string(doc, "jar_sha256"))) {
            error(f, "jar_sha256 must be a SHA-256 hash");
        }
        if (!VERDICTS.contains(String.valueOf(Toml.string(doc, "verdict")))) {
            error(f, "verdict must be approve, reject or questions");
        }
        if (!SCOPES.contains(String.valueOf(Toml.string(doc, "scope")))) {
            error(f, "scope must be full or diff");
        }
        var key = registry.reviewerKey(reviewer);
        if (key.isEmpty()) {
            error(f, "no key keys/reviewers/" + reviewer + ".pub");
        } else {
            Signatures.Check sig = Signatures.verify(f, key.get());
            if (sig != Signatures.Check.VALID) {
                error(f, "reviewer signature: " + sig.description());
            }
        }
    }

    private void checkFlag(Path f, String mod) {
        Map<String, Object> doc = parse(f);
        if (doc == null) {
            return;
        }
        if (!mod.equals(Toml.string(doc, "mod"))) {
            error(f, "mod must be \"" + mod + "\"");
        }
        if (!Hashing.isSha256Hex(Toml.string(doc, "jar_sha256"))) {
            error(f, "jar_sha256 must be a SHA-256 hash");
        }
        if (!SEVERITIES.contains(String.valueOf(Toml.string(doc, "severity")))) {
            error(f, "severity must be info, suspicion or critical");
        }
        if (!FLAG_STATUSES.contains(String.valueOf(Toml.string(doc, "status")))) {
            error(f, "status must be open, resolved or confirmed");
        }
        require(f, doc, "description");
    }

    private void require(Path f, Map<String, Object> doc, String key) {
        String value = Toml.string(doc, key);
        if (value == null || value.isBlank()) {
            error(f, key + " is required");
        }
    }

    private Map<String, Object> parse(Path f) {
        try {
            return Toml.parse(f);
        } catch (IOException e) {
            error(f, "cannot read: " + e.getMessage());
        } catch (Toml.TomlException e) {
            errors.add(e.getMessage());
        }
        return null;
    }

    private static List<Path> list(Path dir) throws IOException {
        try (Stream<Path> s = Files.list(dir)) {
            return s.sorted().toList();
        }
    }
}
