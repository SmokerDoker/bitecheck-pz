package io.github.bitecheckpz.cli;

import io.github.bitecheckpz.cli.Main.Args;
import io.github.bitecheckpz.cli.Main.UsageException;
import io.github.bitecheckpz.core.Hashing;
import io.github.bitecheckpz.core.Keys;
import io.github.bitecheckpz.core.ModLayout;
import io.github.bitecheckpz.core.Records;
import io.github.bitecheckpz.core.Registry;
import io.github.bitecheckpz.core.Releases;
import io.github.bitecheckpz.core.ReviewNotes;
import io.github.bitecheckpz.core.Scanner;
import io.github.bitecheckpz.core.Signatures;
import io.github.bitecheckpz.core.SourceCheck;
import io.github.bitecheckpz.core.Toml;
import io.github.bitecheckpz.core.Verifier;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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

    static int status(Args a, PrintStream out) throws IOException, Toml.TomlException, UsageException,
            InterruptedException {
        Registry registry = registry(a);
        tryPull(registry);
        String me = a.option("as").orElseGet(ModCommands::onlyKeyName);
        List<Path> mods;
        try (Stream<Path> files = Files.list(registry.modsDir())) {
            mods = files.filter(Files::isDirectory).sorted().toList();
        }
        int width = mods.stream().mapToInt(d -> d.getFileName().toString().length()).max().orElse(0);
        for (Path dir : mods) {
            String mod = dir.getFileName().toString();
            Map<String, Object> modInfo = registry.read(dir.resolve("mod.toml"));
            if (Toml.bool(modInfo, "example")) {
                continue;
            }
            String version = latestVersion(registry, mod);
            out.printf("%-" + width + "s %-12s %s%n", mod, version == null ? "-" : version,
                    version == null ? "no release" : nextStep(registry, mod, version, modInfo, me));
        }
        return Main.OK;
    }

    static int inspect(Args a, PrintStream out) throws IOException, Toml.TomlException, UsageException,
            InterruptedException {
        tryPull(registry(a));
        Target t = target(a);
        String source = Toml.string(t.modInfo, "source");
        String commit = Toml.string(t.release, "commit");
        if (source == null || !source.startsWith("https://") || commit == null || !commit.matches("[0-9a-f]{40}")) {
            throw new IOException(t.mod + ": source or commit missing or invalid");
        }
        Path dir = reviewDir(t);
        if (!Files.isDirectory(dir.resolve(".git"))) {
            Files.createDirectories(dir.getParent());
            Exec.git(null, "clone", "--quiet", "--", source, dir.toString());
        }
        if (Exec.tryGit(dir, "cat-file", "-e", commit + "^{commit}").code() != 0) {
            Exec.git(dir, "fetch", "--quiet", "origin");
        }
        Exec.git(dir, "-c", "advice.detachedHead=false", "checkout", "--quiet", "--force", commit);
        out.println(t.mod + " " + t.version + "  (" + source.replaceFirst("^https://github\\.com/", "") + " @ "
                + commit.substring(0, 7) + ")");
        out.println("Source: " + dir);
        String previous = t.registry.previousVersion(t.mod, t.version);
        String base = previous == null ? null : verifiedCommit(t.registry, t.mod, previous);
        if (base != null) {
            String stat = Exec.git(dir, "diff", "--shortstat", base, commit);
            out.println("Changes since " + previous + " (verified): " + (stat.isEmpty() ? "none" : stat.strip()));
            out.println("  git -C \"" + dir + "\" diff " + base.substring(0, 7) + " " + commit.substring(0, 7));
        } else if (previous != null) {
            out.println("Previous release " + previous + " is not verified: review everything");
        }
        printFlags(t, out);
        printScanReport(t, out);
        out.println();
        int result = Main.OK;
        if ("published".equals(Toml.string(Toml.table(t.release, "build"), "type"))) {
            Set<String> oldFindings = new HashSet<>();
            Path buildFile = t.registry.buildFile(t.mod, t.version);
            List<String> built = Files.isRegularFile(buildFile) ? Registry.jarHashes(t.registry.read(buildFile))
                    : List.of();
            List<String> artifacts = Releases.artifacts(t.release);
            List<String> layout = ModLayout.problems(dir, artifacts);
            if (layout.isEmpty()) {
                out.println("Layout: Build 42 loads it (version folder, mod.info, jars named in javaJarFile)");
            } else {
                out.println("Layout: the game or ZombieBuddy will not load this - reject it:");
                layout.forEach(p -> out.println("  !!  " + p));
                result = 15;
            }
            out.println();
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
                String changes = "";
                if (base != null) {
                    // Compare with the same jar in the verified previous release.
                    Path oldJar = Files.createTempFile("bcz-previous", ".jar");
                    try {
                        if (Exec.gitToFile(dir, oldJar, "show", base + ":" + artifact)) {
                            changes = classChanges(classHashes(oldJar), classHashes(jar));
                            Scanner.scan(oldJar).findings().forEach(f -> oldFindings.add(findingText(f)));
                        } else {
                            changes = ", new jar";
                        }
                    } finally {
                        Files.deleteIfExists(oldJar);
                    }
                }
                out.println("  " + (bad ? "!!" : softCount > 0 ? "??" : "OK") + "  " + name + "  "
                        + report.classCount() + (report.classCount() == 1 ? " class" : " classes") + changes
                        + (hard > 0 ? ", " + hard + " hard" : "") + (softCount > 0 ? ", " + softCount + " soft" : "")
                        + (src.ok() ? "" : ", no source for " + String.join(", ", src.classesWithoutSource()))
                        + (inBuild ? "" : ", NOT the jar in the build record"));
                for (Scanner.Finding f : report.findings()) {
                    findings.putIfAbsent(findingText(f), f.severity());
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
                out.println("SHA-256: no build record yet, so nothing to compare");
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
                        ? "!!" : "??") + "  " + text + (base != null && !oldFindings.contains(text) ? "  (new)" : "")));
            }
            String approve = "bcz approve " + t.mod + (base != null ? " --scope diff" : "");
            out.println();
            boolean pending = "pending".equals(Toml.string(t.modInfo, "status"));
            out.println(!layout.isEmpty()
                    ? (pending ? "Do not publish it: the mod does not load"
                            : "Do not approve: bcz reject " + t.mod + " --notes \"does not load in Build 42 (layout)\"")
                    : !built.isEmpty() && matching != artifacts.size()
                    ? "Do not approve: bcz reject " + t.mod + " --notes \"jars do not match the build record\""
                    : result != Main.OK
                    ? "Check the !! lines, then: " + approve + "  or  bcz reject " + t.mod + " --notes \"why\""
                    : pending ? "Next: bcz publish " + t.mod
                            + " (publisher), then after CI: " + approve
                    : soft ? "Next: " + approve + " --notes \"why the findings are fine\""
                    : "Next: " + approve);
        } else {
            String key = (t.mod + "-" + t.version).replaceAll("[^A-Za-z0-9.-]", "-");
            Map<String, Object> build = Toml.table(t.release, "build");
            if ("workshop".equals(Toml.string(build, "type"))) {
                out.println("  Players get the jar from Workshop item " + Toml.string(build, "workshop_id")
                        + "; CI rebuilds it from this source and checks that both have the same files.");
                out.println("  Review the build files too: the build command runs them.");
                out.println("  The Workshop jar and its scan: 'Mod submission' or 'Registry' run, artifact mod-" + key);
            } else {
                out.println("  CI builds this jar; run 'bcz scan' on the jar of the 'Mod submission' run (artifact mod-"
                        + key + ").");
            }
            out.println();
            out.println("pending".equals(Toml.string(t.modInfo, "status"))
                    ? "Next: bcz publish " + t.mod + " (publisher), then after CI: bcz approve " + t.mod
                    : "Next: bcz approve " + t.mod + (base != null ? " --scope diff" : ""));
        }
        return result;
    }

    /** The commit of {@code version} if every jar of it is VERIFIED, otherwise null. */
    private static String verifiedCommit(Registry registry, String mod, String version)
            throws IOException, Toml.TomlException {
        Path build = registry.buildFile(mod, version);
        var key = registry.registryKey();
        if (!Files.isRegularFile(build) || key.isEmpty()) {
            return null;
        }
        List<String> jars = Registry.jarHashes(registry.read(build));
        Verifier verifier = new Verifier(registry, key.get());
        for (String jar : jars) {
            if (verifier.verifyHash(jar).status() != Verifier.Status.VERIFIED) {
                return null;
            }
        }
        return jars.isEmpty() ? null : Toml.string(registry.read(registry.releaseFile(mod, version)), "commit");
    }

    /** SHA-256 of every class in a jar, by entry name. */
    private static Map<String, String> classHashes(Path jar) throws IOException {
        Map<String, String> hashes = new java.util.TreeMap<>();
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(jar.toFile())) {
            for (var entry : java.util.Collections.list(zip.entries())) {
                if (entry.getName().endsWith(".class")) {
                    try (var in = zip.getInputStream(entry)) {
                        hashes.put(entry.getName(), Hashing.sha256Hex(in.readAllBytes()));
                    }
                }
            }
        }
        return hashes;
    }

    /** ", 2 changed, 1 new, 1 removed" compared with the previous jar, or ", unchanged". */
    private static String classChanges(Map<String, String> before, Map<String, String> after) {
        long changed = after.keySet().stream().filter(c -> before.containsKey(c) && !before.get(c).equals(after.get(c)))
                .count();
        long added = after.keySet().stream().filter(c -> !before.containsKey(c)).count();
        long removed = before.keySet().stream().filter(c -> !after.containsKey(c)).count();
        if (changed + added + removed == 0) {
            return ", unchanged";
        }
        return (changed > 0 ? ", " + changed + " changed" : "") + (added > 0 ? ", " + added + " new" : "")
                + (removed > 0 ? ", " + removed + " removed" : "");
    }

    /** One finding as one short line, the same for every jar it appears in. */
    private static String findingText(Scanner.Finding f) {
        String cls = f.where().substring(f.where().lastIndexOf('.') + 1);
        String what = String.join(", ", java.util.Arrays.stream(f.detail().split("; "))
                .map(d -> d.replaceFirst(" \\(.*\\)$", "")).distinct().toList());
        return f.rule() + "  " + cls + ": " + what;
    }

    static int publish(Args a, PrintStream out) throws IOException, GeneralSecurityException, Toml.TomlException,
            UsageException, InterruptedException {
        pull(registry(a));
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
        commitAndPush(out, t.registry, "Publish " + t.mod + " " + t.version, "registry/mods/" + t.mod);
        if (Files.isRegularFile(t.registry.buildFile(t.mod, t.version))) {
            out.println("Published " + t.mod + " " + t.version + ". Next: bcz approve " + t.mod);
        } else {
            out.println("Published " + t.mod + " " + t.version + ". CI now writes the build record (about a minute),");
            out.println("then: bcz approve " + t.mod);
        }
        return Main.OK;
    }

    static int review(Args a, PrintStream out, String verdict) throws IOException, GeneralSecurityException,
            Toml.TomlException, UsageException, InterruptedException {
        pull(registry(a));
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
            throw new IOException("CI has not written the build record yet (about a minute after publish); "
                    + "try again shortly");
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
        if (scope.equals("diff")) {
            String previous = t.registry.previousVersion(t.mod, t.version);
            if (previous == null || verifiedCommit(t.registry, t.mod, previous) == null) {
                throw new IOException(previous == null ? "this is the first release: review it fully"
                        : "the previous release " + previous + " is not verified: review everything (without --scope diff)");
            }
        }
        String notes = a.option("notes").orElse(null);
        if (notes == null) {
            if (!verdict.equals("approve")) {
                throw new UsageException("say why with --notes \"...\"");
            }
            String commit = String.valueOf(Toml.string(t.release, "commit"));
            notes = (scope.equals("full") ? "Full review" : "Review of the changes") + " of the source at commit "
                    + commit.substring(0, Math.min(7, commit.length())) + ", scan and source check.";
        }
        Integer changed = null;
        if (scope.equals("diff")) {
            changed = changedPercent(t);
            if (changed > Records.MAX_DIFF_PERCENT) {
                throw new IOException(changed + " % of the code changed since the previous release (more than "
                        + Records.MAX_DIFF_PERCENT + " %): review everything (without --scope diff)");
            }
            out.println("Changed since the previous release: " + changed + " % of the code");
        }
        Path file = Records.writeReview(t.releaseFile, null, me, verdict, scope, notes, changed);
        Signatures.sign(file, privateKey(me));
        if (Signatures.verify(file, publicKey) != Signatures.Check.VALID) {
            Files.deleteIfExists(file);
            Files.deleteIfExists(Signatures.signaturePath(file));
            throw new IOException("your key does not match keys/reviewers/" + me + ".pub");
        }
        commitAndPush(out, t.registry, "Review " + t.mod + " " + t.version + ": " + verdict, "registry/mods/" + t.mod);
        out.println((verdict.equals("approve") ? "Approved " : "Rejected ") + t.mod + " " + t.version + ".");
        return Main.OK;
    }

    /**
     * How much of the source changed between the previous release's commit and this one: changed
     * lines (added + removed) against all lines at this commit, in percent. Needs 'bcz inspect'.
     */
    static int changedPercent(Target t) throws IOException, Toml.TomlException, InterruptedException {
        Path dir = reviewDir(t);
        if (!Files.isDirectory(dir.resolve(".git"))) {
            throw new IOException("run 'bcz inspect " + t.mod + "' first");
        }
        String commit = Toml.string(t.release, "commit");
        String previous = t.registry.previousVersion(t.mod, t.version);
        String base = Toml.string(t.registry.read(t.registry.releaseFile(t.mod, previous)), "commit");
        String emptyTree = "4b825dc642cb6eb9a060e54bf8d69288fbee4904"; // git's empty tree: "everything" at a commit
        long total = lines(Exec.git(dir, "diff", "--shortstat", emptyTree, commit), false);
        long changed = lines(Exec.git(dir, "diff", "--shortstat", base, commit), true);
        return total == 0 ? 100 : (int) Math.min(100, Math.round(changed * 100.0 / total));
    }

    /** From "3 files changed, 10 insertions(+), 4 deletions(-)": insertions, plus deletions if asked. */
    static long lines(String shortstat, boolean withDeletions) {
        long n = 0;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+) (insertion|deletion)").matcher(shortstat);
        while (m.find()) {
            if (m.group(2).equals("insertion") || withDeletions) {
                n += Long.parseLong(m.group(1));
            }
        }
        return n;
    }

    /**
     * CI's scan report of the built jars, checked against scan_sha256 in the build record: an
     * approval signs that record, so the reviewer co-signs exactly this report.
     */
    private static void printScanReport(Target t, PrintStream out) throws IOException {
        Path build = t.registry.buildFile(t.mod, t.version);
        Path report = build.resolveSibling(t.version + ".scan.txt");
        if (!Files.isRegularFile(build) || !Files.isRegularFile(report)) {
            return;
        }
        try {
            String expected = Toml.string(t.registry.read(build), "scan_sha256");
            if (expected == null || !expected.equals(Hashing.sha256Hex(report))) {
                out.println("CI's scan report does not match its build record - do not approve; report it");
                return;
            }
        } catch (Toml.TomlException e) {
            out.println("Unreadable build record: " + e.getMessage());
            return;
        }
        out.println("CI's scan of the built jars (your approval signs this report):");
        Files.readAllLines(report).forEach(l -> out.println("  " + l));
    }

    /** Open flags of the mod (any severity), so a reviewer sees what others reported. */
    private static void printFlags(Target t, PrintStream out) throws IOException {
        Path dir = t.registry.flagsDir(t.mod);
        if (!Files.isDirectory(dir)) {
            return;
        }
        List<Path> files;
        try (var list = Files.list(dir)) {
            files = list.filter(p -> p.getFileName().toString().endsWith(".toml")).sorted().toList();
        }
        for (Path f : files) {
            try {
                Map<String, Object> flag = t.registry.read(f);
                if (!"open".equals(Toml.string(flag, "status"))) {
                    continue;
                }
                out.println("Open " + Toml.string(flag, "severity") + " flag on " + Toml.string(flag, "version") + ": "
                        + Toml.string(flag, "description"));
            } catch (Toml.TomlException e) {
                out.println("Unreadable flag " + f.getFileName() + ": " + e.getMessage());
            }
        }
    }

    /**
     * A volunteer rebuilds a release on their own computer: the source at the release commit, built
     * with the release's build command against the local game, and the jar's SHA-256 compared with
     * the signed build record. If it matches, a signed rebuild record goes into the registry
     * ({@code rebuilds/<version>/<name>.toml}); the index then says how many people rebuilt it.
     * Runs the mod's build command: do it in a virtual machine or a separate user account.
     */
    static int rebuild(Args a, PrintStream out) throws IOException, GeneralSecurityException, Toml.TomlException,
            UsageException, InterruptedException {
        pull(registry(a));
        Target t = target(a);
        Map<String, Object> build = Toml.table(t.release, "build");
        if (!"rebuild".equals(Optional.ofNullable(Toml.string(build, "type")).orElse("rebuild"))) {
            throw new IOException(t.mod + " " + t.version + " is a '" + Toml.string(build, "type")
                    + "' release: only reproducible builds (type rebuild) can be rebuilt");
        }
        Path buildFile = t.registry.buildFile(t.mod, t.version);
        if (!Files.isRegularFile(buildFile)) {
            throw new IOException("CI has not written the build record yet");
        }
        List<String> expected = Registry.jarHashes(t.registry.read(buildFile));
        String command = Toml.string(build, "command");
        if (command == null || command.isBlank()) {
            throw new IOException("the release has no build command");
        }
        Path gameDir = a.option("game-dir").map(Path::of).orElseThrow(() -> new UsageException(
                "--game-dir <folder with java/projectzomboid.jar or projectzomboid.jar> (your game)"));
        // the source at the release commit (the same clone bcz inspect uses)
        Path dir = reviewDir(t);
        String source = Toml.string(t.modInfo, "source");
        String commit = Toml.string(t.release, "commit");
        if (!Files.isDirectory(dir.resolve(".git"))) {
            Files.createDirectories(dir.getParent());
            Exec.git(null, "clone", "--quiet", "--", source, dir.toString());
        }
        Exec.git(dir, "fetch", "--quiet", "origin");
        Exec.git(dir, "-c", "advice.detachedHead=false", "checkout", "--quiet", "--force", commit);
        Exec.git(dir, "clean", "-fdxq");
        out.println("Building " + t.mod + " " + t.version + " with: " + command);
        out.println("(this runs the mod's build: use a virtual machine or a separate user account)");
        ProcessBuilder pb = new ProcessBuilder(isWindows() ? List.of("cmd", "/c", command) : List.of("bash", "-c", command))
                .directory(dir.toFile()).inheritIO();
        pb.environment().put("PZ_GAME_DIR", gameDir.toAbsolutePath().toString());
        int code = pb.start().waitFor();
        if (code != 0) {
            throw new IOException("the build failed (exit " + code + ")");
        }
        String artifact = Releases.artifacts(t.release).get(0);
        Path jar = dir.resolve(artifact).normalize();
        if (!jar.startsWith(dir) || !Files.isRegularFile(jar)) {
            throw new IOException("the build did not produce " + artifact);
        }
        String sha = Hashing.sha256Hex(jar);
        if (!expected.contains(sha)) {
            out.println("Your build differs: " + sha + " (CI: " + String.join(", ", expected) + ")");
            out.println("Compare with the CI build ('bcz fetch' in the mod's folder, then 'bcz jar-compare').");
            return Main.ERROR;
        }
        String me = me(a);
        Keys.PrivateKeyInfo key = privateKey(me);
        Path file = t.registry.modDir(t.mod).resolve("rebuilds").resolve(t.version).resolve(me + ".toml");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "mod = " + Toml.quote(t.mod) + "\nversion = " + Toml.quote(t.version)
                + "\nbuild_sha256 = " + Toml.quote(Hashing.sha256Hex(buildFile)) + "\njar_sha256 = " + Toml.quote(sha)
                + "\nrebuilder = " + Toml.quote(me) + "\nos = " + Toml.quote(System.getProperty("os.name"))
                + "\njava = " + Toml.quote(System.getProperty("java.version"))
                + "\nrebuilt_at = " + Toml.quote(java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString())
                + "\n", java.nio.charset.StandardCharsets.UTF_8);
        Signatures.sign(file, key);
        commitAndPush(out, t.registry, "Rebuild of " + t.mod + " " + t.version + " by " + me, "registry/mods/" + t.mod);
        out.println("Your build is identical to CI's (" + sha + "). Thank you: rebuild record written.");
        return Main.OK;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    /**
     * A review note on a closed-source jar (one the registry cannot verify): the findings only,
     * never code. Players see it in the launcher's warning about that jar.
     */
    static int note(Args a, PrintStream out) throws IOException, GeneralSecurityException, Toml.TomlException,
            UsageException, InterruptedException {
        Registry registry = registry(a);
        pull(registry);
        String target = a.positional(0, "jar or sha256");
        Path jar = Path.of(target);
        String sha = Hashing.isSha256Hex(target) ? target : Hashing.sha256Hex(jar);
        String me = me(a);
        String level = registry.reviewerLevel(me);
        if (!level.equals("reviewer") && !level.equals("trusted")) {
            throw new IOException("only reviewers write review notes ('" + me + "' is " + level + ")");
        }
        String findings = a.option("findings").orElseThrow(() -> new UsageException(
                "write down what you found with --findings \"...\" (no code, no file contents)"));
        String name = a.option("name").orElse(Hashing.isSha256Hex(target) ? "" : jar.getFileName().toString());
        var publicKey = registry.reviewerKey(me)
                .orElseThrow(() -> new IOException("no keys/reviewers/" + me + ".pub in the registry"));
        Path file = ReviewNotes.write(registry, sha, name, me, findings);
        Signatures.sign(file, privateKey(me));
        if (Signatures.verify(file, publicKey) != Signatures.Check.VALID) {
            Files.deleteIfExists(file);
            Files.deleteIfExists(Signatures.signaturePath(file));
            throw new IOException("your key does not match keys/reviewers/" + me + ".pub");
        }
        commitAndPush(out, registry, "Review note on " + (name.isEmpty() ? sha.substring(0, 12) : name),
                "registry/notes");
        out.println("Wrote the review note on " + sha + ".");
        return Main.OK;
    }

    /**
     * A trusted reviewer accepts the scanner's hard findings of a release (recorded in its build
     * record), with a reason: without such an exception the release is never verified.
     */
    static int except(Args a, PrintStream out) throws IOException, GeneralSecurityException, Toml.TomlException,
            UsageException, InterruptedException {
        pull(registry(a));
        Target t = target(a);
        String me = me(a);
        if (!t.registry.reviewerLevel(me).equals("trusted")) {
            throw new IOException("only a trusted reviewer can accept hard findings");
        }
        String problem = Registry.selfReviewProblem(t.modInfo, me, t.registry.policy());
        if (problem != null) {
            throw new IOException(problem);
        }
        Path buildFile = t.registry.buildFile(t.mod, t.version);
        if (!Files.isRegularFile(buildFile)) {
            throw new IOException("CI has not written the build record yet");
        }
        Object recorded = t.registry.read(buildFile).get("hard_findings");
        List<String> hard = new java.util.ArrayList<>();
        if (recorded instanceof List<?> list) {
            list.forEach(o -> hard.add(String.valueOf(o)));
        }
        if (hard.isEmpty()) {
            throw new IOException(t.mod + " " + t.version + " has no hard findings in its build record; nothing to accept");
        }
        String reason = a.option("reason").orElseThrow(() -> new UsageException(
                "say why " + String.join(", ", hard) + " is acceptable here with --reason \"...\""));
        List<String> rules = a.option("rules").map(r -> List.of(r.split(","))).orElse(hard).stream()
                .map(String::strip).filter(r -> !r.isEmpty()).toList();
        if (!rules.containsAll(hard)) {
            throw new UsageException("the build record has " + String.join(", ", hard)
                    + "; accept all of them (or none): --rules " + String.join(",", hard));
        }
        var publicKey = t.registry.reviewerKey(me)
                .orElseThrow(() -> new IOException("no keys/reviewers/" + me + ".pub in the registry"));
        Path file = Records.writeException(t.releaseFile, me, rules, reason);
        Signatures.sign(file, privateKey(me));
        if (Signatures.verify(file, publicKey) != Signatures.Check.VALID) {
            Files.deleteIfExists(file);
            Files.deleteIfExists(Signatures.signaturePath(file));
            throw new IOException("your key does not match keys/reviewers/" + me + ".pub");
        }
        commitAndPush(out, t.registry, "Exception for " + t.mod + " " + t.version + ": " + String.join(", ", rules),
                "registry/mods/" + t.mod);
        out.println("Accepted " + String.join(", ", rules) + " for " + t.mod + " " + t.version
                + ". It still needs the normal approvals.");
        return Main.OK;
    }

    /** Reports a problem with a release: an open flag that covers every jar of that version. */
    static int flag(Args a, PrintStream out) throws IOException, Toml.TomlException, UsageException,
            InterruptedException {
        pull(registry(a));
        Target t = target(a);
        String severity = a.option("severity").orElse("suspicion");
        if (!List.of("info", "suspicion", "critical").contains(severity)) {
            throw new UsageException("--severity must be info, suspicion or critical");
        }
        String notes = a.option("notes").orElseThrow(() -> new UsageException("say what you found with --notes \"...\""));
        String reporter = a.option("as").orElseGet(ModCommands::onlyKeyName);
        Path dir = t.registry.flagsDir(t.mod);
        Files.createDirectories(dir);
        int n = 1;
        while (Files.exists(dir.resolve(t.version + "-" + n + ".toml"))) {
            n++;
        }
        Path file = dir.resolve(t.version + "-" + n + ".toml");
        Files.writeString(file, "mod = " + Toml.quote(t.mod) + "\nversion = " + Toml.quote(t.version)
                + "\nseverity = " + Toml.quote(severity) + "\nstatus = \"open\"\ndescription = " + Toml.quote(notes)
                + (reporter == null ? "" : "\nreported_by = " + Toml.quote(reporter))
                + "\nreported_at = " + Toml.quote(java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
                        .toString()) + "\n", StandardCharsets.UTF_8);
        commitAndPush(out, t.registry, "Flag " + t.mod + " " + t.version + ": " + severity, "registry/mods/" + t.mod);
        out.println("Flagged " + t.mod + " " + t.version + " (" + severity + ")"
                + (severity.equals("critical") ? ": players are warned until it is resolved." : "."));
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
    static Registry registry(Args a) throws UsageException {
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
        List<String> versions = registry.versions(mod);
        return versions.isEmpty() ? null : versions.get(versions.size() - 1);
    }

    /** Where 'bcz inspect' checks out a release's source. */
    private static Path reviewDir(Target t) {
        return home().resolve("review").resolve(t.mod + "-" + t.version);
    }

    static Path home() {
        return Path.of(System.getProperty("user.home"), ".bitecheck");
    }

    /** Your name: --as, the name 'bcz login' saved, or the only personal key in ~/.bitecheck/keys. */
    static String me(Args a) throws UsageException {
        if (a.option("as").isPresent()) {
            return a.option("as").get();
        }
        String name = onlyKeyName();
        if (name == null) {
            throw new UsageException("cannot tell who you are: run 'bcz login' or pass --as <name>");
        }
        return name;
    }

    static String onlyKeyName() {
        try {
            String saved = Toml.string(Toml.parse(home().resolve("config.toml")), "name");
            if (saved != null && Registry.NAME.matcher(saved).matches()) {
                return saved;
            }
        } catch (IOException | Toml.TomlException e) {
            // no saved login: fall back to the only key
        }
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

    static Keys.PrivateKeyInfo privateKey(String me) throws IOException, GeneralSecurityException {
        Registry.requireName(me, "name");
        Path file = home().resolve("keys").resolve(me + ".key");
        if (!Files.isRegularFile(file)) {
            throw new IOException("no private key " + file);
        }
        return Keys.readPrivate(file);
    }

    /** Brings the registry checkout up to date, so you see and sign the current state. */
    static void pull(Registry registry) throws IOException, InterruptedException {
        Exec.git(registry.root(), "pull", "--ff-only", "--autostash", "--quiet");
    }

    /** Like {@link #pull}, but offline or outside a clone the commands just show the local state. */
    static void tryPull(Registry registry) throws InterruptedException {
        try {
            pull(registry);
        } catch (IOException e) {
            // nothing to do: status and inspect only read the registry
        }
    }

    /**
     * Commits the given registry paths - and nothing else - and pushes them. Without write access
     * to the registry, the commit goes to a pull request from your fork instead.
     */
    static void commitAndPush(PrintStream out, Registry registry, String message, String... paths)
            throws IOException, InterruptedException {
        Path root = registry.root();
        List<String> add = new ArrayList<>(List.of("add", "--"));
        add.addAll(List.of(paths));
        Exec.git(root, add.toArray(String[]::new));
        List<String> status = new ArrayList<>(List.of("status", "--porcelain", "--"));
        status.addAll(List.of(paths));
        if (!Exec.git(root, status.toArray(String[]::new)).isEmpty()) {
            List<String> commit = new ArrayList<>(List.of("commit", "--quiet", "-m", message, "--"));
            commit.addAll(List.of(paths));
            Exec.git(root, commit.toArray(String[]::new));
        }
        Exec.Result push = Exec.tryGit(root, "push", "--quiet");
        if (push.code() == 0) {
            return;
        }
        if (!push.output().matches("(?s).*(403|[Pp]ermission|denied|not allowed|protected branch).*")) {
            throw new IOException("git push failed:\n" + push.output());
        }
        String url = PullRequests.open(root, message);
        // The commit now lives in the pull request; keep the local branch equal to the registry.
        Exec.git(root, "reset", "--quiet", "--keep", "@{u}");
        out.println("Pull request: " + url);
    }
}
