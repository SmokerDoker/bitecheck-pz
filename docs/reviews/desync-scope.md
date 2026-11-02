# desync-scope (preliminary review)

- Source: https://github.com/justinjd00/knoxdrift, commit `68d3bd5fd27fc99546958e2a146730737272a4e4` (2026-08-17)
- License: MIT. Author: jd (justinjd00). Requires ZombieBuddy (`mod.info`: `require=\ZombieBuddy`, `ZBVersionMin=1.6.0`);
  runs on client and server.
- Reviewed: 2026-11-02, all 19 mod Java files (1492 lines) and the test, jar rebuilt from source and compared,
  `javap -v -p` of all classes, all 4 Lua files, `mod.info`, Gradle files and every other file in the repository.
  Reflection and patch targets checked against the local `projectzomboid.jar`.

## Jars

| Path | SHA-256 | Classes | Scan | Source check |
|---|---|---|---|---|
| `DesyncScope/42/media/java/DesyncScope.jar` | `780c7bd426c025faad73bfedf09d1efc0cbdbbe7034ce7fcc9916ecbce0b0089` | 21 | 0 hard, 18 soft | all classes in source |

The jar matches the source exactly: compiling `DesyncScope/java/src` with `javac --release 17` (JDK 21) against
stubs of the ZombieBuddy annotations gives 21 class files that are byte-identical to the ones in the jar.
`java/test/SmokeTest.java` is an offline test and not in the jar.

Soft findings:

- "method handles" in all 16 flagged classes: string concatenation (`StringConcatFactory`) and lambdas /
  method references (`LambdaMetafactory`). No reflective access through them.
- "reflection" in `Refl`, the mod's helper for all game access (the mod has no compile-time dependency on the
  game). Class names and method names are constants in the code, none come from Lua or the network:
  `Class.forName` without initialisation; `getDeclaredMethods` lookup by name and argument count up the class
  hierarchy, then `setAccessible(true)` and `invoke` (all methods it looks up are public getters anyway);
  `getField` for public fields `x`/`y`/`z` of vector objects; no-arg constructor of `zombie.iso.Vector3` and
  `org.joml.Vector3f` as scratch vectors. Methods called: `ZomboidFileSystem.getInstance`, `getCacheDir`,
  `LuaManager$GlobalObject.isServer`/`isClient`, `IsoPlayer.getInstance`, `getX`/`getY`/`getZ`,
  `getPlayer`, `getPosition(Vector3)`, `getPlayerId`, `getVehicle`, `getVelocity(Vector3f)`, `isLocalPhysicSim`,
  `getId`, `UdpConnection.getBufferPosition`. All are reads; `getPosition`/`getVelocity` fill the mod's own
  scratch vector.
- "accesses files" in `DesyncScope` and `Samples`: see below.

## What the code does

ZombieBuddy patches, all `@Patch.OnEnter`/`@Patch.OnExit` advice returning `void`, that only read and count:
`PacketsCache.getPacket` (counts packet types by class name), `UdpConnection.endPacket` /
`endPacketImmediate` / `endPacketUnordered` / `endPacketUnreliable` / `endPacketSuperHighUnreliable` (counts
outgoing packets per send variant and reads the buffer position as size; for `endPacket(int, int, byte)` it
records the priority/reliability arguments as text), `PlayerPacket.processClient` and
`VehiclePhysicsPacket.processClient` (distance between the position in the packet and the current local
position, before the packet is applied). No patch changes arguments, return values or packet buffers, skips
the original method or sends anything; RakNet is only observed. No socket, URL, process, native code or class
loading; nothing is transmitted anywhere. Data stays in memory, on the console and in local files.

`Main.main` (called by ZombieBuddy) logs and registers a shutdown hook that closes an open CSV. The
`@Exposer.LuaClass` `DesyncScope` gives Lua scalar/string functions (enable, thresholds, HUD lines, report,
start/stop recording); none takes a path.

Files: only under `<cache dir>/DesyncScope`, file names fixed (`report-<side>-<timestamp>.txt`,
`samples-<side>-<timestamp>.csv`). The cache dir is asked from `ZomboidFileSystem.getInstance()`; that method
does not exist in the current game build (the game has a static field `instance`), so the mod always falls back
to `System.getProperty("user.home") + "/Zomboid/DesyncScope"`, also when the server uses `-cachedir`. CSV
capture is off by default and capped at 500000 rows (Lua can raise the cap, minimum 1000).

## Lua and other files

- `shared/Shared.lua`: config values, pushes them into Java. `server/Server.lua`: writes a report every 15
  minutes (`DesyncScope.writeReport()`), flushes the CSV. `client/Client.lua`: F10 HUD toggle and console helpers
  (`dsRec()`, `dsSave()` …). `client/HUD.lua`: overlay drawing; `require` only of vanilla `ISUI/ISPanel`.
- No `OnClientCommand`/`sendClientCommand`, so players cannot trigger anything on the server. No
  `getFileWriter`/`getFileReader`, no URL or HTTP calls, no `loadstring`.
- Other files: README files, Gradle build (resolves `ZombieBuddy.jar` locally, no extra dependencies),
  `.gitignore`, `docs/*.md` (German research notes). `mod.info` lies only in `DesyncScope/common/`, not in `42/`.

## Result

Nothing suspicious. Measure-only as described; no packet is sent or altered, no data leaves the machine.
For a human reviewer: the source uses `me.zed_0xff.zombie_buddy.annotations.Patch` and
`me.zed_0xff.zombie_buddy.Exposer`; check that these match the ZombieBuddy version the mod claims
(`ZBVersionMin=1.6.0`). Not safety issues: reports land in `~/Zomboid/DesyncScope` regardless of `-cachedir`
(see above), and `Game` looks up `zombie.iso.IsoPlayer` (the class is `zombie.characters.IsoPlayer`), so the
`player.local` stream stays empty.
