// SPDX-License-Identifier: MIT
package dev.lodestone.adapter;

import java.util.Map;
import java.util.regex.Pattern;

/** Immutable, JSON-safe metadata for a staged invocation artifact. */
public record ArtifactReference(String uri, String mediaType, String sha256,
                                long sizeBytes, long expiresAtEpochMs) {
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final String URI_PREFIX = "lodestone://artifacts/sha256/";

    public ArtifactReference {
        if (sha256 == null || !SHA256.matcher(sha256).matches()) {
            throw new IllegalArgumentException("sha256 must be 64 lowercase hexadecimal characters");
        }
        if (!((URI_PREFIX + sha256).equals(uri))) {
            throw new IllegalArgumentException("artifact uri must contain its SHA-256 digest");
        }
        if (mediaType == null || mediaType.isBlank()) {
            throw new IllegalArgumentException("mediaType must not be blank");
        }
        if (sizeBytes < 1L) {
            throw new IllegalArgumentException("sizeBytes must be positive");
        }
        if (expiresAtEpochMs < 1L) {
            throw new IllegalArgumentException("expiresAtEpochMs must be positive");
        }
    }

    /** Exact canonical JSON metadata accepted by the runtime publication barrier. */
    public Map<String, Object> toMetadata() {
        return Map.of(
                "uri", uri,
                "mediaType", mediaType,
                "sha256", sha256,
                "sizeBytes", sizeBytes,
                "expiresAtEpochMs", expiresAtEpochMs);
    }
}
