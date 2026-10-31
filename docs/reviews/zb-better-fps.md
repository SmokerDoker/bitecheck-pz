# zb-better-fps (preliminary review)

- Source: https://github.com/zed-0xff/ZBBetterFPS, commit `e0159bdb508d940d784878487d022972ae245507` (2026-10-04)
- License: MIT. Author: Zed (zed-0xff). Requires ZombieBuddy (B41 version also `modoptions`).
- Reviewed: 2026-11-01, all 15 Java files (2178 lines), all Lua files searched for risky APIs.

## Jars

All seven jars come from the same source; older folders are builds for older game versions.

| Path | SHA-256 | Classes | Scan | Source check |
|---|---|---|---|---|
| `41/media/java/client/ZBBetterFPS-2x.jar` | `096309efd5ce372563fcb3127adccee40884871a6460277e17b1632e116295b8` | 18 | 0 hard, 3 soft | all classes in source |
| `41/media/java/client/ZBBetterFPS.jar` | `663a11ca40eb3eb9dcb20f5ad5ae4c8c2cc33d469f8de929cf9bb6b679fe55b5` | 18 | 0 hard, 2 soft | all classes in source |
| `42.12/media/java/client/ZBBetterFPS-2x.jar` | `752a5dce1b1641318b4703a23f316ca027878293ba07ebbb761db83cc9601a8f` | 23 | 0 hard, 4 soft | all classes in source |
| `42.12/media/java/client/ZBBetterFPS.jar` | `e651ec5cf3466741e67304d757cbe8c4503a579b152ce176ed5af87547a3dbdd` | 23 | 0 hard, 3 soft | all classes in source |
| `42.13/media/java/client/ZBBetterFPS-2x.jar` | `d79cb445a557e88dce1b45afc7c4a3d57c4c8f48a60003b925a7cdfbb3f1ee70` | 24 | 0 hard, 4 soft | all classes in source |
| `42.13/media/java/client/ZBBetterFPS.jar` | `f9a7e2ca230cf1e4ba7c033563083e885cd33c4faca5c5abfffbe8bcb3477662` | 24 | 0 hard, 3 soft | all classes in source |
| `42.20/media/java/client/ZBBetterFPS.jar` | `c92e32854a758316309e54a4c02bd60aefe1c158111773a2562f21a21455470b` | 24 | 0 hard, 3 soft | all classes in source |

Soft findings are all "method handles" from lambdas / `ThreadLocal.withInitial` and
ZombieBuddy's `VarHandle` field access, not from reflective calls.

## What the code does (per file)

| File | Purpose |
|---|---|
| `ZBBetterFPS` | Option flags exposed to Lua (`@Exposer.LuaClass`) |
| `Utils` | Reads `IsoWorld.currentCell` through a ZombieBuddy `VarHandle` |
| `ZBMetrics` | Completely commented out |
| `Patch_MainLoop` | Frame limiter: `Thread.sleep` when paused or unfocused |
| `Patch_IndieGL` | Caches GL state to skip redundant OpenGL calls |
| `Patch_MultiTextureFBO2` | Instant zoom (skips the zoom animation) |
| `Patch_DefaultShader_B42` | Caches shader uniform values |
| `Patch_VertexBufferObject_B42` | Caches the model-view-projection uniform per shader |
| `Patch_IsoChunkMap` | Limits render distance, syncs the value to a Lua table |
| `Patch_RingBuffer` | Larger sprite batch buffers (`java.nio` buffers only) |
| `Patch_RingBuffer_IsStateChanged` | Merges sprite draw calls that use the same GL texture |
| `Patch_IsoMovingObject_B41/B42_12/B42_13/B42_20` | Faster re-implementation of `IsoMovingObject.separate()` (character pushing/collision), one per game version |

Bytecode references outside the game, ZombieBuddy, LWJGL/JOML and Kahlua are limited to
`java.util`, `java.nio` buffers, `ThreadLocal`, `Thread.sleep`, `System.nanoTime` /
`currentTimeMillis`, `System.out.println` and `java.lang.invoke` (lambdas, string
concatenation, `VarHandle.get`). No file, network, process or class-loading access.

## Things a reviewer should look at

- `Patch_IsoMovingObject_*` replaces a gameplay method completely. That is a correctness risk
  (different collision behaviour after a game update), not a security risk. The mod ships one
  variant per game version and all optimizations are off by default.

## Result

Nothing suspicious.
