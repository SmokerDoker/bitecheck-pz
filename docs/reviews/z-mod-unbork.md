# z-mod-unbork (preliminary review)

- Source: https://github.com/zed-0xff/ZModUnbork, commit `a9b34052ef6b8dc117d58a80648bd3e52484c655` (2026-10-04)
- License: MIT. Author: Zed (zed-0xff). Requires ZombieBuddy (`-2x.jar` for ZB 2.x, the other jar for ZB 3.x) and the `zdk` Lua mod. `versionMin=42.12`, `loadfirst=true`.
- Reviewed: 2026-11-02, every file in the repository (5 Java files, 143 lines; 37 Lua files, 2948 lines); `javap -v -p` of all classes in both jars compared with the source. The `zdk` dependency was not reviewed.

## Jars

| Path | SHA-256 | Classes | Scan | Source check |
|---|---|---|---|---|
| `42/media/java/shared/ZModUnbork-2x.jar` | `051c3564d5f87bd77ce269fb98f9d467ec5e5a3928a33c69df206781bf9435c8` | 3 | 0 hard, 2 soft | all classes in source |
| `42/media/java/shared/ZModUnbork.jar` | `c9f36cc8cdef4ff05f26858c472d50ad0f961cd87750c05e708d6b00593b712e` | 7 | 0 hard, 3 soft | all classes in source |

All soft findings ("method handles") come from `invokedynamic` bootstraps only: string
concatenation (`StringConcatFactory`) in `Patch_AnimNode` and `Patch_ScriptParser`, and a lambda
(`LambdaMetafactory`) in `Patch_Item$Patch_OnScriptsLoaded`. No reflective access.

`ZModUnbork.jar` matches the source of this commit (Java 17, class version 61, ZombieBuddy 3
annotations `me.zed_0xff.zombie_buddy.annotations.Patch`). `ZModUnbork-2x.jar` is byte-identical to
`ZModUnbork.jar` of the parent of commit `64d3fac` ("support both ZB3 and ZB2"), i.e. an older build.
It contains only `Main`, `Patch_AnimNode` and `Patch_ScriptParser`, uses the old annotation package
`me.zed_0xff.zombie_buddy.Patch`, and has `Patch_ScriptParser.PATTERN` as `public static final`
(package-private in the current source). That matches the source at that parent commit; otherwise the
constants, strings and calls are identical to the current source.

## What the code does

| Class | Purpose |
|---|---|
| `Main` | `Exposer.exposeMethod("zombie.inventory.types.MapItem", "getSymbols")`: makes one game method callable from Lua (for "Handmade maps: Interactive Mapping") |
| `Patch_AnimNode` | `@Patch.OnExit` on `AnimNode.Parse`: catches a `NullPointerException`, logs it, and lets the parse return null instead of crashing |
| `Patch_ScriptParser` | `@Patch.OnExit` on `ScriptParser.stripComments`: removes `-- comment` text after `item <name>` header lines (regex), logs each replacement |
| `Patch_Item` (ZB3 jar only) | `@Patch.OnEnter` on `Item.OnScriptsLoaded`: removes entries from the item's `itemRecipeMap` that point to evolved recipes that do not exist, logs each removal |
| `Patch_KahluaThread` (ZB3 jar only) | `@Patch.OnExit` on `KahluaThread.tableget`: only logs Lua table reads that return nil on tables whose metatable has `__nullSubkeysAsTables`; never changes the result |

Bytecode references outside the game, Kahlua and ZombieBuddy (`Patch`, `Logger`, `Exposer`):
`java.util` collections, `java.util.regex`, `String`/`StringBuilder`, `System.lineSeparator` and
`java.lang.invoke` (lambda, string concatenation). No file, network, process, native or
class-loading access, no obfuscation.

## Lua and other files

The Lua part is a large set of compatibility shims for older mods on newer B42 builds: method
aliases and added methods on game classes (traits, professions, stats, body locations, item types,
tags, ammo types, translations), mostly through `zdk.hook`, `zdk.augment_metatable` and
`zdk.patch_all_metatables`. Several only activate when a specific mod is active (Arsenal GunFighter,
Bandits + Dynamic Traits / Project RV Interior, Clothes Box Redux, Realistic Cold Mod).

- File access: `getFileReader`/`getFileWriter("ZModUnbork.ini")` (two options, `verbose_logs` and
  `log_stats`; stays in `~/Zomboid/Lua`). `getModFileReader` reads the English translation files of
  active mods (read-only).
- Global hooks: `getText`, `getTextOrNull`, `getItemNameFromFullType` (fallback translations),
  `require` (pass-through; only wraps `RC_TempSim/body/SimulationWetness` when that mod is active),
  and, outside debug mode, `getClassField`/`getClassFieldVal`: these return stub values for three
  body-location fields and otherwise call the original. They do not re-enable reflection that the
  game restricts to debug mode.
- `require` only of vanilla files (`TimedActions/ISBaseTimedAction`, `NPCs/BodyLocations`) and of
  `RVClientSP` from Project RV Interior when it is active.
- `41.78/ISScavengeAction.lua` is a copy of the B41 vanilla timed action, defined only if missing.
- No URLs or HTTP calls, no `loadstring`/`loadfile`, no `sendClientCommand`/`sendServerCommand`.

Other files: `common/mod.info`, `common/icon_256.png`, `common/media/scripts/ZModUnbork/evolvedrecipes.txt`
(entirely commented out), `info.yml` + Rakefile (generate the mod list in `steam.txt`, build the jar),
`java/build.gradle`, `spec/` (ZBSpec tests, excluded from the Workshop upload by `.workshopignore`),
TODO, LICENSE, `.gitignore`. `*.jar.zbs` are the author's ZombieBuddy signature files, not code.

## Things a reviewer should look at

- `zdk` does all the hooking for the Lua part and was not reviewed here. It should get its own review
  (or be confirmed as trusted) before this entry is accepted.
- The mod changes many game classes and global functions at load time. That is its purpose
  (compatibility), but it can change the behaviour of other mods. A correctness risk, not a security risk.
- `Patch_KahluaThread` hooks `tableget`, which runs on every Lua table read. It only logs, but it is on
  a very hot path (performance).
- Workshop ID `3677147974` comes from the task and the Rakefile `url` task. `info.yml` lists the same ID
  as "Support Goods MyRoom (MyPhoto)". Please check it on the Workshop.
- The `-2x.jar` is an older build (parent commit) with fewer patches, not a build of this exact commit.

## Result

Nothing suspicious in this repository. The verdict depends on `zdk`, which has not been reviewed yet.
