package io.github.bitecheckpz.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Dependency-free tests for the core library. Run with {@code ./gradlew check}.
 */
public final class SelfTest {

    private static int passed;
    private static final List<String> failures = new ArrayList<>();

    private SelfTest() {
    }

    public static void main(String[] args) throws Exception {
        run("toml: parses the supported subset", SelfTest::tomlParses);
        run("toml: rejects unsupported syntax", SelfTest::tomlRejects);
        run("signatures: round trip, tampering and wrong key", SelfTest::signatures);
        run("verifier: fully approved release is VERIFIED", () -> {
            Fixture f = Fixture.create();
            expect(f.verify().status(), Verifier.Status.VERIFIED);
        });
        run("verifier: unknown jar is UNVERIFIED", () -> {
            Fixture f = Fixture.create();
            Path other = f.dir.resolve("other.jar");
            Files.writeString(other, "something else");
            expect(new Verifier(f.registry(), f.registryKey).verify(other).status(), Verifier.Status.UNVERIFIED);
        });
        run("verifier: one approval is not enough", () -> {
            Fixture f = Fixture.create();
            Files.delete(f.reviewFile("carol"));
            expect(f.verify().status(), Verifier.Status.UNVERIFIED);
        });
        run("verifier: two approvals without a trusted reviewer are not enough", () -> {
            Fixture f = Fixture.create();
            Files.writeString(f.dir.resolve("keys/trust.toml"),
                    "[reviewers.bob]\nlevel = \"reviewer\"\n[reviewers.carol]\nlevel = \"reviewer\"\n");
            expect(f.verify().status(), Verifier.Status.UNVERIFIED);
        });
        run("verifier: changed release file is INVALID", () -> {
            Fixture f = Fixture.create();
            Files.writeString(f.releaseFile, "\n# sneaky change\n", java.nio.file.StandardOpenOption.APPEND);
            expect(f.verify().status(), Verifier.Status.INVALID);
        });
        run("verifier: build record not signed by the registry is INVALID", () -> {
            Fixture f = Fixture.create();
            Signatures.sign(f.buildFile, f.author); // author pretends to be the registry
            expect(f.verify().status(), Verifier.Status.INVALID);
        });
        run("verifier: review signed with the wrong key is not counted", () -> {
            Fixture f = Fixture.create();
            Signatures.sign(f.reviewFile("carol"), f.bob);
            expect(f.verify().status(), Verifier.Status.UNVERIFIED);
        });
        run("verifier: author cannot approve their own mod", () -> {
            Fixture f = Fixture.create();
            Files.delete(f.reviewFile("carol"));
            Files.delete(Signatures.signaturePath(f.reviewFile("carol")));
            f.addReview("alice", f.author, "approve");
            expect(f.verify().status(), Verifier.Status.UNVERIFIED);
        });
        run("verifier: open critical flag is FLAGGED", () -> {
            Fixture f = Fixture.create();
            f.addFlag("open", "critical");
            expect(f.verify().status(), Verifier.Status.FLAGGED);
        });
        run("verifier: open minor flag keeps it VERIFIED", () -> {
            Fixture f = Fixture.create();
            f.addFlag("open", "info");
            expect(f.verify().status(), Verifier.Status.VERIFIED);
        });
        run("verifier: confirmed flag is BLOCKED", () -> {
            Fixture f = Fixture.create();
            f.addFlag("confirmed", "critical");
            expect(f.verify().status(), Verifier.Status.BLOCKED);
        });
        run("verifier: block list entry is BLOCKED", () -> {
            Fixture f = Fixture.create();
            Files.writeString(f.dir.resolve("registry/blocklist.toml"),
                    "[blocked]\n" + Hashing.sha256Hex(f.jar) + " = \"steals tokens\"\n");
            expect(f.verify().status(), Verifier.Status.BLOCKED);
        });
        run("verifier: example entries are never VERIFIED", () -> {
            Fixture f = Fixture.create();
            Files.writeString(f.modDir.resolve("mod.toml"), "\nexample = true\n",
                    java.nio.file.StandardOpenOption.APPEND);
            expect(f.verify().status(), Verifier.Status.UNVERIFIED);
        });

        System.out.println();
        System.out.println(passed + " passed, " + failures.size() + " failed");
        if (!failures.isEmpty()) {
            failures.forEach(f -> System.out.println("FAILED: " + f));
            System.exit(1);
        }
    }

    // ---- tests ----

    private static void tomlParses() throws Exception {
        Map<String, Object> doc = Toml.parse("""
                # comment
                name = "a \\"quoted\\" # not a comment"  # comment
                count = 1_000
                negative = -5
                flag = true
                list = ["x", "y", ]
                [build]
                jdk = "temurin-21"
                [reviewers.bob]
                level = "trusted"
                """, "test");
        expect(Toml.string(doc, "name"), "a \"quoted\" # not a comment");
        expect(doc.get("count"), 1000L);
        expect(doc.get("negative"), -5L);
        expect(doc.get("flag"), true);
        expect(doc.get("list"), List.of("x", "y"));
        expect(Toml.string(Toml.table(doc, "build"), "jdk"), "temurin-21");
        expect(Toml.string(Toml.table(doc, "reviewers.bob"), "level"), "trusted");
    }

    private static void tomlRejects() {
        for (String bad : List.of(
                "a = 1.5", "a = 2026-01-01", "a = 'literal'", "a = \"\"\"x\"\"\"", "[[arr]]",
                "a.b = 1", "a = {x = 1}", "a = 1\na = 2", "[t]\n[t]", "a = [1,", "just text", "a = \"open")) {
            try {
                Toml.parse(bad, "test");
                throw new AssertionError("accepted: " + bad);
            } catch (Toml.TomlException expected) {
                // ok
            }
        }
    }

    private static void signatures() throws Exception {
        Path dir = Files.createTempDirectory("bcz-sig");
        Path file = dir.resolve("data.toml");
        Files.writeString(file, "x = 1\n");
        KeyPair pair = Keys.generate();
        Keys.PrivateKeyInfo priv = new Keys.PrivateKeyInfo(pair.getPrivate(), Keys.keyId(pair.getPublic()));
        Keys.PublicKeyInfo pub = new Keys.PublicKeyInfo(pair.getPublic(), Keys.keyId(pair.getPublic()), "");

        expect(Signatures.verify(file, pub), Signatures.Check.MISSING);
        Signatures.sign(file, priv);
        expect(Signatures.verify(file, pub), Signatures.Check.VALID);

        KeyPair other = Keys.generate();
        expect(Signatures.verify(file, new Keys.PublicKeyInfo(other.getPublic(), Keys.keyId(other.getPublic()), "")),
                Signatures.Check.WRONG_KEY);

        Files.writeString(file, "x = 2\n");
        expect(Signatures.verify(file, pub), Signatures.Check.INVALID);

        // Key files survive a round trip.
        Path keyFile = dir.resolve("k.key");
        Keys.writePrivate(keyFile, pair.getPrivate(), priv.id());
        Path pubFile = dir.resolve("k.pub");
        Files.writeString(pubFile, Keys.encodePublic(pair.getPublic(), "k"));
        Files.writeString(file, "x = 3\n");
        Signatures.sign(file, Keys.readPrivate(keyFile));
        expect(Signatures.verify(file, Keys.readPublic(pubFile)), Signatures.Check.VALID);
        deleteTree(dir);
    }

    // ---- fixture: a small registry with one fully approved release ----

    private static final class Fixture {
        Path dir;
        Path modDir;
        Path releaseFile;
        Path buildFile;
        Path jar;
        Keys.PublicKeyInfo registryKey;
        Keys.PrivateKeyInfo registry;
        Keys.PrivateKeyInfo author;
        Keys.PrivateKeyInfo bob;
        Keys.PrivateKeyInfo carol;

        static Fixture create() throws Exception {
            Fixture f = new Fixture();
            f.dir = Files.createTempDirectory("bcz-registry");
            Files.createDirectories(f.dir.resolve("keys/authors"));
            Files.createDirectories(f.dir.resolve("keys/reviewers"));
            f.registry = f.key("keys/registry.pub", "registry");
            f.registryKey = Keys.readPublic(f.dir.resolve("keys/registry.pub"));
            f.author = f.key("keys/authors/alice.pub", "alice");
            f.bob = f.key("keys/reviewers/bob.pub", "bob");
            f.carol = f.key("keys/reviewers/carol.pub", "carol");
            Files.writeString(f.dir.resolve("keys/trust.toml"),
                    "[reviewers.bob]\nlevel = \"trusted\"\n[reviewers.carol]\nlevel = \"reviewer\"\n"
                            + "[reviewers.alice]\nlevel = \"trusted\"\n");

            f.modDir = f.dir.resolve("registry/mods/better-guns");
            Files.createDirectories(f.modDir.resolve("releases"));
            Files.writeString(f.modDir.resolve("mod.toml"), """
                    id = "better-guns"
                    name = "Better Guns"
                    author = "alice"
                    source = "https://example.invalid/better-guns"
                    license = "MIT"
                    status = "active"
                    """);
            f.releaseFile = f.modDir.resolve("releases/1.0.0.toml");
            Files.writeString(f.releaseFile, """
                    mod = "better-guns"
                    version = "1.0.0"
                    commit = "0123456789abcdef0123456789abcdef01234567"
                    """);
            Signatures.sign(f.releaseFile, f.author);

            f.jar = f.dir.resolve("better-guns-1.0.0.jar");
            Files.write(f.jar, "pretend this is a jar".getBytes(StandardCharsets.UTF_8));
            f.buildFile = Records.writeBuildRecord(f.releaseFile, f.jar, List.of("runner-a", "runner-b"));
            Signatures.sign(f.buildFile, f.registry);

            f.addReview("bob", f.bob, "approve");
            f.addReview("carol", f.carol, "approve");
            return f;
        }

        Keys.PrivateKeyInfo key(String pubPath, String name) throws IOException {
            KeyPair pair = Keys.generate();
            Files.writeString(dir.resolve(pubPath), Keys.encodePublic(pair.getPublic(), name));
            return new Keys.PrivateKeyInfo(pair.getPrivate(), Keys.keyId(pair.getPublic()));
        }

        Path reviewFile(String reviewer) {
            return modDir.resolve("reviews/1.0.0/" + reviewer + ".toml");
        }

        void addReview(String reviewer, Keys.PrivateKeyInfo key, String verdict) throws Exception {
            Path file = Records.writeReview(releaseFile, jar, reviewer, verdict, "full", "looks fine");
            Signatures.sign(file, key);
        }

        void addFlag(String status, String severity) throws IOException {
            Files.createDirectories(modDir.resolve("flags"));
            Files.writeString(modDir.resolve("flags/f1.toml"),
                    "mod = \"better-guns\"\nversion = \"1.0.0\"\njar_sha256 = \"" + Hashing.sha256Hex(jar)
                            + "\"\nseverity = \"" + severity + "\"\nstatus = \"" + status
                            + "\"\ndescription = \"test flag\"\n");
        }

        Registry registry() {
            return new Registry(dir);
        }

        Verifier.Result verify() throws IOException {
            return new Verifier(registry(), registryKey).verify(jar);
        }
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
            failures.add(name + ": " + t);
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
                Files.delete(p);
            }
        }
    }
}
