import org.gradle.util.GradleVersion

pluginManagement {
    repositories {
        gradlePluginPortal()
        maven { url = uri("https://maven.fabricmc.net/") }
        maven { url = uri("https://maven.neoforged.net/releases") }
        maven { url = uri("https://maven.minecraftforge.net/") }
    }
}

rootProject.name = "lodestone"

val includeForge = providers.gradleProperty("includeForge")
    .map { it.toBoolean() }
    .orElse(true)
    .get()
val includeForge121 = providers.gradleProperty("includeForge121")
    .map { it.toBoolean() }
    .orElse(true)
    .get()
val includeForge192 = providers.gradleProperty("includeForge192")
    .map { it.toBoolean() }
    .orElse(true)
    .get()
val includeForge182 = providers.gradleProperty("includeForge182")
    .map { it.toBoolean() }
    .orElse(true)
    .get()
val includeForge165 = providers.gradleProperty("includeForge165")
    .map { it.toBoolean() }
    .orElse(false)
    .get()
val includeFabric262 = providers.gradleProperty("includeFabric262")
    .map { it.toBoolean() }
    .orElse(GradleVersion.current() >= GradleVersion.version("9.5"))
    .get()
val includeModern = providers.gradleProperty("includeModern")
    .map { it.toBoolean() }
    .orElse(true)
    .get()

include(
    ":common:protocol-model",
    ":common:adapter-api",
    ":common:legacy-java8",
    ":common:runtime-core",
    ":common:goal-engine",
    ":gateway:mcp-server",
    ":gateway:rcon-launcher",
    ":gateway:legacy-bridge-launcher",
    ":adapters:rcon:java",
    ":adapters:legacy-bridge:java",
    ":verification:contract-tests"
)

if (includeModern) {
    include(
        ":hosts:fabric:mc1_20_1",
        ":hosts:fabric:mc1_19_2",
        ":hosts:fabric:mc1_18_2",
        ":hosts:fabric:mc1_21_1",
        ":hosts:neoforge:mc1_21_1",
        ":hosts:paper:mc1_21_1",
        ":hosts:spigot:mc1_21_1",
        ":hosts:folia:mc1_21_4",
        ":adapters:neoforge:mc1_21_1",
        ":adapters:paper:mc1_21_1",
        ":adapters:spigot:mc1_21_1",
        ":adapters:folia:mc1_21_4",
        ":adapters:fabric:mc1_21_1",
        ":adapters:fabric:mc1_20_1",
        ":adapters:fabric:mc1_19_2",
        ":adapters:fabric:mc1_18_2"
    )
}

if (includeForge) {
    include(":hosts:forge:mc1_20_1", ":adapters:forge:mc1_20_1")
}

if (includeForge121) {
    include(":hosts:forge:mc1_21_1", ":adapters:forge:mc1_21_1")
}

if (includeForge192) {
    include(":hosts:forge:mc1_19_2", ":adapters:forge:mc1_19_2")
}

if (includeForge182) {
    include(":hosts:forge:mc1_18_2", ":adapters:forge:mc1_18_2")
}

if (includeForge165) {
    include(":hosts:forge:mc1_16_5", ":adapters:forge:mc1_16_5")
}

if (includeFabric262 && includeModern) {
    include(":hosts:fabric:mc1_26_2", ":adapters:fabric:mc1_26_2")
}

project(":adapters:rcon:java").projectDir = file("adapters/rcon/java")
project(":adapters:legacy-bridge:java").projectDir = file("adapters/legacy-bridge/java")
project(":gateway:legacy-bridge-launcher").projectDir = file("gateway/legacy-bridge-launcher")
project(":common:goal-engine").projectDir = file("common/goal-engine")

if (includeModern) {
    project(":adapters:neoforge:mc1_21_1").projectDir = file("adapters/neoforge/1.21.1")
    project(":adapters:fabric:mc1_21_1").projectDir = file("adapters/fabric/1.21.1")
    project(":adapters:fabric:mc1_20_1").projectDir = file("adapters/fabric/1.20.1")
    project(":adapters:fabric:mc1_19_2").projectDir = file("adapters/fabric/1.19.2")
    project(":adapters:fabric:mc1_18_2").projectDir = file("adapters/fabric/1.18.2")
    project(":hosts:fabric:mc1_20_1").projectDir = file("hosts/fabric/1.20.1")
    project(":hosts:fabric:mc1_19_2").projectDir = file("hosts/fabric/1.19.2")
    project(":hosts:fabric:mc1_18_2").projectDir = file("hosts/fabric/1.18.2")
    project(":hosts:fabric:mc1_21_1").projectDir = file("hosts/fabric/1.21.1")
    project(":hosts:neoforge:mc1_21_1").projectDir = file("hosts/neoforge/1.21.1")
    project(":hosts:paper:mc1_21_1").projectDir = file("hosts/paper/1.21.1")
    project(":hosts:spigot:mc1_21_1").projectDir = file("hosts/spigot/1.21.1")
    project(":hosts:folia:mc1_21_4").projectDir = file("hosts/folia/1.21.4")
    project(":adapters:paper:mc1_21_1").projectDir = file("adapters/paper/1.21.1")
    project(":adapters:spigot:mc1_21_1").projectDir = file("adapters/spigot/1.21.1")
    project(":adapters:folia:mc1_21_4").projectDir = file("adapters/folia/1.21.4")
}

if (includeFabric262 && includeModern) {
    project(":adapters:fabric:mc1_26_2").projectDir = file("adapters/fabric/26.2")
    project(":hosts:fabric:mc1_26_2").projectDir = file("hosts/fabric/26.2")
}

if (includeForge) {
    project(":adapters:forge:mc1_20_1").projectDir = file("adapters/forge/1.20.1")
    project(":hosts:forge:mc1_20_1").projectDir = file("hosts/forge/1.20.1")
}

if (includeForge192) {
    project(":adapters:forge:mc1_19_2").projectDir = file("adapters/forge/1.19.2")
    project(":hosts:forge:mc1_19_2").projectDir = file("hosts/forge/1.19.2")
}
if (includeForge182) {
    project(":adapters:forge:mc1_18_2").projectDir = file("adapters/forge/1.18.2")
    project(":hosts:forge:mc1_18_2").projectDir = file("hosts/forge/1.18.2")
}

if (includeForge165) {
    project(":adapters:forge:mc1_16_5").projectDir = file("adapters/forge/1.16.5")
    project(":hosts:forge:mc1_16_5").projectDir = file("hosts/forge/1.16.5")
}

if (includeForge121) {
    project(":adapters:forge:mc1_21_1").projectDir = file("adapters/forge/1.21.1")
    project(":hosts:forge:mc1_21_1").projectDir = file("hosts/forge/1.21.1")
}
