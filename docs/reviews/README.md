# Preliminary reviews

These notes record a first, file-by-file look at mods that were added to the registry as
`status = "pending"` entries. They are **not** signed reviews and do not make anything
`VERIFIED`. A human reviewer reads the source again, compares it with these notes and, if they
agree, signs a review with `bcz review` (see CONTRIBUTING.md).

Every entry here is a `published` release: the jars the author committed to the repository at
the given commit. ZombieBuddy mods compile against the game's own (non-redistributable) jars,
so CI cannot rebuild them; instead `bcz source-check` confirms that every class in the jar is
declared in the public source, and the bytecode references are compared by hand
(`javap -v -p`).

| Mod | Upstream commit | Jars | Hard findings | Notes |
|---|---|---|---|---|
| [zb-hello-world](zb-hello-world.md) | `c5c637d` | 2 | 0 | Draws one line of text on the main menu |
| [z-item-tiers](z-item-tiers.md) | `3958fd7` | 2 | 0 | Item rarity system, needs the `zdk` Lua mod |
| [zb-better-fps](zb-better-fps.md) | `e0159bd` | 7 | 0 | Rendering/CPU optimizations, all off by default |
