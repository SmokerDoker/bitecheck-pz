# z-item-tiers (preliminary review)

- Source: https://github.com/zed-0xff/ZItemTiers, commit `3958fd7c658c2134f8eaaa9baa8abc813b05669f` (2026-10-04)
- License: MIT. Author: Zed (zed-0xff). Requires ZombieBuddy and the `zdk` Lua mod.
- Reviewed: 2026-11-01, every Java file, all Lua files searched for risky APIs.

## Jars

| Path | SHA-256 | Classes | Scan | Source check |
|---|---|---|---|---|
| `42.13/media/java/shared/ZItemTiers-2x.jar` | `61f6f0693ba4442ce7ed024a4297aea90656bba9f83892ddf2d3aee5207a4deb` | 12 | 0 hard, 1 soft | all classes in source |
| `42.13/media/java/shared/ZItemTiers.jar` | `9adc1e46eccb2d67876d422cf57bb97284264da8b8d6ffd3b551a3b2b276191c` | 12 | 0 hard, 1 soft | all classes in source |

The soft finding ("method handles" in `CustomAttributeStorage`) comes from a lambda
(`LambdaMetafactory`), not from reflective access.

## What the code does

Patches on `InventoryItem`, `HandWeapon`, `Clothing` and `InventoryContainer` that apply a
rarity tier (Common … Legendary) to items and scale their stats (6 Java files, 144 lines);
`CustomAttributeStorage` keeps extra per-item values in an in-memory `ConcurrentHashMap`
(not persisted); `ZItemTiersHelper` holds shared helpers. Only game
classes and `java.util` are used. No file, network, process or class-loading access.

## Lua

Item tier tables, tooltips and options. No `getFileWriter`/`getFileReader`, no URL or
HTTP calls, no `loadstring`.

## Result

Nothing suspicious.
