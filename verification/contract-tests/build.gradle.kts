description = "Cross-adapter Lodestone protocol and capability contract tests."

dependencies {
    testImplementation(project(":common:protocol-model"))
    testImplementation(project(":common:adapter-api"))
    testImplementation(project(":common:runtime-core"))
}

tasks.named<Test>("test") {
    inputs.files(
        rootProject.fileTree("protocol") {
            include("**/*.json")
        },
        rootProject.fileTree("verification/curseforge-profiles") {
            include("*/manifest.json")
        },
        rootProject.fileTree("hosts") {
            include("**/*.properties", "**/*.toml", "**/fabric.mod.json", "**/*Plugin.java")
        }
    ).withPathSensitivity(PathSensitivity.RELATIVE)
}
