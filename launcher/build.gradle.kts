plugins {
    application
}

dependencies {
    implementation(project(":core"))
}

application {
    applicationName = "BiteCheck"
    mainClass.set("io.github.bitecheckpz.launcher.LauncherMain")
}

// The registry's public key is built into the launcher (pinned), when the registry has one.
tasks.processResources {
    from(rootProject.file("keys")) {
        include("registry.pub")
        into("io/github/bitecheckpz/launcher")
    }
}

// One self-contained jar (BiteCheck.jar) that runs on Project Zomboid's bundled Java.
val fatJar by tasks.registering(Jar::class) {
    group = "build"
    description = "Builds BiteCheck.jar with the core library included."
    archiveFileName.set("BiteCheck.jar")
    manifest {
        attributes(
            "Main-Class" to "io.github.bitecheckpz.launcher.LauncherMain",
            "Implementation-Version" to project.version.toString(),
        )
    }
    from(sourceSets["main"].output)
    dependsOn(configurations.runtimeClasspath)
    from({ configurations.runtimeClasspath.get().filter { it.name.endsWith(".jar") }.map { zipTree(it) } })
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

// Packages the launcher with start scripts for Windows, macOS and Linux.
val launcherZip by tasks.registering(Zip::class) {
    group = "distribution"
    description = "Builds the BiteCheck launcher download."
    archiveFileName.set("BiteCheck-${project.version}.zip")
    into("BiteCheck") {
        from(fatJar)
        from("src/dist")
        from(rootProject.file("LICENSE"))
    }
    filePermissions { unix("rw-r--r--") }
    eachFile {
        if (name.endsWith(".sh") || name.endsWith(".command")) {
            permissions { unix("rwxr-xr-x") }
        }
    }
}

// Dependency-free tests (see src/test/java/.../LauncherSelfTest.java).
val selfTest by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Runs the launcher self tests."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("io.github.bitecheckpz.launcher.LauncherSelfTest")
}

tasks.named("check") {
    dependsOn(selfTest)
}
