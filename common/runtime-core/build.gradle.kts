description = "Capability registry, dispatch, lifecycle, policy, and sessions."

tasks.processResources {
    from(rootProject.file("protocol/catalog/core-capabilities.json"))
    from(rootProject.file("protocol/catalog/hard-scripts.json"))
}

dependencies {
    api(project(":common:protocol-model"))
    api(project(":common:adapter-api"))
}

tasks.jar {
    manifest.attributes("FMLModType" to "GAMELIBRARY", "Automatic-Module-Name" to "dev.lodestone.runtime")
}
