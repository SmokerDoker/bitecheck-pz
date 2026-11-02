# BiteCheck Gradle plugin

`io.github.smokerdoker.bitecheck` builds Java mods for Project Zomboid Build 42 and
[ZombieBuddy](https://github.com/zed-0xff/ZombieBuddy) reproducibly, so that BiteCheck's CI can
rebuild them byte for byte. The easiest start is the mod template in [`../template`](../template).

```kotlin
plugins {
    id("io.github.smokerdoker.bitecheck") version "0.1.0"
}

bitecheck {
    modName.set("MyMod")
}
```

```sh
./gradlew bitecheckMod    # -> build/bitecheck/ (complete mod folder)
```

## What it does

- Applies `java`; Java toolchain and `--release` = `javaVersion` (default 25, the Java version
  Project Zomboid Build 42 ships); source encoding UTF-8.
- Adds the game's `projectzomboid.jar` as `compileOnly`. Build 42 has all game code (and its
  libraries) in that one jar. The game directory is, in this order:
  `bitecheck { gameDir }`, Gradle property `pzGameDir`, environment variable `PZ_GAME_DIR`, or the
  first Steam install found automatically (default Steam folders on Windows, macOS and Linux, plus
  every library in `steamapps/libraryfolders.vdf`; game `ProjectZomboid` first, then
  `Project Zomboid Dedicated Server`). The jar is looked up at `projectzomboid.jar` (game,
  Windows/Linux), `java/projectzomboid.jar` (dedicated server), `projectzomboid/projectzomboid.jar`
  (native Linux depot) and `Project Zomboid.app/Contents/Java/projectzomboid.jar` (macOS). Without
  a game the build fails with a message that says how to set it.
- Adds ZombieBuddy.jar as `compileOnly`: ZombieBuddy mods compile against ZombieBuddy.jar itself
  (that is what [ZBHelloWorld](https://github.com/zed-0xff/ZBHelloWorld) does with the jar in the
  game folder). To make that reproducible, task `bitecheckZombieBuddy` downloads the jar of a pinned
  GitHub release (default 2.3.4) and checks its SHA-256; it is cached in
  `~/.gradle/caches/bitecheck/`. Another release: `zombieBuddyVersion` (+ `zombieBuddySha256` if the
  plugin does not know its hash). A local jar for experiments: `-PzombieBuddyJar=<path>`.
- Makes every archive reproducible: no file timestamps, fixed file order, permissions `rw-r--r--`
  / `rwxr-xr-x`. The manifest only contains `Manifest-Version` (no dates, no machine names).
- Names the jar `<modName>.jar`.
- Task `bitecheckMod` (also run by `assemble`): syncs `build/bitecheck/` with the files from
  `src/mod/` plus the jar at `<gameVersion>/media/java/<side>/<modName>.jar`, and creates the
  `common/` folder Build 42 needs. This folder is the mod: copy it to `~/Zomboid/mods/<ModName>/`.

## Settings

| Setting | Default | |
| --- | --- | --- |
| `javaVersion` | `25` | toolchain and `--release` |
| `gameDir` | see above | game or dedicated server directory |
| `modName` | project name | jar name, `javaJarFile` in mod.info |
| `gameVersion` | `"42"` | version folder of the mod |
| `side` | `"client"` | `client`, `server` or `shared` (loads on both) |
| `modDir` | `src/mod` | other mod files |
| `zombieBuddyVersion` | `"2.3.4"` | ZombieBuddy release to compile against |
| `zombieBuddyUrl` / `zombieBuddySha256` | from the version | |
| `zombieBuddyJar` | Gradle property `zombieBuddyJar` | local jar instead of the download |

## In BiteCheck's CI

`build-mod.yml` installs the Project Zomboid Dedicated Server (Steam app 380870) with SteamCMD and
sets `PZ_GAME_DIR` to it before it runs the release's build command, e.g.
`./gradlew --no-daemon bitecheckMod`. The game files stay on the runner.

## Developing the plugin

```sh
cd gradle-plugin
./gradlew build      # unit tests + Gradle TestKit functional tests (fake game jar, no game needed)
```

This is a separate Gradle build on purpose: BiteCheck itself has no third-party dependencies.
Releases: push a tag `plugin-v<version>`; `.github/workflows/gradle-plugin.yml` tests and publishes
to the Gradle Plugin Portal (secrets `GRADLE_PUBLISH_KEY`, `GRADLE_PUBLISH_SECRET`).
