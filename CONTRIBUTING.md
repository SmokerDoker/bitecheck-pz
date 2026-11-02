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
bcz inspect <mod>               # source at the release commit in ~/.bitecheck/review/, scan, source check
bcz approve <mod>               # or: bcz reject <mod> --notes "why"
bcz flag <mod> --severity critical --notes "what you found"
```

These commands pull first, commit only the mod's folder and push it — or, without write access to the registry, open a pull request from your fork.

**Updates:** when the previous release is verified, `bcz inspect` shows what changed since then (source diff, changed classes per jar, new findings marked `(new)`). If you reviewed only those changes, approve with `bcz approve <mod> --scope diff`. A diff review counts only while the previous release stays verified.

`bcz inspect` scans published jars itself; for a mod CI rebuilt, download the jar from the "Mod submission" run (artifact `mod-<mod>-<version>`) and run `bcz scan` on it. Without `--notes`, an approval says "Full review of the source at commit …"; add `--notes "…"` for anything else you checked.

Publishers of upstream mods sign a prepared entry with `bcz publish <mod>`, which also marks it active.

A review must state what was checked (full review or diff to the previous version) and explain every soft scanner finding. If you find something malicious, **flag it immediately** — a critical flag removes the approval for all players until it is resolved.

## 3. Work on BiteCheck itself (developers)

- Open an issue before starting larger changes, so we can agree on the approach.
- Keep pull requests small and focused.
- By contributing, you agree that your contribution is licensed under GPL-3.0.
