description = "Java 8-safe security and transaction primitives shared by legacy Forge hosts."

tasks.jar {
    manifest.attributes("Automatic-Module-Name" to "dev.lodestone.legacy.shared")
}
