# Releasing Lodestone

Lodestone publishes one GitHub release per product version. A release contains every certified
loader, server-plugin, profile, and launcher artifact for that version. It does not create a
separate release for every Minecraft/loader pair.

This keeps one authoritative changelog, one compatibility statement, and one set of checksums.
Users select an asset from the release compatibility table by deployment type, Minecraft version,
and loader or server.

## Current state

- C0 is an immutable compatibility baseline, not a tag reconstructed from newer source. Its frozen
  manifest and evidence remain under `verification/evidence/`.
- v1.0.0 has a 32-artifact, 22-row release record at
  `verification/evidence/release-conformance-v1.0.0.json`. Its final audit evidence includes exact
  Quilt-profile byte acceptance and Bukkit-family invalid-token checks. The immutable tag workflow
  still performs the clean tagged rebuild, assembly, remote upload verification, and publication.
  Its historical profile rebuild runs under Windows PowerShell, the exact v1.0.0 stager runtime;
  assembly still rejects every rebuilt profile ZIP whose bytes differ from certification.

## Tags and promotion

| Stage | Tag | GitHub state | Meaning |
| --- | --- | --- | --- |
| Assembly | none | draft | Internal asset assembly; mutable and not a consumer promise |
| Candidate | `vX.Y.Z-rc.N` | prerelease | Fully built and certified candidate awaiting promotion |
| Stable | `vX.Y.Z` | release | Exact audited commit and immutable certified assets |

Tags are never moved or reused. An RC is never converted into the stable tag. Any changed release
byte requires a new version.

## Certification gates

Before creating an RC:

1. All source, unit, contract, gateway, and affected adapter tests pass from the candidate commit.
2. Every changed distributable is rebuilt and staged from that commit.
3. Every affected compatibility row loads a fresh world and passes its bounded live checks.
4. No server process, Java process, listener, temporary world, or secret remains after the matrix.
5. A new candidate manifest freezes filename, byte length, SHA-256, source commit, platform, game
   version, artifact type, and live-evidence row. Historical manifests are not overwritten.
6. The final large security/correctness audit has no release blockers.
7. Dependency, license, redistribution, and secret scans have no unexplained blocker.
8. Installation, compatibility, configuration, troubleshooting, changelog, and intention evidence
   describe the exact candidate rather than a nearby build.

Stable promotion additionally requires verifying the stable build against the candidate manifest
and confirming that release notes and uploaded assets match it byte for byte.

## Asset names

Release assets use lowercase, flat, predictable names:

```text
lodestone-X.Y.Z-mod-fabric-mc-1.20.1.jar
lodestone-X.Y.Z-mod-forge-mc-1.12.2.jar
lodestone-X.Y.Z-profile-quilt-mc-1.20.1-curseforge.zip
lodestone-X.Y.Z-plugin-paper-mc-1.21.1.jar
lodestone-X.Y.Z-plugin-folia-mc-1.21.4.jar
lodestone-X.Y.Z-launcher-rcon-legacy-any.zip
```

The release table uses these columns:

```text
Type | Loader/server | Minecraft | Java | Asset | Certification | Notes
```

Do not publish an omnibus `all.zip`: it duplicates data and makes incorrect installation more
likely.

## Integrity files

Every RC and stable release includes:

- `release-manifest.json` with source commit, platform metadata, byte lengths, SHA-256 hashes, and
  evidence identifiers;
- `SHA256SUMS` generated from the exact uploaded files;
- SPDX or CycloneDX SBOMs for distinct distributables;
- portable build-provenance material.

Detached signatures are included only when a configured signing identity is available. A release
must never imply that unsigned artifacts are signed; v1.0.0's assembler always provides exact
SHA-256 checksums, provenance, and the first-party SPDX inventory.

Signing keys and service credentials never enter the repository. Prefer keyless Sigstore/cosign
signing when its public identity disclosure is acceptable; otherwise use a protected offline key.

## GitHub workflow

The source of truth is `main`. Source may be pushed before a release as a private durable backup,
provided the complete staged inventory and secret scan are clean. Generated builds, local profiles,
worlds, logs, caches, credentials, and downloaded server libraries remain outside Git.

A release is created only after certification, from the exact signed tag. The tag workflow runs the
full source/unit/gateway/adapter/contract verification gate, rebuilds the certified inventory, and
assembles exactly 36 upload files. It creates an inaccessible draft, confirms its remote filenames
and count, downloads every asset into a clean directory, and compares every byte length and SHA-256
to staging before publishing. Any upload or verification failure stops before publication and leaves
only the draft for inspection. A failed tag job is retried only through the dispatch input that
checks out the same immutable tag; it never moves or recreates that tag. Never replace a published
stable asset silently.
