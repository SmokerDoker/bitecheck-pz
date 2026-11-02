plugins {
    application
}

dependencies {
    implementation(project(":core"))
}

application {
    applicationName = "bcz"
    mainClass.set("io.github.bitecheckpz.cli.Main")
}

// Dependency-free self tests (see src/test/java/.../CliSelfTest.java); they need git.
val selfTest by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Runs the bcz command self tests."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("io.github.bitecheckpz.cli.CliSelfTest")
}

tasks.named("check") {
    dependsOn(selfTest)
}

tasks.jar {
    manifest {
        attributes("Main-Class" to "io.github.bitecheckpz.cli.Main")
    }
}
