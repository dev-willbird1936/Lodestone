description = "MCP gateway over Lodestone runtime services."

dependencies {
    implementation(project(":common:runtime-core"))
    implementation("com.google.code.gson:gson:2.14.0")
}

tasks.jar {
    manifest.attributes("FMLModType" to "GAMELIBRARY", "Automatic-Module-Name" to "dev.lodestone.gateway")
}
