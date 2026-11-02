# drying-rack-lua-bridge (preliminary review)

- Source: https://github.com/ThisIsSP4RTAN/PZ-DryingRackLuaBridge, commit `69e6fedaadd65ee56008757ab5cd606c98cbcc05` (2026-04-15)
- License: MIT (LICENSE.txt still has the `[year] [fullname]` placeholders). Author: SP4RTAN (ThisIsSP4RTAN). Requires ZombieBuddy (`ZBVersionMin=1.6.6`), `versionMin=42.16.3`, `loadModBefore=\RC_RealisticColdMod`.
- Reviewed: 2026-11-02, all 3 Java files (376 lines), `javap -v -p` of all classes compared with the source, every other file in the repository.

## Jars

| Path | SHA-256 | Classes | Scan | Source check |
|---|---|---|---|---|
| `DryingRackLuaBridge/common/media/java/DryingRackLuaBridge.jar` | `97a9ccbf543c4962ddf9f6570a7a82b12d30473cd7f2f0a64ded0c2b4842a999` | 3 | 0 hard, 1 soft | all classes in source |

The three committed `build/classes/java/main/**/*.class` files are byte-identical to the classes in
the jar. They are leftover build output, not artifacts.

The soft finding (reflection in `DryingRackBridge`) is real reflective access, all on game objects
with constant names:

- `DryingCraftLogic.getDeclaredField("previousTickElapsedTimes")` + `setAccessible(true)`: reads the
  private map and puts updated elapsed times into it (`advanceDryingProgress`).
- `DryingCraftLogic.getMethod("getAllInProgressCraftData")`, fallback
  `getSuperclass().getDeclaredMethod(...)`, + `setAccessible(true)` and `invoke`: lists in-progress recipes.
- `getClass().getMethod(...)` + `invoke` of the public getters `getEntity` (on the object passed from
  Lua) and `getEntityFullTypeDebug` / `getFullType` / `getFullTypeDebug` (on the entity), to resolve
  the entity and check its type against a fixed list of seven vanilla drying racks.

## What the code does

| File | Purpose |
|---|---|
| `DryingRackBridge` | Lua API (`@Exposer.LuaClass`, 14 `@LuaMethod`s): temperature / drying-factor overrides per `GameEntity` in two static `WeakHashMap`s, vanilla/effective factor queries, `advanceDryingProgress` (offline catch-up via `CraftRecipeData.setElapsedTime`), `getInProgressCount` |
| `Patch_DryingCraftLogic_getDryingFactor` | `@Patch.OnExit` on `DryingCraftLogic.getDryingFactor`, replaces the return value if an override exists |
| `Patch_DryingLogicSystem_getDryingFactor` | Same for `DryingLogicSystem.getDryingFactor(DryingLogic)` |

Bytecode references outside the game, ZombieBuddy and Kahlua: `java.util` collections,
`java.lang.reflect.Field/Method`, `Float`/`Double`, `Math.min`. No file, network, process, native or
class-loading access, no obfuscation. `javap` output matches the source (same members, strings,
calls and annotations). Compiled for Java 17 (class file version 61).

## Lua and other files

No Lua. `DryingRackLuaBridge/mod.info` and `DryingRackLuaBridge/common/mod.info` (identical), README,
LICENSE.txt, `build.gradle` (compiles against local game and ZombieBuddy jars, no extra
dependencies), `.gitignore`/`.gitattributes` (Visual Studio template), and committed build output
(`build/classes`, `build/tmp/jar/MANIFEST.MF`, `build/tmp/compileJava/previous-compilation-data.bin`,
Gradle's incremental-compile cache, not shipped).

## Things a reviewer should look at

- Layout: only a `common/` folder, no `42/` version folder; `mod.info` sits in the mod root and in
  `common/`. Check that Build 42 and ZombieBuddy load the jar from this layout as intended.
- `versionMin=42.16.3`: check this against the game version the registry targets.
- Any Lua mod can change drying speed and recipe progress of drying racks through the API. This is
  the mod's purpose (gameplay only); `advanceDryingProgress` changes crafting state.
- Reflection on `previousTickElapsedTimes` / `getAllInProgressCraftData` depends on game internals and
  fails silently after a game update (correctness, not security).

## Result

Nothing suspicious. Reflection is limited to fixed drying-rack internals of the game.
