# BiteCheck

**A verified Java mod launcher and curated mod registry for Project Zomboid.**

> BiteCheck is a community project. It is not affiliated with, endorsed by, or supported by The Indie Stone. "Project Zomboid" is a trademark of The Indie Stone.

## Why

Java mods can change far more of Project Zomboid than Lua mods can — but they also run with full access to your computer. Today you either trust a `.jar` blindly or you don't use Java mods at all.

BiteCheck adds a verification step before a Java mod is loaded:

- **Public source only.** The curated registry only lists mods whose full source code is public.
- **Reproducible builds.** The `.jar` you download is built by CI from that exact source, twice, on independent runners. Both builds must be byte-for-byte identical.
- **Signatures.** Every release is signed by its author or publisher, by the registry, and by its reviewers (during start-up one trusted reviewer, later at least two independent ones).
- **Automatic scanning + human review.** Suspicious APIs (process start, native code, dynamic class loading, network, file access outside `~/Zomboid`) are flagged before a human reviews the code.
- **Per-version approval.** Approval is tied to the SHA-256 hash of one exact `.jar`. A new version is unverified until it is reviewed.

## How it works for players

BiteCheck works together with [ZombieBuddy](https://github.com/zed-0xff/ZombieBuddy), which loads Java mods. Start the game with the BiteCheck launcher (`BiteCheck.bat`, `BiteCheck.command` or `bitecheck.sh`); it checks every installed Java mod against the signed registry index and then starts Project Zomboid through Steam:

- **Verified mods** load without any prompt.
- **Unverified mods** still work, just like with ZombieBuddy: ZombieBuddy warns you in game and asks before loading each one. Your choice is remembered for that exact file only.
- **Suspicious mods**: until a mod is reviewed, BiteCheck scans it locally. If the scan finds a process start, native code, runtime code loading or obfuscation, the mod is not loaded unless you allow it anyway (double confirmation). The local scan is a quick check, not a review.
- **Flagged mods** are not loaded unless you allow them anyway (double confirmation).
- **Mods with confirmed malware** are on a block list and never load.
- **Lua mods** are loaded by the game itself and are not affected by BiteCheck at all.

Under the hood BiteCheck writes allow/deny decisions per jar hash into ZombieBuddy's `~/.zombie_buddy/mod_approvals.json` and never touches anything else. To check your mods every time you press Play in Steam, put BiteCheck into the game's launch options (the launcher's "Steam setup…" button shows the exact line, e.g. `"…\BiteCheck.bat" --wrap %command% -agentlib:zbNative --`). Without it, ZombieBuddy's own questions still protect you. Dedicated servers run BiteCheck the same way before the server starts, see [docs/SERVER.md](docs/SERVER.md). The launcher runs on Windows, macOS and Linux with the Java runtime that comes with Project Zomboid; no separate Java install is needed.

## Download

Installers and the portable zip are on the [Releases page](https://github.com/SmokerDoker/bitecheck-pz/releases/latest):

| System | File | Note |
| --- | --- | --- |
| Windows 10/11 | `BiteCheck-<version>-windows-x64.msi` | installs for your user only, no admin rights; SmartScreen warns once (not code-signed yet) |
| macOS, Apple Silicon | `BiteCheck-<version>-macos-arm64.dmg` | not notarized yet: open once via System Settings → Privacy & Security → "Open anyway" |
| macOS, Intel | `BiteCheck-<version>-macos-x64.dmg` | same |
| Linux (Debian/Ubuntu) | `BiteCheck-<version>-linux-x64.deb` | installs to `/opt/bitecheck` |
| Linux (Fedora/openSUSE) | `BiteCheck-<version>-linux-x64.rpm` | |
| Any, incl. Steam Deck | `BiteCheck-<version>.zip` | portable, uses Project Zomboid's own Java |

The installers bring their own small Java runtime. `SHA256SUMS.txt` on the release lists the hashes of all files; every build is made by GitHub Actions from the tagged commit (`release.yml`).

## How it works for modders

Submitting a mod should be no harder than publishing to the Workshop:

1. Start from the [mod template](template/README.md) (or add the [Gradle plugin](gradle-plugin/README.md) `io.github.smokerdoker.bitecheck` to your project). It compiles against the game without bundling it and makes the build reproducible.
2. Run `bcz login` once: it links your GitHub account (via the GitHub CLI) and creates your signing key.
3. Tag a release and run `bcz submit` — or let the template's GitHub Action ([submit-action](submit-action/action.yml)) do it on every release tag.

CI then builds your mod twice against the Project Zomboid dedicated server and compares the hashes. Details: [CONTRIBUTING.md](CONTRIBUTING.md).

Your mod is never blocked while it waits for review: players can load it as an unverified mod with a warning.

## Status

🚧 **Early development.** Not ready for players yet.

- [x] Registry format and repository structure
- [x] `bcz` CLI: keys, signatures, build records, reviews, verification, scanner, lint, index
- [x] Launcher for Windows, macOS and Linux on top of [ZombieBuddy](https://github.com/zed-0xff/ZombieBuddy)
- [x] CI pipeline: double build, hash comparison, scanner, registry signature, signed index
- [x] Test kit with three harmless test mods (`scripts/make-test-kit.sh`)
- [x] Steam launch options integration (`--wrap %command%`) and dedicated server mode ([docs/SERVER.md](docs/SERVER.md))
- [x] Registry website generated from the registry files (`bcz site`, GitHub Pages)
- [x] Installers for Windows, macOS and Linux built by GitHub Actions (`release.yml`)
- [x] Start-up policy: one trusted review, publishers may review upstream mods (later two reviews, see `registry/policy.toml`)
- [x] First three mods prepared as pending entries with preliminary reviews ([docs/reviews](docs/reviews/README.md))
- [ ] First test with real players
- [x] Registry key set up ([docs/SETUP-REGISTRY.md](docs/SETUP-REGISTRY.md)); first verified mod: zb-hello-world
- [x] `bcz login` / `bcz submit` (GitHub integration), [mod template](template/README.md), [Gradle plugin](gradle-plugin/README.md) and submit action
- [x] CI rebuilds mods against the Project Zomboid dedicated server (only its `projectzomboid.jar`, fetched with DepotDownloader)
- [x] Reviewer commands: `bcz status`, `inspect` (with diffs to the last verified release), `approve`, `reject`, `flag`
- [x] Key revocation (`keys/revoked.toml`), reviewer levels (`bcz trust`, `bcz reviewers`), ownership check for pull requests
- [ ] Gradle plugin published to the Plugin Portal

## The `bcz` command line tool

```sh
./gradlew :cli:installDist                 # builds with JDK 21, runs on Java 17+
alias bcz=cli/build/install/bcz/bin/bcz     # or bcz.bat on Windows

bcz status                                  # reviewers: what is next for every mod
bcz inspect my-mod                          # source at the release commit, scan, source check
bcz approve my-mod                          # sign your review (bcz publish my-mod: sign as publisher)
bcz flag my-mod --severity critical --notes "…"   # report a problem with a release
bcz login                                   # mod authors: link GitHub (gh CLI), create your key
bcz submit                                  # mod authors: submit the tagged release in this folder
bcz trust bob reviewer                      # maintainers: reviewer levels; bcz reviewers lists them

bcz keygen alice                            # Ed25519 key pair; private key in ~/.bitecheck/keys/
bcz sign registry/mods/my-mod/releases/1.0.0.toml --key ~/.bitecheck/keys/alice.key
bcz build-record <release.toml> <jar> --builder runner-a --builder runner-b   # CI
bcz review <release.toml> <jar> --reviewer bob --verdict approve              # reviewers
bcz verify my-mod.jar --registry . --registry-key keys/registry.pub
bcz scan my-mod.jar                         # APIs reviewers must check; exit 14 on hard findings
bcz lint                                    # check all registry files and signatures
bcz index --registry-key keys/registry.pub --out index.toml   # CI: launcher index
bcz site --registry-key keys/registry.pub --out _site          # CI: registry website
bcz source-check mod.jar java/src           # published jars: every class is declared in the source
```

`bcz verify` prints `VERIFIED`, `UNVERIFIED`, `FLAGGED`, `BLOCKED` or `INVALID` with the reasons, and exits with 0, 10, 11, 12 or 13. See [registry/README.md](registry/README.md) for the exact rules.

BiteCheck has **no third-party dependencies**: everything you have to trust is in this repository or in the JDK itself. Its own jars are built reproducibly, and CI checks that.

## Repository layout

```
core/            Verification library (signatures, registry, verifier, scanner, index)
cli/             The bcz command line tool
launcher/        The player launcher (window + command line) and its start scripts
examples/        Harmless test mods
scripts/         make-test-kit.sh builds a self-contained test kit
docs/            Setup and test guides
registry/        Mod entries, releases, reviews and flags (see registry/README.md)
keys/            Public keys of the registry, authors and reviewers
.github/         CI: build, mod submissions, registry signing and index publishing
```

### CI workflows

| Workflow | When | What |
| --- | --- | --- |
| `build.yml` | every push / PR | tests, reproducible jars, launcher download as artifact |
| `validate-registry.yml` | PRs touching the registry | TOML check, `bcz lint` and `bcz submitter-check` (changes belong to the PR's author), with tools from the base branch |
| `submission.yml` | PRs adding a release | builds the mod twice on different runners, compares hashes, scans the jar |
| `registry.yml` | merges to main, daily | rebuilds new releases, signs build records, publishes the signed index |
| `site.yml` | registry changes, after `registry.yml` | registry website on GitHub Pages |
| `release.yml` | tags `v*`, by hand | installers (msi, dmg, deb, rpm), portable zip and bcz; a tag publishes them as a GitHub release |
| `gradle-plugin.yml` | changes to `gradle-plugin/`, tags `plugin-v*` | builds and tests the Gradle plugin; a tag publishes it to the Plugin Portal |

Run the tests with `./gradlew check`.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). Security issues: see [SECURITY.md](SECURITY.md).

## License

BiteCheck is licensed under the [GNU General Public License v3.0](LICENSE). You may fork and redistribute it under the same license; see [TRADEMARK.md](TRADEMARK.md) for how the BiteCheck name may be used.
