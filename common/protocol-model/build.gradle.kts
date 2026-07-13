description = "Versioned Lodestone protocol data model."

dependencies {
    api("com.google.code.gson:gson:2.14.0")
}

tasks.jar {
    manifest.attributes("FMLModType" to "GAMELIBRARY", "Automatic-Module-Name" to "dev.lodestone.protocol")
}
