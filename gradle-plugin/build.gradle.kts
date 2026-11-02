// The BiteCheck Gradle plugin: reproducible Project Zomboid / ZombieBuddy mod builds.
// Build and test:  ./gradlew build            (from this directory)
// Publish:         ./gradlew publishPlugins   (CI only, .github/workflows/gradle-plugin.yml)
plugins {
    `java-gradle-plugin`
    id("com.gradle.plugin-publish") version "2.2.1"
}

group = "io.github.smokerdoker"
version = (findProperty("pluginVersion") as String?) ?: "0.1.0"

repositories {
    mavenCentral()
}

java {
    // Plugin classes run inside the mod author's Gradle, which needs Java 17 or newer.
    withSourcesJar()
    withJavadocJar()
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(17)
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}

tasks.withType<AbstractArchiveTask>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

testing {
    suites {
        named<JvmTestSuite>("test") {
            useJUnitJupiter("6.1.3")
            dependencies {
                implementation(gradleApi())
            }
        }
        register<JvmTestSuite>("functionalTest") {
            useJUnitJupiter("6.1.3")
            dependencies {
                implementation(project())
            }
            targets.all {
                testTask.configure { shouldRunAfter(tasks.named("test")) }
            }
        }
    }
}

gradlePlugin {
    website.set("https://github.com/SmokerDoker/bitecheck-pz")
    vcsUrl.set("https://github.com/SmokerDoker/bitecheck-pz")
    testSourceSets(sourceSets["functionalTest"])
    plugins {
        create("bitecheck") {
            id = "io.github.smokerdoker.bitecheck"
            implementationClass = "io.github.smokerdoker.bitecheck.BiteCheckPlugin"
            displayName = "BiteCheck mod build for Project Zomboid"
            description = "Reproducible Java mod builds for Project Zomboid and ZombieBuddy: compiles against " +
                "the installed game (never bundled), pins the ZombieBuddy API by SHA-256 and lays the jar out " +
                "in the mod folder, ready for the BiteCheck registry."
            tags.set(listOf("project-zomboid", "zombiebuddy", "modding", "reproducible-builds"))
        }
    }
}

tasks.named("check") {
    dependsOn(testing.suites.named("functionalTest"))
}
