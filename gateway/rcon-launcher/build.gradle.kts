plugins {
    application
}

description = "Standalone MCP launcher for the narrow RCON adapter."

application {
    mainClass.set("dev.lodestone.rcon.launcher.LodestoneRconMain")
}

dependencies {
    implementation(project(":adapters:rcon:java"))
    implementation(project(":gateway:mcp-server"))
    implementation(project(":common:adapter-api"))
    implementation(project(":common:runtime-core"))
}
