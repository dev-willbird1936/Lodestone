description = "Java 21 gateway adapter for native legacy Forge bridges."

dependencies {
    implementation(project(":common:runtime-core"))
}

tasks.jar {
    manifest.attributes("Automatic-Module-Name" to "dev.lodestone.legacybridge")
}
