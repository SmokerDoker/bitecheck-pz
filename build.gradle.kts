// BiteCheck deliberately has no third-party dependencies: everything a user
// has to trust is in this repository or in the JDK itself.

subprojects {
    apply(plugin = "java")

    group = "io.github.bitecheckpz"
    version = (findProperty("bitecheckVersion") as String?) ?: "0.1.0-SNAPSHOT"

    extensions.configure<JavaPluginExtension> {
        toolchain.languageVersion.set(JavaLanguageVersion.of(21))
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        // Class files for Java 17+, so BiteCheck also runs on older Java runtimes
        // (Project Zomboid's bundled one, or whatever Java a player has installed).
        options.release.set(17)
        options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
    }

    // Reproducible archives: same sources -> byte-identical jars.
    tasks.withType<AbstractArchiveTask>().configureEach {
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
    }
}
