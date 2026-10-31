plugins {
    `java-library`
}

// Dependency-free self tests (see src/test/java/.../SelfTest.java).
val selfTest by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Runs the core self tests."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("io.github.bitecheckpz.core.SelfTest")
}

tasks.named("check") {
    dependsOn(selfTest)
}
