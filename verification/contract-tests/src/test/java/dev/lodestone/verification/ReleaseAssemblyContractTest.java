// SPDX-License-Identifier: MIT
package dev.lodestone.verification;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonArray;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReleaseAssemblyContractTest {
    private static final String SOURCE_COMMIT = "a".repeat(40);
    private static final String SOURCE_TREE = "b".repeat(40);
    private final Path root = Path.of(System.getProperty("lodestone.rootDir"));

    @Test
    void releaseInventoryDefinesExactFlatV100Payload() throws Exception {
        var inventoryPath = root.resolve("verification/release-assets-v1.0.0.json");
        var inventory = JsonParser.parseReader(Files.newBufferedReader(
                inventoryPath, StandardCharsets.UTF_8)).getAsJsonObject();

        assertEquals(1, inventory.get("formatVersion").getAsInt());
        assertEquals("1.0.0", inventory.get("productVersion").getAsString());
        assertEquals("v1.0.0", inventory.get("tag").getAsString());
        assertEquals(32, inventory.getAsJsonArray("artifacts").size());

        var counts = new java.util.HashMap<String, Integer>();
        var names = new HashSet<String>();
        var sources = new HashSet<String>();
        for (var element : inventory.getAsJsonArray("artifacts")) {
            var artifact = element.getAsJsonObject();
            var type = artifact.get("type").getAsString();
            var filename = artifact.get("uploadFilename").getAsString();
            var source = artifact.get("sourcePath").getAsString();
            counts.merge(type, 1, Integer::sum);
            assertTrue(names.add(filename), "duplicate upload filename: " + filename);
            assertTrue(sources.add(source), "duplicate source path: " + source);
            assertTrue(filename.matches("^[a-z0-9][a-z0-9._-]*$"), "unsafe upload filename: " + filename);
            assertTrue(source.matches("^[a-z0-9][a-zA-Z0-9._/-]*$"), "unsafe source path: " + source);
            assertTrue(!source.startsWith("/") && !source.contains("..") && !source.contains("\\"),
                    "source path must stay project-relative: " + source);
            assertTrue(artifact.has("platform") && !artifact.get("platform").getAsString().isBlank(),
                    filename + " missing platform");
            for (var field : new String[]{"minecraftVersions", "evidenceRows", "buildJava", "runtimeJava"}) {
                assertTrue(artifact.has(field) && artifact.get(field).isJsonArray()
                                && !artifact.getAsJsonArray(field).isEmpty(),
                        filename + " missing nonempty " + field);
            }
            for (var value : artifact.getAsJsonArray("minecraftVersions")) {
                assertFalse(value.getAsString().equals("any") || value.getAsString().contains(","),
                        filename + " contains a pseudo Minecraft version: " + value);
            }
            for (var field : new String[]{"buildJava", "runtimeJava"}) {
                for (var value : artifact.getAsJsonArray(field)) {
                    assertTrue(value.getAsString().matches("^[0-9]+$"), filename + " has invalid " + field);
                }
            }
            if (type.equals("profile")) {
                assertTrue(artifact.has("installerJava") && !artifact.getAsJsonArray("installerJava").isEmpty(),
                        filename + " missing installerJava");
                assertTrue(artifact.has("embeddedArtifactUploadFilename")
                                && !artifact.get("embeddedArtifactUploadFilename").getAsString().isBlank(),
                        filename + " missing embedded host binding");
            }
            if (type.equals("launcher")) {
                assertTrue(artifact.has("launcherJava") && !artifact.getAsJsonArray("launcherJava").isEmpty(),
                        filename + " missing launcherJava");
            }
        }
        assertEquals(Map.of("mod", 14, "plugin", 3, "profile", 13, "launcher", 2), counts);

        var forge165 = inventory.getAsJsonArray("artifacts").asList().stream()
                .map(element -> element.getAsJsonObject())
                .filter(artifact -> artifact.get("uploadFilename").getAsString()
                        .equals("lodestone-1.0.0-mod-forge-mc-1.16.5.jar"))
                .findFirst().orElseThrow();
        assertEquals(List.of("17"), forge165.getAsJsonArray("runtimeJava").asList().stream()
                .map(value -> value.getAsString()).toList());
    }

    @Test
    void releaseWorkflowGatesFullVerificationAndPublishesOnlyAfterDraftReadback() throws Exception {
        var workflow = Files.readString(root.resolve(".github/workflows/release.yml"), StandardCharsets.UTF_8);
        var portableWorkflow = workflow.replace("\r\n", "\n");
        assertTrue(workflow.contains("ref: ${{ inputs.tag || github.ref }}"),
                "manual release retries must check out the requested immutable tag");
        assertTrue(workflow.contains("Verify checked-out immutable release tag"));
        assertTrue(workflow.contains("Verify Windows PowerShell hash runtime for contract tests"),
                "the Windows PowerShell subprocess used by immutable-tag contracts must be preflighted");
        assertTrue(workflow.contains("$env:PSModulePath = \"$legacyModules;$machineModules\""),
                "the full gate must restore Windows PowerShell utility-module discovery");
        assertTrue(workflow.contains("Get-ChildItem -LiteralPath $env:GITHUB_WORKSPACE -Recurse -Filter 'TEST-*.xml'"),
                "a failed release gate must retain diagnostics from the module that actually failed");
        assertTrue(workflow.contains("preserving the original failed gate"),
                "release diagnostics must not mask the original failing gate when no reports are retained");
        assertTrue(workflow.contains(".\\gradlew.bat projects check --no-parallel"),
                "tag publishing must run the full verification gate");
        assertTrue(workflow.contains("JAVA_HOME: ${{ steps.java21.outputs.path }}"),
                "Gradle 8 verification must not inherit the Java 25 setup action");
        assertTrue(portableWorkflow.contains("Rebuild all release artifacts twice where required\n"
                        + "        # v1.0.0's immutable historical profile stagers target Windows PowerShell.\n"
                        + "        # The final staged ZIPs are still checked byte-for-byte against certification.\n"
                        + "        shell: powershell"),
                "immutable v1.0.0 profile staging must use its compatible Windows PowerShell runtime");
        assertTrue(workflow.contains("$relativeStager = 'verification/curseforge-profiles/stage-fabric-1182-profile.ps1'"),
                "the immutable-tag recovery must explicitly limit its portability overlay to the affected stager");
        assertFalse(workflow.contains("origin/main"),
                "release-tool overlays must not resolve a mutable branch during an immutable release retry");
        assertTrue(workflow.contains("$expectedAssemblerBlob = '764580ef61e014c59f8a345162d6e483f7ee5242'"));
        assertTrue(workflow.contains("$expectedStagerBlob = '21cdd0ed344d23b68a4e3e385fa077b5a9e5752c'"));
        assertTrue(workflow.contains("-ReleaseToolCommit $controlCommit"),
                "release sidecars must record the pinned control snapshot");
        assertTrue(workflow.contains("git restore --source=HEAD --worktree -- $relativeStager"),
                "the immutable tag source must be restored before release assembly");
        assertTrue(workflow.contains("cat-file blob `\"HEAD:$relativeStager`\""),
                "the immutable tag stager must be restored from its raw Git blob before source snapshot validation");
        assertTrue(workflow.contains("git hash-object --no-filters -- $relativeStager"),
                "the immutable tag stager's restored raw bytes must be compared to its Git blob");
        assertTrue(workflow.contains("--draft --title 'Lodestone v1.0.0' --generate-notes"),
                "uploads must begin as an inaccessible draft");
        assertTrue(workflow.contains("gh release download $tag"),
                "draft upload must be downloaded for byte verification");
        assertTrue(workflow.contains("Get-FileHash -LiteralPath $asset.FullName -Algorithm SHA256"),
                "draft upload must compare every staged SHA-256");
        assertTrue(workflow.contains("gh release edit $tag --repo $env:GITHUB_REPOSITORY --draft=false"),
                "only a verified complete draft may publish");
        assertTrue(workflow.indexOf("--draft --title") < workflow.indexOf("gh release download $tag")
                        && workflow.indexOf("gh release download $tag")
                        < workflow.indexOf("--draft=false"),
                "release publication must follow draft upload and downloaded-byte verification");

        var quiltProfileMatrix = Files.readString(root.resolve("verification/quilt-profile-matrix.ps1"),
                StandardCharsets.UTF_8);
        assertTrue(quiltProfileMatrix.contains("Extract-ProfileHost"));
        assertTrue(quiltProfileMatrix.contains("-Quilt1201HostJar $host1201"));
        assertFalse(quiltProfileMatrix.contains("prepare-quilt-host"),
                "final Quilt profile acceptance must not rewrite embedded bytes");
    }

    @Test
    void assembleCreatesCompleteFormatV2ReleaseThenVerifyIsReadOnly(@TempDir Path temporary) throws Exception {
        var fixture = temporary.resolve("fixture-project");
        createArtifactFixture(fixture);
        var staging = temporary.resolve("release");
        var repeatStaging = temporary.resolve("release-repeat");

        var assembled = runReleaseTool("Assemble", fixture, staging, SOURCE_COMMIT, SOURCE_TREE, true);
        assertEquals(0, assembled.exitCode(), assembled.output());
        assertTrue(assembled.output().contains("ASSEMBLED artifacts=32"), assembled.output());
        assertTrue(assembled.output().indexOf("PREMOVE-VERIFIED artifacts=32")
                        < assembled.output().indexOf("ASSEMBLED artifacts=32"),
                "assembly must fully verify temporary payload before final rename: " + assembled.output());

        List<Path> stagedFiles;
        try (var files = Files.list(staging)) {
            stagedFiles = files.sorted().toList();
        }
        assertEquals(36, stagedFiles.size());
        assertTrue(stagedFiles.stream().allMatch(Files::isRegularFile));

        var manifest = readJson(staging.resolve("release-manifest.json"));
        assertEquals(2, manifest.get("formatVersion").getAsInt());
        assertEquals("1.0.0", manifest.get("productVersion").getAsString());
        assertEquals("v1.0.0", manifest.get("tag").getAsString());
        Instant.parse(manifest.get("generatedAtUtc").getAsString());
        assertEquals(SOURCE_COMMIT, manifest.getAsJsonObject("source").get("commit").getAsString());
        assertEquals(SOURCE_TREE, manifest.getAsJsonObject("source").get("tree").getAsString());
        var releaseTool = manifest.getAsJsonObject("source").getAsJsonObject("releaseTool");
        assertEquals(SOURCE_COMMIT, releaseTool.get("commit").getAsString());
        assertEquals(SOURCE_TREE, releaseTool.get("tree").getAsString());
        assertTrue(releaseTool.get("assemblerBlob").getAsString().matches("[0-9a-f]{40}"));
        assertTrue(releaseTool.get("stagerBlob").getAsString().matches("[0-9a-f]{40}"));
        var certification = manifest.getAsJsonObject("certification");
        assertEquals("verification/evidence/release-conformance-v1.0.0.json",
                certification.get("evidencePath").getAsString());
        assertEquals(2, certification.get("formatVersion").getAsInt());
        assertEquals(22, certification.get("matrixRowCount").getAsInt());
        assertEquals(32, certification.get("artifactCount").getAsInt());
        assertEquals(1, certification.get("retainedLogCount").getAsInt());
        assertEquals(sha256(fixture.resolve("verification/evidence/release-conformance-v1.0.0.json")),
                certification.get("sha256").getAsString());
        assertEquals(32, manifest.getAsJsonArray("artifacts").size());
        for (var element : manifest.getAsJsonArray("artifacts")) {
            var artifact = element.getAsJsonObject();
            for (var field : new String[]{"uploadFilename", "sourcePath", "bytes", "sha256", "type",
                    "platform", "minecraftVersions", "buildJava", "runtimeJava", "evidenceRows"}) {
                assertTrue(artifact.has(field), "manifest artifact missing " + field);
            }
            assertTrue(artifact.get("minecraftVersions").isJsonArray());
            assertTrue(artifact.get("buildJava").isJsonArray());
            assertTrue(artifact.get("runtimeJava").isJsonArray());
            assertTrue(artifact.get("evidenceRows").isJsonArray());
            var staged = staging.resolve(artifact.get("uploadFilename").getAsString());
            assertEquals(Files.size(staged), artifact.get("bytes").getAsLong());
            assertEquals(sha256(staged), artifact.get("sha256").getAsString());
            if (artifact.get("type").getAsString().equals("profile")) {
                assertTrue(artifact.get("installerJava").isJsonArray());
                var contents = artifact.getAsJsonObject("profileContents");
                assertEquals(artifact.get("embeddedArtifactUploadFilename").getAsString(),
                        contents.get("embeddedArtifactUploadFilename").getAsString());
                assertTrue(contents.get("embeddedEntry").getAsString().startsWith("overrides/mods/"));
                assertTrue(contents.getAsJsonArray("entries").size() >= 2);
                assertEquals(1, contents.getAsJsonArray("entries").asList().stream()
                        .filter(entry -> entry.getAsJsonObject().get("role").getAsString().equals("embedded-mod"))
                        .count());
            }
            if (artifact.get("type").getAsString().equals("launcher")) {
                assertTrue(artifact.get("launcherJava").isJsonArray());
            }
        }

        var provenance = readJson(staging.resolve("lodestone-1.0.0-provenance.json"));
        assertEquals("https://in-toto.io/Statement/v1", provenance.get("_type").getAsString());
        assertEquals("https://slsa.dev/provenance/v1", provenance.get("predicateType").getAsString());
        assertEquals(32, provenance.getAsJsonArray("subject").size());
        var buildDefinition = provenance.getAsJsonObject("predicate").getAsJsonObject("buildDefinition");
        assertEquals(SOURCE_TREE, buildDefinition.getAsJsonObject("internalParameters")
                .get("assemblySourceTree").getAsString());
        var releaseToolProvenance = buildDefinition.getAsJsonObject("internalParameters")
                .getAsJsonObject("releaseTool");
        assertEquals(SOURCE_COMMIT, releaseToolProvenance.get("commit").getAsString());
        assertEquals(SOURCE_COMMIT, buildDefinition.getAsJsonObject("externalParameters")
                .getAsJsonObject("certifiedArtifactBuildSource").get("commit").getAsString());

        var sbom = readJson(staging.resolve("lodestone-1.0.0-sbom.spdx.json"));
        assertEquals("SPDX-2.3", sbom.get("spdxVersion").getAsString());
        assertEquals(32, sbom.getAsJsonArray("files").size());
        assertTrue(sbom.get("documentComment").getAsString().contains("first-party release artifact inventory"));
        assertTrue(sbom.get("documentComment").getAsString().contains("does not assert complete third-party"));

        var expectedChecksummed = new ArrayList<String>();
        for (var path : stagedFiles) {
            if (!path.getFileName().toString().equals("SHA256SUMS")) {
                expectedChecksummed.add(path.getFileName().toString());
            }
        }
        expectedChecksummed.sort(String::compareTo);
        var checksumLines = Files.readAllLines(staging.resolve("SHA256SUMS"), StandardCharsets.UTF_8);
        assertEquals(35, checksumLines.size());
        var actualChecksummed = new ArrayList<String>();
        for (var line : checksumLines) {
            assertTrue(line.matches("^[0-9a-f]{64}  [a-z0-9][a-z0-9._-]*$"), line);
            var split = line.split("  ", 2);
            actualChecksummed.add(split[1]);
            assertEquals(sha256(staging.resolve(split[1])), split[0], split[1]);
        }
        assertEquals(expectedChecksummed, actualChecksummed);

        var beforeVerify = stagedHashes(staging);
        var verified = runReleaseTool("Verify", fixture, staging, SOURCE_COMMIT, SOURCE_TREE, true);
        assertEquals(0, verified.exitCode(), verified.output());
        assertTrue(verified.output().contains("VERIFIED artifacts=32"), verified.output());
        assertEquals(beforeVerify, stagedHashes(staging), "verify mode changed staged bytes");

        var repeated = runReleaseTool("Assemble", fixture, repeatStaging, SOURCE_COMMIT, SOURCE_TREE, true);
        assertEquals(0, repeated.exitCode(), repeated.output());
        assertEquals(stagedHashes(staging), stagedHashes(repeatStaging),
                "repeated assembly must produce byte-identical 36-file payloads");
    }

    @Test
    void assembleRejectsProfileManifestOutsideV100Contract(@TempDir Path temporary) throws Exception {
        var fixture = temporary.resolve("fixture-project");
        createArtifactFixture(fixture);
        var profile = inventory().getAsJsonArray("artifacts").asList().stream()
                .map(element -> element.getAsJsonObject())
                .filter(artifact -> artifact.get("type").getAsString().equals("profile"))
                .findFirst().orElseThrow();
        var source = fixture.resolve(profile.get("sourcePath").getAsString());
        var profileName = source.getFileName().toString().replaceFirst("-local\\.zip$", "");
        var manifest = fixture.resolve("verification/curseforge-profiles")
                .resolve(profileName).resolve("manifest.json");
        var invalidManifest = profileManifestBytes(profile, profileName, "0.9.0");
        Files.write(manifest, invalidManifest);
        var embedded = artifactByName(profile.get("embeddedArtifactUploadFilename").getAsString());
        var embeddedBytes = Files.readAllBytes(fixture.resolve(embedded.get("sourcePath").getAsString()));
        writeProfile(source, invalidManifest, embeddedBytes, profile.get("platform").getAsString(), false);
        refreshCertificationArtifact(fixture, profile);

        var staging = temporary.resolve("release");
        var result = runReleaseTool("Assemble", fixture, staging, SOURCE_COMMIT, SOURCE_TREE, true);
        assertNotEquals(0, result.exitCode(), result.output());
        assertTrue(result.output().contains("profile manifest must target product 1.0.0"), result.output());
        assertFalse(Files.exists(staging), "failed assembly published a destination");
        try (var files = Files.list(temporary)) {
            assertFalse(files.anyMatch(path -> path.getFileName().toString().contains(".assembling-")),
                    "failed assembly leaked temporary output");
        }
    }

    @Test
    void assembleRejectsRemoteProfileDependenciesOutsideNestedInventory(@TempDir Path temporary) throws Exception {
        var fixture = temporary.resolve("fixture-project");
        createArtifactFixture(fixture);
        var profile = inventory().getAsJsonArray("artifacts").asList().stream()
                .map(element -> element.getAsJsonObject())
                .filter(artifact -> artifact.get("type").getAsString().equals("profile"))
                .findFirst().orElseThrow();
        var source = fixture.resolve(profile.get("sourcePath").getAsString());
        var profileName = source.getFileName().toString().replaceFirst("-local\\.zip$", "");
        var manifest = fixture.resolve("verification/curseforge-profiles")
                .resolve(profileName).resolve("manifest.json");
        var remoteManifest = new String(profileManifestBytes(profile, profileName, "1.0.0"),
                StandardCharsets.UTF_8).replace("\"files\":[]",
                "\"files\":[{\"projectID\":1,\"fileID\":2,\"required\":true}]")
                .getBytes(StandardCharsets.UTF_8);
        Files.write(manifest, remoteManifest);
        var embedded = artifactByName(profile.get("embeddedArtifactUploadFilename").getAsString());
        var embeddedBytes = Files.readAllBytes(fixture.resolve(embedded.get("sourcePath").getAsString()));
        writeProfile(source, remoteManifest, embeddedBytes, profile.get("platform").getAsString(), false);
        refreshCertificationArtifact(fixture, profile);

        var result = runReleaseTool("Assemble", fixture, temporary.resolve("release"),
                SOURCE_COMMIT, SOURCE_TREE, true);
        assertNotEquals(0, result.exitCode(), result.output());
        assertTrue(result.output().contains("profile manifest files must be empty"), result.output());
    }

    @Test
    void assembleRejectsExistingDestination(@TempDir Path temporary) throws Exception {
        var fixture = temporary.resolve("fixture-project");
        createArtifactFixture(fixture);
        var staging = Files.createDirectory(temporary.resolve("release"));

        var result = runReleaseTool("Assemble", fixture, staging, SOURCE_COMMIT, SOURCE_TREE, true);
        assertNotEquals(0, result.exitCode(), result.output());
        assertTrue(result.output().contains("must not already exist"), result.output());
        try (var files = Files.list(staging)) {
            assertEquals(0, files.count(), "existing destination was mutated");
        }
    }

    @Test
    void verifyRejectsUnexpectedStagedFile(@TempDir Path temporary) throws Exception {
        var fixture = temporary.resolve("fixture-project");
        createArtifactFixture(fixture);
        var staging = temporary.resolve("release");
        assertEquals(0, runReleaseTool("Assemble", fixture, staging,
                SOURCE_COMMIT, SOURCE_TREE, true).exitCode());
        Files.writeString(staging.resolve("unexpected.bin"), "unexpected", StandardCharsets.UTF_8);

        var result = runReleaseTool("Verify", fixture, staging, SOURCE_COMMIT, SOURCE_TREE, true);
        assertNotEquals(0, result.exitCode(), result.output());
        assertTrue(result.output().contains("exactly 36 files"), result.output());
    }

    @Test
    void verifyRejectsMissingStagedFile(@TempDir Path temporary) throws Exception {
        var fixture = temporary.resolve("fixture-project");
        createArtifactFixture(fixture);
        var staging = temporary.resolve("release");
        assertEquals(0, runReleaseTool("Assemble", fixture, staging,
                SOURCE_COMMIT, SOURCE_TREE, true).exitCode());
        Files.delete(staging.resolve("lodestone-1.0.0-mod-fabric-mc-1.18.2.jar"));

        var result = runReleaseTool("Verify", fixture, staging, SOURCE_COMMIT, SOURCE_TREE, true);
        assertNotEquals(0, result.exitCode(), result.output());
        assertTrue(result.output().contains("exactly 36 files"), result.output());
    }

    @Test
    void verifyRejectsChangedStagedBytes(@TempDir Path temporary) throws Exception {
        var fixture = temporary.resolve("fixture-project");
        createArtifactFixture(fixture);
        var staging = temporary.resolve("release");
        assertEquals(0, runReleaseTool("Assemble", fixture, staging,
                SOURCE_COMMIT, SOURCE_TREE, true).exitCode());
        Files.writeString(staging.resolve("lodestone-1.0.0-mod-fabric-mc-1.18.2.jar"), "tamper",
                StandardCharsets.UTF_8, StandardOpenOption.APPEND);

        var result = runReleaseTool("Verify", fixture, staging, SOURCE_COMMIT, SOURCE_TREE, true);
        assertNotEquals(0, result.exitCode(), result.output());
        assertTrue(result.output().contains("Staged bytes differ from manifest"), result.output());
    }

    @Test
    void assembleRejectsSourceArtifactOutsideCertificationEvidence(@TempDir Path temporary) throws Exception {
        var fixture = temporary.resolve("fixture-project");
        createArtifactFixture(fixture);
        Files.writeString(fixture.resolve("hosts/fabric/1.18.2/build/libs/lodestone-1.0.0.jar"), "tamper",
                StandardCharsets.UTF_8, StandardOpenOption.APPEND);

        var result = runReleaseTool("Assemble", fixture, temporary.resolve("release"),
                SOURCE_COMMIT, SOURCE_TREE, true);
        assertNotEquals(0, result.exitCode(), result.output());
        assertTrue(result.output().contains("Certification artifact hash or byte length differs from source"),
                result.output());
    }

    @Test
    void verifyRejectsDifferentSourceIdentity(@TempDir Path temporary) throws Exception {
        var fixture = temporary.resolve("fixture-project");
        createArtifactFixture(fixture);
        var staging = temporary.resolve("release");
        assertEquals(0, runReleaseTool("Assemble", fixture, staging,
                SOURCE_COMMIT, SOURCE_TREE, true).exitCode());

        var result = runReleaseTool("Verify", fixture, staging, "c".repeat(40), SOURCE_TREE, true);
        assertNotEquals(0, result.exitCode(), result.output());
        assertTrue(result.output().contains("differs from current source identity"), result.output());
    }

    @Test
    void assembleRejectsUnsafeProfileEntry(@TempDir Path temporary) throws Exception {
        var fixture = temporary.resolve("fixture-project");
        createArtifactFixture(fixture);
        var profile = firstProfile();
        rewriteProfile(fixture, profile, null, true);

        var result = runReleaseTool("Assemble", fixture, temporary.resolve("release"),
                SOURCE_COMMIT, SOURCE_TREE, true);
        assertNotEquals(0, result.exitCode(), result.output());
        assertTrue(result.output().contains("unsafe ZIP entry"), result.output());
    }

    @Test
    void assembleRejectsProfileWithoutBoundHostBytes(@TempDir Path temporary) throws Exception {
        var fixture = temporary.resolve("fixture-project");
        createArtifactFixture(fixture);
        var profile = firstProfile();
        rewriteProfile(fixture, profile, "wrong-host".getBytes(StandardCharsets.UTF_8), false);

        var result = runReleaseTool("Assemble", fixture, temporary.resolve("release"),
                SOURCE_COMMIT, SOURCE_TREE, true);
        assertNotEquals(0, result.exitCode(), result.output());
        assertTrue(result.output().contains("exactly one byte-identical copy"), result.output());
    }

    @Test
    void assembleRejectsChangedRetainedEvidenceLog(@TempDir Path temporary) throws Exception {
        var fixture = temporary.resolve("fixture-project");
        createArtifactFixture(fixture);
        Files.writeString(fixture.resolve("verification/evidence/logs/fixture.log"),
                "tampered evidence\n", StandardCharsets.UTF_8);

        var result = runReleaseTool("Assemble", fixture, temporary.resolve("release"),
                SOURCE_COMMIT, SOURCE_TREE, true);
        assertNotEquals(0, result.exitCode(), result.output());
        assertTrue(result.output().contains("Retained evidence log hash differs"), result.output());
    }

    @Test
    void finalFreezeRejectsDirtyGitTreeWithoutTestBypass(@TempDir Path temporary) throws Exception {
        var fixture = temporary.resolve("fixture-project");
        createArtifactFixture(fixture);
        runCommand(fixture, "git", "init", "--quiet");
        runCommand(fixture, "git", "config", "user.name", "Lodestone Contract Test");
        runCommand(fixture, "git", "config", "user.email", "contract-test@invalid.example");
        runCommand(fixture, "git", "add", "-A");
        runCommand(fixture, "git", "commit", "--quiet", "-m", "fixture");
        Files.writeString(fixture.resolve("dirty-untracked.txt"), "dirty", StandardCharsets.UTF_8);

        var result = runReleaseTool("Assemble", fixture, temporary.resolve("release"),
                SOURCE_COMMIT, SOURCE_TREE, false);
        assertNotEquals(0, result.exitCode(), result.output());
        assertTrue(result.output().contains("requires a clean Git tree"), result.output());
    }

    @Test
    void finalFreezeBindsActualCleanGitCommitAndTree(@TempDir Path temporary) throws Exception {
        var fixture = temporary.resolve("fixture-project");
        createArtifactFixture(fixture);
        runCommand(fixture, "git", "init", "--quiet");
        runCommand(fixture, "git", "config", "user.name", "Lodestone Contract Test");
        runCommand(fixture, "git", "config", "user.email", "contract-test@invalid.example");
        runCommand(fixture, "git", "add", "-A");
        runCommand(fixture, "git", "commit", "--quiet", "-m", "fixture");
        var buildCommit = runCommand(fixture, "git", "rev-parse", "HEAD").output().trim();
        var buildTree = runCommand(fixture, "git", "rev-parse", "HEAD^{tree}").output().trim();
        updateCertificationBuildSource(fixture, buildCommit, buildTree);
        runCommand(fixture, "git", "add", "verification/evidence/release-conformance-v1.0.0.json");
        runCommand(fixture, "git", "commit", "--quiet", "-m", "certification");
        runCommand(fixture, "git", "tag", "v1.0.0");
        var commit = runCommand(fixture, "git", "rev-parse", "HEAD").output().trim();
        var tree = runCommand(fixture, "git", "rev-parse", "HEAD^{tree}").output().trim();
        var staging = temporary.resolve("release");

        var assembled = runReleaseTool("Assemble", fixture, staging, SOURCE_COMMIT, SOURCE_TREE, false);
        assertEquals(0, assembled.exitCode(), assembled.output());
        var manifest = readJson(staging.resolve("release-manifest.json"));
        assertEquals(commit, manifest.getAsJsonObject("source").get("commit").getAsString());
        assertEquals(tree, manifest.getAsJsonObject("source").get("tree").getAsString());
        assertFalse(manifest.getAsJsonObject("source").get("testBypass").getAsBoolean());

        var verified = runReleaseTool("Verify", fixture, staging, SOURCE_COMMIT, SOURCE_TREE, false);
        assertEquals(0, verified.exitCode(), verified.output());
    }

    @Test
    void finalFreezeRejectsMissingStableTag(@TempDir Path temporary) throws Exception {
        var fixture = temporary.resolve("fixture-project");
        createArtifactFixture(fixture);
        runCommand(fixture, "git", "init", "--quiet");
        runCommand(fixture, "git", "config", "user.name", "Lodestone Contract Test");
        runCommand(fixture, "git", "config", "user.email", "contract-test@invalid.example");
        runCommand(fixture, "git", "add", "-A");
        runCommand(fixture, "git", "commit", "--quiet", "-m", "fixture");

        var result = runReleaseTool("Assemble", fixture, temporary.resolve("release"),
                SOURCE_COMMIT, SOURCE_TREE, false);
        assertNotEquals(0, result.exitCode(), result.output());
        assertTrue(result.output().contains("requires tag v1.0.0 at HEAD"), result.output());
    }

    @Test
    void immutableC0ManifestPathIsRejectedWithoutMutation(@TempDir Path temporary) throws Exception {
        var fixture = temporary.resolve("fixture-project");
        Files.createDirectories(fixture);
        var c0 = root.resolve("verification/evidence/release-artifacts-2026-07-12.json");
        var before = sha256(c0);

        var result = runReleaseTool("Assemble", fixture, c0, SOURCE_COMMIT, SOURCE_TREE, true);
        assertNotEquals(0, result.exitCode(), result.output());
        assertTrue(result.output().contains("immutable C0"), result.output());
        assertEquals(before, sha256(c0), "C0 manifest bytes changed");
    }

    private JsonObject inventory() throws IOException {
        return readJson(root.resolve("verification/release-assets-v1.0.0.json"));
    }

    private JsonObject readJson(Path path) throws IOException {
        return JsonParser.parseReader(Files.newBufferedReader(path, StandardCharsets.UTF_8)).getAsJsonObject();
    }

    private JsonObject artifactByName(String uploadFilename) throws IOException {
        return inventory().getAsJsonArray("artifacts").asList().stream()
                .map(element -> element.getAsJsonObject())
                .filter(artifact -> artifact.get("uploadFilename").getAsString().equals(uploadFilename))
                .findFirst().orElseThrow();
    }

    private JsonObject firstProfile() throws IOException {
        return inventory().getAsJsonArray("artifacts").asList().stream()
                .map(element -> element.getAsJsonObject())
                .filter(artifact -> artifact.get("type").getAsString().equals("profile"))
                .findFirst().orElseThrow();
    }

    private void rewriteProfile(Path fixture, JsonObject profile, byte[] replacementEmbedded,
                                boolean unsafe) throws Exception {
        var source = fixture.resolve(profile.get("sourcePath").getAsString());
        var profileName = source.getFileName().toString().replaceFirst("-local\\.zip$", "");
        var manifest = Files.readAllBytes(fixture.resolve("verification/curseforge-profiles")
                .resolve(profileName).resolve("manifest.json"));
        var embedded = artifactByName(profile.get("embeddedArtifactUploadFilename").getAsString());
        var embeddedBytes = replacementEmbedded == null
                ? Files.readAllBytes(fixture.resolve(embedded.get("sourcePath").getAsString()))
                : replacementEmbedded;
        writeProfile(source, manifest, embeddedBytes, profile.get("platform").getAsString(), unsafe);
        refreshCertificationArtifact(fixture, profile);
    }

    private void createArtifactFixture(Path fixture) throws Exception {
        var artifacts = inventory().getAsJsonArray("artifacts");
        var byName = new java.util.HashMap<String, JsonObject>();
        for (var element : artifacts) {
            var artifact = element.getAsJsonObject();
            byName.put(artifact.get("uploadFilename").getAsString(), artifact);
        }
        for (var element : artifacts) {
            var artifact = element.getAsJsonObject();
            var sourcePath = artifact.get("sourcePath").getAsString();
            var source = fixture.resolve(sourcePath);
            Files.createDirectories(source.getParent());
            if (!"profile".equals(artifact.get("type").getAsString())) {
                Files.writeString(source, "fixture:" + artifact.get("uploadFilename").getAsString(),
                        StandardCharsets.UTF_8);
            }
        }
        for (var element : artifacts) {
            var artifact = element.getAsJsonObject();
            if (!"profile".equals(artifact.get("type").getAsString())) {
                continue;
            }
            var source = fixture.resolve(artifact.get("sourcePath").getAsString());
            var profileName = source.getFileName().toString().replaceFirst("-local\\.zip$", "");
            var manifest = fixture.resolve("verification/curseforge-profiles")
                    .resolve(profileName).resolve("manifest.json");
            Files.createDirectories(manifest.getParent());
            var manifestBytes = profileManifestBytes(artifact, profileName, "1.0.0");
            Files.write(manifest, manifestBytes);
            var embedded = byName.get(artifact.get("embeddedArtifactUploadFilename").getAsString());
            var embeddedBytes = Files.readAllBytes(fixture.resolve(embedded.get("sourcePath").getAsString()));
            writeProfile(source, manifestBytes, embeddedBytes, artifact.get("platform").getAsString(), false);
        }
        writeCertificationEvidence(fixture, artifacts);
    }

    private void writeCertificationEvidence(Path fixture, JsonArray artifacts) throws Exception {
        var evidence = new JsonObject();
        evidence.addProperty("formatVersion", 2);
        evidence.addProperty("productVersion", "1.0.0");
        evidence.addProperty("tag", "v1.0.0");
        evidence.addProperty("certifiedAtUtc", "2026-07-14T05:18:24Z");
        var buildSource = new JsonObject();
        buildSource.addProperty("commit", SOURCE_COMMIT);
        buildSource.addProperty("tree", SOURCE_TREE);
        buildSource.add("inputSnapshot", buildInputSnapshot(fixture));
        evidence.add("buildSource", buildSource);
        var logPath = fixture.resolve("verification/evidence/logs/fixture.log");
        Files.createDirectories(logPath.getParent());
        Files.writeString(logPath, "fixture release evidence\n", StandardCharsets.UTF_8);
        var logs = new JsonArray();
        var log = new JsonObject();
        log.addProperty("path", "verification/evidence/logs/fixture.log");
        log.addProperty("sha256", sha256(logPath));
        logs.add(log);
        evidence.add("logs", logs);
        var matrixRows = new JsonArray();
        var seenRows = new HashSet<String>();
        for (var element : artifacts) {
            for (var evidenceRow : element.getAsJsonObject().getAsJsonArray("evidenceRows")) {
                var id = evidenceRow.getAsString();
                if (seenRows.add(id)) {
                    var row = new JsonObject();
                    row.addProperty("id", id);
                    row.addProperty("status", "pass");
                    row.addProperty("method", "fixture");
                    row.addProperty("runLog", "verification/evidence/logs/fixture.log");
                    matrixRows.add(row);
                }
            }
        }
        evidence.add("matrixRows", matrixRows);
        var certifiedArtifacts = new JsonArray();
        for (var element : artifacts) {
            var artifact = element.getAsJsonObject();
            var source = fixture.resolve(artifact.get("sourcePath").getAsString());
            var certified = new JsonObject();
            certified.addProperty("uploadFilename", artifact.get("uploadFilename").getAsString());
            certified.addProperty("sourcePath", artifact.get("sourcePath").getAsString());
            certified.addProperty("bytes", Files.size(source));
            certified.addProperty("sha256", sha256(source));
            var evidenceRows = new JsonArray();
            for (var evidenceRow : artifact.getAsJsonArray("evidenceRows")) {
                evidenceRows.add(evidenceRow.getAsString());
            }
            certified.add("evidenceRows", evidenceRows);
            certifiedArtifacts.add(certified);
        }
        evidence.add("artifacts", certifiedArtifacts);
        var path = fixture.resolve("verification/evidence/release-conformance-v1.0.0.json");
        Files.createDirectories(path.getParent());
        Files.writeString(path, evidence + "\n", StandardCharsets.UTF_8);
    }

    private void updateCertificationBuildSource(Path fixture, String commit, String tree) throws Exception {
        var path = fixture.resolve("verification/evidence/release-conformance-v1.0.0.json");
        var evidence = readJson(path);
        var source = evidence.getAsJsonObject("buildSource");
        source.addProperty("commit", commit);
        source.addProperty("tree", tree);
        source.add("inputSnapshot", buildInputSnapshot(fixture));
        Files.writeString(path, evidence + "\n", StandardCharsets.UTF_8);
    }

    private JsonObject buildInputSnapshot(Path fixture) throws Exception {
        var roots = List.of("common", "adapters", "gateway", "hosts", "protocol", "verification/curseforge-profiles");
        var files = new ArrayList<Path>();
        for (var rootName : roots) {
            var sourceRoot = fixture.resolve(rootName);
            if (!Files.isDirectory(sourceRoot)) continue;
            try (var stream = Files.walk(sourceRoot)) {
                stream.filter(Files::isRegularFile)
                        .filter(path -> !path.toString().matches(".*[\\\\/](build|runs|run|\\.gradle)[\\\\/].*"))
                        .filter(path -> List.of(".java", ".json", ".toml", ".properties", ".gradle", ".kts", ".ps1")
                                .contains(extension(path)))
                        .forEach(files::add);
            }
        }
        for (var rootFile : List.of("build.gradle.kts", "settings.gradle.kts", "gradle.properties",
                "gradle/wrapper/gradle-wrapper.properties")) {
            var file = fixture.resolve(rootFile);
            if (Files.isRegularFile(file)) files.add(file);
        }
        var rows = files.stream().distinct().sorted(Comparator.comparing(path -> path.toString()))
                .map(path -> fixture.relativize(path).toString().replace('\\', '/') + "\t" + uncheckedSha256(path))
                .toList();
        var digest = MessageDigest.getInstance("SHA-256").digest(String.join("\n", rows).getBytes(StandardCharsets.UTF_8));
        var snapshot = new JsonObject();
        snapshot.addProperty("algorithm", "sha256");
        snapshot.addProperty("fileCount", rows.size());
        snapshot.addProperty("sha256", hex(digest));
        var serializedRows = new JsonArray();
        rows.forEach(serializedRows::add);
        snapshot.add("rows", serializedRows);
        return snapshot;
    }

    private static String extension(Path path) {
        var name = path.getFileName().toString();
        var dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot);
    }

    private static String uncheckedSha256(Path path) {
        try {
            return sha256(path);
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static String hex(byte[] digest) {
        var result = new StringBuilder(64);
        for (var value : digest) result.append(String.format("%02x", value));
        return result.toString();
    }

    private void refreshCertificationArtifact(Path fixture, JsonObject artifact) throws Exception {
        var path = fixture.resolve("verification/evidence/release-conformance-v1.0.0.json");
        var evidence = readJson(path);
        var targetName = artifact.get("uploadFilename").getAsString();
        var source = fixture.resolve(artifact.get("sourcePath").getAsString());
        var certified = evidence.getAsJsonArray("artifacts").asList().stream()
                .map(element -> element.getAsJsonObject())
                .filter(candidate -> candidate.get("uploadFilename").getAsString().equals(targetName))
                .findFirst().orElseThrow();
        certified.addProperty("bytes", Files.size(source));
        certified.addProperty("sha256", sha256(source));
        Files.writeString(path, evidence + "\n", StandardCharsets.UTF_8);
    }

    private static byte[] profileManifestBytes(JsonObject artifact, String profileName, String productVersion) {
        var platform = artifact.get("platform").getAsString();
        var loaderId = switch (platform) {
            case "fabric" -> "fabric-0.0-test";
            case "quilt" -> "quilt-0.0-test";
            case "forge" -> "forge-0.0-test";
            case "neoforge" -> "neoforge-0.0-test";
            default -> throw new IllegalArgumentException("unsupported fixture profile platform: " + platform);
        };
        var minecraftVersion = artifact.getAsJsonArray("minecraftVersions").get(0).getAsString();
        return ("{\"manifestType\":\"minecraftModpack\",\"manifestVersion\":1,"
                + "\"name\":\"" + profileName + "\",\"version\":\"" + productVersion + "\","
                + "\"author\":\"contract-test\",\"files\":[],\"overrides\":\"overrides\","
                + "\"minecraft\":{\"version\":\"" + minecraftVersion
                + "\",\"modLoaders\":[{\"id\":\"" + loaderId + "\",\"primary\":true}]}}\n")
                .getBytes(StandardCharsets.UTF_8);
    }

    private static void writeProfile(Path target, byte[] manifest, byte[] embedded,
                                     String platform, boolean unsafe) throws IOException {
        try (var output = new ZipOutputStream(Files.newOutputStream(target))) {
            output.putNextEntry(new ZipEntry("manifest.json"));
            output.write(manifest);
            output.closeEntry();
            output.putNextEntry(new ZipEntry("overrides/mods/lodestone-fixture.jar"));
            output.write(embedded);
            output.closeEntry();
            if (platform.equals("fabric") || platform.equals("quilt")) {
                output.putNextEntry(new ZipEntry("overrides/mods/fabric-api-fixture.jar"));
                output.write(fabricDependencyJar());
                output.closeEntry();
            }
            if (unsafe) {
                output.putNextEntry(new ZipEntry("../escape.txt"));
                output.write(1);
                output.closeEntry();
            }
        }
    }

    private static byte[] fabricDependencyJar() throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var output = new ZipOutputStream(bytes)) {
            output.putNextEntry(new ZipEntry("fabric.mod.json"));
            output.write("{\"id\":\"fabric-api-fixture\"}".getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }
        return bytes.toByteArray();
    }

    private ProcessResult runReleaseTool(String mode, Path fixture, Path staging,
                                         String sourceCommit, String sourceTree,
                                         boolean allowTestBypass) throws Exception {
        var command = new ArrayList<>(List.of(
                powerShellExecutable(), "-NoLogo", "-NoProfile", "-NonInteractive", "-File",
                root.resolve("verification/assemble-v1-release.ps1").toString(),
                "-Mode", mode,
                "-StagingDirectory", staging.toString(),
                "-ProjectRoot", fixture.toString()));
        if (allowTestBypass) {
            command.addAll(List.of("-AllowDirtyTreeForTests", "-SourceCommit", sourceCommit,
                    "-SourceTree", sourceTree));
        }
        var process = new ProcessBuilder(command).redirectErrorStream(true).start();
        var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return new ProcessResult(process.waitFor(), output);
    }

    private static ProcessResult runCommand(Path directory, String... command) throws Exception {
        var process = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true).start();
        var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        var result = new ProcessResult(process.waitFor(), output);
        assertEquals(0, result.exitCode(), result.output());
        return result;
    }

    private static String powerShellExecutable() {
        return System.getProperty("os.name").toLowerCase().contains("windows") ? "powershell.exe" : "pwsh";
    }

    private static Map<String, String> stagedHashes(Path directory) throws Exception {
        var hashes = new LinkedHashMap<String, String>();
        try (var files = Files.list(directory)) {
            for (var file : files.sorted(Comparator.comparing(path -> path.getFileName().toString())).toList()) {
                hashes.put(file.getFileName().toString(), sha256(file));
            }
        }
        return hashes;
    }

    private static String sha256(Path path) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path));
        var result = new StringBuilder(64);
        for (var value : digest) {
            result.append(String.format("%02x", value));
        }
        return result.toString();
    }

    private record ProcessResult(int exitCode, String output) {
    }
}
