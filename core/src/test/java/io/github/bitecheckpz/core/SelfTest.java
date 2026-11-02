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

        run("json: round trip keeps big numbers, order and unknown fields", SelfTest::jsonRoundTrip);
        run("json: rejects broken input", SelfTest::jsonRejects);
        run("scanner: clean class has no findings", () -> {
            Scanner.Report r = Scanner.scan(jarOf("CleanMod"));
            expect(r.findings().size(), 0);
            expect(r.classCount(), 1);
        });
        run("scanner: process start is hard, URL and strings are soft", () -> {
            Scanner.Report r = Scanner.scan(jarOf("ShadyMod"));
            expect(r.hasHardFindings(), true);
            expect(r.findings().stream().anyMatch(f -> f.rule().equals("process")), true);
            expect(r.findings().stream().anyMatch(f -> f.rule().equals("network")), true);
            expect(r.findings().stream().anyMatch(f -> f.rule().equals("strings")), true);
        });
        run("scanner: native library inside the jar is hard", () -> {
            Path jar = Files.createTempFile("bcz-native", ".jar");
            try (var zip = new java.util.zip.ZipOutputStream(Files.newOutputStream(jar))) {
                zip.putNextEntry(new java.util.zip.ZipEntry("natives/evil.dll"));
                zip.write(new byte[] {1, 2, 3});
                zip.closeEntry();
            }
            expect(Scanner.scan(jar).hasHardFindings(), true);
        });
        run("index: generated index matches the verifier and round trips", () -> {
            Fixture f = Fixture.create();
            Files.writeString(f.dir.resolve("registry/blocklist.toml"), "[blocked]\n" + "ab".repeat(32) + " = \"bad\"\n");
            String text = Index.generate(f.registry(), new Verifier(f.registry(), f.registryKey),
                    java.time.Instant.parse("2026-11-01T10:00:00Z"), "abc");
            Path file = f.dir.resolve("index.toml");
            Files.writeString(file, text);
            Index index = Index.parse(file);
            expect(index.lookup(Hashing.sha256Hex(f.jar)).status(), Verifier.Status.VERIFIED);
            expect(index.lookup("ab".repeat(32)).status(), Verifier.Status.BLOCKED);
            expect(index.lookup("cd".repeat(32)).status(), Verifier.Status.UNVERIFIED);
            expect(index.isStale(java.time.Instant.parse("2026-11-05T10:00:00Z")), false);
            expect(index.isStale(java.time.Instant.parse("2026-11-09T10:00:00Z")), true);
        });
        run("index: flagged release is listed as flagged", () -> {
            Fixture f = Fixture.create();
            f.addFlag("open", "critical");
            Path file = f.dir.resolve("index.toml");
            Files.writeString(file, Index.generate(f.registry(), new Verifier(f.registry(), f.registryKey),
                    java.time.Instant.now(), ""));
            expect(Index.parse(file).lookup(Hashing.sha256Hex(f.jar)).status(), Verifier.Status.FLAGGED);
        });
        run("site: lists mods with jar status, reviews and flags; escapes HTML", () -> {
            Fixture f = Fixture.create();
            java.util.List<Site.ModRow> mods = Site.collect(f.registry(), java.util.Optional.of(f.registryKey));
            expect(mods.size(), 1);
            expect(mods.get(0).badge(), "verified");
            String html = Site.render(mods, java.time.Instant.now(), "https://example.invalid/repo", true);
            expect(html.contains(Hashing.sha256Hex(f.jar)), true);
            expect(html.contains(">bob<"), true);
            f.addFlag("open", "critical");
            Files.writeString(f.modDir.resolve("mod.toml"), Files.readString(f.modDir.resolve("mod.toml"))
                    .replace("Better Guns", "Better <script>Guns"));
            mods = Site.collect(f.registry(), java.util.Optional.of(f.registryKey));
            expect(mods.get(0).badge(), "flagged");
            html = Site.render(mods, java.time.Instant.now(), "https://example.invalid/repo", true);
            expect(html.contains("Better &lt;script&gt;Guns"), true);
            expect(html.contains("<script>Guns"), false);
            // without the registry key nothing is shown as verified
            expect(Site.collect(f.registry(), java.util.Optional.empty()).get(0).badge(), "in review");
        });
        run("lint: valid registry has no problems", () -> {
            Fixture f = Fixture.create();
            expect(Lint.check(f.registry(), java.util.Optional.of(f.registryKey)), List.of());
        });
        run("lint: finds unsigned release, bad review and bad names", () -> {
            Fixture f = Fixture.create();
            Files.delete(Signatures.signaturePath(f.releaseFile));
            Files.writeString(f.reviewFile("carol"), "\nverdict2 = \"x\"\n", java.nio.file.StandardOpenOption.APPEND);
            Files.createDirectories(f.dir.resolve("registry/mods/Bad_Name"));
            List<String> problems = Lint.check(f.registry(), java.util.Optional.of(f.registryKey));
            expect(problems.stream().anyMatch(p -> p.contains("1.0.0.toml: publisher signature")), true);
            expect(problems.stream().anyMatch(p -> p.contains("carol.toml: reviewer signature")), true);
            expect(problems.stream().anyMatch(p -> p.contains("Bad_Name")), true);
        });

        run("published: multi-jar release reviewed as a whole is VERIFIED for every jar", () -> {
            Fixture f = Fixture.create();
            Published p = Published.add(f, false);
            for (Path jar : p.jars) {
                Verifier.Result r = new Verifier(f.registry(), f.registryKey).verify(jar);
                expect(r.status(), Verifier.Status.VERIFIED);
                expect(r.method(), "published");
            }
            expect(Lint.check(f.registry(), java.util.Optional.of(f.registryKey)), List.of());
            Path index = f.dir.resolve("index.toml");
            Files.writeString(index, Index.generate(f.registry(), new Verifier(f.registry(), f.registryKey),
                    java.time.Instant.now(), ""));
            expect(Index.parse(index).lookup(Hashing.sha256Hex(p.jars.get(1))).label().contains("published binary"),
                    true);
        });
        run("published: publisher signs, author is just a name; publisher cannot review", () -> {
            Fixture f = Fixture.create();
            Published p = Published.add(f, false);
            Files.delete(p.dir.resolve("reviews/2.0.0/carol.toml"));
            Path selfReview = Records.writeReview(p.release, null, "alice", "approve", "full", "mine");
            Signatures.sign(selfReview, f.author);
            expect(new Verifier(f.registry(), f.registryKey).verify(p.jars.get(0)).status(),
                    Verifier.Status.UNVERIFIED);
        });
        run("policy: publisher_may_review lets a publisher review an upstream mod, never their own", () -> {
            Fixture f = Fixture.create();
            Published p = Published.add(f, false);
            Files.delete(p.dir.resolve("reviews/2.0.0/bob.toml"));
            Files.delete(p.dir.resolve("reviews/2.0.0/carol.toml"));
            // alice publishes upstream-fps and is also a trusted reviewer
            Files.copy(f.dir.resolve("keys/authors/alice.pub"), f.dir.resolve("keys/reviewers/alice.pub"));
            Files.writeString(f.dir.resolve("keys/trust.toml"), "[reviewers.alice]\nlevel = \"trusted\"\n",
                    java.nio.file.StandardOpenOption.APPEND);
            Path review = Records.writeReview(p.release, null, "alice", "approve", "full", "upstream checked");
            Signatures.sign(review, f.author);
            Path policy = f.dir.resolve("registry/policy.toml");
            Files.writeString(policy, "required_approvals = 1\nrequired_trusted = 1\n");
            Verifier verifier = new Verifier(f.registry(), f.registryKey);
            expect(verifier.verify(p.jars.get(0)).status(), Verifier.Status.UNVERIFIED);
            expect(Lint.check(f.registry(), java.util.Optional.of(f.registryKey)).stream()
                    .anyMatch(e -> e.contains("alice.toml: the publisher cannot review")), true);
            Files.writeString(policy, "required_approvals = 1\nrequired_trusted = 1\npublisher_may_review = true\n");
            expect(verifier.verify(p.jars.get(0)).status(), Verifier.Status.VERIFIED);
            expect(Lint.check(f.registry(), java.util.Optional.of(f.registryKey)), List.of());
            // better-guns is alice's own mod: her review never counts
            Files.delete(f.reviewFile("bob"));
            Files.delete(f.reviewFile("carol"));
            f.addReview("alice", f.author, "approve");
            expect(f.verify().status(), Verifier.Status.UNVERIFIED);
            expect(Lint.check(f.registry(), java.util.Optional.of(f.registryKey)).stream()
                    .anyMatch(e -> e.contains("better-guns/reviews/1.0.0/alice.toml: the publisher cannot review")),
                    true);
            Files.writeString(policy, "publisher_may_review = \"yes\"\n");
            expect(Lint.check(f.registry(), java.util.Optional.of(f.registryKey)).stream()
                    .anyMatch(e -> e.contains("publisher_may_review must be true or false")), true);
        });
        run("diff: a diff review counts only against the verified previous release", () -> {
            Fixture f = Fixture.create();
            Files.writeString(f.dir.resolve("registry/policy.toml"), "required_approvals = 1\nrequired_trusted = 1\n");
            Path release2 = f.modDir.resolve("releases/1.1.0.toml");
            Files.writeString(release2, Files.readString(f.releaseFile).replace("1.0.0", "1.1.0")
                    .replace("0123456789abcdef0123456789abcdef01234567", "1111111111111111111111111111111111111111"));
            Signatures.sign(release2, f.author);
            Path jar2 = f.dir.resolve("better-guns-1.1.0.jar");
            Files.writeString(jar2, "version two");
            Signatures.sign(Records.writeBuildRecord(release2, jar2, List.of("a", "b")), f.registry);
            Path diff = Records.writeReview(release2, jar2, "bob", "approve", "diff", "only the changes");
            Signatures.sign(diff, f.bob);
            expect(Toml.string(Toml.parse(diff), "base_version"), "1.0.0");
            expect(Toml.string(Toml.parse(diff), "base_commit"), "0123456789abcdef0123456789abcdef01234567");
            Verifier verifier = new Verifier(f.registry(), f.registryKey);
            expect(verifier.verify(jar2).status(), Verifier.Status.VERIFIED);
            expect(Lint.check(f.registry(), java.util.Optional.of(f.registryKey)), List.of());
            f.addFlag("open", "critical"); // 1.0.0 is no longer verified
            Verifier.Result r = verifier.verify(jar2);
            expect(r.status(), Verifier.Status.UNVERIFIED);
            expect(r.messages().stream().anyMatch(m -> m.contains("1.0.0 is not verified")), true);
            boolean refused = false;
            try {
                Records.writeReview(f.releaseFile, f.jar, "dave", "approve", "diff", "");
            } catch (IllegalArgumentException e) {
                refused = true; // the first release has nothing to diff against
            }
            expect(refused, true);
        });
        run("revoked: nothing signed by a revoked key counts", () -> {
            Fixture f = Fixture.create();
            String bob = Keys.readPublic(f.dir.resolve("keys/reviewers/bob.pub")).id();
            Path revoked = f.dir.resolve("keys/revoked.toml");
            Files.writeString(revoked, "[revoked." + bob + "]\nname = \"bob\"\nreason = \"key leaked\"\n");
            Verifier.Result r = f.verify();
            expect(r.status(), Verifier.Status.UNVERIFIED);
            expect(r.messages().stream().anyMatch(m -> m.contains("revoked: key leaked")), true);
            expect(Lint.check(f.registry(), java.util.Optional.of(f.registryKey)).stream()
                    .anyMatch(e -> e.contains("key of 'bob' revoked: key leaked")), true);
            String alice = Keys.readPublic(f.dir.resolve("keys/authors/alice.pub")).id();
            Files.writeString(revoked, "[revoked." + alice + "]\nreason = \"lost\"\n",
                    java.nio.file.StandardOpenOption.APPEND);
            expect(f.verify().status(), Verifier.Status.INVALID);
            Files.writeString(revoked, "[revoked.alice]\nreason = \"by name\"\n");
            expect(Lint.check(f.registry(), java.util.Optional.of(f.registryKey)).stream()
                    .anyMatch(e -> e.contains("16-character key id")), true);
        });
        run("flags: a flag without jar_sha256 covers every jar of its version", () -> {
            Fixture f = Fixture.create();
            Files.createDirectories(f.modDir.resolve("flags"));
            Files.writeString(f.modDir.resolve("flags/v.toml"), "mod = \"better-guns\"\nversion = \"1.0.0\"\n"
                    + "severity = \"critical\"\nstatus = \"open\"\ndescription = \"whole release\"\n");
            expect(f.verify().status(), Verifier.Status.FLAGGED);
            expect(Lint.check(f.registry(), java.util.Optional.of(f.registryKey)), List.of());
        });
        run("accounts: GitHub accounts need a key and a valid user name", () -> {
            Fixture f = Fixture.create();
            Files.writeString(f.dir.resolve("keys/accounts.toml"),
                    "[accounts.alice]\ngithub = \"Alice-GH\"\n[accounts.zed]\ngithub = \"not valid\"\n");
            expect(f.registry().githubAccount("alice"), java.util.Optional.of("Alice-GH"));
            List<String> problems = Lint.check(f.registry(), java.util.Optional.of(f.registryKey));
            expect(problems.stream().anyMatch(p -> p.contains("zed: no key")), true);
            expect(problems.stream().anyMatch(p -> p.contains("zed: github must be")), true);
            expect(problems.stream().anyMatch(p -> p.contains("alice")), false);
        });
        run("keys: the public key is derived from the private key", () -> {
            java.security.KeyPair pair = Keys.generate();
            expect(Keys.keyId(Keys.publicKey(pair.getPrivate())), Keys.keyId(pair.getPublic()));
        });
        run("versions: compared part by part, numbers numerically", () -> {
            expect(Registry.compareVersions("1.10", "1.9") > 0, true);
            expect(Registry.compareVersions("2026.10.04", "2026.9.30") > 0, true);
            expect(Registry.compareVersions("1.0", "1.0") == 0, true);
        });
        run("policy: bootstrap policy with one trusted approval", () -> {
            Fixture f = Fixture.create();
            Files.delete(f.reviewFile("carol"));
            expect(f.verify().status(), Verifier.Status.UNVERIFIED);
            Files.writeString(f.dir.resolve("registry/policy.toml"), "required_approvals = 1\nrequired_trusted = 1\n");
            expect(f.verify().status(), Verifier.Status.VERIFIED);
            Files.delete(f.reviewFile("bob"));
            Path carol = Records.writeReview(f.releaseFile, f.jar, "carol", "approve", "full", "x");
            Signatures.sign(carol, f.carol);
            expect(f.verify().status(), Verifier.Status.UNVERIFIED); // carol is not trusted
        });
        run("pending: unsigned pending entry passes lint and is never VERIFIED", () -> {
            Fixture f = Fixture.create();
            Published p = Published.add(f, true);
            expect(Lint.check(f.registry(), java.util.Optional.of(f.registryKey)), List.of());
            expect(new Verifier(f.registry(), f.registryKey).verify(p.jars.get(0)).status(),
                    Verifier.Status.UNVERIFIED);
        });
        run("source-check: classes match their sources; extra class is reported", () -> {
            Path src = Files.createTempDirectory("bcz-src");
            Files.writeString(src.resolve("A.java"), "package x.y;\n// class Fake {}\npublic class A {\n"
                    + "  class Inner {}\n  Runnable r = new Runnable() { public void run() {} };\n}\n");
            Path jar = Files.createTempFile("bcz-sc", ".jar");
            try (var zip = new java.util.zip.ZipOutputStream(Files.newOutputStream(jar))) {
                for (String e : List.of("x/y/A.class", "x/y/A$Inner.class", "x/y/A$1.class", "META-INF/MANIFEST.MF")) {
                    zip.putNextEntry(new java.util.zip.ZipEntry(e));
                    zip.closeEntry();
                }
            }
            SourceCheck.Result ok = SourceCheck.check(jar, src);
            expect(ok.ok(), true);
            expect(ok.classCount(), 3);
            try (var zip = new java.util.zip.ZipOutputStream(Files.newOutputStream(jar))) {
                for (String e : List.of("x/y/A.class", "x/y/Fake.class")) {
                    zip.putNextEntry(new java.util.zip.ZipEntry(e));
                    zip.closeEntry();
                }
            }
            expect(SourceCheck.check(jar, src).classesWithoutSource(), List.of("x.y.Fake"));
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

    private static void jsonRoundTrip() throws Exception {
        String input = """
                {
                  "formatVersion": 2,
                  "mods": [
                    {"id": "ZBetterModList", "workshop_id": 3709229404, "jar_hash": "c1",
                     "decision": true, "time": "2026-04-01T12:34:56Z", "author_id": 76561198043849998,
                     "future": {"nested": [1, 2.5, null, "x\\"y"]}}
                  ],
                  "zz_unknown": false
                }""";
        Object parsed = Json.parse(input);
        String written = Json.write(parsed);
        expect(Json.parse(written), parsed);
        expect(written.contains("76561198043849998"), true);
        expect(written.indexOf("formatVersion") < written.indexOf("zz_unknown"), true);
    }

    private static void jsonRejects() {
        for (String bad : List.of("{", "[1,]x", "{\"a\" 1}", "tru", "\"open", "{}{}")) {
            try {
                Json.parse(bad);
                throw new AssertionError("accepted: " + bad);
            } catch (Json.JsonException expected) {
                // ok
            }
        }
    }

    /** Packs one fixture class from the test classpath into a temporary jar. */
    private static Path jarOf(String simpleName) throws IOException {
        String entry = "io/github/bitecheckpz/core/fixtures/" + simpleName + ".class";
        Path jar = Files.createTempFile("bcz-scan", ".jar");
        try (var in = SelfTest.class.getResourceAsStream("/" + entry);
                var zip = new java.util.zip.ZipOutputStream(Files.newOutputStream(jar))) {
            if (in == null) {
                throw new IOException("fixture not found: " + entry);
            }
            zip.putNextEntry(new java.util.zip.ZipEntry(entry));
            in.transferTo(zip);
            zip.closeEntry();
        }
        return jar;
    }

    /** A "published" release with two jars, published by alice for an upstream author. */
    private static final class Published {
        Path dir;
        Path release;
        List<Path> jars = new ArrayList<>();

        static Published add(Fixture f, boolean pending) throws Exception {
            Published p = new Published();
            p.dir = f.dir.resolve("registry/mods/upstream-fps");
            Files.createDirectories(p.dir.resolve("releases"));
            Files.writeString(p.dir.resolve("mod.toml"), "id = \"upstream-fps\"\nname = \"Upstream FPS\"\n"
                    + "author = \"Some Upstream Author\"\npublisher = \"alice\"\n"
                    + "source = \"https://example.invalid/fps\"\nlicense = \"MIT\"\n"
                    + "status = \"" + (pending ? "pending" : "active") + "\"\n");
            p.release = p.dir.resolve("releases/2.0.0.toml");
            Files.writeString(p.release, "mod = \"upstream-fps\"\nversion = \"2.0.0\"\n"
                    + "commit = \"0123456789abcdef0123456789abcdef01234567\"\n[build]\ntype = \"published\"\n"
                    + "artifacts = [\"42/media/java/client/Fps-2x.jar\", \"42/media/java/client/Fps.jar\"]\n");
            for (String name : List.of("fps-2x", "fps")) {
                Path jar = f.dir.resolve(name + ".jar");
                Files.writeString(jar, "jar " + name);
                p.jars.add(jar);
            }
            if (pending) {
                return p;
            }
            Signatures.sign(p.release, f.author);
            Path build = Records.writeBuildRecord(p.release, p.jars, List.of("a", "b"));
            Signatures.sign(build, f.registry);
            for (var reviewer : List.of(Map.entry("bob", f.bob), Map.entry("carol", f.carol))) {
                Path review = Records.writeReview(p.release, null, reviewer.getKey(), "approve", "full", "ok");
                Signatures.sign(review, reviewer.getValue());
            }
            return p;
        }
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
                    "[reviewers.bob]\nlevel = \"trusted\"\n[reviewers.carol]\nlevel = \"reviewer\"\n");

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
                    [build]
                    jdk = "temurin-21"
                    command = "./gradlew jar"
                    artifact = "build/libs/better-guns.jar"
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
