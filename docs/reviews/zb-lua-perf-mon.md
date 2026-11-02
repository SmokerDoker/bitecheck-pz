# zb-lua-perf-mon (preliminary review)

- Source: https://github.com/zed-0xff/ZBLuaPerfMon, commit `f95adf92434ea76e5a7cc0de847289bb20d45719` (2026-10-04)
- License: MIT. Author: Zed (zed-0xff). Requires ZombieBuddy (`-2x.jar` for ZB 1.6.0–2.99.99, `.jar` for ZB 3.0.0+).
- Reviewed: 2026-11-02, all 13 Java files (1517 lines), `javap -v -p` of all classes of both jars compared with the source, both Lua files and every other file in the repository.

## Jars

| Path | SHA-256 | Classes | Scan | Source check |
|---|---|---|---|---|
| `42/media/java/client/ZBLuaPerfMon-2x.jar` | `2f08c5bb612dc0f0d7af29228cea59250f0f68cd599734ed24f3b82ecf011c28` | 22 | 0 hard, 7 soft | all classes in source |
| `42/media/java/client/ZBLuaPerfMon.jar` | `89fdff2b566cd0b9ca36e67f17e34313ef3097c470d5cdeec975e933b4d0a615` | 22 | 0 hard, 7 soft | all classes in source |

Both jars contain the same code. Differences: `-2x.jar` is compiled for Java 17 (class file 61)
against the ZombieBuddy 2.x annotation package `me.zed_0xff.zombie_buddy.Patch`, `.jar` for
Java 25 (class file 69) against `me.zed_0xff.zombie_buddy.annotations.Patch` as in the source;
otherwise only lambda numbering and `MethodParameters` attributes differ (compiler version).

Soft findings:

- "method handles" in `DebugLogger`, `PathParser`, `PerfRenderer`, `PerfRenderer$FormattedCall`,
  `PerformanceMonitor`: string concatenation (`StringConcatFactory`) and lambdas
  (`LambdaMetafactory`, only in `PerfRenderer` and `PerformanceMonitor`). No reflective access.
- "accesses files" in `PathParser`: only `File.getAbsolutePath()` on the game's own
  `ZomboidFileSystem.base.canonicalFile`, used to shorten Lua file paths for display. No I/O.
- "accesses files" in `DebugLogger`: `new FileWriter(new File(ZomboidFileSystem.getCacheDir(),
  "ZBLuaPerfMon_debug.log"))`, i.e. inside the user's Zomboid folder (`~/Zomboid` or the `-cachedir`).
  It is dead code: every call to `DebugLogger.log` is commented out in the source, and no class in
  either jar references `DebugLogger.log` or `DebugLogger.close`, so the file is never created.

## What the code does

| File | Purpose |
|---|---|
| `Patch_LuaCaller` | `@Patch.OnEnter`/`OnExit` on `LuaCaller.pcall`, `pcallvoid`, `pcallBoolean`, `protectedCallBoolean`, `protectedCallVoid`: measures the call time with `System.nanoTime` and records calls above a threshold (default 50 µs). Arguments are only read (function object for its file/line), nothing is changed |
| `PerformanceMonitor`, `TimingStats` | In-memory statistics (`ConcurrentHashMap`, 50 ms buckets); optional periodic log to the console (`System.out`), off by default |
| `PerfRenderer`, `Patch_UITransition` | Draws the top-N table on screen (`TextManager.DrawString`, `SpriteRenderer.renderRect`) from `UITransition.UpdateAll` |
| `Patch_Core` | Resets statistics on `Core.ResetLua` |
| `PathParser`, `LuaFileInfoExtractor`, `FileInfo`, `FilePrefix` | Classify a Lua file name as GAME / local mod / workshop mod (string comparison with `getCacheDir()`, `SteamWorkshop.GetInstalledItemFolders()` and the game root) |
| `ZBLuaPerfMon` | Settings exposed to Lua (`@Exposer.LuaClass`): only setters/getters/toggles for display and logging options |
| `DebugLogger` | Unused file logger (see above) |
| `Patch_ActiveMods` | Completely commented out, no class in the jars |

Bytecode references outside the game, ZombieBuddy and Kahlua are limited to `java.util`
(collections, streams), `java.time` (in `DebugLogger`), `System.nanoTime`, `System.out/err`,
`String.format` and `java.lang.invoke` (lambdas, string concatenation). No network, process,
native, reflection or class-loading access, no obfuscation. All strings in the constant pools
are the log/display texts and path fragments from the source.

## Lua and other files

`ZBLuaPerfMon.lua` (two key bindings that call `ZBLuaPerfMon.toggleOSD` / `toggleOSDFreeze`) and
`ZBLuaPerfMon/ModOptions.lua` (mod options, passes the values to the Java setters). No
`getFileWriter`/`getFileReader`, no URL or HTTP calls, no `loadstring`, no `require`. Other files:
`common/mod.info`, `preview.png`, README, `steam.txt` (workshop description, links only), LICENSE.txt,
`.workshopignore`, `.gitignore`, `build.gradle` (compiles against local game and ZombieBuddy jars,
zb-gradle-plugin 1.0.1), Rakefile (author's build helper), and the `.jar.zbs` ZombieBuddy signature
files (SteamID64 `76561198043849998`).

## Things a reviewer should look at

- The patches run on every protected Lua call. That is a performance cost while the mod is
  enabled (the mod's stated purpose), not a security issue.
- `-2x.jar` cannot be rebuilt from this exact source (it was compiled against the ZombieBuddy 2.x
  package name); its bytecode otherwise matches the source.

## Result

Nothing suspicious. The only file write is an unused debug logger that would write inside the
Zomboid user folder.
