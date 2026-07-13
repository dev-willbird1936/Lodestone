# Lodestone compatibility live evidence — 2026-07-12

## Build gate

- `.\gradlew.bat check -PincludeFabric262=false --no-daemon`: PASS after the final hardening build.
- `:hosts:fabric:mc1_19_2:build -PincludeFabric262=false --no-daemon`: PASS with Java 17 after the record-compatible catalog loader fix.
- `gradle-9.5.1/bin/gradle.bat :hosts:fabric:mc1_26_2:build -PincludeFabric262=true -PincludeForge=false -PincludeForge121=false --no-daemon`: PASS with Java 25.
- Production packaging: Fabric 1.20.1/1.19.2/1.18.2 `remapJar`, Fabric 1.21.1 `remapJar`, Fabric 26.2 Java 25/non-remapping JAR, NeoForge 1.21.1 JAR, Forge 1.21.1 official-mappings JAR, Forge 1.20.1/1.19.2/1.18.2/1.16.5 `reobfJar`, Forge 1.12.2/1.7.10/1.8.9 Java 8-native bridge JARs, Paper 1.21.1, Spigot 1.21.1, Folia 1.21.4, and the RCON/legacy launchers: PASS.
- Fabric 1.18.2/1.19.2/1.20.1 client source sets compile and package the bounded client-control bridge; all refreshed Fabric rows were rerun through fresh-world acceptance: PASS.
- `minecraft.inventory.container.read` is cataloged and implemented on Fabric 1.18.2/1.19.2/1.20.1/1.21.1 and NeoForge 1.21.1 with stable slot projections and revisions; Fabric click paths enforce revision/slot/button bounds: PASS.
- Post-change shared regression gate: `:common:protocol-model:test :common:adapter-api:test :common:legacy-java8:test :common:runtime-core:test :gateway:mcp-server:test :verification:contract-tests:test --no-daemon`: PASS. Wire serialization, rollback cancellation/failure, final-write commit races, Java 8 transaction/token ownership, and gateway schema regressions pass; the catalog contains 34 unique capability IDs, including exactly one `minecraft.inventory.container.read` entry.
- Forge 1.16.5 packaging used ForgeGradle 5.1, Gradle 7.6.4, Java 17, and the official Forge 36.2.42 mapped dependency; its fresh-world launch uses one required `--add-exports` and one required `--add-opens` flag.
- Fabric 26.2 packaging used Gradle 9.5.1 and Java 25. Other modern builds used Gradle 8.14 with Java 17/21 as required by their target. Standalone Fabric manifests are exact-pinned; Quilt uses `verification/prepare-quilt-host.ps1` to generate 1.20.1/1.21.1 variants with their tested Fabric Loader ranges.

## Fresh-world matrix

Every row reached the server `Done` state, created a fresh overworld, authenticated to Lodestone,
exercised its supported MCP control slice, and shut down cleanly. Every native/plugin write-capable
row applied a block write followed by readback, rejected replacement of an existing chest, and read
the chest back unchanged. Paper's stronger fixture populated `CustomName` first and proved that NBT
field still existed after the rejected write.

| Target | Result |
| --- | --- |
| Fabric 1.20.1 / Loader 0.15.11 / Java 17 | PASS |
| Fabric 1.19.2 / Loader 0.14.25 / Java 17 | PASS |
| Fabric 1.18.2 / Loader 0.14.25 / Java 17 | PASS |
| Fabric 1.21.1 / Loader 0.16.10 / Java 21 | PASS |
| Fabric 26.2 / Loader 0.19.3 / Java 25 | PASS |
| Quilt 1.20.1 / Loader 0.29.2 via Fabric compatibility | PASS |
| Quilt 1.21.1 / Loader 0.29.2 via Fabric compatibility | PASS |
| NeoForge 1.21.1 / 21.1.211 | PASS |
| Forge 1.21.1 / 52.1.0 | PASS |
| Forge 1.20.1 / 47.4.10 | PASS |
| Forge 1.19.2 / 43.5.2 | PASS |
| Forge 1.18.2 / 40.3.12 | PASS |
| Forge 1.16.5 / 36.2.42 / Java 17 | PASS |
| Paper 1.21.1 build 133 / Java 21 | PASS |
| Spigot 1.21.1 BuildTools `4344-Spigot-a759b62` / Java 21 | PASS |
| Folia 1.21.4 build 6 / Java 21 | PASS |
| Forge 1.12.2 / 14.23.5.2859 native bridge | PASS |
| Forge 1.12.2 / 14.23.5.2859 via RCON | PASS |
| Forge 1.8.9 / 11.15.1.2318-1.8.9 via RCON | PASS |
| Forge 1.7.10 / 10.13.4.1614-1.7.10 native bridge | PASS |
| Forge 1.7.10 / 10.13.4.1614 via RCON | PASS |
| Forge 1.8.9 / 11.15.1.2318-1.8.9 native bridge | PASS |

The complete prepared-target rerun was executed after the advisor findings were fixed. The packaged
matrix passed all 13 rows; Paper, Spigot, Folia, native legacy Forge, and RCON legacy Forge passed
all of their rows as well (22 fresh-world rows total). No final rerun row produced a crash report,
fatal server-loop marker, or retained its Lodestone/Minecraft test listener after shutdown. Earlier
failed attempts are retained only as disposable historical state under generated `runs/` directories
and are excluded from the clean-run audit.

## Final static and cleanup audit

- Catalog uniqueness: 34 IDs / 34 unique; container-read ID count: 1.
- Adapter `IMPLEMENTED` scan: 13 sets checked; 0 duplicate IDs and 0 IDs absent from the catalog.
- PowerShell verifier parse audit: 27 files checked; 0 parser errors.
- CurseForge-compatible local profiles: 13 directories and 13 ZIPs checked; 0 missing or invalid
  `manifest.json` files. These are format/staging checks, not manual CurseForge GUI launches.
- Crash-report sweep across all matrix roots: 0 reports.
- Workspace generated-state audit: 10 historical crash reports remain under ignored `hosts/**/runs/`
  directories from earlier experiments; they are not final matrix evidence and were not used to
  determine the current rows.
- Residual processes after all tests: 0 Java processes, 0 Gradle daemon processes.
- Residual matrix listeners on the allocated Minecraft/MCP/RCON ports: 0.

The modern native, Paper, Spigot, and Folia write acceptance fixtures explicitly prepare a loaded
test chunk. Those write handlers reject unloaded chunks instead of implicitly forcing them to load.
The Java 8 legacy bridge rows intentionally retain old-API compatibility and may load a target chunk
for a requested write. All native/plugin adapters reject existing block entities before mutation
until NBT-safe restoration exists. Coordinate-overflow cases are rejected before batch arithmetic.

## Final packaged artifact hashes

| Artifact | SHA-256 |
| --- | --- |
| Fabric 1.20.1 host | `C8CEED83ECC20EAB73D341437FC2F0BA7E5AC217EA719E975236943CCD42C8DF` |
| Quilt 1.20.1 staged compatibility host variant | `9AC79ADC395142867F917E78E0659962609C5C5BDC82B658BF98751E3661D884` |
| Fabric 1.19.2 host | `844C8AFA6C1E1674527A74B53E20F1FB46CB1060CC9CC2249539013B53445DCF` |
| Fabric 1.18.2 host | `65F475BC750264C780A94B5592FE44B2095C94C780F1D81831C02A39EF8AAC7B` |
| Fabric 1.21.1 host | `E3AE18ACC416A680BCEAED2FA0A3C665FABEC0F4E9B5122E058C5A5EE0665FB1` |
| Quilt 1.21.1 staged compatibility host variant | `B711C38A61A7B51D592E1CA2D9B5FC37FED728C794096B7B7115D29B110F549A` |
| Fabric 26.2 host | `054CBF948769245AA5441E21E942EEB2326C94F44AD2DA064F04D32EE97BADAC` |
| NeoForge 1.21.1 host | `0D83EA4BA5BBFF65181DED670DA2EE24F08EFC129055356ECD5A5FA283C0EAEB` |
| Forge 1.21.1 host | `252C43CE7C07A1AAEDC220FF4042B487F13A56E19230C7CA8FC0D53CC674CEB0` |
| Forge 1.20.1 reobfuscated host | `E8C7F8B67F64D6D3C568824600786EC6EAB205E05F03EAD7C596381A94E0A164` |
| Forge 1.19.2 reobfuscated host | `92D332E604BB46BE14DFC2367073524427F7BCB5D3A6769A36F234091E42FC0C` |
| Forge 1.18.2 reobfuscated host | `8C9A7DE4795158E92A6110C85D9F097EE92090414ABB34A40D6C67FC6A618EF9` |
| Forge 1.16.5 reobfuscated host | `D3A61D2FF79234F5491C200E329D7062E0DD27D23E2EB7868E82E96ABBB1F0F1` |
| Paper 1.21.1 host | `DB63E4C6EB226C660035BEB7B9E5A4D46AD9DD4DF45DE7EB769558F44CF6EB75` |
| Spigot 1.21.1 host | `9874085EE40E96DC7445950EC42CF6133DFFAA02E6343360C8DE0DCA7EF1E666` |
| Folia 1.21.4 host | `7114768530CBCE6ADB6AAC1EC54932DEE503603631EFABAF9CC134B751FCB861` |
| Forge 1.12.2 native bridge host | `2066D271396DD55A31CF43894A443DEAF4383412AAEC3A5F7586568610385901` |
| Forge 1.7.10 native bridge host | `4343508D0529CF785BE63FB54EF9EE8A4AD144DEB0484DCF05110597E255F27A` |
| Forge 1.8.9 native bridge host | `07D60FB448226615BC2D0006CBE93BB9566A171AAFE370F204A98D5BB2827AB8` |

The thirteen checked-in CurseForge-compatible profile ZIPs were regenerated from the current staged
host artifacts after the current compatibility hardening:

| Profile ZIP | SHA-256 |
| --- | --- |
| `lodestone-fabric-1.20.1-local.zip` | `E5FCA9AD5AA255AEA7D002206DCC68BF381A6B303449674FDAA3128699110CEA` |
| `lodestone-fabric-1.19.2-local.zip` | `D274C15A434B7DBF94218C73EEFDA143C520ED937E2F6355D1A6FCCA33ED2C72` |
| `lodestone-fabric-1.18.2-local.zip` | `208C4199EE6EF72649E540577005A8338832CD467A8B9071D3C9CA39ADC202C3` |
| `lodestone-fabric-1.21.1-local.zip` | `C8E9EAB0670AB8A08DFFF80A6796DBD14D6F391E0DC0039CA16D0611FCE02B59` |
| `lodestone-fabric-26.2-local.zip` | `ADC438034F1B8B1D85EFC82B922F6559CC622FD784FA4D5025289C5DF84FCEF6` |
| `lodestone-forge-1.16.5-local.zip` | `2F2A255C6F0A52EB8BD5915BDBD99E88821C840FA98CFD9AE9467367E93BDB83` |
| `lodestone-forge-1.20.1-local.zip` | `31698A5FF9D6D739B2C1D01F2484CBD0C92AD7473D3CF82A289BD7D36254E567` |
| `lodestone-forge-1.19.2-local.zip` | `017FABF9F2A370C5287F737C40AECC02643096A59E08CB41A7F336A946EA54D6` |
| `lodestone-forge-1.18.2-local.zip` | `BD5440D603D2F76B45803550EC24F2503324EAA00100321C800D6FF224577ABA` |
| `lodestone-forge-1.21.1-local.zip` | `750944B3941895E7333D516A252FDF6A9E729D655D3883586939A266A64CA58A` |
| `lodestone-neoforge-1.21.1-local.zip` | `D23E5410BCC0396DA749FF0DF89F101AB884597E539242ABD56E02A76DFF44E8` |
| `lodestone-quilt-1.20.1-local.zip` | `8F67EE0B4C8056BF4A1B62D1105C9EC3750ADC8E825D94CFC97348398061FFE3` |
| `lodestone-quilt-1.21.1-local.zip` | `E36E148B068B576F06472B9295D999CE3B0D87103DED8B17E86A2F0B56422D0B` |

## Advisor status and remaining scope

The completed earlier large `codex-advisor` scan returned a conditional/yellow handoff: no P0, but four P1 findings. It identified that the packaged matrix only dry-ran modern writes, Fabric metadata accepted untested versions, generated `runs/` state was unignored, and rejected idempotent requests could poison their retry key. Those findings are fixed: the matrix applies and reads back a block, manifests are exact-pinned, Quilt uses generated compatibility variants, runtime directories are ignored, and admission checks precede idempotency-cache insertion. Its remaining cancellation-rollback concern is now also addressed across modern Fabric/Forge/NeoForge/Paper/Folia writes through a shared reverse-restore primitive with cancellation/failure regression tests. A new final advisor scan is still pending at this point in the evidence chronology, so no final green advisor verdict is claimed yet.

The earlier advisor scan was read-only; shared tests, packaged loader rows, server plugins, legacy native bridges, and RCON matrices were rerun after its fixes. That cycle found and fixed real Quilt metadata and older-Gson startup crashes. This continuation added Quilt 1.21.1 and Forge 1.16.5, centralized rollback, replaced newer-only production Gson parser calls, and hardened null-safe verifier diagnostics. Paper 1.21.1, Spigot 1.21.1, Folia 1.21.4, native Forge 1.12.2/1.7.10/1.8.9, and RCON Forge 1.12.2/1.7.10/1.8.9 have fresh passing evidence; the complete post-final-advisor rerun is the remaining release gate.

Forge 1.16.5 / 36.2.42 now has a real mapped API port, isolated ForgeGradle 5.1 / Gradle 7.6.4
build, official server staging, importable profile, Java 17 module-opening launch contract, and a
fresh-world applied-write/readback PASS. Its implementation uncovered and fixed both ModLauncher
module encapsulation and old-Gson runtime collisions before the row was promoted to green.

Folia 1.21.1 remains pending because no official build was available. Bedrock, later Minecraft lines, full connected-player/UI/container/NBT semantics, and broad third-party mod interoperability remain future coverage.
