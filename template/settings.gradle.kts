pluginManagement {
    // E2E test only: the BiteCheck plugin from this repository instead of the Plugin Portal
    includeBuild("../gradle-plugin")
}

plugins {
    // Downloads a matching JDK when none is installed (local builds only; CI sets up the JDK itself).
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "MyMod"
