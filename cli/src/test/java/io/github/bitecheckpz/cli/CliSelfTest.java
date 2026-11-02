package io.github.bitecheckpz.cli;

import io.github.bitecheckpz.core.Hashing;
import io.github.bitecheckpz.core.Keys;
import io.github.bitecheckpz.core.Records;
import io.github.bitecheckpz.core.Registry;
import io.github.bitecheckpz.core.Signatures;
import io.github.bitecheckpz.core.Toml;
import io.github.bitecheckpz.core.Verifier;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Dependency-free tests for the bcz commands that publishers, reviewers and maintainers use.
 * They run against a throwaway registry with a local bare repository as its remote, so they need
 * git but no network. Run with {@code ./gradlew check}.
 */
public final class CliSelfTest {

    private static int passed;
    private static final List<String> failures = new ArrayList<>();
    private static final String JAR = "42/media/java/client/M.jar";

    private CliSelfTest() {
    }

    public static void main(String[] args) throws Exception {
        Path tmp = Files.createTempDirectory("bcz-cli");
        String home = System.getProperty("user.home");
        System.setProperty("user.home", tmp.resolve("home").toString());
        try {
            World w = World.create(tmp);
            run("publish: signs the release, activates the entry and pushes", () -> {
                expect(bcz(w, "publish", "m", "1.0"), Main.OK);
                expect(bcz(w, "publish", "m", "1.1"), Main.OK);
                expect(Toml.string(Toml.parse(w.repo.resolve("registry/mods/m/mod.toml")), "status"), "active");
                expect(git(w.remote, "log", "--oneline", "main").contains("Publish m 1.1"), true);
                w.ciWritesBuildRecords();
            });
            run("approve: a diff review needs a verified previous release", () -> {
                expect(bcz(w, "approve", "m", "1.1", "--scope", "diff"), Main.ERROR);
                expect(lastOutput.contains("1.0 is not verified"), true);
            });
            run("approve: compares the jars with the build record, signs and pushes", () -> {
                expect(bcz(w, "approve", "m", "1.0"), Main.OK);
                expect(w.status("jar v1"), Verifier.Status.VERIFIED);
                expect(git(w.remote, "log", "--oneline", "main").contains("Review m 1.0: approve"), true);
            });
            run("approve: diff review of the next release counts", () -> {
                expect(bcz(w, "approve", "m", "1.1", "--scope", "diff"), Main.OK);
                expect(Toml.string(Toml.parse(w.repo.resolve("registry/mods/m/reviews/1.1/pub.toml")),
                        "base_version"), "1.0");
                expect(w.status("jar v2"), Verifier.Status.VERIFIED);
            });
            run("flag: a critical flag warns players, and diff reviews based on it stop counting", () -> {
                expect(bcz(w, "flag", "m", "1.0", "--severity", "critical", "--notes", "steals saves"), Main.OK);
                expect(w.status("jar v1"), Verifier.Status.FLAGGED);
                expect(w.status("jar v2"), Verifier.Status.UNVERIFIED);
                expect(bcz(w, "flag", "m", "--severity", "bad", "--notes", "x"), Main.USAGE);
            });
            run("trust and reviewers: set a level and list the reviewers", () -> {
                expect(bcz(w, "trust", "rev", "reviewer"), Main.OK);
                expect(bcz(w, "trust", "rev"), Main.OK);
                expect(lastOutput.strip(), "rev: reviewer");
                expect(bcz(w, "trust", "nobody", "trusted"), Main.ERROR);
                expect(bcz(w, "reviewers"), Main.OK);
                expect(lastOutput.contains("pub") && lastOutput.contains("2 reviews"), true);
                expect(git(w.remote, "log", "--oneline", "main").contains("Trust: rev is reviewer"), true);
            });
            run("submitter-check: changes must belong to the pull request's author", () -> {
                String base = git(w.repo, "rev-parse", "HEAD");
                Files.writeString(w.repo.resolve("registry/mods/m/flags/extra.toml"), "x");
                Files.writeString(w.repo.resolve("registry/mods/m/releases/1.1.toml"), "changed\n",
                        java.nio.file.StandardOpenOption.APPEND);
                commit(w, "pr");
                expect(bcz(w, "submitter-check", "--login", "PubGH", "--base", base), Main.OK);
                expect(bcz(w, "submitter-check", "--login", "mallory", "--base", base), Main.ERROR);
                expect(lastOutput.contains("belongs to 'pub' (GitHub PubGH), not to mallory"), true);
                // Not the author's business: reviewer levels, removing a flag, moving someone's review.
                Files.writeString(w.repo.resolve("keys/trust.toml"), "\n", java.nio.file.StandardOpenOption.APPEND);
                Files.delete(w.repo.resolve("registry/mods/m/flags/1.0-1.toml"));
                Files.move(w.repo.resolve("registry/mods/m/reviews/1.0/pub.toml"),
                        w.repo.resolve("registry/mods/m/reviews/1.0/new.toml"));
                commit(w, "more");
                expect(bcz(w, "submitter-check", "--login", "PubGH", "--base", base), Main.ERROR);
                expect(lastOutput.contains("maintainer: keys/trust.toml"), true);
                expect(lastOutput.contains("maintainer: registry/mods/m/flags/1.0-1.toml: changes or removes a flag"),
                        true);
                expect(lastOutput.contains("reviews/1.0/new.toml: 'new' has no GitHub account"), true);
                expect(bcz(w, "submitter-check", "--login", "mallory", "--base", base), Main.ERROR);
                expect(lastOutput.contains("reviews/1.0/pub.toml: belongs to 'pub'"), true);
                git(w.repo, "reset", "-q", "--hard", base);
                // "rev" has a key but no account yet: nobody may claim it in a pull request.
                Files.writeString(w.repo.resolve("keys/accounts.toml"), "[accounts.rev]\ngithub = \"mallory\"\n",
                        java.nio.file.StandardOpenOption.APPEND);
                commit(w, "claim");
                expect(bcz(w, "submitter-check", "--login", "mallory", "--base", base), Main.ERROR);
                expect(lastOutput.contains("links or changes the account of existing name 'rev'"), true);
                // A new author may link their own new key.
                git(w.repo, "reset", "-q", "--hard", base);
                Files.copy(w.repo.resolve("keys/reviewers/rev.pub"), w.repo.resolve("keys/authors/newbie.pub"));
                Files.writeString(w.repo.resolve("keys/accounts.toml"), "[accounts.newbie]\ngithub = \"Newbie\"\n",
                        java.nio.file.StandardOpenOption.APPEND);
                commit(w, "new author");
                expect(bcz(w, "submitter-check", "--login", "newbie", "--base", base), Main.OK);
                git(w.repo, "reset", "-q", "--hard", base);
            });
        } finally {
            System.setProperty("user.home", home);
            deleteTree(tmp);
        }
        System.out.println();
        System.out.println(passed + " passed, " + failures.size() + " failed");
        if (!failures.isEmpty()) {
            failures.forEach(f -> System.out.println("FAILED: " + f));
            System.exit(1);
        }
    }

    private static String lastOutput = "";

    private static int bcz(World w, String... args) {
        List<String> all = new ArrayList<>(List.of(args));
        all.addAll(List.of("--registry", w.repo.toString()));
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        PrintStream print = new PrintStream(buffer, true, StandardCharsets.UTF_8);
        int code = Main.run(all.toArray(String[]::new), print, print);
        lastOutput = buffer.toString(StandardCharsets.UTF_8);
        return code;
    }

    private static String git(Path dir, String... args) throws Exception {
        return Exec.git(dir, args);
    }

    /** A registry with one upstream mod "m" (releases 1.0 and 1.1) published and reviewed by "pub". */
    private static final class World {
        Path remote;
        Path repo;
        Path ci;
        Path source;
        Keys.PrivateKeyInfo registryKey;
        Keys.PublicKeyInfo registryPub;

        static World create(Path tmp) throws Exception {
            World w = new World();
            KeyPair registry = Keys.generate();
            KeyPair pub = Keys.generate();
            KeyPair rev = Keys.generate();
            w.registryKey = new Keys.PrivateKeyInfo(registry.getPrivate(), Keys.keyId(registry.getPublic()));
            Keys.writePrivate(tmp.resolve("home/.bitecheck/keys/pub.key"), pub.getPrivate(), Keys.keyId(pub.getPublic()));

            // The mod's own repository: two commits with a changed jar.
            w.source = Files.createDirectories(tmp.resolve("src"));
            git(w.source, "init", "-q", "-b", "main");
            identity(w.source);
            Files.createDirectories(w.source.resolve(JAR).getParent());
            Files.writeString(w.source.resolve(JAR), "jar v1");
            git(w.source, "add", "-A");
            git(w.source, "commit", "-q", "-m", "v1");
            String commit1 = git(w.source, "rev-parse", "HEAD");
            Files.writeString(w.source.resolve(JAR), "jar v2");
            git(w.source, "commit", "-q", "-am", "v2");
            String commit2 = git(w.source, "rev-parse", "HEAD");
            for (String[] v : new String[][] {{"1.0", commit1}, {"1.1", commit2}}) {
                Path review = tmp.resolve("home/.bitecheck/review/m-" + v[0]);
                git(null, "clone", "-q", w.source.toString(), review.toString());
                git(review, "-c", "advice.detachedHead=false", "checkout", "-q", v[1]);
            }

            // The registry, with a bare repository as its GitHub.
            w.remote = tmp.resolve("remote.git");
            git(null, "init", "-q", "--bare", "-b", "main", w.remote.toString());
            w.repo = Files.createDirectories(tmp.resolve("repo"));
            git(w.repo, "init", "-q", "-b", "main");
            identity(w.repo);
            write(w.repo, ".gitattributes", "registry/** text eol=lf\nkeys/** text eol=lf\n");
            write(w.repo, "keys/registry.pub", Keys.encodePublic(registry.getPublic(), "registry"));
            write(w.repo, "keys/authors/pub.pub", Keys.encodePublic(pub.getPublic(), "pub"));
            write(w.repo, "keys/reviewers/pub.pub", Keys.encodePublic(pub.getPublic(), "pub"));
            write(w.repo, "keys/reviewers/rev.pub", Keys.encodePublic(rev.getPublic(), "rev"));
            write(w.repo, "keys/trust.toml", "[reviewers.pub]\nlevel = \"trusted\"\n");
            write(w.repo, "keys/accounts.toml", "[accounts.pub]\ngithub = \"PubGH\"\n");
            write(w.repo, "registry/policy.toml",
                    "required_approvals = 1\nrequired_trusted = 1\npublisher_may_review = true\n");
            write(w.repo, "registry/mods/m/mod.toml", "id = \"m\"\nname = \"M\"\nauthor = \"Upstream Dev\"\n"
                    + "publisher = \"pub\"\nsource = \"https://example.invalid/m\"\nlicense = \"MIT\"\n"
                    + "status = \"pending\"\n");
            for (String[] v : new String[][] {{"1.0", commit1}, {"1.1", commit2}}) {
                write(w.repo, "registry/mods/m/releases/" + v[0] + ".toml", "mod = \"m\"\nversion = \"" + v[0]
                        + "\"\ncommit = \"" + v[1] + "\"\n\n[build]\ntype = \"published\"\nartifacts = [\"" + JAR + "\"]\n");
            }
            git(w.repo, "add", "-A");
            git(w.repo, "commit", "-q", "-m", "init");
            git(w.repo, "remote", "add", "origin", w.remote.toString());
            git(w.repo, "push", "-q", "-u", "origin", "main");
            w.registryPub = Keys.readPublic(w.repo.resolve("keys/registry.pub"));
            return w;
        }

        /** What the Registry workflow does after a publish: build records signed by the registry key. */
        void ciWritesBuildRecords() throws Exception {
            ci = repo.resolveSibling("ci");
            git(null, "clone", "-q", remote.toString(), ci.toString());
            identity(ci);
            Path home = Path.of(System.getProperty("user.home"), ".bitecheck/review");
            for (String v : List.of("1.0", "1.1")) {
                Path record = Records.writeBuildRecord(ci.resolve("registry/mods/m/releases/" + v + ".toml"),
                        List.of(home.resolve("m-" + v).resolve(JAR)), List.of("a", "b"));
                Signatures.sign(record, registryKey);
            }
            git(ci, "add", "-A");
            git(ci, "commit", "-q", "-m", "build records");
            git(ci, "push", "-q");
        }

        Verifier.Status status(String jarContent) throws IOException {
            return new Verifier(new Registry(repo), registryPub)
                    .verifyHash(Hashing.sha256Hex(jarContent.getBytes(StandardCharsets.UTF_8))).status();
        }
    }

    private static void commit(World w, String message) throws Exception {
        git(w.repo, "add", "-A");
        git(w.repo, "commit", "-q", "-m", message);
    }

    private static void identity(Path repo) throws Exception {
        git(repo, "config", "user.name", "test");
        git(repo, "config", "user.email", "test@example.invalid");
    }

    private static void write(Path root, String path, String content) throws IOException {
        Path file = root.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    // ---- mini test harness ----

    private interface Body {
        void run() throws Exception;
    }

    private static void run(String name, Body body) {
        try {
            body.run();
            passed++;
            System.out.println("ok   " + name);
        } catch (Throwable t) {
            failures.add(name + ": " + t + (lastOutput.isBlank() ? "" : "\n     last output: " + lastOutput.strip()));
            System.out.println("FAIL " + name + ": " + t);
        }
    }

    private static void expect(Object actual, Object expected) {
        if (!expected.equals(actual)) {
            throw new AssertionError("expected " + expected + " but got " + actual);
        }
    }

    private static void deleteTree(Path dir) throws IOException {
        try (Stream<Path> paths = Files.walk(dir)) {
            for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) {
                p.toFile().setWritable(true);
                Files.delete(p);
            }
        }
    }
}
