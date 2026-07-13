// SPDX-License-Identifier: MIT
package dev.lodestone.legacyshared;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Collections;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

/** Atomic, owner-only token storage that remains compatible with Java 8 legacy Forge. */
public final class LegacyTokenFile {
    private static final int MAX_TOKEN_BYTES = 4_096;

    private LegacyTokenFile() {
    }

    public static String readOrCreate(Path path, String generated) throws IOException {
        if (path == null || generated == null || generated.trim().isEmpty()) {
            throw new IllegalArgumentException("token path and generated token are required");
        }
        Path normalized = path.toAbsolutePath().normalize();
        Files.createDirectories(normalized.getParent());
        rejectSymbolicLinks(normalized);
        if (Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS)) {
            restrict(normalized);
            return readExisting(normalized);
        }

        Path temporary = normalized.resolveSibling(normalized.getFileName() + ".tmp-" + UUID.randomUUID());
        try {
            Files.createFile(temporary);
            restrict(temporary);
            Files.write(temporary, generated.getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            try {
                moveIntoPlace(temporary, normalized);
            } catch (FileAlreadyExistsException race) {
                rejectSymbolicLinks(normalized);
                restrict(normalized);
                return readExisting(normalized);
            }
            rejectSymbolicLinks(normalized);
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
        java.nio.channels.SeekableByteChannel channel = Files.newByteChannel(path,
                StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
        try {
            ByteBuffer bounded = ByteBuffer.allocate(MAX_TOKEN_BYTES + 1);
            while (bounded.hasRemaining()) {
                int read = channel.read(bounded);
                if (read < 0) break;
            }
            if (bounded.position() > MAX_TOKEN_BYTES) {
                throw new IOException("token file is unexpectedly large: " + path);
            }
            bytes = Arrays.copyOf(bounded.array(), bounded.position());
        } finally {
            channel.close();
        }
        String existing = new String(bytes, StandardCharsets.UTF_8).trim();
        if (existing.isEmpty()) throw new IOException("token file is empty: " + path);
        return existing;
    }

    private static void rejectSymbolicLinks(Path path) throws IOException {
        Path current = path.getRoot();
        for (Path component : path) {
            current = current == null ? component : current.resolve(component);
            if (Files.isSymbolicLink(current)) {
                throw new IOException("token path must not contain symbolic links: " + current);
            }
        }
    }

    private static void restrict(Path path) throws IOException {
        PosixFileAttributeView posix = Files.getFileAttributeView(path, PosixFileAttributeView.class);
        if (posix != null) {
            posix.setPermissions(EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
            return;
        }
        AclFileAttributeView acl = Files.getFileAttributeView(path, AclFileAttributeView.class);
        if (acl != null) {
            AclEntry ownerOnly = AclEntry.newBuilder()
                    .setType(AclEntryType.ALLOW)
                    .setPrincipal(Files.getOwner(path))
                    .setPermissions(EnumSet.allOf(AclEntryPermission.class))
                    .setFlags(Collections.emptySet())
                    .build();
            acl.setAcl(Collections.singletonList(ownerOnly));
            return;
        }
        throw new IOException("filesystem cannot enforce owner-only token permissions: " + path);
    }
}
