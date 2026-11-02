# zb-macos-hide-menu-bar (preliminary review)

- Source: https://github.com/zed-0xff/ZBMacOSHideMenuBar, commit `53fab6ccfe6e3076cec443519181c7e64fac7129` (2026-10-04)
- License: MIT. Author: Zed (zed-0xff). Requires ZombieBuddy (`-2x.jar` for ZB 2.x, the other jar for ZB 3.x). macOS only.
- Reviewed: 2026-11-02, every file in the repository (2 Java files, 202 lines); `javap -v -p` of all classes in both jars compared with the source.

## Jars

| Path | SHA-256 | Classes | Scan | Source check |
|---|---|---|---|---|
| `42/media/java/client/ZBMacOSHideMenuBar-2x.jar` | `ae8abf4cd75b70400fbf8629b62e1ff9fcd1bd178f0162c21bf0476a249469db` | 3 | 0 hard, 2 soft | all classes in source |
| `42/media/java/client/ZBMacOSHideMenuBar.jar` | `ff6e2eb03c46551c7fb9d271c680155b9c3fedab9706c43c0e807a8fa9afb23e` | 3 | 0 hard, 2 soft | all classes in source |

Both soft findings ("method handles") come from `invokedynamic` bootstraps only: string
concatenation (`StringConcatFactory`) in `MacOSMenuBarHelper` and a lambda (`LambdaMetafactory`)
in `Main`. No reflective access.

The two jars contain the same code. `ZBMacOSHideMenuBar.jar` is built for Java 25 (class version 69)
against ZombieBuddy 3 (`me.zed_0xff.zombie_buddy.annotations.Patch`). `ZBMacOSHideMenuBar-2x.jar` is
byte-identical to the main jar of the parent commit (`a87eecc^`): Java 17 (class version 61), old
annotation package `me.zed_0xff.zombie_buddy.Patch`. That one-line import change is the only source
difference between the two builds; all constants, strings, members and calls are otherwise identical.

## What the code does

`Main.Patch_setBorderlessWindow`: ZombieBuddy `@Patch.OnExit` on `org.lwjglx.opengl.Display.setBorderlessWindow`.
When borderless mode is on and `os.name` contains "OS X", it runs on the render thread: hide menu bar,
`GLFW.glfwSetWindowPos(window, 0, 0)`, disable the window shadow. When borderless mode is off, it
restores the menu bar. `Main.main` does the same once at load if the game is already borderless.

`MacOSMenuBarHelper` is the native part. It calls into the Objective-C runtime through LWJGL
(`ObjCRuntime`, `JNI.invokePPP`/`invokePPPI` → `objc_msgSend`, address taken from LWJGL's own
libobjc handle). Every call, with its arguments taken from constants:

| Call | Purpose |
|---|---|
| `objc_getClass("NSApplication")`, `sel_getUid("sharedApplication")`, `objc_msgSend` | get `NSApp` |
| `[NSApp setPresentationOptions: 6]` | hide menu bar (2) + hide Dock (4); Apple requires HideMenuBar to be combined with a Dock option |
| `[NSApp setPresentationOptions: 0]` | restore default (menu bar and Dock visible) |
| `glfwGetCocoaWindow(window)`, `[window setHasShadow: NO]` | remove the 1px border of the borderless window |

Selector names are fixed strings (`sharedApplication`, `setPresentationOptions:`, `setHasShadow:`);
no selector, class or function address comes from outside, no native library is loaded, nothing
is written to memory. The native access is limited to hiding/restoring the menu bar and Dock and
disabling the window shadow of the game's own window. All native calls are guarded by the macOS
check, so on Windows/Linux the mod does nothing.

Other bytecode references: `System.getProperty("os.name")`, `System.out.println` (one log line per
`setBorderlessWindow` call), `DebugLog.log`, `Exception.printStackTrace`, `Core`, `RenderThread`,
`Display`, `GLFW`. No file, network, process, reflection or class-loading access, no obfuscation.

## Lua and other files

`42/media/lua/client/ZMacOSHideMenuBar.lua` is empty (0 bytes). `common/mod.info`, `42/preview.png`,
`common/preview.xcf` (GIMP source of the preview), README, `steam.txt`, LICENSE, `.github/FUNDING.yml`,
`.gitignore`, `.workshopignore`, Rakefile and `java/build.gradle` (author's build helpers, not shipped
logic; Gradle uses the `io.github.zed-0xff.zb-gradle-plugin` 1.0.1 plugin and local game jars).
`*.jar.zbs` are the author's ZombieBuddy signature files (SteamID64 + signature), not code.

## Things a reviewer should look at

- The native calls were checked against the source and bytecode, not run. A human with a Mac
  should confirm the behaviour (menu bar and Dock hidden in borderless mode, restored when it is off).
- The mod also hides the Dock while the game is borderless. The README only mentions the menu bar.
- The `-2x.jar` is an older build (parent commit), not a build of this exact commit. The code is the
  same apart from the ZombieBuddy annotation package.

## Result

Nothing suspicious. The native code only calls `setPresentationOptions:` on `NSApp` and
`setHasShadow:` on the game window.
