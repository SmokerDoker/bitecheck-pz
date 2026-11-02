package io.github.smokerdoker.bitecheck;

import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;

/**
 * The {@code bitecheck { }} block of a mod's build script. Every setting has a default; a typical mod
 * only sets {@code modName}.
 */
public abstract class BiteCheckExtension {

    /** Java version of the game's runtime. Project Zomboid Build 42 ships Java 25. Default: 25. */
    public abstract Property<Integer> getJavaVersion();

    /**
     * Project Zomboid install directory (game or dedicated server). Default: Gradle property
     * {@code pzGameDir}, else environment variable {@code PZ_GAME_DIR}, else the Steam install found
     * on this computer.
     */
    public abstract DirectoryProperty getGameDir();

    /** Name of the jar (without ".jar"), as referenced by {@code javaJarFile} in mod.info. Default: project name. */
    public abstract Property<String> getModName();

    /** The game version folder of the mod, e.g. "42" or "42.13". Default: "42". */
    public abstract Property<String> getGameVersion();

    /**
     * Where the jar goes inside {@code <gameVersion>/media/java/}: "client" (game client only),
     * "server" (dedicated server only) or "shared" (both). Default: "client".
     */
    public abstract Property<String> getSide();

    /** Mod files (mod.info, Lua, textures, ...) copied into the mod folder. Default: src/mod. */
    public abstract DirectoryProperty getModDir();

    /** ZombieBuddy release whose API the mod compiles against. Default: 2.3.4. */
    public abstract Property<String> getZombieBuddyVersion();

    /** Download URL of ZombieBuddy.jar. Default: the GitHub release asset of {@link #getZombieBuddyVersion()}. */
    public abstract Property<String> getZombieBuddyUrl();

    /** Expected SHA-256 of ZombieBuddy.jar. Known for the versions in {@link ZombieBuddy#KNOWN}; required otherwise. */
    public abstract Property<String> getZombieBuddySha256();

    /**
     * A local ZombieBuddy.jar to compile against instead of the pinned download (Gradle property
     * {@code zombieBuddyJar}). For trying out unreleased ZombieBuddy versions; not reproducible
     * elsewhere unless the same file is used.
     */
    public abstract RegularFileProperty getZombieBuddyJar();
}
