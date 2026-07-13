// SPDX-License-Identifier: MIT
package dev.lodestone.runtime;

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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class TokenFileTest {
    @Test
    void createsOnceAndReusesTheAtomicToken(@TempDir Path directory) throws Exception {
        var path = directory.resolve("config").resolve("lodestone.token");

        var first = TokenFile.readOrCreate(path, "first-generated-token");
        var second = TokenFile.readOrCreate(path, "second-generated-token");

        assertEquals("first-generated-token", first);
        assertEquals(first, second);
        assertEquals(first, Files.readString(path));
        assertNotEquals("second-generated-token", Files.readString(path));

        var posix = Files.getFileAttributeView(path, PosixFileAttributeView.class);
        if (posix != null) {
            assertEquals(EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                    posix.readAttributes().permissions());
        } else {
            var acl = Files.getFileAttributeView(path, AclFileAttributeView.class);
            assertTrue(acl != null, "test filesystem must expose POSIX permissions or ACLs");
            assertFalse(acl.getAcl().isEmpty());
            var owner = Files.getOwner(path);
            assertTrue(acl.getAcl().stream().allMatch(entry -> entry.principal().equals(owner)));
        }
    }

    @Test
    void rejectsSymbolicLinkTokenFiles(@TempDir Path directory) throws Exception {
        var target = directory.resolve("target.token");
        Files.writeString(target, "stolen-token");
        var link = directory.resolve("lodestone.token");
        try {
            Files.createSymbolicLink(link, target.getFileName());
        } catch (UnsupportedOperationException | java.nio.file.FileSystemException unavailable) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false,
                    "test filesystem does not permit symbolic links: " + unavailable.getMessage());
        }

        assertThrows(java.io.IOException.class, () -> TokenFile.readOrCreate(link, "generated-token"));
        assertEquals("stolen-token", Files.readString(target));
    }
}
