# zb-hello-world (preliminary review)

- Source: https://github.com/zed-0xff/ZBHelloWorld, commit `c5c637d8106098942699413b09cb8a0652b4e59a` (2026-10-04)
- License: MIT. Author: Zed (zed-0xff). Requires ZombieBuddy.
- Reviewed: 2026-11-01, every file in the repository.

## Jars

| Path | SHA-256 | Classes | Scan | Source check |
|---|---|---|---|---|
| `42/media/java/client/ZBHelloWorld-2x.jar` | `817cde27c290801c6f121d1e0d1ba6fb8baa1c4a133d268e4927e55e3ccddba2` | 1 | 0 hard, 0 soft | all classes in source |
| `42/media/java/client/ZBHelloWorld.jar` | `73ea63e2d02099a1b5f4770c05d3e79cb63d9533e5095ca75f12dc21a136dbc8` | 1 | 0 hard, 0 soft | all classes in source |

## What the code does

One class, `Patch_MainScreenState`: a ZombieBuddy patch on the main menu's render method that
draws the text "Hello World from ZBHelloWorld!". No file, network, process, reflection or
class-loading access. `javap` output matches the source.

## Lua and other files

No Lua. `mod.info`, images, README, Rakefile (build helper for the author, not shipped logic).

## Result

Nothing suspicious. Suitable as the first, trivial registry entry.
