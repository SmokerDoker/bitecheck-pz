package io.github.bitecheckpz.cli;

import io.github.bitecheckpz.cli.Main.Args;
import io.github.bitecheckpz.cli.Main.UsageException;
import io.github.bitecheckpz.core.Hashing;
import io.github.bitecheckpz.core.Keys;
import io.github.bitecheckpz.core.Records;
import io.github.bitecheckpz.core.Registry;
import io.github.bitecheckpz.core.Releases;
import io.github.bitecheckpz.core.Scanner;
import io.github.bitecheckpz.core.Signatures;
import io.github.bitecheckpz.core.SourceCheck;
import io.github.bitecheckpz.core.Toml;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Short commands for publishers and reviewers that take a mod id instead of file paths:
 * {@code status}, {@code inspect}, {@code publish}, {@code approve} and {@code reject}.
 *
 * <p>They find the registry checkout by walking up from the current directory, pick the newest
 * release unless a version is given, and sign with {@code ~/.bitecheck/keys/<name>.key}, where the
 * name comes from {@code --as} or from the only personal key in that folder.
 */
final class ModCommands {

    private ModCommands() {
    }

    /** One release of one mod in the registry, with everything the commands need. */
    private record Target(Registry registry, String mod, String version, Path modFile, Map<String, Object> modInfo,
            Path releaseFile, Map<String, Object> release) {
    }

    // ---- commands ----

    static int status(Args a, PrintStream out) throws IOException, Toml.TomlException, UsageException {
        Registry registry = registry(a);
        String me = a.option("as").orElseGet(ModCommands::onlyKeyName);
        List<Path> mods;
        try (Stream<Path> files = Files.list(registry.modsDir())) {
            mods = files.filter(Files::isDirectory).sorted().toList();
        }
        for (Path dir : mods) {
            String mod = dir.getFileName().toString();
            Map<String, Object> modInfo = registry.read(dir.resolve("mod.toml"));
            if (Toml.bool(modInfo, "example")) {
                continue;
            }
            String version = latestVersion(registry, mod);
            out.printf("%-22s %-12s %s%n", mod, version == null ? "-" : version,
                    version == null ? "no release" : nextStep(registry, mod, version, modInfo, me));
        }
        return Main.OK;
    }

    static int inspect(Args a, PrintStream out) throws IOException, Toml.TomlException, UsageException,
            InterruptedException {
        Target t = target(a);
        String source = Toml.string(t.modInfo, "source");
        String commit = Toml.string(t.release, "commit");
        if (source == null || !source.startsWith("https://") || commit == null || !commit.matches("[0-9a-f]{40}")) {
            throw new IOException(t.mod + ": source or commit missing or invalid");
        }
        Path dir = reviewDir(t);
        if (!Files.isDirectory(dir.resolve(".git"))) {
            Files.createDirectories(dir.getParent());
            git(null, "clone", "--quiet", "--", source, dir.toString());
        }
        if (git(dir, "cat-file", "-e", commit + "^{commit}") != 0) {
            git(dir, "fetch", "--quiet", "origin");
        }
        git(dir, "-c", "advice.detachedHead=false", "checkout", "--quiet", "--force", commit);
        out.println(t.mod + " " + t.version + "  (" + source.replaceFirst("^https://github\\.com/", "") + " @ "
                + commit.substring(0, 7) + ")");
        out.println("Source: " + dir);
        out.println();
        int result = Main.OK;
        if ("published".equals(Toml.string(Toml.table(t.release, "build"), "type"))) {
            Path buildFile = t.registry.buildFile(t.mod, t.version);
            List<String> built = Files.isRegularFile(buildFile) ? Registry.jarHashes(t.registry.read(buildFile))
                    : List.of();
            List<String> artifacts = Releases.artifacts(t.release);
            // 42.13/media/java/client/Mod.jar -> 42.13/Mod.jar
            List<String> names = artifacts.stream().map(s -> s.replaceFirst("/media/java/[^/]+/", "/")).toList();
            int width = names.stream().mapToInt(String::length).max().orElse(0);
            Map<String, Scanner.Severity> findings = new java.util.LinkedHashMap<>();
            boolean soft = false;
            int matching = 0;
            for (int i = 0; i < artifacts.size(); i++) {
                String artifact = artifacts.get(i);
                String name = String.format("%-" + width + "s", names.get(i));
                Path jar = dir.resolve(artifact).normalize();
                if (!Releases.isSafeArtifactPath(artifact) || !jar.startsWith(dir) || !Files.isRegularFile(jar)) {
                    out.println("  !!  " + name + "  missing");
                    result = Main.ERROR;
                    continue;
                }
                Scanner.Report report = Scanner.scan(jar);
                SourceCheck.Result src = SourceCheck.check(jar, dir);
                boolean inBuild = built.isEmpty() || built.contains(report.jarSha256());
                matching += built.contains(report.jarSha256()) ? 1 : 0;
                long hard = report.count(Scanner.Severity.HARD);
                long softCount = report.count(Scanner.Severity.SOFT);
                boolean bad = hard > 0 || !src.ok() || !inBuild;
                out.println("  " + (bad ? "!!" : softCount > 0 ? "??" : "OK") + "  " + name + "  "
                        + report.classCount() + (report.classCount() == 1 ? " class" : " classes")
                        + (hard > 0 ? ", " + hard + " hard" : "") + (softCount > 0 ? ", " + softCount + " soft" : "")
                        + (src.ok() ? "" : ", no source for " + String.join(", ", src.classesWithoutSource()))
                        + (inBuild ? "" : ", NOT the jar in the build record"));
                for (Scanner.Finding f : report.findings()) {
                    String cls = f.where().substring(f.where().lastIndexOf('.') + 1);
                    String what = String.join(", ", java.util.Arrays.stream(f.detail().split("; "))
                            .map(d -> d.replaceFirst(" \\(.*\\)$", "")).distinct().toList());
                    findings.putIfAbsent(f.rule() + "  " + cls + ": " + what, f.severity());
                }
                soft |= softCount > 0;
                if (hard > 0) {
                    result = 14;
                } else if (bad && result == Main.OK) {
                    result = 15;
                }
            }
            out.println();
            if (built.isEmpty()) {
                out.println("SHA-256: no build record yet, so nothing to compare. CI writes it after 'bcz publish';");
                out.println("         'bcz approve' compares these jars with it before signing.");
            } else if (matching == artifacts.size()) {
                out.println("SHA-256: all " + matching + " jars match the build record signed by CI");
            } else {
                out.println("SHA-256: only " + matching + " of " + artifacts.size()
                        + " jars match the build record - do not approve");
            }
            if (!findings.isEmpty()) {
                out.println();
                out.println("Findings (explain each one in your review):");
                findings.forEach((text, severity) -> out.println("  " + (severity == Scanner.Severity.HARD
                        ? "!!" : "??") + "  " + text));
            }
            out.println();
            out.println(!built.isEmpty() && matching != artifacts.size()
                    ? "Do not approve: bcz reject " + t.mod + " --notes \"jars do not match the build record\""
                    : result != Main.OK
                    ? "Check the !! lines, then: bcz approve " + t.mod + "  or  bcz reject " + t.mod + " --notes \"why\""
                    : soft ? "Next: bcz approve " + t.mod + " --notes \"why the findings are fine\""
                    : "Next: bcz approve " + t.mod);
        } else {
            out.println("  CI builds this jar; run 'bcz scan' on the jar of the 'Mod submission' run.");
            out.println();
            out.println("Next: bcz approve " + t.mod);
        }
        return result;
    }

    static int publish(Args a, PrintStream out) throws IOException, GeneralSecurityException, Toml.TomlException,
            UsageException {
        Target t = target(a);
        String me = me(a);
        String signer = Registry.signer(t.modInfo);
        if (!me.equals(signer)) {
            throw new IOException(t.mod + " is published by '" + signer + "', not by '" + me + "'");
        }
        var publicKey = t.registry.authorKey(me)
                .orElseThrow(() -> new IOException("no keys/authors/" + me + ".pub in the registry"));
        Signatures.sign(t.releaseFile, privateKey(me));
        if (Signatures.verify(t.releaseFile, publicKey) != Signatures.Check.VALID) {
            Files.deleteIfExists(Signatures.signaturePath(t.releaseFile));
            throw new IOException("your key does not match keys/authors/" + me + ".pub");
        }
        String modText = Files.readString(t.modFile, StandardCharsets.UTF_8);
        String active = modText.replaceFirst("(?m)^status\\s*=\\s*\"pending\"[ \\t]*$", "status = \"active\"");
        if (!active.equals(modText)) {
            Files.writeString(t.modFile, active, StandardCharsets.UTF_8);
        }
        out.println("Signed " + t.mod + " " + t.version + " as '" + me + "'"
                + (active.equals(modText) ? "" : "; status is now active"));
        out.println("Next: commit and push, e.g.");
        out.println("  git add registry/mods/" + t.mod + " && git commit -m \"Publish " + t.mod + " " + t.version
                + "\" && git push");
        out.println("CI then writes the build record. After that: git pull, bcz approve " + t.mod);
        return Main.OK;
    }

    static int review(Args a, PrintStream out, String verdict) throws IOException, GeneralSecurityException,
            Toml.TomlException, UsageException {
        Target t = target(a);
        String me = me(a);
        String problem = Registry.selfReviewProblem(t.modInfo, me, t.registry.policy());
        if (problem != null) {
            throw new IOException(problem);
        }
        if ("pending".equals(Toml.string(t.modInfo, "status"))) {
            throw new IOException(t.mod + " is still pending; the publisher runs 'bcz publish " + t.mod + "' first");
        }
        if (!Files.isRegularFile(t.registry.buildFile(t.mod, t.version))) {
            throw new IOException("no build record for " + t.mod + " " + t.version
                    + " yet; CI writes it after the release is pushed. Run 'git pull' and try again.");
        }
        if ("published".equals(Toml.string(Toml.table(t.release, "build"), "type"))) {
            // The review signs the build record; make sure it lists exactly the jars you inspected.
            Path dir = reviewDir(t);
            if (!Files.isDirectory(dir.resolve(".git"))) {
                throw new IOException("run 'bcz inspect " + t.mod + "' first");
            }
            List<String> built = Registry.jarHashes(t.registry.read(t.registry.buildFile(t.mod, t.version)));
            List<String> artifacts = Releases.artifacts(t.release);
            for (String artifact : artifacts) {
                Path jar = dir.resolve(artifact).normalize();
                if (!Releases.isSafeArtifactPath(artifact) || !jar.startsWith(dir) || !Files.isRegularFile(jar)
                        || !built.contains(Hashing.sha256Hex(jar))) {
                    throw new IOException(artifact + " does not match the build record - do not approve; "
                            + "run 'bcz inspect " + t.mod + "' again and report it");
                }
            }
            out.println("SHA-256: all " + artifacts.size() + " jars match the build record signed by CI");
        }
        var publicKey = t.registry.reviewerKey(me)
                .orElseThrow(() -> new IOException("no keys/reviewers/" + me + ".pub in the registry"));
        String scope = a.option("scope").orElse("full");
        String notes = a.option("notes").orElse(null);
        if (notes == null) {
            if (!verdict.equals("approve")) {
                throw new UsageException("say why with --notes \"...\"");
            }
            String commit = String.valueOf(Toml.string(t.release, "commit"));
            notes = (scope.equals("full") ? "Full review" : "Review of the changes") + " of the source at commit "
                    + commit.substring(0, Math.min(7, commit.length())) + ", scan and source check.";
        }
        Path file = Records.writeReview(t.releaseFile, null, me, verdict, scope, notes);
        Signatures.sign(file, privateKey(me));
        if (Signatures.verify(file, publicKey) != Signatures.Check.VALID) {
            Files.deleteIfExists(file);
            Files.deleteIfExists(Signatures.signaturePath(file));
            throw new IOException("your key does not match keys/reviewers/" + me + ".pub");
        }
        out.println("Signed review of " + t.mod + " " + t.version + ": " + verdict + " by '" + me + "'");
        out.println("Next: commit and push, e.g.");
        out.println("  git add registry/mods/" + t.mod + " && git commit -m \"Review " + t.mod + " " + t.version
                + ": " + verdict + "\" && git push");
        return Main.OK;
    }

    // ---- helpers ----

    private static String nextStep(Registry registry, String mod, String version, Map<String, Object> modInfo,
            String me) throws IOException, Toml.TomlException {
        String signer = Registry.signer(modInfo);
        if ("pending".equals(Toml.string(modInfo, "status"))) {
            return signer != null && signer.equals(me) ? "pending -> bcz inspect " + mod + ", then bcz publish " + mod
                    : "pending, waiting for the publisher '" + signer + "'";
        }
        String status = Toml.string(modInfo, "status");
        if (status != null && !status.equals("active")) {
            return status;
        }
        if (!Files.isRegularFile(registry.buildFile(mod, version))) {
            return "waiting for the CI build record (push, then git pull)";
        }
        Path mine = me == null ? null : registry.reviewsDir(mod, version).resolve(me + ".toml");
        if (mine != null && Files.isRegularFile(mine)) {
            return "reviewed by you: " + Toml.string(registry.read(mine), "verdict");
        }
        List<String> reviewers = new ArrayList<>();
        if (Files.isDirectory(registry.reviewsDir(mod, version))) {
            try (Stream<Path> files = Files.list(registry.reviewsDir(mod, version))) {
                files.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".toml"))
                        .map(n -> n.substring(0, n.length() - 5)).sorted().forEach(reviewers::add);
            }
        }
        String others = reviewers.isEmpty() ? "no reviews" : "reviews: " + String.join(", ", reviewers);
        return me != null && Registry.selfReviewProblem(modInfo, me, registry.policy()) == null
                ? others + " -> bcz inspect " + mod + ", then bcz approve " + mod
                : others;
    }

    private static Target target(Args a) throws IOException, Toml.TomlException, UsageException {
        Registry registry = registry(a);
        if (a.positionals.isEmpty()) {
            throw new UsageException("which mod? e.g. 'bcz " + a.command + " zb-hello-world' ('bcz status' lists them)");
        }
        String mod = a.positionals.get(0);
        Registry.requireName(mod, "mod");
        Path modFile = registry.modDir(mod).resolve("mod.toml");
        if (!Files.isRegularFile(modFile)) {
            throw new IOException("no mod '" + mod + "' in the registry; see 'bcz status'");
        }
        String version = a.positionals.size() > 1 ? a.positionals.get(1) : latestVersion(registry, mod);
        if (version == null) {
            throw new IOException(mod + " has no release");
        }
        Registry.requireVersion(version);
        Path releaseFile = registry.releaseFile(mod, version);
        if (!Files.isRegularFile(releaseFile)) {
            throw new IOException(mod + " has no release " + version);
        }
        return new Target(registry, mod, version, modFile, registry.read(modFile), releaseFile,
                registry.read(releaseFile));
    }

    /**
     * The registry checkout: --registry, else the first parent of the current directory that has
     * one, else the checkout this bcz was built in (cli/build/install/bcz/lib/cli.jar).
     */
    private static Registry registry(Args a) throws UsageException {
        if (a.option("registry").isPresent()) {
            return new Registry(Path.of(a.option("registry").get()));
        }
        List<Path> starts = new ArrayList<>(List.of(Path.of("").toAbsolutePath()));
        try {
            starts.add(Path.of(ModCommands.class.getProtectionDomain().getCodeSource().getLocation().toURI()));
        } catch (Exception e) {
            // no code location (unusual class loader): only the current directory is searched
        }
        for (Path start : starts) {
            for (Path dir = start; dir != null; dir = dir.getParent()) {
                if (Files.isDirectory(dir.resolve("registry/mods")) && Files.isDirectory(dir.resolve("keys"))) {
                    return new Registry(dir);
                }
            }
        }
        throw new UsageException("run this inside the BiteCheck registry checkout, or pass --registry <dir>");
    }

    /** Newest release of a mod by version order, or null. */
    private static String latestVersion(Registry registry, String mod) throws IOException {
        Path releases = registry.modDir(mod).resolve("releases");
        if (!Files.isDirectory(releases)) {
            return null;
        }
        try (Stream<Path> files = Files.list(releases)) {
            return files.map(p -> p.getFileName().toString())
                    .filter(n -> n.endsWith(".toml") && !n.endsWith(".build.toml"))
                    .map(n -> n.substring(0, n.length() - ".toml".length()))
                    .max(ModCommands::compareVersions).orElse(null);
        }
    }

    /** Compares versions part by part, numbers numerically: 1.10 > 1.9, 2026.10.04 > 2026.9.30. */
    static int compareVersions(String x, String y) {
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

    /** Where 'bcz inspect' checks out a release's source. */
    private static Path reviewDir(Target t) {
        return home().resolve("review").resolve(t.mod + "-" + t.version);
    }

    private static Path home() {
        return Path.of(System.getProperty("user.home"), ".bitecheck");
    }

    /** Your name: --as, or the only personal key in ~/.bitecheck/keys. */
    private static String me(Args a) throws UsageException {
        if (a.option("as").isPresent()) {
            return a.option("as").get();
        }
        String name = onlyKeyName();
        if (name == null) {
            throw new UsageException("cannot tell who you are: pass --as <name> (your key is ~/.bitecheck/keys/<name>.key)");
        }
        return name;
    }

    private static String onlyKeyName() {
        Path dir = home().resolve("keys");
        if (!Files.isDirectory(dir)) {
            return null;
        }
        try (Stream<Path> files = Files.list(dir)) {
            List<String> names = files.map(p -> p.getFileName().toString())
                    .filter(n -> n.endsWith(".key") && !n.equals("registry.key"))
                    .map(n -> n.substring(0, n.length() - ".key".length()))
                    .sorted(Comparator.naturalOrder()).toList();
            return names.size() == 1 ? names.get(0) : null;
        } catch (IOException e) {
            return null;
        }
    }

    private static Keys.PrivateKeyInfo privateKey(String me) throws IOException, GeneralSecurityException {
        Registry.requireName(me, "name");
        Path file = home().resolve("keys").resolve(me + ".key");
        if (!Files.isRegularFile(file)) {
            throw new IOException("no private key " + file);
        }
        return Keys.readPrivate(file);
    }

    private static int git(Path dir, String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of("git"));
        if (dir != null) {
            command.addAll(List.of("-C", dir.toString()));
        }
        command.addAll(List.of(args));
        Process process;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
        } catch (IOException e) {
            throw new IOException("git is needed for this command and was not found", e);
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        int code = process.waitFor();
        if (code != 0 && (args[0].equals("clone") || List.of(args).contains("checkout"))) {
            throw new IOException("git " + String.join(" ", args) + " failed" + (output.isEmpty() ? "" : ":\n" + output));
        }
        return code;
    }
}
