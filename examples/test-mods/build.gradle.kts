// Three harmless test mods for trying BiteCheck with Project Zomboid and ZombieBuddy:
// verified, flagged, unverified, and one whose code the local scan finds suspicious (see the test index made by scripts/make-test-kit.sh).
// ./gradlew :examples:test-mods:testModsZip

val kinds = listOf("Verified", "Flagged", "Unverified", "Suspicious")

val sets = the<SourceSetContainer>()
kinds.forEach { sets.create(it.lowercase()) }

val modJars = kinds.associateWith { kind ->
    tasks.register<Jar>("jar$kind") {
        archiveFileName.set("BiteCheckTest$kind.jar")
        from(sets[kind.lowercase()].output)
    }
}

val testModsZip by tasks.registering(Zip::class) {
    group = "distribution"
    description = "Builds the three test mods as Project Zomboid mod folders."
    archiveFileName.set("BiteCheckTestMods.zip")
    kinds.forEach { kind ->
        into("BiteCheckTest$kind") { from("mods/BiteCheckTest$kind") }
        into("BiteCheckTest$kind/42/media/java") { from(modJars.getValue(kind)) }
    }
}
