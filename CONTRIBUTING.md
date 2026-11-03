# Contributing to BiteCheck

Thanks for helping! There are three ways to contribute.

## 1. Submit a mod (modders)

1. Copy the [`template/`](template/README.md) folder of this repository into a new repository of your own. It uses the BiteCheck Gradle plugin, which compiles against the game's jar without bundling it and makes the jar reproducible. Make the repository public under a license that allows review and rebuilding (e.g. MIT, Apache 2.0, GPL).
2. Install the [GitHub CLI](https://cli.github.com), run `gh auth login`, then `bcz login`. This creates your signing key in `~/.bitecheck/keys/` — back it up.
3. Fill in `bitecheck.toml`, tag a release (`git tag v1.0.0 && git push --tags`) and run `bcz submit` in your mod's folder. It writes and signs the release file, registers your key and GitHub account with your first submission, and opens a pull request here.
4. CI builds your mod twice against the Project Zomboid dedicated server, compares the hashes and scans the jar. A reviewer approves it (how many approvals are needed is set in `registry/policy.toml`), then it is merged.
5. Before you upload to the Workshop, run `bcz fetch` in your mod's folder: it checks that your jar is exactly the one CI built and BiteCheck verifies. If not (for example, you built against another game version), it offers to replace it with CI's jar.

For later releases, the template's workflow (`.github/workflows/bitecheck.yml`, using [submit-action](submit-action/action.yml)) submits every `v*` tag automatically. It needs two repository secrets: `BITECHECK_AUTHOR_KEY` (the content of your `~/.bitecheck/keys/<you>.key`) and `BITECHECK_GITHUB_TOKEN` (a personal access token with `public_repo`, so it can open the pull request from your fork). See the template's README.

Your mod is never blocked while it waits: players can load it as an unverified mod with a warning.

Requirements for a release:

- Build 42 or newer (ZombieBuddy does not load Java mods in Build 41). CI builds against the current public version of the game unless `game_branch` says otherwise.
- No obfuscation.
- No native libraries, no runtime code loading, no process execution.
- Network access and file access outside `~/Zomboid` must be explained in the mod description and will be checked carefully in review.
- Game classes from The Indie Stone are referenced at compile time only (`compileOnly`) and never bundled. CI fails a build whose jar contains game classes.

### Mods that are only on the Steam Workshop

A mod whose jar players get from the Workshop can be listed without changing how it is built: a release with `type = "workshop"` (see [registry/README.md](registry/README.md)) names the Workshop item and the build command. CI builds the jar from the public source twice and checks that the Workshop jar has exactly the same files; players are then verified with the Workshop jar they already have. The build must give the same class files as the author's (same JDK, the `--release` the author used), and every Workshop update needs a new release.

### Without the template

Any build works if it is reproducible: CI runs your `command` twice on different machines with `PZ_GAME_DIR` pointing to the dedicated server, and both jars must be byte-identical. With Gradle that means `isPreserveFileTimestamps = false` and `isReproducibleFileOrder = true` on archive tasks, a pinned wrapper, the JDK named in `bitecheck.toml`, pinned dependencies, and no dates or machine names in the jar. Check locally by building twice and comparing `bcz hash` of both jars.

`bitecheck.toml` at the root of your repository:

```toml
id = "my-mod"
name = "My Mod"
license = "MIT"
workshop_id = ""

[build]
type = "rebuild"
jdk = "temurin-25"
command = "./gradlew --no-daemon bitecheckMod"
artifacts = ["build/bitecheck/42/media/java/client/MyMod.jar"]
# game_branch = "pre-release"   # optional Steam branch of the dedicated server
```

## 2. Review mods (reviewers)

Anyone can review. Reviews from new accounts are visible but do not count toward approval until the reviewer has built up reputation (see the specification).

Set up once: `gh auth login`, then `bcz login` (creates your key); a maintainer adds your key to `keys/reviewers/` and sets your level with `bcz trust`. Then, inside the registry checkout:

```sh
bcz status                      # what is waiting for you
bcz inspect <mod>               # source at the release commit in ~/.bitecheck/review/, layout, scan, source check
bcz approve <mod>               # or: bcz reject <mod> --notes "why"
bcz flag <mod> --severity critical --notes "what you found"
```

These commands pull first, commit only the mod's folder and push it — or, without write access to the registry, open a pull request from your fork.

**Updates:** when the previous release is verified, `bcz inspect` shows what changed since then (source diff, changed classes per jar, new findings marked `(new)`). If you reviewed only those changes, approve with `bcz approve <mod> --scope diff`. A diff review counts only while the previous release stays verified.

`bcz inspect` scans published jars itself; for a mod CI rebuilt, download the jar from the "Mod submission" run (artifact `mod-<mod>-<version>`) and run `bcz scan` on it. Without `--notes`, an approval says "Full review of the source at commit …"; add `--notes "…"` for anything else you checked.

Publishers of upstream mods sign a prepared entry with `bcz publish <mod>`, which also marks it active.

A review covers the Java code and the build files (for `rebuild` and `workshop` releases the build command runs them). Lua files are not part of it: BiteCheck only decides whether a jar loads, and Lua runs in the game's sandbox either way. What the Java code exposes to Lua (`@LuaMethod`, exposed classes) is part of the review, because every Lua mod can call it: `bcz scan` marks such classes (`lua`) and warns (`lua-access`) when they also access files or the network or use reflection, so check that the methods Lua can call cannot be used for more than the mod needs.

A review must state what was checked (full review or diff to the previous version) and explain every soft scanner finding. If you find something malicious, **flag it immediately** — a critical flag removes the approval for all players until it is resolved.

### How to review

A Java mod runs with the player's rights. What a malicious one would do — steal files (Steam login, browser data, SSH keys), download and run code, change other mods or jars, mine crypto, open a multiplayer backdoor, destroy data — needs specific APIs. The review starts from them:

1. **Trust the basis first.** `bcz inspect` must show the jar matching the source (CI's rebuild hash or the source check) and no hard findings. Then read the source, not the jar.
2. **Know the claim.** Read the README / mod description and count the files and lines. The question for everything below: does the code do what it claims, and nothing else?
3. **Filter by APIs outside the game.** List the imports of all files (`grep -h '^import' *.java | sort -u`) and look for files (`java.io`, `java.nio.file`), network (`java.net`), processes (`ProcessBuilder`, `Runtime.exec`), loading code (`ClassLoader`, `defineClass`, `System.load`), `Unsafe`, `ScriptEngine` and deserialization (`ObjectInputStream`). Also grep for fully qualified names (`java.net.` …), which need no import. Game classes (`zombie.*`) are not harmless by themselves: `ZomboidFileSystem` (paths, including saves and the game folder), `zombie.network.*` (sending to the server or clients), `LuaManager` (loading and running Lua), `SteamWorkshop` / `SteamUtils` (Workshop uploads, Steam) and `Core` / `GameWindow` (settings, saving) need the same look.
4. **Find the entry points.** What runs by itself: `Main.main`, static initializers, the `@Patch` targets. What Lua can call: `@Exposer.LuaClass`, `@LuaMethod`. A path, URL or class name that comes from Lua is a warning sign.
5. **Explain every soft finding.** Most "method handles" are lambdas or string concatenation; say where and why for each one.
6. **Follow the data at the sensitive places.** Files: which paths, built from constants or from outside, do they stay in `~/Zomboid`? Network: where to, what is sent? Reflection: constant names are fine; names built or decoded at run time are not.
7. **Place the patches.** Separate correctness risks (e.g. a game method replaced per game version, which breaks on updates) from security risks. In multiplayer, check that the server does not act on client packets or commands without checking who sent them.

**How deep:** in proportion to the risk. Read small mods without sensitive APIs completely. In large ones, read the entry points and every file that uses a sensitive API completely and skim pure logic (rendering math, item stats): anything dangerous needs the APIs from step 3. Obfuscation is a red flag on its own — Base64 strings, byte arrays, XOR, strangely assembled strings have no place in mod code.

**Decide:** approve when the code matches its description and every finding is explained (the notes say what you checked and how you explained the findings, correctness risks included); reject when something is wrong but not malicious, e.g. unexplained file access outside `~/Zomboid`; flag on real suspicion of malice.

## 3. Work on BiteCheck itself (developers)

- Open an issue before starting larger changes, so we can agree on the approach.
- Keep pull requests small and focused.
- By contributing, you agree that your contribution is licensed under GPL-3.0.
