plugins {
    // Downloads a matching JDK when none is installed (local builds only; CI sets up the JDK itself).
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "MyMod"
