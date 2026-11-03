# My Mod (BiteCheck mod template)

A minimal, harmless Java mod for Project Zomboid Build 42, loaded by
[ZombieBuddy](https://github.com/zed-0xff/ZombieBuddy). It draws one line of text on the main menu.
The build is reproducible, so it can be listed in the [BiteCheck registry](https://github.com/SmokerDoker/bitecheck-pz):
BiteCheck's CI rebuilds the jar from your source twice and checks that both builds are identical.

## 1. Start from the template

Copy this folder (`template/` in the [BiteCheck repository](https://github.com/SmokerDoker/bitecheck-pz))
into a new, public repository of your own. Then rename the example everywhere:

| What | Where |
| --- | --- |
| Gradle project name | `settings.gradle.kts` (`rootProject.name`) |
| Jar name | `build.gradle.kts` (`modName`), `src/mod/42/mod.info` (`javaJarFile`), `bitecheck.toml` (`artifacts`) |
| Java package | `src/main/java/...`, `src/mod/42/mod.info` (`javaPkgName`) |
| Mod name and id | `src/mod/42/mod.info` (`name`, `id`) |
| Registry entry | `bitecheck.toml` (`id`, `name`, `license`, `workshop_id`) |
| License | `LICENSE` (any license that allows review and rebuilding, e.g. MIT) |

On Windows, git does not keep the executable bit of the copied `gradlew`. Set it once before your
first commit: `git update-index --add --chmod=+x gradlew` (BiteCheck's CI copes without it, but
Linux and macOS users of your repository need it).

## Layout

```
bitecheck.toml                  registry metadata, read by bcz submit
build.gradle.kts                applies the BiteCheck Gradle plugin
src/main/java/                  your Java code (ZombieBuddy patches)
src/mod/                        the rest of the mod folder: 42/mod.info, Lua, textures, ...
.github/workflows/bitecheck.yml submits every release tag to the registry
```

`./gradlew bitecheckMod` assembles the complete mod folder in `build/bitecheck/`:

```
build/bitecheck/
  common/
  42/mod.info
  42/media/java/client/MyMod.jar
```

## 2. Build locally

You need Project Zomboid (Build 42) installed with Steam; the build compiles against the game's
`projectzomboid.jar` but never puts game classes into your jar. A JDK 25 is downloaded
automatically if you do not have one.

```sh
./gradlew bitecheckMod          # Windows: gradlew.bat bitecheckMod
```

The plugin finds the game in the default Steam libraries (also on other drives listed by Steam).
Elsewhere, tell it where the game or the dedicated server is installed:

```sh
./gradlew bitecheckMod -PpzGameDir="/path/to/ProjectZomboid"   # or: export PZ_GAME_DIR=...
```

To try the mod, copy `build/bitecheck/` to `~/Zomboid/mods/MyMod/` (Windows:
`%UserProfile%\Zomboid\mods\MyMod\`), enable it in the game's mod list and start the game with
ZombieBuddy. ZombieBuddy asks before it loads an unverified jar.

Check that your build is reproducible: build twice (`./gradlew clean bitecheckMod`) and compare the
SHA-256 of the jar (`sha256sum` or `certutil -hashfile <jar> SHA256`). It must not change.

The template compiles against the ZombieBuddy 2.3.4 API (`me.zed_0xff.zombie_buddy.Patch`), the
newest ZombieBuddy jar published as a GitHub release; ZombieBuddy 3 loads such mods through its
compatibility layer. The plugin downloads that jar and checks its SHA-256.

## 3. Get a BiteCheck identity (once)

Install the `bcz` command line tool (see the BiteCheck README) and run:

```sh
bcz login
```

It creates your signing key in `~/.bitecheck/keys/` and links it to your GitHub account. Keep the
private key secret and back it up: every release you submit is signed with it.

## 4. Release

Add two secrets to your repository (Settings > Secrets and variables > Actions):

| Secret | Content |
| --- | --- |
| `BITECHECK_AUTHOR_KEY` | your private key: the content of `~/.bitecheck/keys/<name>.key` |
| `BITECHECK_GITHUB_TOKEN` | a personal access token (classic) with the scopes `public_repo` and `workflow`; the workflow's own token cannot open a pull request in the registry |

Paste both values into the web form. If the workflow later reports `Bad credentials`, the token
secret does not hold a valid token (for example because the clipboard held something else); set it
again.

Then tag a release:

```sh
git tag v1.0.0
git push origin v1.0.0
```

The workflow `.github/workflows/bitecheck.yml` runs `bcz submit` for version `1.0.0` at that commit:
it reads `bitecheck.toml`, writes and signs the release file and opens a pull request in the
registry. BiteCheck's CI then installs the free Project Zomboid Dedicated Server, builds your mod
twice with `./gradlew --no-daemon bitecheckMod`, compares both jars and scans them; a reviewer
approves it. You can also run `bcz submit` by hand.

Until your release is reviewed, players can still load it as an unverified mod with a warning.

Before you upload the mod to the Workshop, check that your jar is the one BiteCheck verifies:

```sh
bcz fetch       # "local jar matches the verified build", or it offers CI's jar
```

It only replaces your jar if it differs and you confirm. CI's jars are kept for 30 days.

CI compiles against the current public version of the game. Build your release with an up-to-date
game too: compiling against another game version can change the jar (constants of the game are
copied into it), and then the jar you upload to the Workshop no longer matches the one BiteCheck
verified. `[build] game_branch` in `bitecheck.toml` selects a Steam beta branch of the dedicated
server instead (for example `pre-release`). The branch must exist and must not need a password,
otherwise the build fails. Build 41 (`legacy41`) is not supported: ZombieBuddy needs Build 42.

## Rules

Source public, no obfuscation, no native code, no runtime code loading, no process execution, and
game classes are `compileOnly` (never bundled). Network access and file access outside `~/Zomboid`
must be explained in the mod description. See the BiteCheck
[CONTRIBUTING.md](https://github.com/SmokerDoker/bitecheck-pz/blob/main/CONTRIBUTING.md).
