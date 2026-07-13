// SPDX-License-Identifier: MIT
package dev.lodestone.adapter;

/** Invocation-scoped staging boundary for binary capability results. */
@FunctionalInterface
public interface ArtifactSink {
    /**
     * Stages content for transactional publication by the runtime. Staged content is not readable
     * until the invocation succeeds and the handler returns the exact
     * {@link ArtifactReference#toMetadata()} value. The runtime may advance only the expiry during
     * publication and returns that canonical metadata to the caller. Implementations defensively
     * copy {@code content}.
     */
    ArtifactReference stage(String mediaType, byte[] content);
}
