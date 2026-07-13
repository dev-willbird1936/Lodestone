description = "Narrow authenticated RCON transport adapter."

dependencies {
    implementation(project(":common:runtime-core"))
}

tasks.jar {
    manifest.attributes(
        "Automatic-Module-Name" to "dev.lodestone.rcon"
    )
}
