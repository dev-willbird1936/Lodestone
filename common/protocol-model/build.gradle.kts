description = "Versioned Lodestone protocol data model."

dependencies {
    api("com.google.code.gson:gson:2.10.1")
}

tasks.jar {
    manifest.attributes("FMLModType" to "GAMELIBRARY", "Automatic-Module-Name" to "dev.lodestone.protocol")
}
