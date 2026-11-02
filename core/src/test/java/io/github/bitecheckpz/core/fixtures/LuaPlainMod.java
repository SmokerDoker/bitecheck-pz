package io.github.bitecheckpz.core.fixtures;

import se.krka.kahlua.integration.annotations.LuaMethod;

/** Scanner fixture: a harmless global Lua function. */
public final class LuaPlainMod {
    private LuaPlainMod() {
    }

    @LuaMethod(name = "greeting", global = true)
    public static String greeting(String name) {
        return "Hello " + name;
    }
}
