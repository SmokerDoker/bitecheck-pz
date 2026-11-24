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
import java.util.Optional;

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
        run("update check: reads the version from the release redirect only", () -> {
            String base = "https://github.com/SmokerDoker/bitecheck-pz/releases/tag/";
            expect(UpdateCheck.versionFromLocation(base + "v0.1.2"), Optional.of("0.1.2"));
            expect(UpdateCheck.versionFromLocation(base + "v0.2.0-rc1"), Optional.empty());
            expect(UpdateCheck.versionFromLocation(base + "v0.1.2\nhttps://evil.example"), Optional.empty());
            expect(UpdateCheck.versionFromLocation(null), Optional.empty());
            expect(UpdateCheck.isNewer("0.1.2", "0.1.1"), true);
            expect(UpdateCheck.isNewer("0.2", "0.1.9"), true);
            expect(UpdateCheck.isNewer("0.1.10", "0.1.9"), true);
            expect(UpdateCheck.isNewer("0.1.1", "0.1.1"), false);
            expect(UpdateCheck.isNewer("0.1.0", "0.1.1"), false);
            expect(UpdateCheck.isNewer("0.2.0", "0.2.0-rc1"), false);
            expect(UpdateCheck.note(Config.empty()), Optional.empty()); // test classes have no version: no request
        });
        run("self update: picks the installer for this system", () -> {
            expect(SelfUpdate.assetName(Platform.Os.WINDOWS, "amd64", "0.1.5", null), Optional.of("BiteCheck-0.1.5-windows-x64.msi"));
            expect(SelfUpdate.assetName(Platform.Os.WINDOWS, "aarch64", "0.1.5", null), Optional.empty());
            expect(SelfUpdate.assetName(Platform.Os.MACOS, "aarch64", "0.1.5", null), Optional.of("BiteCheck-0.1.5-macos-arm64.dmg"));
            expect(SelfUpdate.assetName(Platform.Os.MACOS, "x86_64", "0.1.5", null), Optional.of("BiteCheck-0.1.5-macos-x64.dmg"));
            expect(SelfUpdate.assetName(Platform.Os.LINUX, "amd64", "0.1.5", "rpm"), Optional.of("BiteCheck-0.1.5-linux-x64.rpm"));
            expect(SelfUpdate.assetName(Platform.Os.LINUX, "amd64", "0.1.5", null), Optional.empty());
            expect(SelfUpdate.macBundle("/Applications/BiteCheck.app/Contents/MacOS/BiteCheck").map(Path::toString)
                    .map(p -> p.replace('\\', '/').endsWith("/Applications/BiteCheck.app")), Optional.of(true));
            expect(SelfUpdate.macBundle("/opt/bitecheck/bin/BiteCheck"), Optional.empty());
            String script = SelfUpdate.windowsScript("42", "C:\\Users\\A B\\100%\\x.msi", "C:\\BiteCheck\\BiteCheck.exe", true);
            expect(script.contains("msiexec /i \"C:\\Users\\A B\\100%%\\x.msi\" /quiet"), true);
            expect(script.contains("PID eq 42"), true);
            expect(script.contains("start \"\" \"C:\\BiteCheck\\BiteCheck.exe\""), true);
            expect(SelfUpdate.windowsScript("42", "x.msi", "a.exe", false).contains("start \"\""), false);
        });
        run("self update: installs on its own at most once a day per version", () -> {
            Path dir = Files.createTempDirectory("bc-update");
            Instant now = Instant.now();
            expect(SelfUpdate.autoAllowed(Config.empty(), dir, "0.1.5", now), true);
            Files.writeString(dir.resolve("attempt-0.1.5"), "");
            expect(SelfUpdate.autoAllowed(Config.empty(), dir, "0.1.5", now), false);
            expect(SelfUpdate.autoAllowed(Config.empty(), dir, "0.1.5", now.plus(java.time.Duration.ofDays(2))), true);
            expect(SelfUpdate.autoAllowed(Config.empty(), dir, "0.1.6", now), true);
            Path cfg = dir.resolve("config.toml");
            Files.writeString(cfg, "auto_update = \"off\"\n");
            expect(SelfUpdate.autoAllowed(Config.load(cfg), dir, "0.1.6", now), false);
        });
        run("self update: installs only a signed, listed and matching installer", () -> {
            Path dir = Files.createTempDirectory("bc-update");
            KeyPair pair = Keys.generate();
            Keys.PrivateKeyInfo priv = new Keys.PrivateKeyInfo(pair.getPrivate(), Keys.keyId(pair.getPublic()));
            Path pubFile = dir.resolve("release.pub");
            Files.writeString(pubFile, Keys.encodePublic(pair.getPublic(), "release"));
            Keys.PublicKeyInfo pub = Keys.readPublic(pubFile);
            Path installer = dir.resolve("BiteCheck-0.1.5-windows-x64.msi");
            Files.writeString(installer, "installer");
            Path sums = dir.resolve(SelfUpdate.SUMS);
            Files.writeString(sums, "ab".repeat(32) + "  BiteCheck-windows-x64.msi\n"
                    + Hashing.sha256Hex(installer) + "  BiteCheck-0.1.5-windows-x64.msi\n");
            expectThrows(() -> SelfUpdate.check(sums, installer, pub)); // not signed yet
            Signatures.sign(sums, priv);
            SelfUpdate.check(sums, installer, pub);
            Files.writeString(installer, "tampered");
            expectThrows(() -> SelfUpdate.check(sums, installer, pub));
            Path older = dir.resolve("BiteCheck-0.1.4-windows-x64.msi");
            Files.writeString(older, "installer");
            expectThrows(() -> SelfUpdate.check(sums, older, pub)); // not listed under this name
            KeyPair other = Keys.generate();
            Files.writeString(installer, "installer");
            Signatures.sign(sums, new Keys.PrivateKeyInfo(other.getPrivate(), Keys.keyId(other.getPublic())));
            expectThrows(() -> SelfUpdate.check(sums, installer, pub)); // signed with another key
            expect(SelfUpdate.expectedHash(List.of("CD".repeat(32) + " *a.msi"), "a.msi"), Optional.of("cd".repeat(32)));
        });
        run("usage sharing: off by default, sends nothing", () -> {
            Path dir = Files.createTempDirectory("bc-usage");
            UsageSharing sharing = UsageSharing.load(dir);
            expect(sharing.enabled(), false);
            List<String> sent = new ArrayList<>();
            String r = sharing.sendIfDue(java.net.URI.create("https://example.invalid"), "test/1",
                    List.of(new UsageSharing.Jar("ab".repeat(32), "123", "Mod", 10)), java.time.LocalDate.of(2026, 11, 22),
                    (url, body) -> { sent.add(body); return "OK"; });
            expect(r, "sharing is off");
            expect(sent.size(), 0);
        });
        run("usage sharing: once a day, monthly new id, only hash, workshop id, mod id, size", () -> {
            Path dir = Files.createTempDirectory("bc-usage");
            UsageSharing sharing = UsageSharing.load(dir);
            sharing.setEnabled(true);
            List<String> sent = new ArrayList<>();
            UsageSharing.Sender sender = (url, body) -> { sent.add(body); return "OK 1 jars"; };
            java.net.URI url = java.net.URI.create("https://example.invalid");
            List<UsageSharing.Jar> jars = List.of(new UsageSharing.Jar("ab".repeat(32), "123", "My\tMod", 4096));
            expect(sharing.sendIfDue(url, "test/1", jars, java.time.LocalDate.of(2026, 11, 22), sender), "OK 1 jars");
            expect(UsageSharing.load(dir).sendIfDue(url, "test/1", jars, java.time.LocalDate.of(2026, 11, 22), sender), "already reported today");
            UsageSharing.load(dir).sendIfDue(url, "test/1", jars, java.time.LocalDate.of(2026, 11, 23), sender);
            UsageSharing.load(dir).sendIfDue(url, "test/1", jars, java.time.LocalDate.of(2026, 12, 1), sender);
            expect(sent.size(), 3);
            String[] lines = sent.get(0).split("\n");
            expect(lines[0], "jumpstart-usage 1");
            expect(lines[3], "jar\t" + "ab".repeat(32) + "\t123\tMy Mod\t4096");
            expect(lines.length, 4);
            String id1 = sent.get(0).split("\n")[1];
            expect(sent.get(1).split("\n")[1], id1); // same month, same id
            expect(sent.get(2).split("\n")[1].equals(id1), false); // new month, new id
        });
        run("usage sharing: workshop id from the jar's path, preview without an id", () -> {
            expect(UsageSharing.Jar.workshopIdOf(Path.of("C:/Steam/steamapps/workshop/content/108600/3684713089/mods/X/42/a.jar")), "3684713089");
            expect(UsageSharing.Jar.workshopIdOf(Path.of("/home/u/Zomboid/mods/X/42/a.jar")), null);
            String preview = UsageSharing.preview("test/1", List.of(new UsageSharing.Jar("ab".repeat(32), null, "Local", 1)));
            expect(preview.contains("install\t<random id, new every month>"), true);
            expect(preview.contains("jar\t" + "ab".repeat(32) + "\t\tLocal\t1"), true);
        });
        run("flow: verified allowed, unverified off until the player answers, blocked denied", () -> {
            World w = World.create();
            Session s = w.session();
            expect(status(s, "good.jar"), Verifier.Status.VERIFIED);
            expect(status(s, "unknown.jar"), Verifier.Status.UNVERIFIED);
            expect(row(s, "unknown.jar").undecided(), true);
            expect(status(s, "evil.jar"), Verifier.Status.BLOCKED);
            s.apply();
            ZombieBuddyApprovals a = ZombieBuddyApprovals.load(w.zb);
            expect(a.decision(w.sha("good.jar")), Boolean.TRUE);
            expect(a.decision(w.sha("unknown.jar")), Boolean.FALSE); // ZombieBuddy has nothing to ask
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
        run("window: a mod with several jars is one line, showing its most serious jar", () -> {
            World w = World.create();
            Path good = w.modsDir.resolve("Good");
            Files.createDirectories(good.resolve("media/java"));
            Files.writeString(good.resolve("media/java/bad.jar"), "blocked copy");
            w.publishIndex(Instant.now().plusSeconds(60), Map.of("verified", w.sha("good.jar"),
                    "blocked", Hashing.sha256Hex(good.resolve("media/java/bad.jar"))));
            List<LauncherWindow.ModLine> lines = LauncherWindow.ModLine.group(w.session().rows).stream()
                    .filter(l -> l.mod().folder().equals(good)).toList();
            expect(lines.size(), 1);
            expect(lines.get(0).rows().size(), 2);
            expect(lines.get(0).shown().status(), Verifier.Status.BLOCKED);
            expect(lines.get(0).tooltip().contains(":/") || lines.get(0).tooltip().contains(":\\"), false);
        });
        run("flow: approval is taken back when a mod loses verification", () -> {
            World w = World.create();
            w.session().apply();
            w.publishIndex(Instant.now().plusSeconds(60), Map.of());
            Session s = w.session();
            expect(action(s, "good.jar"), Planner.Action.DENY);
            expect(row(s, "good.jar").undecided(), true);
            s.apply();
            expect(ZombieBuddyApprovals.load(w.zb).decision(w.sha("good.jar")), Boolean.FALSE);
        });
        run("answers: yes allows the mod, no keeps it off and is not asked again", () -> {
            World w = World.create();
            Session s = w.session();
            expect(StartGate.questions(s).stream().anyMatch(q -> q.mod().id().equals("Unknown")), true);
            s.state.allowedAnyway().put(w.sha("unknown.jar"), "test");
            s.state.refused().put(w.sha("shady.jar"), "test");
            s.replan().apply();
            Session again = w.session();
            expect(action(again, "unknown.jar"), Planner.Action.NONE);
            expect(row(again, "unknown.jar").undecided(), false);
            expect(row(again, "shady.jar").refused(), true);
            expect(row(again, "shady.jar").undecided(), false);
            expect(StartGate.questions(again).stream().anyMatch(q -> q.mod().id().equals("Unknown")
                    || q.mod().id().equals("Shady")), false);
            ZombieBuddyApprovals a = ZombieBuddyApprovals.load(w.zb);
            expect(a.decision(w.sha("unknown.jar")), Boolean.TRUE);
            expect(a.decision(w.sha("shady.jar")), Boolean.FALSE);
            // an approval someone else wrote into ZombieBuddy's file does not count
            a.set("Other", null, w.sha("unknown.jar"), true);
            again.state.allowedAnyway().remove(w.sha("unknown.jar"));
            expect(row(again.replan(), "unknown.jar").undecided(), true);
            expect(action(again.replan(), "unknown.jar"), Planner.Action.DENY);
        });
        run("zombiebuddy: the jar in the game folder is checked like any mod and stops the start", () -> {
            World w = World.create();
            Path game = w.root.resolve("game");
            Files.createDirectories(game);
            Files.writeString(w.biteCheck.resolve("config.toml"), Files.readString(w.biteCheck.resolve("config.toml"))
                    + "game_dir = " + io.github.bitecheckpz.core.Toml.quote(game.toString()) + "\n");
            w.mod(game, "ZBTmp", "ZombieBuddy.jar", "zb loader");
            Path zb = game.resolve("ZombieBuddy.jar");
            Files.move(w.jars.get("ZombieBuddy.jar"), zb);
            w.jars.put("ZombieBuddy.jar", zb);
            Session s = w.session();
            Planner.Row loader = row(s, "ZombieBuddy.jar");
            expect(loader.mod().isLoader(), true);
            expect(loader.status(), Verifier.Status.UNVERIFIED);
            expect(loader.stopsGame(), true);
            expect(StartGate.questions(s).get(0).mod().isLoader(), true); // asked first
            s.apply();
            expect(ZombieBuddyApprovals.load(w.zb).decision(w.sha("ZombieBuddy.jar")), null); // not its own approval
            s.state.allowedAnyway().put(w.sha("ZombieBuddy.jar"), "test");
            expect(row(s.replan(), "ZombieBuddy.jar").stopsGame(), false);
            // a waiting update (zbNative puts it in place at the start) is checked too
            Files.writeString(game.resolve("ZombieBuddy.jar.new"), "update");
            Session withUpdate = w.session();
            expect(withUpdate.rows.stream().anyMatch(r -> r.jar().path().getFileName().toString()
                    .equals("ZombieBuddy.jar.new") && r.stopsGame()), true);
            // blocked: never allowed, the game does not start
            w.publishIndex(Instant.now().plusSeconds(60), Map.of("blocked", w.sha("ZombieBuddy.jar")));
            Session blocked = w.session();
            expect(row(blocked, "ZombieBuddy.jar").stopsGame(), true);
            expect(row(blocked, "ZombieBuddy.jar").undecided(), false);
            // verified: starts without a question
            w.publishIndex(Instant.now().plusSeconds(120), Map.of("verified", w.sha("ZombieBuddy.jar")));
            Files.delete(game.resolve("ZombieBuddy.jar.new"));
            expect(w.session().startStoppers().isEmpty(), true);
        });
        run("update hint: Steam update, change without update, version numbers", () -> {
            Path dir = Files.createTempDirectory("bc-hint");
            Path jar = dir.resolve("Mod/42/media/java/m.jar");
            Files.createDirectories(jar.getParent());
            java.util.jar.Manifest mf = new java.util.jar.Manifest();
            mf.getMainAttributes().put(java.util.jar.Attributes.Name.MANIFEST_VERSION, "1.0");
            mf.getMainAttributes().put(java.util.jar.Attributes.Name.IMPLEMENTATION_VERSION, "2.0");
            try (var out = new java.util.jar.JarOutputStream(Files.newOutputStream(jar), mf)) {
                out.putNextEntry(new java.util.zip.ZipEntry("a.txt"));
                out.write(1);
                out.closeEntry();
            }
            String sha = Hashing.sha256Hex(jar);
            JavaMods.Jar j = new JavaMods.Jar(jar, sha, true);
            JavaMods.JavaMod workshop = new JavaMods.JavaMod("Mod", "Mod", dir.resolve("Mod"), "123", "workshop", List.of(j));
            JavaMods.JavaMod local = new JavaMods.JavaMod("Mod", "Mod", dir.resolve("Mod"), null, "local", List.of(j));
            ZombieBuddyApprovals a = ZombieBuddyApprovals.load(dir.resolve("zb"));
            LauncherState st = LauncherState.load(dir.resolve("state.toml"));
            String key = "Mod/42/media/java/m.jar";
            st.trusted().put(key, new LauncherState.Trusted(key, "ab".repeat(32), "1.0", "100"));
            java.util.function.Function<List<Planner.Row>, Planner.ChangeKind> kind = rows -> rows.get(0).change().kind();
            expect(kind.apply(Planner.plan(List.of(workshop), Optional.empty(), Instant.now(), a, st, Map.of("123", "200"))),
                    Planner.ChangeKind.UPDATE);
            expect(kind.apply(Planner.plan(List.of(workshop), Optional.empty(), Instant.now(), a, st, Map.of("123", "100"))),
                    Planner.ChangeKind.WITHOUT_UPDATE);
            expect(kind.apply(Planner.plan(List.of(workshop), Optional.empty(), Instant.now(), a, st, Map.of())),
                    Planner.ChangeKind.UNKNOWN);
            expect(kind.apply(Planner.plan(List.of(local), Optional.empty(), Instant.now(), a, st)),
                    Planner.ChangeKind.UPDATE); // 1.0 -> 2.0
            st.trusted().put(key, new LauncherState.Trusted(key, "ab".repeat(32), "2.0", null));
            expect(kind.apply(Planner.plan(List.of(local), Optional.empty(), Instant.now(), a, st)),
                    Planner.ChangeKind.WITHOUT_UPDATE);
            // no earlier trusted version, or the same jar: no hint
            st.trusted().clear();
            expect(Planner.plan(List.of(local), Optional.empty(), Instant.now(), a, st).get(0).change(), null);
            // allowing it remembers this version; the state keeps it
            st.allowedAnyway().put(sha, "test");
            Planner.apply(Planner.plan(List.of(workshop), Optional.empty(), Instant.now(), a, st, Map.of("123", "300")),
                    a, st, Map.of("123", "300"));
            st.refused().put("cd".repeat(32), "test");
            st.save();
            LauncherState loaded = LauncherState.load(dir.resolve("state.toml"));
            expect(loaded.trusted().get(key), new LauncherState.Trusted(key, sha, "2.0", "300"));
            expect(loaded.refused().containsKey("cd".repeat(32)), true);
        });
        run("zombiebuddy config: preload and trusted authors cannot get around BiteCheck", () -> {
            World w = World.create();
            Path config = w.zb.resolve("config.json");
            Files.createDirectories(w.zb);
            String good = w.jars.get("good.jar").toString().replace("\\", "\\\\");
            String shady = w.jars.get("shady.jar").toString().replace("\\", "\\\\");
            Files.writeString(config, "{\"trusted_authors\": [\"76561198000000000\"], \"preload_mods\": {"
                    + "\"Good\": {\"infPath\": \"x\", \"jarPath\": \"" + good + "\"},"
                    + "\"Shady\": {\"infPath\": \"y\", \"jarPath\": \"" + shady + "\"},"
                    + "\"Gone\": {\"infPath\": \"z\", \"jarPath\": \"/no/such.jar\"}},"
                    + "\"auto_fix_mod_order\": false}");
            Session s = w.session();
            s.apply();
            Map<?, ?> root = (Map<?, ?>) Json.parse(Files.readString(config));
            expect(((List<?>) root.get("trusted_authors")).size(), 0);
            expect(((Map<?, ?>) root.get("preload_mods")).keySet(), java.util.Set.of("Good")); // verified only
            expect(root.get("auto_fix_mod_order"), Boolean.FALSE); // the rest stays
            expect(s.notes.stream().filter(n -> n.contains("Preloading of")).count(), 2L);
            // once the player allows Shady, its preloading is no longer touched
            Files.writeString(config, "{\"preload_mods\": {\"Shady\": {\"infPath\": \"y\", \"jarPath\": \"" + shady + "\"}}}");
            s.state.allowedAnyway().put(w.sha("shady.jar"), "test");
            s.replan().apply();
            expect(((Map<?, ?>) ((Map<?, ?>) Json.parse(Files.readString(config))).get("preload_mods")).size(), 1);
        });
        run("platform: reads when Steam updated the installed Workshop items", () -> {
            String acf = """
                    "AppWorkshop"
                    {
                    	"WorkshopItemsInstalled"
                    	{
                    		"3684713089"
                    		{
                    			"size"		"123"
                    			"timeupdated"		"1700000000"
                    			"manifest"		"55"
                    		}
                    	}
                    	"WorkshopItemDetails"
                    	{
                    		"3684713089"
                    		{
                    			"manifest"		"56"
                    			"timeupdated"		"1800000000"
                    		}
                    	}
                    }
                    """;
            expect(Platform.parseWorkshopUpdateTimes(acf), Map.of("3684713089", "1700000000"));
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
        run("timestamp: confirms an older index, so it still approves", () -> {
            World w = World.create();
            w.publishIndex(Instant.now().minus(10, ChronoUnit.DAYS), Map.of("verified", w.sha("good.jar")));
            w.publishTimestamp(Instant.now().minus(1, ChronoUnit.HOURS), Hashing.sha256Hex(w.indexFile), w.timestamp);
            Session s = w.session();
            expect(status(s, "good.jar"), Verifier.Status.VERIFIED);
            expect(s.index.stale(s.now), false);
        });
        run("timestamp: saved timestamp still confirms when offline", () -> {
            World w = World.create();
            w.publishIndex(Instant.now().minus(10, ChronoUnit.DAYS), Map.of("verified", w.sha("good.jar")));
            w.publishTimestamp(Instant.now().minus(1, ChronoUnit.HOURS), Hashing.sha256Hex(w.indexFile), w.timestamp);
            w.session();
            Files.delete(w.indexFile); // offline
            Session s = w.session();
            expect(s.index.fromCache(), true);
            expect(status(s, "good.jar"), Verifier.Status.VERIFIED);
        });
        run("timestamp: for another index, wrong key or too old an index it does not count", () -> {
            World w = World.create();
            w.publishIndex(Instant.now().minus(10, ChronoUnit.DAYS), Map.of("verified", w.sha("good.jar"),
                    "blocked", w.sha("evil.jar")));
            Instant recent = Instant.now().minus(1, ChronoUnit.HOURS);
            w.publishTimestamp(recent, "ab".repeat(32), w.timestamp);
            expect(status(w.session(), "good.jar"), Verifier.Status.UNVERIFIED);
            w.publishTimestamp(recent, Hashing.sha256Hex(w.indexFile), w.registry); // not the timestamp key
            Session wrongKey = w.session();
            expect(status(wrongKey, "good.jar"), Verifier.Status.UNVERIFIED);
            expect(wrongKey.notes.stream().anyMatch(n -> n.contains("Ignoring the index timestamp")), true);
            expect(status(wrongKey, "evil.jar"), Verifier.Status.BLOCKED);

            World old = World.create();
            old.publishIndex(Instant.now().minus(50, ChronoUnit.DAYS), Map.of("verified", old.sha("good.jar")));
            old.publishTimestamp(recent, Hashing.sha256Hex(old.indexFile), old.timestamp);
            expect(status(old.session(), "good.jar"), Verifier.Status.UNVERIFIED);
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
            // another tool's wrapper (Jumpstart) is not BiteCheck: BiteCheck goes in front of %command%
            String jumpstart = "\"C:\\Users\\me\\AppData\\Local\\Jumpstart\\Jumpstart.exe\" --wrap %command% -agentlib:zbNative --";
            expect(SteamSetup.isSetUp(java.util.Optional.of(jumpstart)), false);
            String both = SteamSetup.launchOptions(Platform.Os.WINDOWS, script, java.util.Optional.of(jumpstart));
            expect(both, "\"C:\\Users\\me\\AppData\\Local\\Jumpstart\\Jumpstart.exe\" --wrap " + quoted
                    + " --wrap %command% -agentlib:zbNative --");
            expect(SteamSetup.isSetUp(java.util.Optional.of(both)), true);
            expect(SteamSetup.hasJumpstart(both), true);
            expect(SteamSetup.hasJumpstart("\"/jre64/bin/java\" -jar \"/home/me/.jumpstart/JumpstartSetup.jar\" --wrap %command%"), true);
            expect(SteamSetup.hasJumpstart(done), false);
            expect(SteamSetup.launchOptions(Platform.Os.WINDOWS, script, java.util.Optional.of(both)), both);
            expect(SteamSetup.isSetUp(java.util.Optional.of("\"C:\\Program Files\\BiteCheck\\BiteCheck.exe\" --wrap %command%")), true);
            expect(SteamSetup.isSetUp(java.util.Optional.of("\"/Applications/BiteCheck.app/Contents/MacOS/BiteCheck\" --wrap %command%")), true);
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
            expect(ZombieBuddyApprovals.load(w.zb).decision(w.sha("unknown.jar")), Boolean.FALSE); // off until asked again
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
        Keys.PrivateKeyInfo timestamp;

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
            KeyPair tsPair = Keys.generate();
            w.timestamp = new Keys.PrivateKeyInfo(tsPair.getPrivate(), Keys.keyId(tsPair.getPublic()));
            Path tsPub = w.root.resolve("timestamp.pub");
            Files.writeString(tsPub, Keys.encodePublic(tsPair.getPublic(), "timestamp"));
            w.indexFile = w.root.resolve("published/index.toml");
            Files.createDirectories(w.indexFile.getParent());
            Files.writeString(w.biteCheck.resolve("config.toml"), ""
                    + "index_url = " + q(w.indexFile.toUri().toString()) + "\n"
                    + "registry_key = " + q(pub.toString()) + "\n"
                    + "timestamp_key = " + q(tsPub.toString()) + "\n"
                    + "zomboid_dir = " + q(w.root.resolve("Zomboid").toString()) + "\n"
                    + "zombiebuddy_dir = " + q(w.zb.toString()) + "\n"
                    + "workshop_dir = " + q(workshop.toString()) + "\n"
                    + "steam_dir = " + q(w.root.resolve("no-steam").toString()) + "\n"
                    + "update_check = \"off\"\n");
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

        /** Writes and signs timestamp.toml next to the published index. */
        void publishTimestamp(Instant issuedAt, String indexSha256, Keys.PrivateKeyInfo key) throws Exception {
            Path file = indexFile.resolveSibling("timestamp.toml");
            Files.writeString(file, io.github.bitecheckpz.core.Timestamp.generate(indexSha256, issuedAt));
            Signatures.sign(file, key);
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
