package com.example.mymod;

import me.zed_0xff.zombie_buddy.Patch;
import zombie.ui.TextManager;
import zombie.ui.UIFont;

/**
 * Harmless example: draws one line of text on the main menu. ZombieBuddy finds every {@code @Patch}
 * class in the package named by {@code javaPkgName} in mod.info; no Main class is needed.
 */
@Patch(className = "zombie.gameStates.MainScreenState", methodName = "renderBackground")
public class Patch_MainScreenState {

    @Patch.OnExit
    public static void exit() {
        TextManager.instance.DrawString(UIFont.Medium, 0, 20, "Hello from MyMod (built with BiteCheck)",
                1.0, 1.0, 1.0, 1.0);
    }
}
