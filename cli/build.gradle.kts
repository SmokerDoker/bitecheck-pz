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

tasks.jar {
    manifest {
        attributes("Main-Class" to "io.github.bitecheckpz.cli.Main")
    }
}
