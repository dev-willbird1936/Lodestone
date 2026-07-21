// SPDX-License-Identifier: MIT
package dev.lodestone.runtime;

import dev.lodestone.protocol.JsonSupport;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Instance-independent discovery registry: one file per running Lodestone instance, named by
 * port, written under a well-known location outside any specific instance folder ({@code
 * ~/.lodestone/instances}) rather than under {@code user.dir} (which is instance-specific and is
 * exactly what a caller has to already know today).
 *
 * <p>One file per instance avoids multiple JVMs needing to lock and merge a single shared file.
 * Entries reference the instance's existing token file path rather than duplicating the token
 * value: the token is the actual secret, and a second copy of it would only widen where it can
 * leak from without adding any capability a path reference doesn't already provide (a reader that
 * can access the registry directory can equally access the referenced token file).
 *
 * <p>Stale-entry detection (a registry file outliving a hard-killed process) is intentionally not
 * handled here: each entry records its own {@code pid} so a <em>reader</em> can check liveness
 * itself. That check belongs in the discovery tool, not in the mod, which has no reason to police
 * other instances' files.
 */
public final class InstanceRegistry {
    private InstanceRegistry() {
    }

    public static Path directory() {
        return Path.of(System.getProperty("user.home"), ".lodestone", "instances");
    }

    public static Path entryPath(int port) {
        return directory().resolve(port + ".json");
    }

    /** Writes (or atomically replaces) this instance's registry entry. */
    public static void write(InstanceRegistryEntry entry) throws IOException {
        var path = entryPath(entry.port());
        Files.createDirectories(path.getParent());
        var json = JsonSupport.MAPPER.toJson(entry);

        var temporary = path.resolveSibling(path.getFileName() + ".tmp-" + UUID.randomUUID());
        try {
            Files.writeString(temporary, json, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            restrict(temporary);
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /** Removes this instance's registry entry, if present. Best-effort: never throws. */
    public static void delete(int port) {
        try {
            Files.deleteIfExists(entryPath(port));
        } catch (IOException ignored) {
            // A leftover file is handled by the discovery tool's PID liveness check.
        }
    }

    /**
     * Lists every live local Minecraft process that has registered Lodestone. Registry entries
     * are written only after the loopback MCP listener has bound.
     */
    public static List<InstanceRegistryEntry> liveEntries() throws IOException {
        var directory = directory();
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (var paths = Files.list(directory)) {
            return paths
                    .filter(path -> Files.isRegularFile(path)
                            && path.getFileName().toString().endsWith(".json"))
                    .map(InstanceRegistry::readEntry)
                    .flatMap(java.util.Optional::stream)
                    .filter(entry -> ProcessHandle.of(entry.pid()).map(ProcessHandle::isAlive).orElse(false))
                    .sorted(Comparator.comparingInt(InstanceRegistryEntry::port))
                    .collect(Collectors.toUnmodifiableList());
        }
    }

    private static java.util.Optional<InstanceRegistryEntry> readEntry(Path path) {
        try {
            return java.util.Optional.of(JsonSupport.MAPPER.fromJson(
                    Files.readString(path, StandardCharsets.UTF_8), InstanceRegistryEntry.class));
        } catch (IOException | RuntimeException ignored) {
            return java.util.Optional.empty();
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
            var aclEntry = AclEntry.newBuilder()
                    .setType(AclEntryType.ALLOW)
                    .setPrincipal(owner)
                    .setPermissions(permissions)
                    .setFlags(Set.of())
                    .build();
            acl.setAcl(List.of(aclEntry));
        }
        // Unlike TokenFile, this entry does not hold the secret itself, so a filesystem that can
        // enforce neither POSIX permissions nor ACLs still gets a usable (if unrestricted) entry
        // rather than failing instance startup over discovery bookkeeping.
    }
}
