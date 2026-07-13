# Packaged dedicated-server compatibility matrix

> Superseded by `compatibility-live-2026-07-12.md`, which contains the final seven-row
> packaged matrix, plugin/RCON/native rows, and hashes for the current artifacts.

Date: 2026-07-11

This is the release-boundary test. It runs the built host JAR from a clean,
external dedicated-server directory, not a Gradle UserDev classpath.

## Matrix

| Target | Loader/runtime | Result |
| --- | --- | --- |
| Fabric 1.20.1 | Fabric Loader 0.15.11, Fabric API 0.92.2+1.20.1 | PASS |
| Fabric 1.21.1 | Fabric Loader 0.16.10, Fabric API 0.116.9+1.21.1 | PASS |
| Fabric 26.2 | Fabric Loader 0.19.3, Fabric API 0.154.2+26.2, Java 25, non-remapping Loom 1.17.1 | PASS |
| Quilt 1.20.1 | Quilt Loader 0.29.2 with Fabric compatibility API 0.92.2+1.20.1 | PASS |
| NeoForge 1.21.1 | NeoForge 21.1.211 | PASS |
| Forge 1.20.1 | Forge 47.4.10 | PASS |

## Reproduction

The external directories were prepared with the official Fabric server JARs
and NeoForge `--installServer`, then the exact artifacts listed below were
copied into `mods/`.

```powershell
gradlew.bat check --no-daemon --console=plain --quiet
gradlew.bat :hosts:fabric:mc1_20_1:remapJar :hosts:fabric:mc1_21_1:remapJar --no-daemon --console=plain --quiet
gradlew.bat :hosts:neoforge:mc1_21_1:jar --no-daemon --console=plain --quiet
$java17 = Join-Path $HOME '.gradle/jdks/eclipse_adoptium-17-amd64-windows/jdk-17.0.19+10'
gradlew.bat :hosts:forge:mc1_20_1:reobfJar "-Porg.gradle.java.installations.paths=$java17" --no-daemon --console=plain --quiet
./verification/curseforge-profiles/stage-fabric-1201-profile.ps1 -Jar ./hosts/fabric/1.20.1/build/libs/lodestone-0.1.0-SNAPSHOT.jar
./verification/curseforge-profiles/stage-fabric-profile.ps1 -Jar ./hosts/fabric/1.21.1/build/libs/lodestone-0.1.0-SNAPSHOT.jar
./verification/curseforge-profiles/stage-quilt-profile.ps1 -Jar ./hosts/fabric/1.20.1/build/libs/lodestone-0.1.0-SNAPSHOT.jar
./verification/curseforge-profiles/stage-neoforge-profile.ps1 -Jar ./hosts/neoforge/1.21.1/build/libs/lodestone-0.1.0-SNAPSHOT.jar
./verification/curseforge-profiles/stage-fabric-262-profile.ps1 -Jar ./hosts/fabric/26.2/build/libs/lodestone-0.1.0-SNAPSHOT.jar
./verification/packaged-server-matrix.ps1
```

The matrix runner passed all six native/compatibility rows after the shared runtime lifecycle and
atomic registry-refresh fixes. Quilt uses the Fabric-compatible Lodestone 1.20.1 host
artifact; it is tracked as a loader-compatibility row, not as a separate native adapter.

Each row asserts:

- a fresh world reaches Minecraft `Done` and writes `world/level.dat` and an overworld region;
  26.2 stores that region under `world/dimensions/minecraft/overworld/region`;
- authenticated MCP `initialize` negotiates `2025-11-25`;
- native block read, bounded bulk block read, bounded region scan, and bounded entity list return `OK`;
- bounded world-write dry-run returns `OK` with `validated=true`;
- permissioned server chat broadcast returns `OK`;
- the server mints an MCP session ID on initialize; an unknown/uninitialized session cannot use
  the initialized session, while the original initialized session continues to list tools;
- an invalid token returns HTTP 401;
- the authenticated `stop` command returns `OK`, the server exits, and ports 25565 and the Lodestone port close.

No packaged-matrix crash, loader failure, classpath error, or open test port remained.

## Final artifact hashes

| Artifact | SHA-256 | Classes | `module-info.class` |
| --- | --- | ---: | ---: |
| Fabric 1.20.1 host JAR | `3C7FF129523C28DFFFD89B8596A8F66415B6A0E450F5ED94E63798C3736B7535` | 61 | 0 |
| Fabric 1.21.1 host JAR | `E8B6BB5E57C5E76D96039D391D65392B16F683CEE92591BA5B61B88D4CABFCC6` | 63 | 0 |
| Fabric 26.2 host JAR | `500B0047B59224587F099028F52D5CDEF17D7B4DCB06E1ED0D524F074EE5802A` | 60 | 0 |
| NeoForge 1.21.1 host JAR | `2E3FEB505C5A51AB62A079A9C4AD47277BBD9DB1B3C81D1A9583F67705599956` | 63 | 0 |
| Forge 1.20.1 host JAR | `D9FB6AEECCD828EB603A5ACB4DCF14D43D71CAEE382C913CEDAED3B2DBF88A2B` | 60 | 0 |

Staged CurseForge-compatible profile ZIPs were regenerated from these artifacts
and passed loader/version metadata validation before packaging.
