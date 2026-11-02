package io.github.bitecheckpz.core.fixtures;

import java.io.File;
import se.krka.kahlua.integration.annotations.LuaMethod;

/** Scanner fixture: a global Lua function that deletes any file Lua names. */
public final class LuaFileMod {
    private LuaFileMod() {
    }

    @LuaMethod(name = "deleteAnything", global = true)
    public static boolean delete(String path) {
        return new File(path).delete();
    }
}
