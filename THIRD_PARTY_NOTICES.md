# Third-party notices

Lodestone includes the following third-party data and build dependencies:

- Five JSON furniture/building/terrain/template catalogs imported verbatim from
  [amenti-labs/vibecraft](https://github.com/amenti-labs/vibecraft), upstream revision
  `be5045f20027b60dd1d1a8604379c51ebf84e2f4`. They are MIT licensed, Copyright (c) 2024
  VibeCraft Contributors. The distributed license and machine-readable provenance are packaged
  beside the catalogs under `common/runtime-core/src/main/resources/vibecraft/`.

- NeoForge `21.1.211` and NeoGradle `7.1.38`, used as the Minecraft 1.21.1 development/runtime API.
- Fabric Loader `0.16.10`, Fabric API `0.116.9+1.21.1`, and Fabric Loom `1.10.5`, used as the
  Minecraft 1.21.1 Fabric development/runtime toolchain.
- Fabric Loader `0.15.11` and Fabric API `0.92.2+1.20.1`, used as the Minecraft 1.20.1 Fabric
  compatibility toolchain.
- Fabric Loader `0.19.3`, Fabric API `0.154.2+26.2`, Fabric Loom `1.17.1`, and Gradle `9.6.1`,
  used as the Minecraft 26.2 non-remapping development/runtime toolchain with Java 25.
- Gson `2.10.1`, used by the shared Lodestone protocol/runtime and supplied by the Minecraft
  runtime in native host distributions.
- JUnit Jupiter `5.11.4`, test-only.
- Gradle `8.14`, build tooling for the obfuscated loader projects.
- The RCON transport uses the Java 21 standard library and the Source RCON wire protocol; it adds
  no third-party runtime dependency.

Consult each dependency's distributed license and notice files before redistribution. This notice
is an engineering record, not legal advice or a legal review.
