# z-better-workshop-upload (preliminary review)

- Source: https://github.com/zed-0xff/ZBetterWorkshopUpload, commit `c7bd197396c85ab5721676eca6551a7f73c37504` (2026-10-04)
- License: MIT. Author: Zed (zed-0xff). Requires ZombieBuddy (`-2x.jar` for ZB 2.0.0–2.99.99, `.jar` for ZB 3.0.0+) and the `zdk` Lua mod.
- Reviewed: 2026-11-02, all 6 Java files (1067 lines) plus the unit test, `javap -v -p` of all classes of both jars compared with the source, both Lua files and every other file in the repository.

## Jars

| Path | SHA-256 | Classes | Scan | Source check |
|---|---|---|---|---|
| `42/media/java/client/ZBetterWorkshopUpload-2x.jar` | `05b458aa645169d8a4f98a80aaf3673ac5ec2c31dbada4f05321c02eaa22bc77` | 17 | 0 hard, 10 soft | all classes in source |
| `42/media/java/client/ZBetterWorkshopUpload.jar` | `e1b974fc5fb4360290145f16d47d713784e3108025fcfc0d75c9087f1cda5d3e` | 17 | 0 hard, 10 soft | all classes in source |

Both jars are compiled for Java 17 (class file 61) and contain the same code, with one difference:
`.jar` matches the source exactly; `-2x.jar` was built from the previous source (before commit
`41ec5af`, "support both ZB3 and ZB2"), which uses the ZombieBuddy 2.x API: annotation package
`me.zed_0xff.zombie_buddy.Patch` and `Accessor.tryGet(item, "workshopFolder", null)` instead of
`Reflect.on(item).field("workshopFolder")` in `WorkshopContentFilter.getWorkshopFolderPath`.
Both jars were added in that commit and not changed since.

Soft findings:

- "method handles" in `DescriptionIncludeProcessor`, `Patch_getContentFolder`,
  `Patch_getSubmitDescription`, `WorkshopContentFilter`: string concatenation
  (`StringConcatFactory`) only, no lambdas.
- "accesses files": the real file access, described below.
- Not flagged by the scan because it goes through the ZombieBuddy API: one real reflective read of
  the private game field `SteamWorkshopItem.workshopFolder` (constant name, read only).

## What the code does

| File | Purpose |
|---|---|
| `Patch_SteamWorkshop` | `OnEnter` on `SteamWorkshop.SubmitWorkshopItem`: builds the filtered staging copy and sets a thread-local context; `OnExit` clears the context. `OnEnter` on `onItemUpdated`/`onItemNotUpdated`/`onItemCreated`/`onItemNotCreated`: deletes the staging copies |
| `Patch_SteamWorkshopItem` | `getContentFolder`: returns `<staging>/Contents` instead of the original folder, only inside the submit context and only for the item being submitted. `getSubmitDescription`: expands `@include("file")` lines |
| `Patch_getSteamWorkshopStagedItems` | Sorts the workshop item list by folder name |
| `WorkshopContentFilter` | Exclusion patterns (mod options + `.workshopignore`), staging copy, cleanup |
| `DescriptionIncludeProcessor` | `@include` expansion |
| `ZBetterWorkshopUpload` | Lua API (`@Exposer.LuaClass`): `getWorkshopItemFilteredContents` (relative file names for the upload preview), `listAllFiles`, `loadExcludedPatterns`, `getDefaultExcludedPatterns`, `clearCache` |

File access, exactly:

- Reads: the item's workshop folder (`SteamWorkshopItem.workshopFolder`, normally
  `~/Zomboid/Workshop/<item>`) recursively; `.workshopignore` (fixed name) in the directory of every
  `Contents` entry and in every parent directory up to the file system root, i.e. also outside the
  mod folder (read only, the lines are only used as exclusion patterns); `@include` files.
- `@include("name")`: `name` is rejected if it contains `..` (`StringUtils.containsDoubleDot`) or is
  absolute, then read from `new File(workshopFolder, name)` as UTF-8 and put into the description
  that is sent to Steam. Only the uploader's own description triggers this.
- Writes: one new directory from `Files.createTempDirectory("zb_workshop_filtered_")`, i.e. in the
  OS temp directory (`java.io.tmpdir`, e.g. `%TEMP%`), not in `~/Zomboid`. The whole workshop folder
  is copied into it (`FileInputStream` → `FileOutputStream`), with only `Contents` filtered. Target
  names come from `File.getName()` of listed entries, so no path traversal.
- Deletes: only the parent of the stored `<staging>/Contents` path, i.e. the temp directory created
  above; the map is filled only by `enterSubmitContext`.
- `.workshopignore` and mod-option patterns are only converted to an escaped regex and matched
  against relative path strings; they are never used to build a path, so they cannot make the mod
  read, write or delete anything else.
- Symlinks: copying and listing follow symlinked files and directories inside the workshop folder
  (their content is copied and uploaded; a symlink loop would recurse until a `StackOverflowError`).
  `deleteDirectory` also follows directory symlinks, but the staging tree only contains plain
  files and directories created by the mod itself.

Bytecode references outside the game and ZombieBuddy: `java.io` / `java.nio.file` as above,
`java.util`, `ThreadLocal`, `String.matches`/`split`, `System.out/err`, string concatenation.
No network, process, native or class-loading access, no obfuscation. The constant-pool strings
are the log messages, default patterns, `.workshopignore`, `Contents`, `workshopFolder`,
`zb_workshop_filtered_` and `@include(` from the source.

## Lua and other files

`ZBetterWorkshopUpload.lua`: `zdk.hook` on the vanilla `WorkshopSubmitScreen` (layout, mod icons,
Esc key, file preview list from `getWorkshopItemFilteredContents`). `ZBetterWorkshopUploadOptions.lua`:
mod option "Excluded Patterns", passed to `loadExcludedPatterns`. `require` only of the vanilla
`OptionScreens/WorkshopSubmitScreen` and its own options file. No `getFileWriter`/`getFileReader`,
no URL or HTTP calls, no `loadstring`. Other files: `common/mod.info`, README, `steam.txt`, LICENSE.txt,
`.gitignore`s, `java/.workshopignore`, `build.gradle` (compiles against local game, LWJGL and
ZombieBuddy jars, zb-gradle-plugin 1.0.1), Rakefile (author's build helper), the JUnit test for
`DescriptionIncludeProcessor` (not in the jars), and the `.jar.zbs` ZombieBuddy signature files
(SteamID64 `76561198043849998`).

## Things a reviewer should look at

- The staging copy is written to the OS temp directory, outside `~/Zomboid`. Decide whether that is
  acceptable; it is a freshly created, randomly named directory and the only one the mod deletes.
  If no Steam callback arrives (e.g. the game is closed during upload), the copy stays in temp.
- `.workshopignore` is read from every parent directory up to the drive root (read only, used only
  for exclusion).
- `getWorkshopItemFilteredContents` is callable by any Lua mod and returns the file names (not
  contents) of a workshop item folder.
- `-2x.jar` does not match the current source exactly (ZB 2.x API, see above); a reviewer who wants
  a byte-level check has to build it from the parent of `41ec5af` against ZombieBuddy 2.x.
- The Lua fallback default pattern list differs from the Java defaults (cosmetic; Java is used).

## Result

Nothing malicious. File access stays within the workshop item folder, the `.workshopignore` files
above it (read only) and the mod's own temp staging directory; the temp location outside
`~/Zomboid` needs a reviewer decision.
