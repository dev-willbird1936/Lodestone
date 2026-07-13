// SPDX-License-Identifier: MIT
package dev.lodestone.legacyshared;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacyTokenFileTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void createsOwnerOnlyTokenAndPreservesExistingValue() throws Exception {
        Path path = temporaryDirectory.resolve("config").resolve("lodestone-legacy.token");
        assertEquals("first-token", LegacyTokenFile.readOrCreate(path, "first-token"));
        assertEquals("first-token", LegacyTokenFile.readOrCreate(path, "second-token"));

        PosixFileAttributeView posix = Files.getFileAttributeView(path, PosixFileAttributeView.class);
        if (posix != null) {
            assertEquals(EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                    posix.readAttributes().permissions());
            return;
        }
        AclFileAttributeView acl = Files.getFileAttributeView(path, AclFileAttributeView.class);
        assertTrue(acl != null, "test filesystem must expose POSIX permissions or ACLs");
        assertFalse(acl.getAcl().isEmpty());
        java.nio.file.attribute.UserPrincipal owner = Files.getOwner(path);
        assertTrue(acl.getAcl().stream().allMatch(entry -> entry.principal().equals(owner)));
    }

    @Test
    void rejectsSymbolicLinkFilesAndParents() throws Exception {
        Path target = temporaryDirectory.resolve("target.token");
        Files.write(target, "stolen-token".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Path fileLink = temporaryDirectory.resolve("file-link.token");
        Path realDirectory = temporaryDirectory.resolve("real-config");
        Files.createDirectories(realDirectory);
        Path parentLink = temporaryDirectory.resolve("linked-config");
        try {
            Files.createSymbolicLink(fileLink, target.getFileName());
            Files.createSymbolicLink(parentLink, realDirectory.getFileName());
        } catch (UnsupportedOperationException | java.nio.file.FileSystemException unavailable) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false,
                    "test filesystem does not permit symbolic links: " + unavailable.getMessage());
        }

        assertThrows(java.io.IOException.class,
                () -> LegacyTokenFile.readOrCreate(fileLink, "generated-token"));
        assertThrows(java.io.IOException.class,
                () -> LegacyTokenFile.readOrCreate(parentLink.resolve("lodestone.token"), "generated-token"));
        assertEquals("stolen-token", new String(Files.readAllBytes(target),
                java.nio.charset.StandardCharsets.UTF_8));
    }

    @Test
    void rejectsOversizedExistingToken() throws Exception {
        Path path = temporaryDirectory.resolve("oversized.token");
        Files.write(path, new byte[4_097]);

        assertThrows(java.io.IOException.class,
                () -> LegacyTokenFile.readOrCreate(path, "generated-token"));
    }
}
