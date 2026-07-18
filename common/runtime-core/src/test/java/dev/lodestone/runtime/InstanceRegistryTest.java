// SPDX-License-Identifier: MIT
package dev.lodestone.runtime;

import dev.lodestone.protocol.JsonSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class InstanceRegistryTest {
    private String originalUserHome;
    private Path fakeHome;

    @BeforeEach
    void redirectUserHome(@org.junit.jupiter.api.io.TempDir Path tempDir) {
        originalUserHome = System.getProperty("user.home");
        fakeHome = tempDir;
        System.setProperty("user.home", fakeHome.toString());
    }

    @AfterEach
    void restoreUserHome() {
        System.setProperty("user.home", originalUserHome);
    }

    @Test
    void writesUnderHomeDirectoryNotUserDir() throws IOException {
        var directory = InstanceRegistry.directory();

        assertEquals(fakeHome.resolve(".lodestone").resolve("instances"), directory);
        assertFalse(directory.startsWith(Path.of(System.getProperty("user.dir"))));
    }

    @Test
    void writeCreatesAReadableEntryNamedByPort() throws IOException {
        var entry = new InstanceRegistryEntry(
                37821, fakeHome.resolve("config").resolve("lodestone.token").toString(),
                12345L, "/instances/mod-testing", "1.1.0", Instant.parse("2026-07-18T00:00:00Z"));

        InstanceRegistry.write(entry);

        var path = InstanceRegistry.entryPath(37821);
        assertTrue(Files.isRegularFile(path));
        var roundTripped = JsonSupport.MAPPER.fromJson(Files.readString(path), InstanceRegistryEntry.class);
        assertEquals(entry, roundTripped);
    }

    @Test
    void writeReplacesAnExistingEntryForTheSamePort() throws IOException {
        var first = new InstanceRegistryEntry(37821, "token-a", 1L, "dir-a", "1.0.0", Instant.EPOCH);
        var second = new InstanceRegistryEntry(37821, "token-b", 2L, "dir-b", "1.1.0", Instant.now());

        InstanceRegistry.write(first);
        InstanceRegistry.write(second);

        var roundTripped = JsonSupport.MAPPER.fromJson(
                Files.readString(InstanceRegistry.entryPath(37821)), InstanceRegistryEntry.class);
        assertEquals(second, roundTripped);
    }

    @Test
    void deleteRemovesTheEntryAndToleratesAMissingOne() throws IOException {
        InstanceRegistry.write(new InstanceRegistryEntry(37821, "token", 1L, "dir", "1.1.0", Instant.now()));
        assertTrue(Files.isRegularFile(InstanceRegistry.entryPath(37821)));

        InstanceRegistry.delete(37821);
        assertFalse(Files.exists(InstanceRegistry.entryPath(37821)));

        InstanceRegistry.delete(37821);
    }

    @Test
    void writeDoesNotDisturbOtherPortsEntries() throws IOException {
        InstanceRegistry.write(new InstanceRegistryEntry(37821, "token-a", 1L, "dir-a", "1.1.0", Instant.now()));
        InstanceRegistry.write(new InstanceRegistryEntry(37828, "token-b", 2L, "dir-b", "1.1.0", Instant.now()));

        assertTrue(Files.isRegularFile(InstanceRegistry.entryPath(37821)));
        assertTrue(Files.isRegularFile(InstanceRegistry.entryPath(37828)));
    }
}
