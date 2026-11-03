package io.github.bitecheckpz.launcher;

import io.github.bitecheckpz.core.Hashing;
import io.github.bitecheckpz.core.Json;
import io.github.bitecheckpz.core.Keys;
import io.github.bitecheckpz.core.Signatures;
import io.github.bitecheckpz.core.Verifier;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Dependency-free launcher tests. Run with {@code ./gradlew check}. */
public final class LauncherSelfTest {

    private static int passed;
    private static final List<String> failures = new ArrayList<>();

    private LauncherSelfTest() {
    }

    public static void main(String[] args) {
        run("approvals: keeps other entries and unknown fields", LauncherSelfTest::approvalsRoundTrip);
        run("approvals: refuses an unknown format version", () -> {
            Path dir = Files.createTempDirectory("bc-zb");
            Files.writeString(dir.resolve("mod_approvals.json"), "{\"formatVersion\": 3, \"mods\": []}");
            expectThrows(() -> ZombieBuddyApprovals.load(dir));
        });
        run("approvals: missing file starts empty", () -> {
            Path dir = Files.createTempDirectory("bc-zb");
            ZombieBuddyApprovals a = ZombieBuddyApprovals.load(dir);
            a.set("m", "123", "ab".repeat(32), true);
            a.save();
            Map<?, ?> root = (Map<?, ?>) Json.parse(Files.readString(dir.resolve("mod_approvals.json")));
            expect(((Json.Num) root.get("formatVersion")).text(), "2");
            expect(((List<?>) root.get("mods")).size(), 1);
        });
        run("mods: finds B42 layout, skips Lua-only mods and ZombieBuddy", LauncherSelfTest::discovery);
        run("platform: parses Steam library folders", () -> {
            String vdf = """
                    "libraryfolders"
                    {
                    	"0" { "path"		"C:\\\\Program Files (x86)\\\\Steam" "label" "" }
                    	"1" { "path"		"D:\\\\SteamLibrary" }
                    }
                    """;
            List<Path> libs = Platform.parseLibraryFolders(vdf);
            expect(libs.size(), 2);
            expect(libs.get(1).toString().endsWith("SteamLibrary"), true);
        });
        run("flow: verified allowed, unverified untouched, blocked denied", () -> {
            World w = World.create();
            Session s = w.session();
            expect(status(s, "good.jar"), Verifier.Status.VERIFIED);
            expect(status(s, "unknown.jar"), Verifier.Status.UNVERIFIED);
            expect(status(s, "evil.jar"), Verifier.Status.BLOCKED);
            s.apply();
            ZombieBuddyApprovals a = ZombieBuddyApprovals.load(w.zb);
            expect(a.decision(w.sha("good.jar")), Boolean.TRUE);
            expect(a.decision(w.sha("unknown.jar")), null);
            expect(a.decision(w.sha("evil.jar")), Boolean.FALSE);
            expect(a.decision("ff".repeat(32)), Boolean.TRUE); // the player's own earlier decision survives
        });
        run("flow: Build 41 copies are not listed, but a blocked one is still denied", () -> {
            World w = World.create();
            Path good = w.modsDir.resolve("Good");
            Files.createDirectories(good.resolve("media/java"));
            try (var zip = new java.util.zip.ZipOutputStream(Files.newOutputStream(good.resolve("media/java/old.jar")))) {
                zip.putNextEntry(new java.util.zip.ZipEntry("content.txt"));
                zip.write("unknown b41 copy".getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
            Files.writeString(good.resolve("media/java/bad.jar"), "blocked b41 copy");
            w.publishIndex(Instant.now().plusSeconds(60), Map.of("verified", w.sha("good.jar"),
                    "blocked", Hashing.sha256Hex(good.resolve("media/java/bad.jar"))));
            Session s = w.session();
            expect(s.rows.stream().anyMatch(r -> r.jar().path().endsWith("old.jar")), false);
            expect(status(s, "bad.jar"), Verifier.Status.BLOCKED);
            s.apply();
            expect(ZombieBuddyApprovals.load(w.zb).decision(Hashing.sha256Hex(good.resolve("media/java/bad.jar"))),
                    Boolean.FALSE);
        });
        run("flow: approval is taken back when a mod loses verification", () -> {
            World w = World.create();
            w.session().apply();
            w.publishIndex(Instant.now().plusSeconds(60), Map.of());
            Session s = w.session();
            expect(action(s, "good.jar"), Planner.Action.ASK_IN_GAME);
            s.apply();
            expect(ZombieBuddyApprovals.load(w.zb).decision(w.sha("good.jar")), null);
        });
        run("flow: flagged mod is blocked until the player allows it", () -> {
            World w = World.create();
            w.publishIndex(Instant.now().plusSeconds(60), Map.of("flagged", w.sha("good.jar")));
            Session s = w.session();
            expect(status(s, "good.jar"), Verifier.Status.FLAGGED);
            expect(action(s, "good.jar"), Planner.Action.DENY);
            s.apply();
            s.state.allowedAnyway().put(w.sha("good.jar"), "test");
            Session again = s.replan();
            expect(action(again, "good.jar"), Planner.Action.ALLOW);
            again.apply();
            expect(ZombieBuddyApprovals.load(w.zb).decision(w.sha("good.jar")), Boolean.TRUE);
        });
        run("scan: unverified mod with hard findings is blocked as suspicious", () -> {
            World w = World.create();
            Session s = w.session();
            Planner.Row shady = row(s, "shady.jar");
            expect(shady.status(), Verifier.Status.UNVERIFIED);
            expect(shady.suspicious(), true);
            expect(shady.action(), Planner.Action.DENY);
            expect(row(s, "unknown.jar").suspicious(), false);
            expect(row(s, "unknown.jar").info().contains("local scan: no findings"), true);
            s.apply();
            expect(ZombieBuddyApprovals.load(w.zb).decision(w.sha("shady.jar")), Boolean.FALSE);
        });
        run("scan: player can allow a suspicious mod anyway", () -> {
            World w = World.create();
            Session s = w.session();
            s.state.allowedAnyway().put(w.sha("shady.jar"), "test");
            Session again = s.replan();
            expect(action(again, "shady.jar"), Planner.Action.ALLOW);
            again.apply();
            expect(ZombieBuddyApprovals.load(w.zb).decision(w.sha("shady.jar")), Boolean.TRUE);
        });
        run("scan: verified mods are not blocked by the local scan", () -> {
            World w = World.create();
            w.publishIndex(Instant.now().plusSeconds(60), Map.of("verified", w.sha("shady.jar")));
            Session s = w.session();
            expect(status(s, "shady.jar"), Verifier.Status.VERIFIED);
            expect(action(s, "shady.jar"), Planner.Action.ALLOW);
        });
        run("index: older index is rejected (rollback)", () -> {
            World w = World.create();
            w.session(); // accepts the current index
            w.publishIndex(Instant.now().minus(2, ChronoUnit.DAYS), Map.of("verified", w.sha("unknown.jar")));
            Session s = w.session();
            expect(status(s, "unknown.jar"), Verifier.Status.UNVERIFIED);
            expect(s.index.fromCache(), true);
        });
        run("index: wrong signature is rejected", () -> {
            World w = World.create();
            KeyPair other = Keys.generate();
            Signatures.sign(w.indexFile, new Keys.PrivateKeyInfo(other.getPrivate(), Keys.keyId(other.getPublic())));
            Session s = w.session();
            expect(s.index.index().isPresent(), false);
            expect(status(s, "good.jar"), Verifier.Status.UNVERIFIED);
        });
        run("index: stale index no longer approves but still blocks", () -> {
            World w = World.create();
            w.publishIndex(Instant.now().minus(10, ChronoUnit.DAYS), Map.of("verified", w.sha("good.jar"),
                    "blocked", w.sha("evil.jar")));
            Session s = w.session();
            expect(status(s, "good.jar"), Verifier.Status.UNVERIFIED);
            expect(status(s, "evil.jar"), Verifier.Status.BLOCKED);
        });
        run("steam: reads Project Zomboid's launch options from localconfig.vdf", () -> {
            String vdf = """
                    "UserLocalConfigStore" { "Software" { "Valve" { "Steam" { "apps" {
                    	"440" { "LaunchOptions"	"-novid" }
                    	"108600" { "LastPlayed" "1" "LaunchOptions"	"-agentlib:zbNative \\"x\\" --" "cloud" { "a" "b" } }
                    } } } } }
                    """;
            expect(SteamSetup.launchOptionsFromVdf(vdf).orElse(null), "-agentlib:zbNative \"x\" --");
            expect(SteamSetup.launchOptionsFromVdf("\"108600\" { \"x\" \"y\" }").isPresent(), false);
        });
        run("steam: launch options keep the player's own options", () -> {
            Path script = Path.of("/games/BiteCheck/bitecheck.sh");
            String quoted = "\"" + script + "\""; // the path as this OS writes it
            expect(SteamSetup.launchOptions(Platform.Os.LINUX, script, java.util.Optional.empty()),
                    quoted + " --wrap %command% -javaagent:ZombieBuddy.jar --");
            expect(SteamSetup.launchOptions(Platform.Os.WINDOWS, script, java.util.Optional.of("-agentlib:zbNative --")),
                    quoted + " --wrap %command% -agentlib:zbNative --");
            expect(SteamSetup.launchOptions(Platform.Os.LINUX, script, java.util.Optional.of("gamemoderun %command% -x")),
                    "gamemoderun " + quoted + " --wrap %command% -x");
            String done = "\"/x/bitecheck.sh\" --wrap %command% -x";
            expect(SteamSetup.launchOptions(Platform.Os.LINUX, script, java.util.Optional.of(done)), done);
            expect(SteamSetup.isSetUp(java.util.Optional.of(done)), true);
        });
        run("wrap: applies the decisions, runs the game command and passes its exit code", () -> {
            World w = World.create();
            if (Platform.detectOs() == Platform.Os.WINDOWS) {
                return;
            }
            Path marker = w.root.resolve("game-ran");
            int code = LauncherMain.run(new String[] {"--dir", w.biteCheck.toString(), "--wrap", "sh", "-c",
                "touch \"$0\"; exit 7", marker.toString()}, System.out, new java.io.PrintStream(
                        java.io.OutputStream.nullOutputStream()));
            expect(code, 7);
            expect(Files.exists(marker), true);
            ZombieBuddyApprovals a = ZombieBuddyApprovals.load(w.zb);
            expect(a.decision(w.sha("good.jar")), Boolean.TRUE);
            expect(a.decision(w.sha("evil.jar")), Boolean.FALSE);
        });
        run("wrap: the game still starts when BiteCheck fails", () -> {
            World w = World.create();
            if (Platform.detectOs() == Platform.Os.WINDOWS) {
                return;
            }
            Files.writeString(w.biteCheck.resolve("config.toml"), "this is = = not toml\n");
            int code = LauncherMain.run(new String[] {"--dir", w.biteCheck.toString(), "--wrap", "sh", "-c", "exit 0"},
                    System.out, new java.io.PrintStream(java.io.OutputStream.nullOutputStream()));
            expect(code, 0);
        });
        run("server: an unverified mod can be approved ahead of time and revoked", () -> {
            World w = World.create();
            var quiet = new java.io.PrintStream(java.io.OutputStream.nullOutputStream());
            String dir = w.biteCheck.toString();
            expect(LauncherMain.run(new String[] {"--dir", dir, "--allow", w.sha("unknown.jar")}, quiet, quiet), 0);
            expect(ZombieBuddyApprovals.load(w.zb).decision(w.sha("unknown.jar")), Boolean.TRUE);
            expect(LauncherMain.run(new String[] {"--dir", dir, "--sync"}, quiet, quiet), 0);
            expect(ZombieBuddyApprovals.load(w.zb).decision(w.sha("unknown.jar")), Boolean.TRUE);
            expect(LauncherMain.run(new String[] {"--dir", dir, "--revoke", w.sha("unknown.jar")}, quiet, quiet), 0);
            expect(ZombieBuddyApprovals.load(w.zb).decision(w.sha("unknown.jar")), null);
            // suspicious and blocked mods need more than a plain --allow
            expect(LauncherMain.run(new String[] {"--dir", dir, "--allow", w.sha("shady.jar")}, quiet, quiet), 2);
            expect(LauncherMain.run(new String[] {"--dir", dir, "--allow", w.sha("evil.jar"),
                "--i-understand-the-risk"}, quiet, quiet), 1);
        });
        run("server: exported mod list matches itself and reports differences", () -> {
            World w = World.create();
            var quiet = new java.io.PrintStream(java.io.OutputStream.nullOutputStream());
            Path list = w.root.resolve("server-mods.toml");
            String dir = w.biteCheck.toString();
            expect(LauncherMain.run(new String[] {"--dir", dir, "--export", list.toString()}, quiet, quiet), 0);
            expect(LauncherMain.run(new String[] {"--dir", dir, "--compare", list.toString()}, quiet, quiet), 0);
            List<ModList.Item> items = ModList.read(list);
            expect(items.size(), 4);
            expect(items.stream().anyMatch(i -> i.file().equals("42/media/java/good.jar")), true);
            // the player has another version of Good and lacks Evil
            Files.writeString(w.jars.get("good.jar"), "changed");
            Files.delete(w.jars.get("evil.jar"));
            Session s = w.session();
            ModList.Diff diff = ModList.compare(ModList.of(s.rows), items);
            expect(diff.different().size(), 1);
            expect(diff.missing().size(), 1);
            expect(diff.matches(), false);
            expect(LauncherMain.run(new String[] {"--dir", dir, "--compare", list.toString()}, quiet, quiet),
                    LauncherMain.MODS_DIFFER);
        });
        run("server: --server-dir finds mods in a SteamCMD install", () -> {
            World w = World.create();
            Path server = w.root.resolve("pzserver");
            w.mod(server.resolve("steamapps/workshop/content/108600/12345/mods"), "ServerMod", "server.jar", "srv");
            Session s = Session.run(w.biteCheck, Map.of("server_dir", server.toString()));
            Planner.Row r = row(s, "server.jar");
            expect(r.mod().workshopId(), "12345");
            expect(s.platform.gameDir().orElseThrow(), server);
        });

        System.out.println();
        System.out.println(passed + " passed, " + failures.size() + " failed");
        if (!failures.isEmpty()) {
            failures.forEach(f -> System.out.println("FAILED: " + f));
            System.exit(1);
        }
    }

    private static void approvalsRoundTrip() throws Exception {
        Path dir = Files.createTempDirectory("bc-zb");
        Files.writeString(dir.resolve("mod_approvals.json"), """
                {
                  "formatVersion": 2,
                  "mods": [
                    {
                      "id": "ZBetterModList",
                      "workshop_id": 3709229404,
                      "jar_hash": "c180d888eac78369a58dd266e98095ca7e86e16533294ee43ec750e729827064",
                      "decision": true,
                      "time": "2026-04-01T12:34:56Z",
                      "author_id": 76561198043849998,
                      "something_new": [1, 2]
                    }
                  ],
                  "trusted_authors": [76561198043849998]
                }
                """);
        ZombieBuddyApprovals a = ZombieBuddyApprovals.load(dir);
        String sha = "ab".repeat(32);
        a.set("Mine", "42", sha, false);
        a.save();
        String written = Files.readString(dir.resolve("mod_approvals.json"));
        expect(written.contains("\"author_id\": 76561198043849998"), true);
        expect(written.contains("\"trusted_authors\""), true);
        expect(written.contains("\"something_new\""), true);
        expect(written.contains("\"workshop_id\": 42"), true);
        expect(Files.exists(dir.resolve("mod_approvals.json.bitecheck-backup")), true);
        ZombieBuddyApprovals b = ZombieBuddyApprovals.load(dir);
        expect(b.decision(sha), Boolean.FALSE);
        expect(b.decision("c180d888eac78369a58dd266e98095ca7e86e16533294ee43ec750e729827064"), Boolean.TRUE);
        b.remove(sha);
        b.save();
        expect(ZombieBuddyApprovals.load(dir).decision(sha), null);
    }

    private static void discovery() throws Exception {
        Path mods = Files.createTempDirectory("bc-mods");
        Path b42 = mods.resolve("BetterGuns");
        Files.createDirectories(b42.resolve("42/media/java"));
        Files.createDirectories(b42.resolve("common"));
        Files.writeString(b42.resolve("42/mod.info"), "name=Better Guns\nid=BetterGuns\n"
                + "javaJarFile=media/java/BetterGuns.jar\njavaPkgName=com.example.guns\n");
        Files.writeString(b42.resolve("42/media/java/BetterGuns.jar"), "jar");
        Path lua = mods.resolve("LuaOnly");
        Files.createDirectories(lua.resolve("42"));
        Files.writeString(lua.resolve("42/mod.info"), "name=Lua Only\nid=LuaOnly\n");
        Path zb = mods.resolve("ZombieBuddy");
        Files.createDirectories(zb.resolve("42/libs"));
        Files.writeString(zb.resolve("42/mod.info"), "id=ZombieBuddy\njavaJarFile=libs/ZombieBuddy.jar\n"
                + "javaPkgName=me.zed_0xff.zombie_buddy\n");
        Files.writeString(zb.resolve("42/libs/ZombieBuddy.jar"), "zb");

        JavaMods.JavaMod found = JavaMods.inspect(b42, "123", "workshop");
        expect(found.id(), "BetterGuns");
        expect(found.name(), "Better Guns");
        expect(found.jars().size(), 1);
        expect(found.jars().get(0).sha256(), Hashing.sha256Hex("jar".getBytes(StandardCharsets.UTF_8)));
        expect(JavaMods.inspect(lua, null, "local"), null);
        expect(JavaMods.inspect(zb, null, "local"), null);

        // like Staircast: a Build 41 copy in the mod folder itself and in 41/, which Build 42 never loads
        Path both = mods.resolve("Staircast");
        Files.createDirectories(both.resolve("42.13/media/java/client"));
        Files.createDirectories(both.resolve("media/java/client"));
        Files.createDirectories(both.resolve("41/media/java"));
        Files.createDirectories(both.resolve("common/media/java"));
        Files.writeString(both.resolve("mod.info"), "id=Staircast\njavaJarFile=media/java/client/staircast.jar\n");
        Files.writeString(both.resolve("42.13/mod.info"), "id=Staircast\njavaJarFile=media/java/client/staircast.jar\n");
        Files.writeString(both.resolve("media/java/client/staircast.jar"), "b41");
        Files.writeString(both.resolve("41/media/java/old.jar"), "b41 too");
        Files.writeString(both.resolve("42.13/media/java/client/staircast.jar"), "b42");
        Files.writeString(both.resolve("common/media/java/shared.jar"), "shared");
        // every jar is hashed (a blocked one is denied wherever it is); only the Build 42 ones are marked loaded
        List<String> jars = JavaMods.inspect(both, "1", "workshop").jars().stream()
                .map(j -> both.relativize(j.path()).toString().replace('\\', '/') + (j.build42() ? "" : " (41)"))
                .toList();
        expect(jars, List.of("41/media/java/old.jar (41)", "42.13/media/java/client/staircast.jar",
                "common/media/java/shared.jar", "media/java/client/staircast.jar (41)"));

        // no version folder for Build 42: the whole folder, as before
        Path old = mods.resolve("OldOnly");
        Files.createDirectories(old.resolve("media/java"));
        Files.writeString(old.resolve("mod.info"), "id=OldOnly\njavaJarFile=media/java/o.jar\n");
        Files.writeString(old.resolve("media/java/o.jar"), "o");
        expect(JavaMods.inspect(old, null, "local").jars().get(0).build42(), true);
    }

    // ---- a fake computer: Zomboid folder, workshop folder, ZombieBuddy folder, published index ----

    private static final class World {
        Path root;
        Path zb;
        Path biteCheck;
        Path indexFile;
        Path modsDir;
        Keys.PrivateKeyInfo registry;

        static World create() throws Exception {
            World w = new World();
            w.root = Files.createTempDirectory("bc-world");
            w.zb = w.root.resolve("zombie_buddy");
            w.biteCheck = w.root.resolve("bitecheck");
            w.modsDir = w.root.resolve("Zomboid/mods");
            Path workshop = w.root.resolve("workshop/108600");
            Files.createDirectories(w.zb);
            Files.createDirectories(w.biteCheck);
            w.mod(w.modsDir, "Good", "good.jar", "good bytes");
            w.mod(w.modsDir, "Unknown", "unknown.jar", "unknown bytes");
            w.mod(w.modsDir, "Shady", "shady.jar", "shady with native code");
            w.mod(workshop.resolve("999/mods"), "Evil", "evil.jar", "evil bytes");
            // a decision the player made earlier for some other jar
            ZombieBuddyApprovals a = ZombieBuddyApprovals.load(w.zb);
            a.set("Old", null, "ff".repeat(32), true);
            a.save();

            KeyPair pair = Keys.generate();
            w.registry = new Keys.PrivateKeyInfo(pair.getPrivate(), Keys.keyId(pair.getPublic()));
            Path pub = w.root.resolve("registry.pub");
            Files.writeString(pub, Keys.encodePublic(pair.getPublic(), "registry"));
            w.indexFile = w.root.resolve("published/index.toml");
            Files.createDirectories(w.indexFile.getParent());
            Files.writeString(w.biteCheck.resolve("config.toml"), ""
                    + "index_url = " + q(w.indexFile.toUri().toString()) + "\n"
                    + "registry_key = " + q(pub.toString()) + "\n"
                    + "zomboid_dir = " + q(w.root.resolve("Zomboid").toString()) + "\n"
                    + "zombiebuddy_dir = " + q(w.zb.toString()) + "\n"
                    + "workshop_dir = " + q(workshop.toString()) + "\n"
                    + "steam_dir = " + q(w.root.resolve("no-steam").toString()) + "\n");
            w.publishIndex(Instant.now(), Map.of("verified", w.sha("good.jar"), "blocked", w.sha("evil.jar")));
            return w;
        }

        private static String q(String s) {
            return io.github.bitecheckpz.core.Toml.quote(s);
        }

        final Map<String, Path> jars = new java.util.HashMap<>();

        /** Creates a mod folder with a real jar holding one entry (and optionally a native library). */
        void mod(Path parent, String id, String jarName, String content) throws IOException {
            Path dir = parent.resolve(id);
            Files.createDirectories(dir.resolve("42/media/java"));
            Files.writeString(dir.resolve("42/mod.info"), "id=" + id + "\nname=" + id + "\njavaJarFile=media/java/"
                    + jarName + "\njavaPkgName=test." + id.toLowerCase() + "\n");
            Path jar = dir.resolve("42/media/java/" + jarName);
            try (var zip = new java.util.zip.ZipOutputStream(Files.newOutputStream(jar))) {
                zip.putNextEntry(new java.util.zip.ZipEntry("content.txt"));
                zip.write(content.getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
                if (content.contains("native")) {
                    zip.putNextEntry(new java.util.zip.ZipEntry("natives/helper.dll"));
                    zip.write(new byte[] {1, 2, 3});
                    zip.closeEntry();
                }
            }
            jars.put(jarName, jar);
        }

        String sha(String jarName) throws IOException {
            return Hashing.sha256Hex(jars.get(jarName));
        }

        /** Writes and signs an index; sections map: "verified"/"flagged"/"blocked" -> hash. */
        void publishIndex(Instant generatedAt, Map<String, String> sections) throws Exception {
            StringBuilder text = new StringBuilder("format = 1\ngenerated_at = \""
                    + generatedAt.truncatedTo(ChronoUnit.SECONDS) + "\"\nregistry_commit = \"test\"\n");
            for (String section : List.of("verified", "flagged", "blocked")) {
                text.append('[').append(section).append("]\n");
                if (sections.containsKey(section)) {
                    text.append(sections.get(section)).append(" = \"test entry\"\n");
                }
            }
            Files.writeString(indexFile, text.toString());
            Signatures.sign(indexFile, registry);
        }

        Session session() throws IOException {
            Session s = Session.run(biteCheck);
            s.state.save(); // remember the newest index like a real run does
            return s;
        }
    }

    private static Planner.Row row(Session s, String jarName) {
        return s.rows.stream().filter(r -> r.jar().path().getFileName().toString().equals(jarName)).findFirst()
                .orElseThrow(() -> new AssertionError("no row for " + jarName));
    }

    private static Verifier.Status status(Session s, String jarName) {
        return row(s, jarName).status();
    }

    private static Planner.Action action(Session s, String jarName) {
        return row(s, jarName).action();
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
        if (expected == null ? actual != null : !expected.equals(actual)) {
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
}
