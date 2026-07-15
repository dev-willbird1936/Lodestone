description = "Loader-neutral Minecraft goal planning, verification, script execution, and realtime policy."

dependencies {
    api(project(":common:protocol-model"))
}

tasks.jar {
    manifest.attributes("FMLModType" to "GAMELIBRARY", "Automatic-Module-Name" to "dev.lodestone.goals")
}
