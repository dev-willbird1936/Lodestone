// SPDX-License-Identifier: MIT
package dev.lodestone.runtime;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.EnumSet;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Atomic, owner-readable token storage for hosts that embed runtime-core. */
public final class TokenFile {
    private TokenFile() {
    }

    public static String readOrCreate(Path path, String generated) throws IOException {
        if (path == null || generated == null || generated.isBlank()) {
            throw new IllegalArgumentException("token path and generated token are required");
        }
        var normalized = path.toAbsolutePath().normalize();
        Files.createDirectories(normalized.getParent());
        rejectSymbolicLinks(normalized);
        if (Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS)) {
            restrict(normalized);
            return readExisting(normalized);
        }

        var temporary = normalized.resolveSibling(normalized.getFileName() + ".tmp-" + UUID.randomUUID());
        try {
            Files.createFile(temporary);
            restrict(temporary);
            Files.write(temporary, generated.getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            try {
                moveIntoPlace(temporary, normalized);
            } catch (FileAlreadyExistsException race) {
                if (Files.isSymbolicLink(normalized)) {
                    throw new IOException("token file became a symbolic link during creation: " + normalized, race);
                }
                restrict(normalized);
                return readExisting(normalized);
            }
            restrict(normalized);
            return generated;
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void moveIntoPlace(Path temporary, Path target) throws IOException {
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(temporary, target);
        }
    }

    private static String readExisting(Path path) throws IOException {
        rejectSymbolicLinks(path);
        byte[] bytes;
        try (var channel = Files.newByteChannel(path,
                Set.<OpenOption>of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
            var bounded = ByteBuffer.allocate(4_097);
            while (bounded.hasRemaining()) {
                var read = channel.read(bounded);
                if (read < 0) {
                    break;
                }
            }
            if (bounded.position() > 4_096) {
                throw new IOException("token file is unexpectedly large: " + path);
            }
            bytes = Arrays.copyOf(bounded.array(), bounded.position());
        }
        var existing = new String(bytes, StandardCharsets.UTF_8).trim();
        if (existing.isBlank()) {
            throw new IOException("token file is empty: " + path);
        }
        return existing;
    }

    private static void rejectSymbolicLinks(Path path) throws IOException {
        var current = path.getRoot();
        for (var component : path) {
            current = current == null ? component : current.resolve(component);
            if (Files.isSymbolicLink(current)) {
                throw new IOException("token path must not contain symbolic links: " + current);
            }
        }
    }

    private static void restrict(Path path) throws IOException {
        var posix = Files.getFileAttributeView(path, PosixFileAttributeView.class);
        if (posix != null) {
            posix.setPermissions(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
            return;
        }
        var acl = Files.getFileAttributeView(path, AclFileAttributeView.class);
        if (acl != null) {
            var owner = Files.getOwner(path);
            var permissions = EnumSet.allOf(AclEntryPermission.class);
            var entry = AclEntry.newBuilder()
                    .setType(AclEntryType.ALLOW)
                    .setPrincipal(owner)
                    .setPermissions(permissions)
                    .setFlags(Set.of())
                    .build();
            acl.setAcl(List.of(entry));
            return;
        }
        throw new IOException("filesystem cannot enforce owner-only token permissions: " + path);
    }
}
