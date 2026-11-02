# staircast (preliminary review)

- Source: https://github.com/copiumsawsed/pz-Staircast, tag `v1.1.0` = commit `0f3035702951cc337526a0d83c69df8dea64b2bb` (2026-04-02)
- License: MIT. Author: copiumsawsed. Requires ZombieBuddy.
- Release type: workshop (jar from Workshop item 3684713089, rebuilt from source by CI)
- Reviewed: 2026-11-02, every Java file of the Build 42 build (`src/core` and `src/b42`, 12 files,
  895 lines), `pom.xml`, `src/b42/pom.xml`, the `mod.info` template, the other repository files.
  `src/b41` (Build 41 `Game.java`, `pom.xml`, `mod.info`) is only built with `-P b41` and is not
  part of the Build 42 jar; it was not reviewed. The Build 41 jar in the Workshop item
  (`mods/Staircast/media/java/client/staircast.jar`) is out of scope.

## Jars

| Path | SHA-256 | Classes | Scan | Source check |
|---|---|---|---|---|
| `mods/Staircast/42.13/media/java/client/staircast.jar` | `f58c41c7f21020ba22760d52b51656bca231d794d64e721a7119665bcdb8bc5b` | 25 | 0 hard, 2 soft | all files rebuilt byte-identical from source by CI (two runners) |

The 25 classes match the source: 12 top-level classes plus 13 nested `Patch_*` classes.

## Build

- `pom.xml` (parent, packaging `pom`): profile `b42` is active by default and only adds module
  `src/b42`. Plugins, all with fixed versions: `maven-jar-plugin` 3.4.1 (writes the jar to
  `Contents/mods/Staircast/42.13/media/java/client/`, no Maven descriptor),
  `build-helper-maven-plugin` 3.6.1 (`add-source` with the single directory
  `../core/src/main/java`), `maven-resources-plugin` 3.4.0 (copies `src/main/meta` with
  filtering to the mod folder, i.e. produces `42.13/mod.info`; it does not go into the jar).
  No exec, antrun, shade, download or code generation plugins, no `<repositories>`, no extra
  resources in the jar. Compiler, install and other default lifecycle plugins are not pinned in
  the POM; their versions come from the Maven version CI uses.
- `src/b42/pom.xml`: sets `pz.modfolder=42.13/` and adds the game jar as dependency.
- Dependencies, both `provided` (compile classpath only, not bundled):
  `me.zed_0xff:zombie_buddy` `[1.6.4,)` and `projectzomboid:projectzomboid` `[42.13,)`. Both are
  open version ranges; the README installs them locally with `mvn install:install-file`. CI
  should supply known jars (and ideally build offline), since javac would also run annotation
  processors found in jars on the classpath. The byte-identical class check covers the result.
- `mod.info` template: `id=Staircast`, `require=\ZombieBuddy`, `versionMin=42.13`,
  `javaJarFile=media/java/client/staircast.jar`, `javaPkgName=pzmod.staircast`, poster and icon
  from `../common/`, `modversion=${project.version}` (1.1.0). Nothing else.

## What the code does

ZombieBuddy patches (Byte Buddy advice, `@Patch.OnEnter`/`@Patch.OnExit`) on render methods. When
the camera player stands on a staircase, faces upstairs and the head bone is above the next
floor, the mod renders the frame as if the player were one floor up, then puts everything back.

- `Main`/`Mod`/`DebugOptions`: registers an option group `Mod.Staircast` under the game's
  `DebugOptions` (`Enable`, plus debug-only toggles such as `LogCalls`, `DrawTargetSquare`,
  `AssignRoomPermanently`, `TestGracefulFailure`). `trace()` writes to `DebugLog` only when
  `LogCalls` is on.
- `Patch_IsoWorld` (`IsoWorld.renderInternal`, on enter): decides per frame whether to fake the
  view (stairs check, look-angle cone, head height via `CombatManager.getBoneWorldPos`, upper
  square exists) and fills `FakeFrameState` for that player index: real position/square, fake
  position (same x/y, z of the floor above), target square and floor square at the top of the
  stairs. Disables the upper-floor lighting when a zombie stands on a stair square next to the
  player ("zombies hiding in the dark" workaround). Optional debug drawing with `LineDrawer`.
- `FakeFrameState`: per-player state (array of 4) that is only "active" for the frame whose
  `frameCount` it was set in. `apply(...)` sets character x/y/z and current square, or the
  `IsoCamera.FrameState` camera character position/square. `apply(dst, src)` copies the room of
  the floor square to the target square if it has none (`room`, `roomId`) and clears its
  `exterior` flag; `reset` undoes that, but returns early while `AssignRoomPermanently` is on
  (default `true`), so this change stays on the client's in-memory map square.
- `Patch_IsoCell` / `Patch_FBORenderCell` (`renderInternal` enter, `renderTilesInternal` exit):
  switch camera frame state and camera character to the fake position before the tiles are
  rendered, back to the real position afterwards (`IsoCell` for the classic renderer, the
  `FBORenderCell` versions for the B42 chunk renderer).
- `Patch_IsoPlayer` (`IsoPlayer.render`) and `FBORenderCell.renderPlayers`: draw the player at the
  real position during a faked frame (render arguments x/y/z are overwritten), restore on exit.
- `FBORenderCell.isPotentiallyObscuringObject`: during a faked frame, objects with a sprite on the
  fake floor's level are reported as potentially obscuring (original method skipped, return
  value forced to `true`), so the game's fade/cutaway handles them.
- `Patch_FBORenderTrees` (`init`): uses the real camera position for tree setup, restores after.
- `Patch_WeatherFxMask` (`initMask`, `renderFxMask`): fake position and the room/exterior change
  for the weather mask, restored on exit.
- `Patch_LightingJNI` (`checkPlayerTorches`, `updatePlayer`): these are Java methods of the game
  class `zombie.iso.LightingJNI` that gather player data and pass it to the game's native lighting
  code. The patch only moves the player object to the fake position before the original method
  runs (when lighting for the upper floor is enabled) and restores the position on exit, so the
  game computes light for the floor above. In multiplayer (`GameClient.client`) it acts only on
  the local player. The mod itself loads no native library and calls no native method.
- `Game` (b42): thin wrappers over game fields and methods for version differences.

**Recovery on render error (commit `d4d39b5`).** Every patch wraps its body in `try/catch` and
calls `FakeFrameState.recoverFromError`. It prints the stack trace, and if a fake frame is active:
puts the camera character back to the real position and square, sets its last position (see bug
below), resets the camera `FrameState` to the real position/square and undoes the target
square's room/exterior change (unless `AssignRoomPermanently`). Then it sets the `Enable` option
to `false` and logs "mod disabled due to an error", so the mod stays off until re-enabled. Only
the `IsoWorld` patch prints the error without this recovery (it runs before any state is
changed). `TestGracefulFailure` (debug only) throws on purpose to test this path.

**Reflection.** One use: `Patch_IsoWorld` reads the private field `IsoWorld.drawWorld` via
`getDeclaredField` + `trySetAccessible` + `getBoolean` (read only). If the field is missing, the
mod never activates. No `MethodHandles`, no `Unsafe`, no `Class.forName`, no class loaders.

**Not present:** network or packets (only the `GameClient.client` flag is read), file access
(output only via `DebugLog`, `System.err`, `printStackTrace`), process start, native libraries,
class loading, threads, obfuscation. All code concerns rendering the upper floor.

**Multiplayer:** purely client-side rendering. Position changes on the player object last only
for the duration of the patched render/lighting method and are restored on exit; nothing is sent.

**Bug (harmless):** `Game.setLastPos` (b42) calls `setLastX` three times (x, then y, then z)
instead of `setLastX`/`setLastY`/`setLastZ`. Only on the error-recovery path; `lastX` ends up
wrong for a frame.

## Lua and other files

No Lua. `Contents/mods/Staircast/common/` holds two PNGs (icon, poster). Further: `README.md`,
`LICENSE`, `preview.png`, `workshop.txt` (Workshop description), `.gitignore` (excludes jars and
generated `mod.info`), `.gitattributes`.

## Result

Nothing suspicious. Small, readable render patches; build compiles only `src/b42` and
`src/core` with pinned, standard plugins. A human reviewer must still check:

- that the position swaps are always restored on the same thread before any network update
  (render path vs. the game's player position sync), in single player and multiplayer;
- the permanent client-side room/`exterior` change on the square above the stairs (default
  `AssignRoomPermanently=true`): visual only, or could it affect client-side gameplay checks
  (inside/outside, rain, temperature)?
- that the CI build uses known `projectzomboid` and `zombie_buddy` jars and a fixed Maven version
  (open version ranges, unpinned default plugins);
- the `setLastPos` bug (report upstream).

## CI check (pull request #3)

Both runners built `Contents/mods/Staircast/42.13/media/java/client/staircast.jar` (identical,
`c7efc9cd…`); `bcz jar-compare` found the Workshop jar (Workshop manifest 2210927596768167393) to
contain exactly the same files. The two soft scan findings: method handles in `Mod` and the
`Field` access in `Patch_IsoWorld` (`IsoWorld.drawWorld`, read only), both described above.
