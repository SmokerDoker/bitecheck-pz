package io.github.bitecheckpz.cli;

import io.github.bitecheckpz.core.Hashing;
import io.github.bitecheckpz.core.Index;
import io.github.bitecheckpz.core.Lint;
import io.github.bitecheckpz.core.Scanner;
import io.github.bitecheckpz.core.Keys;
import io.github.bitecheckpz.core.Records;
import io.github.bitecheckpz.core.Registry;
import io.github.bitecheckpz.core.Signatures;
import io.github.bitecheckpz.core.Site;
import io.github.bitecheckpz.core.Toml;
import io.github.bitecheckpz.core.Verifier;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** The {@code bcz} command line tool. */
public final class Main {

    static final int OK = 0;
    static final int ERROR = 1;
    static final int USAGE = 2;

    private static final String USAGE_TEXT = """
            bcz - BiteCheck for Project Zomboid

            Publishers and reviewers (run inside the registry checkout; <version> defaults to the newest):
              bcz status
                  What is next for every mod: publish, wait for CI, review.
              bcz inspect <mod> [version]
                  Fetch the mod's source at the release commit to ~/.bitecheck/review/, then scan and
                  source-check its jars.
              bcz publish <mod> [version]
                  Sign the release as its publisher and mark a pending entry active.
              bcz approve <mod> [version] [--notes <text>] [--scope full|diff]
              bcz reject <mod> [version] --notes <text>
                  Write and sign your review of the release's build record. --scope diff: you reviewed
                  only the changes since the previous release, which must be verified.
              bcz flag <mod> [version] --notes <text> [--severity info|suspicion|critical]
                  Report a problem with a release. A critical flag warns every player.
                  These commands sign with ~/.bitecheck/keys/<name>.key (see 'bcz login', or --as <name>).
                  Without write access to the registry they open a pull request from your fork.

            Mod authors (needs the GitHub CLI 'gh', logged in):
              bcz login [--as <name>]
                  Link your GitHub account and create your signing key if you have none.
              bcz submit [--version <v>] [--commit <sha>] [--key <file>] [--registry-repo <owner/repo>]
                  Submit the tagged release of the mod in the current directory (reads bitecheck.toml):
                  writes and signs the release file and opens a pull request to the registry.
              bcz fetch [--version <v>] [--registry-repo <owner/repo>]
                  Compare the mod's jars with the verified build of the tagged release. Only if they
                  differ, and only after you confirm, replace them with the jars CI built.

            Maintainers:
              bcz trust <reviewer> [new|reviewer|trusted]
                  Show or set a reviewer's level.
              bcz reviewers
                  Every reviewer with level, GitHub account and number of reviews.
              bcz submitter-check --login <github-user> --base <git-ref>
                  CI: check that a pull request only changes files of its author's account.

            Usage:
              bcz keygen <name> [--dir <dir>]
                  Create an Ed25519 key pair. The private key goes to ~/.bitecheck/keys/<name>.key
                  (or --dir), the public key to <name>.pub in the current directory.
              bcz hash <file>
                  Print the SHA-256 of a file.
              bcz sign <file> --key <private-key>
                  Write a detached signature <file>.sig.
              bcz verify-sig <file> --pub <public-key>
                  Check <file>.sig against a public key.
              bcz build-record <release.toml> <jar>... [--builder <name>]...
                  Write <version>.build.toml for a release (used by CI). For a published release,
                  pass one jar per artifact in the release's order.
              bcz review <release.toml> [jar] --reviewer <name> --verdict approve|reject|questions
                         [--scope full|diff] [--notes <text>]
                  Write a review file for one jar, or without a jar for the whole build record.
                  Sign it with 'bcz sign' afterwards.
              bcz source-check <jar> <source-dir>
                  Check that every class in a jar is declared in the source code. Exit 15 if not.
              bcz jar-compare <built.jar> <workshop.jar>
                  CI, workshop releases: both jars have the same files (manifest aside). Exit 1 if not.
              bcz verify <jar> [--registry <dir>] [--registry-key <public-key>]
                  Check a mod jar against the registry.
                  Exit codes: 0 verified, 10 unverified, 11 flagged, 12 blocked, 13 invalid.
              bcz scan <jar> [--format text|markdown]
                  Scan a mod jar for APIs reviewers must check. Exit code 14 on hard findings.
              bcz lint [--registry <dir>] [--registry-key <public-key>]
                  Check all registry files: names, fields, signatures.
              bcz release-info <release.toml> [--registry <dir>]
                  Print source, commit and build settings of a release as name=value lines (for CI).
              bcz index [--registry <dir>] --registry-key <public-key> --out <file> [--commit <sha>]
                  Generate the launcher index from the registry (sign it afterwards).
              bcz site [--registry <dir>] [--registry-key <public-key>] --out <dir> [--repo-url <url>]
                  Generate the registry website (index.html) from the registry files.

            Options:
              -h, --help     Show this help.
              --version      Show the version.
            """;

    private Main() {
    }

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    static int run(String[] args, PrintStream out, PrintStream err) {
        if (args.length == 0 || args[0].equals("-h") || args[0].equals("--help") || args[0].equals("help")) {
            out.print(USAGE_TEXT);
            return args.length == 0 ? USAGE : OK;
        }
        if (args[0].equals("--version")) {
            String version = Main.class.getPackage().getImplementationVersion();
            out.println("bcz " + (version == null ? "dev" : version));
            return OK;
        }
        try {
            Args parsed = Args.parse(args);
            return switch (parsed.command) {
                case "keygen" -> keygen(parsed, out);
                case "hash" -> hash(parsed, out);
                case "sign" -> sign(parsed, out);
                case "verify-sig" -> verifySig(parsed, out);
                case "build-record" -> buildRecord(parsed, out);
                case "review" -> review(parsed, out);
                case "verify" -> verify(parsed, out, err);
                case "scan" -> scan(parsed, out);
                case "lint" -> lint(parsed, out);
                case "index" -> index(parsed, out);
                case "release-info" -> releaseInfo(parsed, out);
                case "source-check" -> sourceCheck(parsed, out);
                case "jar-compare" -> jarCompare(parsed, out);
                case "site" -> site(parsed, out);
                case "status" -> ModCommands.status(parsed, out);
                case "inspect" -> ModCommands.inspect(parsed, out);
                case "publish" -> ModCommands.publish(parsed, out);
                case "approve" -> ModCommands.review(parsed, out, "approve");
                case "reject" -> ModCommands.review(parsed, out, "reject");
                case "flag" -> ModCommands.flag(parsed, out);
                case "login" -> AccountCommands.login(parsed, out);
                case "submit" -> AccountCommands.submit(parsed, out);
                case "fetch" -> AccountCommands.fetch(parsed, out, System.in);
                case "submitter-check" -> AccountCommands.submitterCheck(parsed, out);
                case "trust" -> AccountCommands.trust(parsed, out);
                case "reviewers" -> AccountCommands.reviewers(parsed, out);
                default -> throw new UsageException("unknown command '" + parsed.command + "'");
            };
        } catch (UsageException e) {
            err.println("bcz: " + e.getMessage());
            err.println("Run 'bcz --help' for usage.");
            return USAGE;
        } catch (IOException | GeneralSecurityException | Toml.TomlException | IllegalArgumentException e) {
            err.println("bcz: " + e.getMessage());
            return ERROR;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            err.println("bcz: interrupted");
            return ERROR;
        }
    }

    // ---- commands ----

    private static int keygen(Args a, PrintStream out) throws IOException, UsageException {
        String name = a.positional(0, "name");
        if (!Registry.NAME.matcher(name).matches()) {
            throw new UsageException("name must be lower case letters, digits and '-'");
        }
        Path dir = a.option("dir").map(Path::of)
                .orElse(Path.of(System.getProperty("user.home"), ".bitecheck", "keys"));
        KeyPair pair = Keys.generate();
        String id = Keys.keyId(pair.getPublic());
        Path privateFile = dir.resolve(name + ".key");
        Path publicFile = Path.of(name + ".pub");
        if (Files.exists(publicFile)) {
            throw new IOException(publicFile + " already exists");
        }
        Keys.writePrivate(privateFile, pair.getPrivate(), id);
        Files.writeString(publicFile, Keys.encodePublic(pair.getPublic(), name), StandardCharsets.UTF_8);
        out.println("Key id:      " + id);
        out.println("Private key: " + privateFile.toAbsolutePath() + "  (keep this secret, never commit it)");
        out.println("Public key:  " + publicFile.toAbsolutePath());
        return OK;
    }

    private static int hash(Args a, PrintStream out) throws IOException, UsageException {
        Path file = Path.of(a.positional(0, "file"));
        out.println(Hashing.sha256Hex(file) + "  " + file);
        return OK;
    }

    private static int sign(Args a, PrintStream out) throws IOException, GeneralSecurityException, UsageException {
        Path file = Path.of(a.positional(0, "file"));
        Path key = Path.of(a.requiredOption("key"));
        Path sig = Signatures.sign(file, Keys.readPrivate(key));
        out.println("Signed " + file + " -> " + sig);
        return OK;
    }

    private static int verifySig(Args a, PrintStream out)
            throws IOException, GeneralSecurityException, UsageException {
        Path file = Path.of(a.positional(0, "file"));
        Keys.PublicKeyInfo key = Keys.readPublic(Path.of(a.requiredOption("pub")));
        Signatures.Check check = Signatures.verify(file, key);
        out.println(file + ": " + check.description());
        return check == Signatures.Check.VALID ? OK : ERROR;
    }

    private static int buildRecord(Args a, PrintStream out) throws IOException, Toml.TomlException, UsageException {
        Path release = Path.of(a.positional(0, "release.toml"));
        a.positional(1, "jar");
        List<Path> jars = new ArrayList<>();
        for (String p : a.positionals.subList(1, a.positionals.size())) {
            jars.add(Path.of(p));
        }
        Path record = Records.writeBuildRecord(release, jars, a.options("builder"));
        out.println("Wrote " + record + " - sign it with the registry key.");
        return OK;
    }

    private static int review(Args a, PrintStream out) throws IOException, Toml.TomlException, UsageException {
        Path release = Path.of(a.positional(0, "release.toml"));
        Path jar = a.positionals.size() > 1 ? Path.of(a.positionals.get(1)) : null;
        Path file = Records.writeReview(release, jar, a.requiredOption("reviewer"), a.requiredOption("verdict"),
                a.option("scope").orElse("full"), a.option("notes").orElse(""));
        out.println("Wrote " + file + " - now sign it: bcz sign " + file + " --key <your key>");
        return OK;
    }

    private static int verify(Args a, PrintStream out, PrintStream err)
            throws IOException, GeneralSecurityException, UsageException {
        Path jar = Path.of(a.positional(0, "jar"));
        Registry registry = new Registry(Path.of(a.option("registry").orElse(".")));
        Keys.PublicKeyInfo registryKey;
        Optional<String> pinned = a.option("registry-key");
        if (pinned.isPresent()) {
            registryKey = Keys.readPublic(Path.of(pinned.get()));
        } else {
            registryKey = registry.registryKey()
                    .orElseThrow(() -> new IOException("no keys/registry.pub in " + registry.root()));
            err.println("note: using keys/registry.pub from the registry itself; pin it with --registry-key");
        }
        Verifier.Result result = new Verifier(registry, registryKey).verify(jar);
        out.println(result.status() + "  " + jar);
        out.println("  sha256: " + result.jarSha256());
        if (result.mod() != null) {
            out.println("  mod:    " + result.mod() + " " + result.version());
        }
        for (String message : result.messages()) {
            out.println("  - " + message);
        }
        return switch (result.status()) {
            case VERIFIED -> OK;
            case UNVERIFIED -> 10;
            case FLAGGED -> 11;
            case BLOCKED -> 12;
            case INVALID -> 13;
        };
    }

    private static int scan(Args a, PrintStream out) throws IOException, UsageException {
        Path jar = Path.of(a.positional(0, "jar"));
        Scanner.Report report = Scanner.scan(jar);
        boolean md = a.option("format").orElse("text").equals("markdown");
        long hard = report.count(Scanner.Severity.HARD);
        long soft = report.count(Scanner.Severity.SOFT);
        if (md) {
            out.println("### BiteCheck scan: `" + jar.getFileName() + "`");
            out.println();
            out.println("SHA-256 `" + report.jarSha256() + "` - " + report.classCount() + " classes - **"
                    + hard + " hard**, " + soft + " soft findings");
            out.println();
            if (!report.findings().isEmpty()) {
                out.println("| Severity | Rule | Where | Detail |");
                out.println("| --- | --- | --- | --- |");
                for (Scanner.Finding f : report.findings()) {
                    out.println("| " + f.severity() + " | " + f.rule() + " | `" + f.where() + "` | "
                            + f.detail().replace("|", "\\|") + " |");
                }
            }
            if (hard > 0) {
                out.println();
                out.println("Hard findings block this release. Soft findings must be explained in every review.");
            }
        } else {
            out.println(jar + ": " + report.classCount() + " classes, " + hard + " hard, " + soft + " soft findings");
            out.println("  sha256: " + report.jarSha256());
            for (Scanner.Finding f : report.findings()) {
                out.println("  " + f.severity() + "  " + f.rule() + "  " + f.where() + ": " + f.detail());
            }
        }
        return report.hasHardFindings() ? 14 : OK;
    }

    private static int lint(Args a, PrintStream out) throws IOException, GeneralSecurityException {
        Registry registry = new Registry(Path.of(a.option("registry").orElse(".")));
        Optional<Keys.PublicKeyInfo> key = a.option("registry-key").isPresent()
                ? Optional.of(Keys.readPublic(Path.of(a.option("registry-key").get())))
                : registry.registryKey();
        List<String> problems = Lint.check(registry, key);
        if (key.isEmpty()) {
            out.println("note: no registry key, build record signatures were not checked");
        }
        problems.forEach(p -> out.println("error: " + p));
        out.println(problems.isEmpty() ? "Registry OK" : problems.size() + " problem(s)");
        return problems.isEmpty() ? OK : ERROR;
    }

    private static int index(Args a, PrintStream out)
            throws IOException, GeneralSecurityException, Toml.TomlException, UsageException {
        Registry registry = new Registry(Path.of(a.option("registry").orElse(".")));
        Keys.PublicKeyInfo key = Keys.readPublic(Path.of(a.requiredOption("registry-key")));
        Path target = Path.of(a.requiredOption("out"));
        String text = Index.generate(registry, new Verifier(registry, key), Instant.now(),
                a.option("commit").orElse(""));
        Files.writeString(target, text, StandardCharsets.UTF_8);
        Index parsed = Index.parse(target);
        long verified = parsed.entries().values().stream()
                .filter(e -> e.status() == Verifier.Status.VERIFIED).count();
        out.println("Wrote " + target + ": " + parsed.entries().size() + " entries, " + verified + " verified");
        return OK;
    }

    private static int site(Args a, PrintStream out)
            throws IOException, GeneralSecurityException, Toml.TomlException, UsageException {
        Registry registry = new Registry(Path.of(a.option("registry").orElse(".")));
        Optional<Keys.PublicKeyInfo> key = Optional.empty();
        if (a.option("registry-key").isPresent()) {
            key = Optional.of(Keys.readPublic(Path.of(a.option("registry-key").get())));
        }
        Path dir = Path.of(a.requiredOption("out"));
        String repo = a.option("repo-url").orElse("https://github.com/SmokerDoker/bitecheck-pz");
        List<Site.ModRow> mods = Site.collect(registry, key);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("index.html"), Site.render(mods, Instant.now(), repo, key.isPresent()),
                StandardCharsets.UTF_8);
        out.println("Wrote " + dir.resolve("index.html") + ": " + mods.size() + " mod(s)");
        return OK;
    }

    private static int releaseInfo(Args a, PrintStream out) throws IOException, Toml.TomlException, UsageException {
        Path releaseFile = Path.of(a.positional(0, "release.toml"));
        Registry registry = new Registry(Path.of(a.option("registry").orElse(".")));
        var release = Toml.parse(releaseFile);
        String mod = Toml.string(release, "mod");
        String version = Toml.string(release, "version");
        Registry.requireName(mod, "mod");
        Registry.requireVersion(version);
        var modInfo = Toml.parse(registry.modDir(mod).resolve("mod.toml"));
        var build = Toml.table(release, "build");
        String type = Toml.string(build, "type");
        boolean published = "published".equals(type);
        boolean workshop = "workshop".equals(type);
        List<String> artifacts = io.github.bitecheckpz.core.Releases.artifacts(release);
        if (artifacts.isEmpty() || !artifacts.stream().allMatch(io.github.bitecheckpz.core.Releases::isSafeArtifactPath)) {
            throw new IllegalArgumentException("release artifacts are missing or invalid");
        }
        List<String> values = new ArrayList<>(List.of(
                "mod", mod,
                "version", version,
                "type", published ? "published" : workshop ? "workshop" : "rebuild",
                "source", String.valueOf(Toml.string(modInfo, "source")),
                "commit", String.valueOf(Toml.string(release, "commit")),
                "artifacts", String.join(" ", artifacts)));
        if (!published) {
            String jdk = String.valueOf(Toml.string(build, "jdk"));
            if (!jdk.matches("(temurin|zulu|corretto|microsoft|liberica|oracle)-[0-9]{2}")) {
                throw new IllegalArgumentException("[build] jdk must look like temurin-25");
            }
            String branch = Toml.string(build, "game_branch") == null ? "public" : Toml.string(build, "game_branch");
            if (!branch.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")) {
                throw new IllegalArgumentException("[build] game_branch may only contain letters, digits, '.', '_', '-'");
            }
            values.addAll(List.of(
                    "jdk_distribution", jdk.substring(0, jdk.indexOf('-')),
                    "jdk_version", jdk.substring(jdk.indexOf('-') + 1),
                    "command", String.valueOf(Toml.string(build, "command")),
                    "game_branch", branch));
        }
        if (workshop) {
            List<String> inItem = io.github.bitecheckpz.core.Releases.workshopArtifacts(release);
            String id = String.valueOf(Toml.string(build, "workshop_id"));
            if (inItem.size() != artifacts.size()
                    || !inItem.stream().allMatch(io.github.bitecheckpz.core.Releases::isSafeArtifactPath)
                    || !id.matches("[0-9]{1,20}")) {
                throw new IllegalArgumentException("[build] workshop_id or workshop_artifacts are missing or invalid");
            }
            values.addAll(List.of("workshop_id", id, "workshop_artifacts", String.join(" ", inItem)));
        }
        for (int i = 0; i < values.size(); i += 2) {
            String value = values.get(i + 1);
            if (value == null || value.isBlank() || value.equals("null") || value.contains("\n")
                    || value.contains("\r")) {
                throw new IllegalArgumentException("release field " + values.get(i) + " is missing or invalid");
            }
            out.println(values.get(i) + "=" + value);
        }
        if (!String.valueOf(Toml.string(release, "commit")).matches("[0-9a-f]{40}")) {
            throw new IllegalArgumentException("commit must be a full 40-character hash");
        }
        return OK;
    }

    private static int jarCompare(Args a, PrintStream out) throws IOException, UsageException {
        Path built = Path.of(a.positional(0, "built.jar"));
        Path other = Path.of(a.positional(1, "workshop.jar"));
        List<String> differences = io.github.bitecheckpz.core.JarCompare.differences(built, other);
        if (differences.isEmpty()) {
            out.println("Same files: " + other.getFileName() + " contains exactly what was built");
            return OK;
        }
        differences.forEach(d -> out.println("  " + d));
        return ERROR;
    }

    private static int sourceCheck(Args a, PrintStream out) throws IOException, UsageException {
        Path jar = Path.of(a.positional(0, "jar"));
        Path src = Path.of(a.positional(1, "source-dir"));
        io.github.bitecheckpz.core.SourceCheck.Result r = io.github.bitecheckpz.core.SourceCheck.check(jar, src);
        out.println(jar + ": " + r.classCount() + " classes, " + r.sourceFiles() + " source files");
        if (r.ok()) {
            out.println("  every class has a matching declaration in the source");
        }
        r.classesWithoutSource().forEach(c -> out.println("  NO SOURCE: " + c));
        r.otherEntries().forEach(e -> out.println("  other file in jar: " + e));
        return r.ok() ? OK : 15;
    }

    // ---- argument parsing ----

    static final class UsageException extends Exception {
        private static final long serialVersionUID = 1L;

        UsageException(String message) {
            super(message);
        }
    }

    static final class Args {
        final String command;
        final List<String> positionals = new ArrayList<>();
        final Map<String, List<String>> options = new HashMap<>();

        private Args(String command) {
            this.command = command;
        }

        static Args parse(String[] argv) throws UsageException {
            Args a = new Args(argv[0]);
            for (int i = 1; i < argv.length; i++) {
                String arg = argv[i];
                if (arg.startsWith("--")) {
                    String name = arg.substring(2);
                    if (i + 1 >= argv.length) {
                        throw new UsageException("option --" + name + " needs a value");
                    }
                    a.options.computeIfAbsent(name, k -> new ArrayList<>()).add(argv[++i]);
                } else {
                    a.positionals.add(arg);
                }
            }
            return a;
        }

        String positional(int index, String name) throws UsageException {
            if (index >= positionals.size()) {
                throw new UsageException("missing <" + name + ">");
            }
            return positionals.get(index);
        }

        Optional<String> option(String name) {
            List<String> values = options.get(name);
            return values == null ? Optional.empty() : Optional.of(values.get(values.size() - 1));
        }

        String requiredOption(String name) throws UsageException {
            return option(name).orElseThrow(() -> new UsageException("missing --" + name));
        }

        List<String> options(String name) {
            return options.getOrDefault(name, List.of());
        }
    }
}
