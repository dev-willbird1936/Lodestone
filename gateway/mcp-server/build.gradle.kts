import org.gradle.jvm.tasks.Jar

description = "MCP gateway over Lodestone runtime services."

dependencies {
    implementation(project(":common:runtime-core"))
    implementation(project(":common:goal-engine"))
    implementation("com.google.code.gson:gson:2.10.1")
}

val goalEngineJar = project(":common:goal-engine").tasks.named<Jar>("jar")

tasks.jar {
    dependsOn(goalEngineJar)
    from({ zipTree(goalEngineJar.get().archiveFile.get().asFile) })
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

tasks.jar {
    manifest.attributes("FMLModType" to "GAMELIBRARY", "Automatic-Module-Name" to "dev.lodestone.gateway")
}
