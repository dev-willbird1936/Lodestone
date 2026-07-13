plugins {
    base
}

group = "dev.lodestone"
version = providers.gradleProperty("lodestoneVersion").getOrElse("1.0.0")

val junitVersion = "5.11.4"

allprojects {
    repositories {
        mavenCentral()
    }
}

val java17Projects = setOf(
    ":common:protocol-model",
    ":common:adapter-api",
    ":common:runtime-core",
    ":gateway:mcp-server",
    ":hosts:fabric:mc1_20_1",
    ":adapters:fabric:mc1_20_1",
    ":adapters:forge:mc1_16_5",
    ":hosts:forge:mc1_16_5"
)
val java8Projects = setOf(":common:legacy-java8")
val legacyForge165Projects = setOf(":adapters:forge:mc1_16_5", ":hosts:forge:mc1_16_5")

subprojects {
    apply(plugin = "java-library")

    val javaTarget = when {
        path in java8Projects -> 8
        path in java17Projects -> 17
        else -> 21
    }

    if (path !in legacyForge165Projects) {
        extensions.configure<JavaPluginExtension> {
            // Compile the Java 8 shared slice with an installed modern compiler plus --release 8;
            // standalone legacy Forge builds still consume the same Java 8-compatible source.
            val compilerTarget = if (path in java8Projects) 17 else javaTarget
            toolchain.languageVersion.set(JavaLanguageVersion.of(compilerTarget))
        }
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        if (path !in legacyForge165Projects) {
            options.release.set(javaTarget)
        }
    }

    dependencies {
        "testImplementation"(platform("org.junit:junit-bom:$junitVersion"))
        "testImplementation"("org.junit.jupiter:junit-jupiter")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        systemProperty("lodestone.rootDir", rootProject.projectDir.absolutePath)
    }
}

val verifyProtocolCatalog by tasks.registering {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Checks that protocol schemas, catalog, and fixtures are present."
    doLast {
        val required = listOf(
            "protocol/catalog/core-capabilities.json",
            "protocol/schemas/handshake.schema.json",
            "protocol/schemas/capability.schema.json",
            "protocol/schemas/capability-manifest.schema.json",
            "protocol/schemas/request-envelope.schema.json",
            "protocol/schemas/result-envelope.schema.json",
            "protocol/schemas/event-envelope.schema.json",
            "protocol/schemas/error.schema.json",
            "protocol/fixtures/valid/handshake.json",
            "protocol/fixtures/invalid/missing-capability-id.json"
        )
        required.forEach { path -> check(file(path).isFile) { "Missing protocol artifact: $path" } }
    }
}

tasks.named("check") {
    dependsOn(verifyProtocolCatalog)
}
