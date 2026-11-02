# real-reset (preliminary review)

- Source: https://github.com/DrengrCode/RealReset, commit `b024f8f7ec59497a0330ce106980ee420d1e0730` (2026-10-14)
- License: MIT. Author: HoofHearted (DrengrCode). Server-side admin tool; needs the ZombieBuddy agent on the
  dedicated server and the Admin Zone Reset mod (reads its zone file). `mod.info` has no `require=` line; players
  only need the Lua part.
- Reviewed: 2026-11-02, all 7 Java files (the one mod source file `RealReset.java`, 571 lines, line by line),
  jar rebuilt from source and compared, `javap -v -p` of all classes, both Lua files (path handling and the
  client-command handler read in full, the rest searched for risky APIs), `mod.info`, `build.sh`, tests.
  Reflection targets checked against the local `projectzomboid.jar`.

## Jars

| Path | SHA-256 | Classes | Scan | Source check |
|---|---|---|---|---|
| `Contents/mods/RealReset/42/media/java/RealReset.jar` | `97440e953b2326be47bb037223a7425ffee445ebeb8f6ac3a94b3387d3e6d85b` | 4 | 0 hard, 5 soft | all classes in source |

The jar matches the source exactly: compiling `src/com/drengrcode/realreset/RealReset.java` with the command
from `build.sh` (`javac --release 17`, JDK 21) gives four class files that are byte-identical to the ones in the jar.
Of the 7 Java files only `RealReset.java` is shipped; the others are not in the jar:
`stubs/.../LuaMethod.java` is a compile-time stub of the game's Kahlua annotation, `tests/RestoreTest.java` and
`tests/fake/zombie/**` (4 files) are an offline test with fake game classes.

Soft findings:

- "accesses files" in `RealReset`: the chunk move/restore/prune logic described below. In `RealReset$1..$3`:
  anonymous `Comparator<File>` classes that only compare file names for sorting.
- "reflection" in `RealReset`: `Class.forName` + public `getField`/`getMethod`, no `setAccessible`, read-only:
  `zombie.ZomboidFileSystem.instance.getCacheDir()`, static strings `zombie.network.GameServer.ServerName` /
  `.serverName` and `zombie.core.Core.GameSaveWorld` (world folder name), `GameServer.getPlayers()` with
  `getX()`/`getY()` on each player, `zombie.network.ServerMap.instance.getChunk(int, int)` (is the chunk loaded).
  "method handles": string concatenation (`StringConcatFactory`) and the method reference `File::isDirectory`.

## What the code does

Static functions exposed to Lua as globals via `@LuaMethod(global = true)` (`RealReset_selfTest`, `_moveChunk`,
`_previewChunk`, `_restoreChunk`, `_beginRun`, `_endRun`, `_listRuns`, `_runChunks`, `_findLatestRun`,
`_pruneTrash`, `_ceilingInfo`, `_chunkReport`, `_ping`, `_isReady`). A reset renames the chunk file
`map/<wx>/<wy>.bin` into `_realreset_trash/run_<millis>__<label>/<wx>_<wy>.bin`; a restore renames it back
(an existing regenerated file is moved into `replaced/` first). Only `File.renameTo`, `mkdirs`, `listFiles`,
`delete`; no network, process, native code or class loading.

Path handling:

- Every path is below `<cacheDir>/Saves/Multiplayer/<world>`. The world name comes from the JVM property
  `-Drealreset.world`, else the game's server name (in the current game build the field is `GameServer.serverName`;
  `ServerName` and `Core.GameSaveWorld` do not exist and are skipped). The resolved folder must be a directory and,
  after canonicalisation, inside `Saves/Multiplayer`; otherwise it falls back to the only folder there, or refuses.
  These inputs are set by the server operator, not by players.
- Chunk coordinates are Java `int`s, so `map/<wx>/<wy>.bin` cannot contain path separators or `..`.
- The batch label is reduced to `[A-Za-z0-9_-]`, max. 40 characters. Batch ids received from Lua (`runId`, which
  an admin client can send) must match `^run_\d{10,16}__[A-Za-z0-9_-]{0,40}$` before use, which rules out
  traversal.
- Deleting: only in trash housekeeping. Files in `_realreset_trash` whose names match the mod's own patterns
  (`<wx>_<wy>[_<n>].bin`) are deleted once they are older than 7 days or the trash holds more than 20000
  (`-Drealreset.trashDays` / `trashMax`); other files are left alone, and folders are deleted only when empty.
  "Never hard-deletes" in `mod.info` therefore means the reset itself; trashed chunks are gone after 7 days.

I found no way to move or delete a file outside the world save folder.

Who can trigger it: the Java side does not check the caller (the source says so). The server Lua's
`OnClientCommand` handler rejects every command from a player whose server-side `getAccessLevel()` is not
`admin`; a RESET or RESTORE also needs a dry run (PLAN) by the same admin within the last 10 minutes. Other
server-side Lua mods could call the globals directly; the Java side still enforces its own limits: refuses until
the self-test passes, minimum radius of 4 chunks around every online player, skips chunks loaded in server memory,
at most 3000 moves per 10 minutes.

## Lua and other files

- `server/RealReset_Server.lua`: admin check, zone parsing, safehouse/protected-zone/claimed-vehicle checks, a
  queue that calls the Java functions. `getFileReader`/`getFileWriter` only with fixed names in the game's Lua
  folder: `AdminZoneReset_ServerZones.txt` (read), `RealReset_Config.txt` (read/write, admin "SETTINGS", values
  clamped, message stripped of control characters, max. 300 characters), `RealReset_Log.txt` (append),
  `SDVC_Claims/...` from Sunday Driver (read; user names with `/`, `\` or `..` are skipped).
- `client/RealReset_Client.lua`: the admin panel; only `sendClientCommand` to the server and `require` of
  vanilla `ISUI/*` files.
- No URL or HTTP calls, no `loadstring`, no `os.execute`/`io.*` in the shipped Lua (`os.date` only for a time stamp).
- `build.sh` (author's build, see above), `tests/run.sh` and `tests/lua/*_mock.lua` (offline tests), README,
  CHANGELOG, release notes, two identical `mod.info` files.

## Result

Nothing suspicious. The tool does what it says, and file access stays inside the world save folder.
For a human reviewer: this mod moves and, after 7 days, permanently deletes world chunk files by design;
check that the admin-only gate (`getAccessLevel() == "admin"` in the server Lua) is acceptable, and that
ZombieBuddy actually registers the `@LuaMethod` globals for this jar (not verified here).
