# macos-trackpad-fix (preliminary review)

- Source: https://github.com/fopwoc/ProjectZomboidTouchpadFix, commit `88cb28842c9bea786900fd07b808ceba4a1a63cc` (2026-07-31 UTC; local commit time 2026-08-01 01:53 +0300)
- License: WTFPL. Author: Fopwoc (fopwoc), copyright holder Ilya Dobryakov. Requires ZombieBuddy (`ZBVersionMin=2.3.2`), `versionMin=42.0.0`.
- Reviewed: 2026-11-02, every file in the repository (one Java file, 86 lines); `javap -v -p` of the class compared with the source.

## Jars

| Path | SHA-256 | Classes | Scan | Source check |
|---|---|---|---|---|
| `42/media/java/client/MacosTrackpadFix.jar` | `53cccf512825c505f13ddbe1c7d31a10ded367acacf139299d9bfd37bef79239` | 1 | 0 hard, 1 soft | all classes in source |

The soft finding combines two things: real reflection (`java.lang.reflect.Field`, see below) and
"method handles" (`MethodHandles$Lookup`), which come only from string concatenation
(`StringConcatFactory`), not from a reflective call.

## What the code does

One class, `MouseWheelPatch`: a ZombieBuddy `@Patch.OnEnter` on `org.lwjglx.input.Mouse.setDWheel`
that logs every wheel event (x, y) to stdout. For fractional (trackpad) deltas it reads two private
fields of the game class `zombie.core.textures.MultiTextureFBO2` via `getDeclaredField` +
`setAccessible(true)`: `targetZoom` and `zoomLevels` (both `float[]`), on the instance
`Core.getInstance().offscreenBuffer`. It then writes a new value into `targetZoom[0]`
(`previous * exp(y * 0.08)`, clamped to the min/max zoom level). That is the only reflective access;
names are constant strings, targets are game classes only. Errors are caught and printed to stderr.

Bytecode references outside the game and ZombieBuddy: `System.out`/`System.err`, `PrintStream`,
`java.lang.Math`, `Class.getDeclaredField`, `Field.setAccessible/get`, `Throwable` and
`StringConcatFactory`. No file, network, process, native or class-loading access, no obfuscation.
`javap` output matches the source (same constants, strings and calls; no extra members).
Compiled for Java 25 (class file version 69).

## Lua and other files

No Lua. `42/mod.info`, `common/icon.png`, `common/poster.png`, README, LICENSE, COPYRIGHT,
`.gitignore`, and the Gradle build in `java/` (`build.gradle.kts`, wrapper scripts and
`gradle-wrapper.jar`, not shipped in the mod). The build pulls the `io.github.zed-0xff.zb-gradle-plugin`
1.0.3 plugin and compiles against local game and ZombieBuddy jars.

## Things a reviewer should look at

- The README calls the mod an unfinished experiment / proof of concept (no reliable way to tell
  finger scrolling from momentum scrolling). It is a working patch, but users should know that.
- Every wheel event prints up to three lines to the console log. Noisy, not harmful.
- The patch is not limited to macOS; on other systems it only acts on fractional wheel deltas.
- `gradle-wrapper.jar` was not compared with the official Gradle 9.6.1 wrapper (build tooling only).

## Result

Nothing suspicious. Reflection only reads/writes the camera zoom fields of `MultiTextureFBO2`.
