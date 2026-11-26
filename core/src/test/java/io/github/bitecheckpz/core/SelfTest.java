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
        run("verifier: a suspended or withdrawn mod's releases are not VERIFIED", () -> {
            for (String status : List.of("suspended", "withdrawn")) {
                Fixture f = Fixture.create();
                expect(f.verify().status(), Verifier.Status.VERIFIED);
                Path modToml = f.modDir.resolve("mod.toml");
                Files.writeString(modToml, Files.readString(modToml).replace("status = \"active\"", "status = \"" + status + "\""));
                Verifier.Result r = f.verify();
                expect(r.status(), Verifier.Status.UNVERIFIED);
                expect(r.messages().stream().anyMatch(m -> m.contains(status)), true);
            }
        });
        run("verifier: hard findings need a signed exception from a trusted reviewer", () -> {
            Fixture f = Fixture.create();
            // CI recorded a hard finding in the build record it signs
            Files.writeString(f.buildFile, Files.readString(f.buildFile) + "hard_findings = [\"process\", \"unsafe\"]\n");
            Files.deleteIfExists(Signatures.signaturePath(f.buildFile));
            Signatures.sign(f.buildFile, f.registry);
            // the reviews covered the old build record: write them again for this one
            for (var reviewer : List.of(Map.entry("bob", f.bob), Map.entry("carol", f.carol))) {
                Files.delete(f.reviewFile(reviewer.getKey()));
                Files.deleteIfExists(Signatures.signaturePath(f.reviewFile(reviewer.getKey())));
                Path review = Records.writeReview(f.releaseFile, null, reviewer.getKey(), "approve", "full", "ok");
                Signatures.sign(review, reviewer.getValue());
            }
            Verifier.Result r = f.verify();
            expect(r.status(), Verifier.Status.UNVERIFIED);
            expect(r.messages().stream().anyMatch(m -> m.contains("need an exception from a trusted reviewer")), true);
            // carol is no trusted reviewer: her exception does not count
            Path carols = Records.writeException(f.releaseFile, "carol", List.of("unsafe", "process"), "agent");
            Signatures.sign(carols, f.carol);
            expect(f.verify().status(), Verifier.Status.UNVERIFIED);
            // bob accepts only one of the two rules: not enough
            Path bobs = Records.writeException(f.releaseFile, "bob", List.of("unsafe"), "a mod manager needs it");
            Signatures.sign(bobs, f.bob);
            expect(f.verify().status(), Verifier.Status.UNVERIFIED);
            Files.delete(bobs);
            Files.delete(Signatures.signaturePath(bobs));
            bobs = Records.writeException(f.releaseFile, "bob", List.of("process", "unsafe"), "a mod manager needs it");
            Signatures.sign(bobs, f.bob);
            r = f.verify();
            expect(r.status(), Verifier.Status.VERIFIED);
            expect(r.messages().stream().anyMatch(m -> m.contains("accepted by 'bob': a mod manager needs it")), true);
            // the index keeps it visible
            String index = Index.generate(f.registry(), new Verifier(f.registry(), f.registryKey), java.time.Instant.now(), "c");
            expect(index.contains("hard findings (process, unsafe) accepted by 'bob'"), true);
            // a changed exception (rules widened afterwards) breaks its signature
            Files.writeString(bobs, Files.readString(bobs).replace("reason = ", "rules2 = [\"x\"]\nreason = "));
            expect(f.verify().status(), Verifier.Status.UNVERIFIED);
        });
        run("keys: an author changes keys with the old one; earlier releases stay verified", () -> {
            Fixture f = Fixture.create();
            Path authors = f.dir.resolve("keys/authors");
            Path pub = authors.resolve("alice.pub");
            String oldId = Keys.readPublic(pub).id();
            Files.createDirectories(authors.resolve("alice.previous"));
            Files.move(pub, authors.resolve("alice.previous/" + oldId + ".pub"));
            java.security.KeyPair pair = Keys.generate();
            Files.writeString(pub, Keys.encodePublic(pair.getPublic(), "alice"));
            Signatures.sign(pub, f.author); // the old key vouches for the new one
            // without the list of what the old key signed, it counts for nothing any more
            expect(f.verify().status(), Verifier.Status.INVALID);
            Path list = authors.resolve("alice.previous/" + oldId + ".releases");
            Files.writeString(list, Hashing.sha256Hex(f.releaseFile) + "\n");
            Signatures.sign(list, f.author);
            expect(Lint.check(f.registry(), java.util.Optional.of(f.registryKey)), List.of());
            expect(f.verify().status(), Verifier.Status.VERIFIED); // 1.0.0 was signed with the old key
            expect(f.registry().authorKeys("alice").size(), 2);
            // a new release signed with the stolen old key does not count
            Path release2 = f.modDir.resolve("releases/2.0.0.toml");
            Files.writeString(release2, Files.readString(f.releaseFile).replace("1.0.0", "2.0.0"));
            Signatures.sign(release2, f.author);
            expect(f.registry().authorKeysFor("alice", release2).size(), 1);
            expect(Lint.check(f.registry(), java.util.Optional.of(f.registryKey)).stream()
                    .anyMatch(e -> e.contains("releases/2.0.0.toml: publisher signature")), true);
            Files.delete(release2);
            Files.delete(Signatures.signaturePath(release2));
            // a revoked old key cannot vouch for a new one
            Files.writeString(f.dir.resolve("keys/revoked.toml"), "[revoked." + oldId + "]\nreason = \"stolen\"\n");
            expect(Lint.check(f.registry(), java.util.Optional.of(f.registryKey)).stream()
                    .anyMatch(e -> e.contains("must be signed by a previous key")), true);
            Files.delete(f.dir.resolve("keys/revoked.toml"));
            // a key change someone else signed is a lint error
            Files.delete(Signatures.signaturePath(pub));
            Signatures.sign(pub, f.bob);
            expect(Lint.check(f.registry(), java.util.Optional.of(f.registryKey)).stream()
                    .anyMatch(e -> e.contains("must be signed by a previous key")), true);
        });
        run("index: reviewers, open info flags and review notes on closed-source jars", () -> {
            Fixture f = Fixture.create();
            Files.createDirectories(f.modDir.resolve("flags"));
            Files.writeString(f.modDir.resolve("flags/f1.toml"), "mod = \"better-guns\"\nversion = \"1.0.0\"\n"
                    + "severity = \"info\"\nstatus = \"open\"\ndescription = \"writes a big log file\"\nreporter = \"carol\"\n"
                    + "reported_at = \"2026-11-01T10:00:00Z\"\n");
            String closed = "cd".repeat(32);
            Path note = ReviewNotes.write(f.registry(), closed, "Closed 1.0 (Closed.jar)", "carol", "no network, no native code");
            Signatures.sign(note, f.carol);
            Path bad = ReviewNotes.write(f.registry(), "ef".repeat(32), "", "carol", "signed by someone else");
            Signatures.sign(bad, f.bob);
            String text = Index.generate(f.registry(), new Verifier(f.registry(), f.registryKey), java.time.Instant.now(), "c");
            Path file = f.dir.resolve("index.toml");
            Files.writeString(file, text);
            Index index = Index.parse(file);
            String sha = Hashing.sha256Hex(f.jar);
            expect(index.reviewers(sha), List.of("bob", "carol"));
            expect(index.note(sha), "open info flag: writes a big log file");
            expect(index.note(closed), "review note by 'carol': no network, no native code");
            expect(index.note("ef".repeat(32)) == null, true); // a note signed by someone else does not count
            expect(Lint.check(f.registry(), java.util.Optional.of(f.registryKey)).stream()
                    .anyMatch(e -> e.contains("registry/notes/" + "ef".repeat(32))), true);
        });
        run("reviews: independence, newcomers, team, demotion after malware", () -> {
            Fixture f = Fixture.create(); // bob (trusted) and carol (reviewer) approved; policy default: 2 incl. 1 trusted
            expect(f.verify().status(), Verifier.Status.VERIFIED);
            Path trust = f.dir.resolve("keys/trust.toml");
            String levels = Files.readString(trust);
            // invited by the same person: one voice
            Files.writeString(trust, levels.replace("level = \"trusted\"\n", "level = \"trusted\"\ninvited_by = \"dave\"\n")
                    .replace("level = \"reviewer\"\n", "level = \"reviewer\"\ninvited_by = \"dave\"\n"));
            Verifier.Result r = f.verify();
            expect(r.status(), Verifier.Status.UNVERIFIED);
            expect(r.messages().stream().anyMatch(m -> m.contains("not independent")), true);
            Files.writeString(trust, levels);
            // a newcomer counts only when the policy says so
            Files.writeString(trust, levels.replace("level = \"reviewer\"", "level = \"new\""));
            expect(f.verify().status(), Verifier.Status.UNVERIFIED);
            Files.writeString(f.dir.resolve("registry/policy.toml"), "newcomers_count = true\n");
            expect(f.verify().status(), Verifier.Status.VERIFIED);
            Files.writeString(trust, levels);
            // a member of the mod's team does not count
            Path modToml = f.modDir.resolve("mod.toml");
            String mod = Files.readString(modToml);
            Files.writeString(modToml, mod + "team = [\"carol\"]\n");
            expect(f.verify().status(), Verifier.Status.UNVERIFIED);
            Files.writeString(modToml, mod);
            expect(f.verify().status(), Verifier.Status.VERIFIED);
            // carol approved another mod's release that is confirmed malware: she is demoted everywhere
            Path other = f.dir.resolve("registry/mods/evil/reviews/1.0.0");
            Files.createDirectories(other);
            Files.writeString(other.resolve("carol.toml"), "mod = \"evil\"\nversion = \"1.0.0\"\nverdict = \"approve\"\n");
            Files.createDirectories(f.dir.resolve("registry/mods/evil/flags"));
            Files.writeString(f.dir.resolve("registry/mods/evil/flags/f.toml"), "mod = \"evil\"\nversion = \"1.0.0\"\n"
                    + "severity = \"critical\"\nstatus = \"confirmed\"\ndescription = \"steals tokens\"\n");
            Files.writeString(f.dir.resolve("registry/mods/evil/mod.toml"), "id = \"evil\"\nname = \"Evil\"\nauthor = \"alice\"\n");
            expect(f.registry().reviewerLevel("carol"), "demoted");
            expect(f.verify().status(), Verifier.Status.INVALID); // alice signed evil herself: her key counts as revoked
            expect(f.registry().revocation("authors", "alice").orElse("").contains("confirmed malware"), true);
        });
        run("lint: no symbolic links in the registry", () -> {
            Fixture f = Fixture.create();
            Files.createDirectories(f.modDir.resolve("flags"));
            Path link = f.modDir.resolve("flags/linked.toml");
            try {
                Files.createSymbolicLink(link, f.reviewFile("bob"));
            } catch (UnsupportedOperationException | IOException e) {
                return; // this system does not let us create links (Windows without the right); CI on Linux does
            }
            expect(Lint.check(f.registry(), java.util.Optional.of(f.registryKey)).stream()
                    .anyMatch(e -> e.contains("symbolic links are not allowed")), true);
        });
        run("flags: a reporter whose flags were abuse no longer warns anyone", () -> {
            Fixture f = Fixture.create();
            Files.createDirectories(f.modDir.resolve("flags"));
            String flag = "mod = \"better-guns\"\nversion = \"1.0.0\"\nseverity = \"critical\"\ndescription = \"x\"\nreported_by = \"mallory\"\n";
            Files.writeString(f.modDir.resolve("flags/a.toml"), flag + "status = \"open\"\n");
            expect(f.verify().status(), Verifier.Status.FLAGGED);
            Files.writeString(f.modDir.resolve("flags/b.toml"), flag + "status = \"abuse\"\n");
            Files.writeString(f.modDir.resolve("flags/c.toml"), flag + "status = \"abuse\"\n");
            Verifier.Result r = f.verify();
            expect(r.status(), Verifier.Status.VERIFIED);
            expect(r.messages().stream().anyMatch(m -> m.contains("were found to be abuse")), true);
        });
        run("compat: version ranges and what does not match", () -> {
            expect(Compat.isValid(">=42.13, <43"), true);
            expect(Compat.isValid("42.13"), true);
            expect(Compat.isValid("~42"), false);
            expect(Compat.satisfies("42.22.2", ">=42.13, <43"), true);
            expect(Compat.satisfies("42.12.9", ">=42.13"), false);
            expect(Compat.satisfies("43.0", ">=42.13, <43"), false);
            expect(Compat.satisfies("42.13", "42.13.0"), true);
            expect(Compat.satisfies("3.0.0-beta3", ">=2.3"), true);
            expect(Compat.satisfies("2.2.9", ">=2.3"), false);
            expect(Compat.mismatches("game >=42.13; zombiebuddy >=2.3", "42.12.1", "3.0.0-beta3"),
                    List.of("Project Zomboid >=42.13 (installed 42.12.1)"));
            expect(Compat.mismatches("game >=42.13", null, null), List.of()); // unknown: nothing to say
        });
        run("compat: a release's [compat] goes into the index for its verified jars", () -> {
            Fixture f = Fixture.create();
            // the author signs the release with [compat]; the build record names the release's hash
            Files.writeString(f.releaseFile, Files.readString(f.releaseFile) + "\n[compat]\nproject_zomboid = \">=42.13, <43\"\nzombiebuddy = \">=2.3\"\n");
            Files.deleteIfExists(Signatures.signaturePath(f.releaseFile));
            Signatures.sign(f.releaseFile, f.author);
            Files.writeString(f.buildFile, Files.readString(f.buildFile).replaceAll("(?m)^release_sha256 = .*$",
                    "release_sha256 = \"" + Hashing.sha256Hex(f.releaseFile) + "\""));
            Files.deleteIfExists(Signatures.signaturePath(f.buildFile));
            Signatures.sign(f.buildFile, f.registry);
            for (var reviewer : List.of(Map.entry("bob", f.bob), Map.entry("carol", f.carol))) {
                Files.delete(f.reviewFile(reviewer.getKey()));
                Files.deleteIfExists(Signatures.signaturePath(f.reviewFile(reviewer.getKey())));
                Path review = Records.writeReview(f.releaseFile, null, reviewer.getKey(), "approve", "full", "ok");
                Signatures.sign(review, reviewer.getValue());
            }
            expect(f.verify().status(), Verifier.Status.VERIFIED);
            expect(Lint.check(f.registry(), java.util.Optional.of(f.registryKey)), List.of());
            String text = Index.generate(f.registry(), new Verifier(f.registry(), f.registryKey), java.time.Instant.now(), "c");
            expect(text.contains("[compat]"), true);
            Path file = f.dir.resolve("index.toml");
            Files.writeString(file, text);
            Index index = Index.parse(file);
            expect(index.lookup(Hashing.sha256Hex(f.jar)).compat(), "game >=42.13,<43; zombiebuddy >=2.3");
            // a broken range is a lint error
            Files.writeString(f.releaseFile, Files.readString(f.releaseFile).replace(">=2.3", "~2"));
            expect(Lint.check(f.registry(), java.util.Optional.of(f.registryKey)).stream()
                    .anyMatch(e -> e.contains("[compat] zombiebuddy")), true);
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
        run("rebuilds: a volunteer's identical rebuild shows in the index", () -> {
            Fixture f = Fixture.create();
            Path dir = Files.createDirectories(f.modDir.resolve("rebuilds/1.0.0"));
            Path record = dir.resolve("carol.toml");
            Files.writeString(record, "mod = \"better-guns\"\nversion = \"1.0.0\"\nbuild_sha256 = \""
                    + Hashing.sha256Hex(f.buildFile) + "\"\njar_sha256 = \"" + Hashing.sha256Hex(f.jar)
                    + "\"\nrebuilder = \"carol\"\n");
            Signatures.sign(record, f.carol);
            expect(Rebuilds.of(f.registry(), "better-guns", "1.0.0"), List.of("carol"));
            expect(Lint.check(f.registry(), java.util.Optional.of(f.registryKey)), List.of());
            String index = Index.generate(f.registry(), new Verifier(f.registry(), f.registryKey), java.time.Instant.now(), "c");
            expect(index.contains("rebuilt independently by carol"), true);
            // a record for another jar does not count, and lint says why
            Files.writeString(record, Files.readString(record).replace(Hashing.sha256Hex(f.jar), "ab".repeat(32)));
            expect(Rebuilds.of(f.registry(), "better-guns", "1.0.0"), List.of());
            expect(Lint.check(f.registry(), java.util.Optional.of(f.registryKey)).stream()
                    .anyMatch(e -> e.contains("not one of the build record's jars")), true);
        });
        run("dependencies: bundled libraries from Maven metadata, and the OSV query", () -> {
            Path jar = Files.createTempFile("bcz-deps", ".jar");
            try (var zip = new java.util.zip.ZipOutputStream(Files.newOutputStream(jar))) {
                zip.putNextEntry(new java.util.zip.ZipEntry("META-INF/maven/com.google.code.gson/gson/pom.properties"));
                zip.write("groupId=com.google.code.gson\nartifactId=gson\nversion=2.8.5\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                zip.closeEntry();
                zip.putNextEntry(new java.util.zip.ZipEntry("a/B.class"));
                zip.write(1);
                zip.closeEntry();
            }
            List<Dependencies.Library> libs = Dependencies.bundled(jar);
            expect(libs, List.of(new Dependencies.Library("com.google.code.gson", "gson", "2.8.5")));
            Object query = Json.parse(Dependencies.osvQuery(libs));
            expect(String.valueOf(query).contains("com.google.code.gson:gson"), true);
            expect(String.valueOf(query).contains("2.8.5"), true);
        });
        run("scanner: an own class loader and opening other programs are hard", () -> {
            Scanner.Report loader = Scanner.scan(jarOf("OwnLoaderMod"));
            expect(loader.findings().stream().anyMatch(f -> f.severity() == Scanner.Severity.HARD
                    && f.rule().equals("code-loading") && f.detail().contains("extends java.lang.ClassLoader")), true);
            expect(Scanner.scan(jarOf("DesktopMod")).hardRules().contains("system"), true);
            expect(Scanner.scan(jarOf("CleanMod")).hardRules(), List.of());
        });
        run("scanner: Java that Lua can call, and what Lua reaches through it", () -> {
            Scanner.Report risky = Scanner.scan(jarOf("LuaFileMod"));
            expect(risky.findings().stream().anyMatch(f -> f.rule().equals("lua")
                    && f.detail().contains("@LuaMethod")), true);
            expect(risky.findings().stream().anyMatch(f -> f.rule().equals("lua-access")
                    && f.detail().contains("accesses files")), true);
            Scanner.Report plain = Scanner.scan(jarOf("LuaPlainMod"));
            expect(plain.findings().stream().map(Scanner.Finding::rule).toList(), List.of("lua", "reflection"));
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
            Index confirmed = index.confirmedAt(java.time.Instant.parse("2026-11-20T10:00:00Z"));
            expect(confirmed.isStale(java.time.Instant.parse("2026-11-25T10:00:00Z")), false);
            expect(confirmed.isStale(java.time.Instant.parse("2026-11-28T10:00:00Z")), true);
            // an older confirmation changes nothing
            expect(confirmed.confirmedAt(java.time.Instant.parse("2026-11-10T10:00:00Z")).confirmedAt(),
                    confirmed.confirmedAt());
            // no confirmation keeps it fresh longer than MAX_CONFIRMED_AGE after it was generated
            Index late = index.confirmedAt(java.time.Instant.parse("2027-03-01T10:00:00Z"));
            expect(late.isStale(java.time.Instant.parse("2026-12-15T09:00:00Z")), false);
            expect(late.isStale(java.time.Instant.parse("2026-12-16T11:00:00Z")), true);
        });
        run("timestamp: round trips and rejects bad input", () -> {
            Path file = Files.createTempFile("bcz-ts", ".toml");
            String sha = "ab".repeat(32);
            Files.writeString(file, Timestamp.generate(sha, java.time.Instant.parse("2026-11-07T03:17:42.5Z")));
            Timestamp ts = Timestamp.parse(file);
            expect(ts.indexSha256(), sha);
            expect(ts.issuedAt(), java.time.Instant.parse("2026-11-07T03:17:42Z"));
            Files.writeString(file, "format = 1\nindex_sha256 = \"nope\"\nissued_at = \"2026-11-07T03:17:42Z\"\n");
            expectThrows(() -> Timestamp.parse(file));
            Files.writeString(file, "format = 2\nindex_sha256 = \"" + sha + "\"\nissued_at = \"2026-11-07T03:17:42Z\"\n");
            expectThrows(() -> Timestamp.parse(file));
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
            // a diff review of an update that changed 45 % of the code does not count
            Files.delete(diff);
            Files.delete(Signatures.signaturePath(diff));
            Path big = Records.writeReview(release2, jar2, "bob", "approve", "diff", "only the changes", 45);
            Signatures.sign(big, f.bob);
            Verifier.Result tooBig = new Verifier(f.registry(), f.registryKey).verify(jar2);
            expect(tooBig.status(), Verifier.Status.UNVERIFIED);
            expect(tooBig.messages().stream().anyMatch(m -> m.contains("45 % of the code changed")), true);
            Files.delete(big);
            Files.delete(Signatures.signaturePath(big));
            Signatures.sign(Records.writeReview(release2, jar2, "bob", "approve", "diff", "only the changes", 12), f.bob);
            verifier = new Verifier(f.registry(), f.registryKey);
            expect(verifier.verify(jar2).status(), Verifier.Status.VERIFIED);
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
        run("diff: the same jar in two releases does not send diff reviews in a circle", () -> {
            Fixture f = Fixture.create();
            Files.writeString(f.dir.resolve("registry/policy.toml"), "required_approvals = 1\nrequired_trusted = 1\n");
            Path jar = f.dir.resolve("same.jar");
            Files.writeString(jar, "unchanged jar");
            for (String v : List.of("1.1", "1.2")) {
                Path release = f.modDir.resolve("releases/" + v + ".toml");
                Files.writeString(release, Files.readString(f.releaseFile).replace("1.0.0", v));
                Signatures.sign(release, f.author);
                Signatures.sign(Records.writeBuildRecord(release, jar, List.of("a", "b")), f.registry);
                Signatures.sign(Records.writeReview(release, jar, "bob", "approve", "diff", "same jar"), f.bob);
            }
            // Must finish; 1.1 is verified through 1.0, so the jar is verified.
            expect(new Verifier(f.registry(), f.registryKey).verify(jar).status(), Verifier.Status.VERIFIED);
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
        run("mod layout: version folder, mod.info in it or in common/, jars named in javaJarFile", () -> {
            Path repo = Files.createTempDirectory("bcz-layout");
            String zed = "42/media/java/shared/Z-2x.jar";          // mod.info in common/, as ZombieBuddy's author does it
            String old = "41/media/java/shared/Z-2x.jar";          // a Build 41 jar next to it: ignored
            String nested = "Contents/mods/R/42.13/media/java/R.jar";
            String onlyCommon = "C/common/media/java/C.jar";       // no version folder at all
            String noModInfo = "E/42/media/java/E.jar";
            String unnamed = "42/media/java/shared/Old.jar";
            for (String jar : List.of(zed, old, nested, onlyCommon, noModInfo, unnamed)) {
                Files.createDirectories(repo.resolve(jar).getParent());
                Files.writeString(repo.resolve(jar), "jar");
            }
            Files.createDirectories(repo.resolve("common"));
            Files.writeString(repo.resolve("common/mod.info"), "name=Z\njavaJarFile=media/java/shared/Z-2x.jar\n");
            Files.writeString(repo.resolve("Contents/mods/R/42.13/mod.info"), "javaJarFile1 = /media/java/R.jar\n");
            Files.writeString(repo.resolve("C/mod.info"), "javaJarFile=media/java/C.jar\n");
            Files.writeString(repo.resolve("C/common/mod.info"), "javaJarFile=media/java/C.jar\n");
            expect(ModLayout.problems(repo, List.of(old, zed, nested)), List.of());
            List<String> bad = ModLayout.problems(repo, List.of(onlyCommon, noModInfo, unnamed, "media/java/X.jar"));
            expect(bad.size(), 4);
            expect(bad.get(0).contains("no version folder C/42/"), true);
            expect(bad.get(1).contains("no mod.info in E/common/ or E/42/"), true);
            expect(bad.get(2).contains("not named in mod.info"), true);
            expect(bad.get(3).contains("not in <version folder>/media/java/"), true);
            expect(ModLayout.problems(repo, List.of(old)), List.of("no jar for Build 42 (only older version folders)"));
            expect(ModLayout.problems(repo, List.of("../42/media/java/$(rm -rf ~).jar")),
                    List.of("an artifact path is not a safe relative path to a .jar"));
        });
        run("jar-compare: same files match, whatever the manifest, timestamps and order", () -> {
            String m = "META-INF/MANIFEST.MF";
            Path built = zipOf(Map.of("a/A.class", "A", "a/B.class", "B",
                    m, "Manifest-Version: 1.0\nCreated-By: Maven JAR Plugin 3.4.1\nBuild-Jdk-Spec: 25\n"), 0L);
            Path same = zipOf(Map.of("a/B.class", "B", "a/A.class", "A",
                    m, "Manifest-Version: 1.0\nCreated-By: Gradle\nBuilt-By: someone\n"), 1_700_000_000_000L);
            Path extra = zipOf(Map.of("a/A.class", "A", "a/B.class", "B", m, "Manifest-Version: 1.0\n",
                    "a/Evil.class", "E"), 0L);
            Path changed = zipOf(Map.of("a/A.class", "A2", "a/B.class", "B", m, "Manifest-Version: 1.0\n"), 0L);
            Path classPath = zipOf(Map.of("a/A.class", "A", "a/B.class", "B",
                    m, "Manifest-Version: 1.0\nClass-Path: ../../evil.jar\n"), 0L);
            Path otherCase = zipOf(Map.of("a/A.class", "A", "a/B.class", "B", m, "Manifest-Version: 1.0\n",
                    "meta-inf/manifest.mf", "Manifest-Version: 1.0\nLauncher-Agent-Class: a.Evil\n"), 0L);
            expect(JarCompare.differences(built, same), List.of());
            expect(JarCompare.differences(built, extra), List.of("a/Evil.class: not built from the source"));
            expect(JarCompare.differences(built, changed), List.of("a/A.class: differs"));
            expect(JarCompare.differences(built, classPath),
                    List.of(m + ": attributes differ (beyond build tool and JDK)"));
            expect(JarCompare.differences(built, otherCase), List.of("meta-inf/manifest.mf: not built from the source"));
            Path sealed = zipOf(Map.of("a/A.class", "A", "a/B.class", "B",
                    m, "Manifest-Version: 1.0\n\nName: a/\nSealed: true\nSHA-256-Digest: abc=\n"), 0L);
            expect(JarCompare.differences(built, sealed), List.of(m + ": attributes differ (beyond build tool and JDK)"));
        });
        run("jar-compare: a real jarsigner signature is left out, a fake one counts", () -> {
            String m = "META-INF/MANIFEST.MF";
            Path built = zipOf(Map.of("a/A.class", "A", "a/B.class", "B", m, "Manifest-Version: 1.0\n"), 0L);
            Path signed = signed(built);
            expect(JarCompare.validSignature(signed), true);
            expect(JarCompare.differences(built, signed), List.of());
            // something hidden under a signature file name: the signature no longer counts as one
            Path smuggled = withEntries(signed, Map.of("META-INF/PAYLOAD.SF", "not a signature"));
            expect(JarCompare.validSignature(smuggled), false);
            expect(JarCompare.differences(built, smuggled).contains("META-INF/PAYLOAD.SF: not built from the source"),
                    true);
            // a signed file changed after signing: Java's check fails, so the signature counts too
            Path tampered = withEntries(signed, Map.of("a/A.class", "A2"));
            expect(JarCompare.validSignature(tampered), false);
            List<String> d = JarCompare.differences(built, tampered);
            expect(d.contains("a/A.class: differs"), true);
            expect(d.stream().anyMatch(s -> s.startsWith("META-INF/TEST.SF")), true);
            // in the manifest only the real hash of the file is left out; anything else under a digest name counts
            String hash = java.util.Base64.getEncoder().encodeToString(
                    java.security.MessageDigest.getInstance("SHA-256").digest("A".getBytes(StandardCharsets.UTF_8)));
            String payload = java.util.Base64.getEncoder().encodeToString(new byte[32]);
            expect(JarCompare.differences(built, zipOf(Map.of("a/A.class", "A", "a/B.class", "B",
                    m, "Manifest-Version: 1.0\n\nName: a/A.class\nSHA-256-Digest: " + hash + "\n"), 0L)), List.of());
            expect(JarCompare.differences(built, zipOf(Map.of("a/A.class", "A", "a/B.class", "B",
                    m, "Manifest-Version: 1.0\n\nName: a/A.class\nSHA-256-Digest: " + payload + "\n"), 0L)),
                    List.of(m + ": attributes differ (beyond build tool and JDK)"));
            expect(JarCompare.differences(built, zipOf(Map.of("a/A.class", "A", "a/B.class", "B",
                    m, "Manifest-Version: 1.0\n\nName: no/such.class\nSHA-256-Digest: " + hash + "\n"), 0L)),
                    List.of(m + ": attributes differ (beyond build tool and JDK)"));
            expect(JarCompare.differences(built, zipOf(Map.of("a/A.class", "A", "a/B.class", "B",
                    m, "Manifest-Version: 1.0\n\nName: a/A.class\nX-Data-Digest: " + hash + "\n"), 0L)),
                    List.of(m + ": attributes differ (beyond build tool and JDK)"));
            // fake signature files that Java cannot verify count like any other file
            Path fake = zipOf(Map.of("a/A.class", "A", "a/B.class", "B", m, "Manifest-Version: 1.0\n",
                    "META-INF/ME_ZED_0.SF", "sig", "META-INF/ME_ZED_0.RSA", "block"), 0L);
            expect(JarCompare.differences(built, fake), List.of("META-INF/ME_ZED_0.RSA: not built from the source",
                    "META-INF/ME_ZED_0.SF: not built from the source"));
        });
        run("workshop release: lint, build record of the Workshop jar, VERIFIED", () -> {
            Fixture f = Fixture.create();
            Path dir = f.dir.resolve("registry/mods/stairs");
            Files.createDirectories(dir.resolve("releases"));
            Files.writeString(dir.resolve("mod.toml"), "id = \"stairs\"\nname = \"Stairs\"\nauthor = \"Someone\"\n"
                    + "publisher = \"alice\"\nsource = \"https://example.invalid/stairs\"\nlicense = \"MIT\"\n"
                    + "workshop_id = \"3684713089\"\nstatus = \"active\"\n");
            Path release = dir.resolve("releases/1.1.0.toml");
            Files.writeString(release, "mod = \"stairs\"\nversion = \"1.1.0\"\n"
                    + "commit = \"0123456789abcdef0123456789abcdef01234567\"\n[build]\ntype = \"workshop\"\n"
                    + "workshop_id = \"3684713089\"\njdk = \"temurin-25\"\ncommand = \"mvn -B package\"\n"
                    + "artifacts = [\"Contents/mods/Stairs/42.13/media/java/client/stairs.jar\"]\n"
                    + "workshop_artifacts = [\"mods/Stairs/42.13/media/java/client/stairs.jar\"]\n");
            Signatures.sign(release, f.author);
            expect(Lint.check(f.registry(), java.util.Optional.of(f.registryKey)), List.of());
            Path workshopJar = f.dir.resolve("stairs.jar");
            Files.writeString(workshopJar, "jar from the Workshop");
            Path build = Records.writeBuildRecord(release, List.of(workshopJar), List.of("a", "b"));
            Signatures.sign(build, f.registry);
            Map<String, Object> record = Toml.parse(build);
            expect(Toml.string(record, "method"), "workshop");
            expect(String.valueOf(record.get("artifact_paths")), "[mods/Stairs/42.13/media/java/client/stairs.jar]");
            for (var reviewer : List.of(Map.entry("bob", f.bob), Map.entry("carol", f.carol))) {
                Signatures.sign(Records.writeReview(release, null, reviewer.getKey(), "approve", "full", "ok"),
                        reviewer.getValue());
            }
            Verifier.Result r = new Verifier(f.registry(), f.registryKey).verify(workshopJar);
            expect(r.status(), Verifier.Status.VERIFIED);
            expect(r.method(), "workshop");
            // A workshop release needs the Workshop item and one path in it per artifact.
            Files.writeString(release, Files.readString(release).replace("workshop_artifacts", "other"));
            expect(Lint.check(f.registry(), java.util.Optional.of(f.registryKey)).stream()
                    .anyMatch(p -> p.contains("workshop_artifacts needs one path")), true);
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

    /** A temporary jar with the given files (name -> content), all with the given modification time. */
    private static Path zipOf(Map<String, String> files, long time) throws IOException {
        Path jar = Files.createTempFile("bcz-compare", ".jar");
        try (var zip = new java.util.zip.ZipOutputStream(Files.newOutputStream(jar))) {
            for (var e : files.entrySet()) {
                var entry = new java.util.zip.ZipEntry(e.getKey());
                entry.setTime(time);
                zip.putNextEntry(entry);
                zip.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return jar;
    }

    /** Signs a copy of the jar with a new key (keytool from this JDK, then the JDK's JarSigner API). */
    private static Path signed(Path jar) throws Exception {
        Path dir = Files.createTempDirectory("bcz-sign");
        Path store = dir.resolve("test.p12");
        char[] pass = "changeit".toCharArray();
        Path keytool = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win") ? "keytool.exe" : "keytool");
        Process p = new ProcessBuilder(keytool.toString(), "-genkeypair", "-keystore", store.toString(),
                "-storetype", "PKCS12", "-storepass", "changeit", "-keypass", "changeit", "-alias", "test",
                "-keyalg", "EC", "-groupname", "secp256r1", "-dname", "CN=Test", "-validity", "2")
                .redirectErrorStream(true).start();
        String log = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (p.waitFor() != 0) {
            throw new IOException("keytool failed: " + log);
        }
        java.security.KeyStore ks = java.security.KeyStore.getInstance(store.toFile(), pass);
        var key = (java.security.KeyStore.PrivateKeyEntry) ks.getEntry("test",
                new java.security.KeyStore.PasswordProtection(pass));
        var signer = new jdk.security.jarsigner.JarSigner.Builder(key).signerName("TEST").build();
        Path out = dir.resolve("signed.jar");
        try (var zip = new java.util.zip.ZipFile(jar.toFile()); var os = Files.newOutputStream(out)) {
            signer.sign(zip, os);
        }
        return out;
    }

    /** A copy of the jar with some entries replaced or added. */
    private static Path withEntries(Path jar, Map<String, String> replace) throws IOException {
        Path out = Files.createTempFile("bcz-changed", ".jar");
        try (var zip = new java.util.zip.ZipFile(jar.toFile());
                var os = new java.util.zip.ZipOutputStream(Files.newOutputStream(out))) {
            for (var e = zip.entries(); e.hasMoreElements(); ) {
                var entry = e.nextElement();
                os.putNextEntry(new java.util.zip.ZipEntry(entry.getName()));
                if (replace.containsKey(entry.getName())) {
                    os.write(replace.get(entry.getName()).getBytes(StandardCharsets.UTF_8));
                } else {
                    try (var in = zip.getInputStream(entry)) {
                        in.transferTo(os);
                    }
                }
                os.closeEntry();
            }
            for (var r : replace.entrySet()) {
                if (zip.getEntry(r.getKey()) == null) {
                    os.putNextEntry(new java.util.zip.ZipEntry(r.getKey()));
                    os.write(r.getValue().getBytes(StandardCharsets.UTF_8));
                    os.closeEntry();
                }
            }
        }
        return out;
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

    private static void expectThrows(Body body) {
        try {
            body.run();
        } catch (Exception expected) {
            return;
        }
        throw new AssertionError("expected an exception");
    }

    private static void deleteTree(Path dir) throws IOException {
        try (Stream<Path> paths = Files.walk(dir)) {
            for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
    }
}
