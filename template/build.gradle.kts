plugins {
    // Java toolchain 25, game jar + pinned ZombieBuddy API on compileOnly, reproducible jar,
    // task bitecheckMod -> build/bitecheck/ (the complete mod folder)
    id("io.github.smokerdoker.bitecheck") version "0.1.0"
}

bitecheck {
    modName.set("MyMod")            // jar name: 42/media/java/client/MyMod.jar (javaJarFile in mod.info)
    // gameVersion.set("42")        // version folder of the mod
    // side.set("client")           // client | server | shared
    // zombieBuddyVersion.set("2.3.4")
}

// Real dependencies (libraries that end up in your jar) must have pinned versions.
// Game classes and ZombieBuddy are compileOnly: they are never bundled.
