description = "Loader-neutral adapter service-provider contracts."

dependencies {
    api(project(":common:protocol-model"))
}

tasks.jar {
    manifest.attributes("FMLModType" to "GAMELIBRARY", "Automatic-Module-Name" to "dev.lodestone.adapter")
}
