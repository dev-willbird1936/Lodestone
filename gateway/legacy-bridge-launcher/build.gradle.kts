plugins {
    application
}

description = "Standalone MCP launcher for native legacy Forge bridges."

application {
    mainClass.set("dev.lodestone.legacybridge.launcher.LegacyBridgeMain")
}

dependencies {
    implementation(project(":adapters:legacy-bridge:java"))
    implementation(project(":gateway:mcp-server"))
    implementation(project(":common:adapter-api"))
    implementation(project(":common:runtime-core"))
}
