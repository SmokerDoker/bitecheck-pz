# Preliminary reviews

These notes record a first, file-by-file look at mods that were added to the registry as
`status = "pending"` entries. They are **not** signed reviews and do not make anything
`VERIFIED`. A human reviewer reads the source again, compares it with these notes and, if they
agree, signs a review with `bcz review` (see CONTRIBUTING.md).

Every entry here except staircast (a `workshop` release) is a `published` release: the jars the author committed to the repository at
the given commit. These upstream mods are not built with the BiteCheck Gradle plugin, so CI does
not rebuild them; instead `bcz source-check` confirms that every class in the jar is declared in
the public source, and the bytecode is compared with the source by hand (`javap -v -p`).

| Mod | Upstream commit | Jars | Hard findings | Notes |
|---|---|---|---|---|
| [zb-hello-world](zb-hello-world.md) | `c5c637d` | 2 | 0 | Draws one line of text on the main menu |
| [z-item-tiers](z-item-tiers.md) | `3958fd7` | 2 | 0 | Item rarity system, needs the `zdk` Lua mod |
| [zb-better-fps](zb-better-fps.md) | `e0159bd` | 7 | 0 | Rendering/CPU optimizations, all off by default |
| [z-mod-unbork](z-mod-unbork.md) | `a9b3405` | 2 | 0 | Compatibility shims for older mods; Lua part needs `zdk` (not reviewed) |
| [zb-macos-hide-menu-bar](zb-macos-hide-menu-bar.md) | `53fab6c` | 2 | 0 | Fixed native calls via LWJGL; also hides the Dock (not in its README) |
| [macos-trackpad-fix](macos-trackpad-fix.md) | `88cb288` | 1 | 0 | Trackpad zoom; reflection on two zoom fields; author calls it an experiment |
| [zb-lua-perf-mon](zb-lua-perf-mon.md) | `f95adf9` | 2 | 0 | Lua performance overlay; its log writer is dead code |
| [z-better-workshop-upload](z-better-workshop-upload.md) | `c7bd197` | 2 | 0 | Filtered Workshop upload; staging copy in the OS temp folder; `-2x` jar from an older commit |
| [real-reset](real-reset.md) | `b024f8f` | 1 | 0 | Server admin chunk reset; paths stay in the save folder, admin only |
| [desync-scope](desync-scope.md) | `68d3bd5` | 1 | 0 | Multiplayer desync diagnostics, only measures; reports in `~/Zomboid/DesyncScope` |
| [staircast](staircast.md) | `0f30357` | 1 | 0 | See the floor above on stairs; `workshop` release: Workshop jar rebuilt from source by CI |

`bcz inspect` also checks the layout: a version folder (`42/`, `42.x/`) next to `common/`, `mod.info`
in one of them, and every jar named in `javaJarFile`. drying-rack-lua-bridge was left out because
it has no version folder.
