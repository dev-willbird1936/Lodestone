// SPDX-License-Identifier: MIT
package dev.lodestone.runtime;

import dev.lodestone.adapter.ArtifactReference;
import dev.lodestone.adapter.ArtifactSink;
import dev.lodestone.protocol.ResourceDescriptor;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.LongSupplier;

/** Owner-isolated, bounded, in-memory store for invocation-transactional PNG artifacts. */
final class CallerArtifactStore implements AutoCloseable {
    static final long DEFAULT_TTL_MS = 60_000L;
    static final long DEFAULT_MAX_ARTIFACT_BYTES = 11L * 1024L * 1024L;
    static final long DEFAULT_MAX_BYTES_PER_CALLER = 64L * 1024L * 1024L;
    static final int DEFAULT_MAX_HASHES_PER_CALLER = 2;
    static final long DEFAULT_MAX_GLOBAL_BYTES = 256L * 1024L * 1024L;
    static final int DEFAULT_MAX_GLOBAL_HASHES = 256;
    static final long DEFAULT_MAX_PIXELS = 16_777_216L;

    private static final String PNG_MEDIA_TYPE = "image/png";
    private static final String URI_PREFIX = "lodestone://artifacts/sha256/";
    private static final Set<String> METADATA_FIELDS = Set.of(
            "uri", "mediaType", "sha256", "sizeBytes", "expiresAtEpochMs");
    private static final byte[] PNG_SIGNATURE = new byte[] {
            (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a
    };

    private final LongSupplier clock;
    private final long ttlMs;
    private final long maxArtifactBytes;
    private final long maxBytesPerCaller;
    private final int maxHashesPerCaller;
    private final long maxGlobalBytes;
    private final int maxGlobalHashes;
    private final long maxPixels;
    private final Map<String, CallerState> callers = new HashMap<>();
    private final Map<String, CallerEpoch> callerEpochs = new HashMap<>();
    private long globalBytes;
    private int globalHashes;
    private long publicationSequence;
    private boolean closed;

    CallerArtifactStore() {
        this(System::currentTimeMillis, DEFAULT_TTL_MS, DEFAULT_MAX_ARTIFACT_BYTES,
                DEFAULT_MAX_BYTES_PER_CALLER, DEFAULT_MAX_HASHES_PER_CALLER,
                DEFAULT_MAX_GLOBAL_BYTES, DEFAULT_MAX_GLOBAL_HASHES, DEFAULT_MAX_PIXELS);
    }

    CallerArtifactStore(LongSupplier clock, long ttlMs, long maxBytesPerCaller, int maxHashesPerCaller) {
        this(clock, ttlMs, Math.min(DEFAULT_MAX_ARTIFACT_BYTES, maxBytesPerCaller),
                maxBytesPerCaller, maxHashesPerCaller,
                saturatedMultiply(maxBytesPerCaller, 64L),
                saturatedMultiply(maxHashesPerCaller, 64), DEFAULT_MAX_PIXELS);
    }

    CallerArtifactStore(LongSupplier clock, long ttlMs, long maxArtifactBytes,
                        long maxBytesPerCaller, int maxHashesPerCaller,
                        long maxGlobalBytes, int maxGlobalHashes, long maxPixels) {
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
        if (ttlMs < 1L || maxArtifactBytes < 1L || maxBytesPerCaller < 1L
                || maxHashesPerCaller < 1 || maxGlobalBytes < 1L
                || maxGlobalHashes < 1 || maxPixels < 1L) {
            throw new IllegalArgumentException("artifact limits must be positive");
        }
        if (maxArtifactBytes > maxBytesPerCaller) {
            throw new IllegalArgumentException("per-artifact limit must not exceed the per-caller byte quota");
        }
        this.ttlMs = ttlMs;
        this.maxArtifactBytes = maxArtifactBytes;
        this.maxBytesPerCaller = maxBytesPerCaller;
        this.maxHashesPerCaller = maxHashesPerCaller;
        this.maxGlobalBytes = maxGlobalBytes;
        this.maxGlobalHashes = maxGlobalHashes;
        this.maxPixels = maxPixels;
    }

    synchronized InvocationArtifactSink openSink(String callerSessionId, String invocationOwnerId) {
        requireOpen();
        var caller = requireIdentifier(callerSessionId, "callerSessionId");
        var epoch = callerEpochs.computeIfAbsent(caller, ignored -> new CallerEpoch());
        return new InvocationArtifactSink(this, caller,
                requireIdentifier(invocationOwnerId, "invocationOwnerId"), epoch);
    }

    synchronized List<ResourceDescriptor> list(String callerSessionId) {
        var caller = requireIdentifier(callerSessionId, "callerSessionId");
        if (closed) return List.of();
        var state = prune(caller);
        if (state == null) return List.of();
        return state.entries.values().stream()
                .filter(ArtifactEntry::published)
                .sorted(Comparator.comparing(ArtifactEntry::uri))
                .map(entry -> new ResourceDescriptor(entry.uri(),
                        "Invocation screenshot " + entry.digest.substring(0, 12),
                        "Caller-isolated screenshot artifact; expires 60 seconds after publication.",
                        entry.mediaType))
                .toList();
    }

    synchronized Optional<ResourceContent> read(String uri, String callerSessionId) {
        var caller = requireIdentifier(callerSessionId, "callerSessionId");
        if (uri == null || uri.isBlank() || closed || !uri.startsWith(URI_PREFIX)) return Optional.empty();
        var state = prune(caller);
        if (state == null) return Optional.empty();
        var entry = state.entries.get(uri.substring(URI_PREFIX.length()));
        if (entry == null || !entry.published() || !entry.uri().equals(uri)) return Optional.empty();
        return Optional.of(ResourceContent.binary(entry.mediaType, entry.bytes));
    }

    synchronized void releaseCaller(String callerSessionId) {
        var caller = requireIdentifier(callerSessionId, "callerSessionId");
        var epoch = callerEpochs.remove(caller);
        if (epoch != null) epoch.revoked = true;
        var removed = callers.remove(caller);
        if (removed != null) wipe(removed);
    }

    /** Removes the newest caller-owned publication for one URI, preserving older identical captures. */
    synchronized boolean releaseArtifact(String uri, String callerSessionId) {
        var caller = requireIdentifier(callerSessionId, "callerSessionId");
        if (uri == null || !uri.startsWith(URI_PREFIX) || closed) return false;
        var state = prune(caller);
        if (state == null) return false;
        var entry = state.entries.get(uri.substring(URI_PREFIX.length()));
        if (entry == null || !entry.uri().equals(uri) || entry.publications.isEmpty()) return false;
        var newest = entry.publications.entrySet().stream()
                .max(Map.Entry.comparingByValue(Comparator.comparingLong(Publication::sequence)))
                .orElseThrow();
        entry.publications.remove(newest.getKey());
        removeIfUnowned(caller, state, entry);
        return true;
    }

    private synchronized ArtifactReference stage(String caller, String owner, CallerEpoch epoch,
                                                 String mediaType, byte[] content) {
        requireOpen();
        requireEpoch(caller, epoch);
        if (!PNG_MEDIA_TYPE.equals(mediaType)) {
            throw new IllegalArgumentException("only image/png artifacts are accepted");
        }
        if (content == null || content.length < 1) {
            throw new IllegalArgumentException("artifact content must not be empty");
        }
        if (content.length > maxArtifactBytes) {
            throw new IllegalStateException("artifact exceeds the raw per-artifact wire-safe limit");
        }
        var snapshot = content.clone();
        try {
            var png = validatePng(snapshot, maxPixels);
            var digest = sha256(snapshot);
            var state = prune(caller);
            if (state == null) {
                state = new CallerState();
                callers.put(caller, state);
            }
            var existing = state.entries.get(digest);
            if (existing != null) {
                if (!Arrays.equals(existing.bytes, snapshot)) {
                    throw new IllegalStateException("SHA-256 collision detected");
                }
                var prior = existing.pending.get(owner);
                if (prior != null) return prior;
                var reference = provisionalReference(digest, mediaType, snapshot.length);
                existing.pending.put(owner, reference);
                return reference;
            }

            var evictions = planCallerEvictions(caller, state, snapshot.length);
            var evictedBytes = evictions.stream().mapToLong(entry -> entry.bytes.length).sum();
            if ((long) globalHashes - evictions.size() + 1L > maxGlobalHashes) {
                removeEmptyState(caller, state);
                throw new IllegalStateException("global artifact hash quota is full");
            }
            if (globalBytes - evictedBytes + snapshot.length > maxGlobalBytes) {
                removeEmptyState(caller, state);
                throw new IllegalStateException("global artifact byte quota is full");
            }
            for (var eviction : evictions) remove(caller, state, eviction);
            // Replacing the caller's last committed entry detaches its now-empty state; reattach
            // before inserting the accepted replacement so lookup and quota accounting agree.
            callers.put(caller, state);
            var reference = provisionalReference(digest, mediaType, snapshot.length);
            var entry = new ArtifactEntry(digest, mediaType, snapshot, png.width, png.height);
            entry.pending.put(owner, reference);
            state.entries.put(digest, entry);
            state.bytes += snapshot.length;
            globalBytes += snapshot.length;
            globalHashes++;
            snapshot = null;
            return reference;
        } finally {
            if (snapshot != null) Arrays.fill(snapshot, (byte) 0);
        }
    }

    private synchronized CommitResult commit(String caller, String owner, CallerEpoch epoch,
                                             List<ArtifactReference> references,
                                             Map<String, Object> output) {
        requireOpen();
        requireEpoch(caller, epoch);
        var state = prune(caller);
        var publishedEntries = new ArrayList<ArtifactEntry>();
        try {
            var occurrences = validateOutputMetadata(output, references);
            if (state == null && !references.isEmpty()) {
                throw new IllegalStateException("staged artifacts disappeared before publication");
            }
            var entries = new ArrayList<ArtifactEntry>(references.size());
            for (var reference : references) {
                var entry = state.entries.get(reference.sha256());
                if (entry == null || !reference.equals(entry.pending.get(owner))) {
                    throw new IllegalStateException("staged artifact is no longer publishable");
                }
                var occurrence = occurrences.stream()
                        .filter(value -> metadataMatches(value.metadata, reference)).findFirst().orElseThrow();
                if (!exactLong(occurrence.container.get("width"), entry.width)
                        || !exactLong(occurrence.container.get("height"), entry.height)) {
                    throw new IllegalArgumentException("declared screenshot dimensions do not match PNG IHDR");
                }
                entries.add(entry);
            }

            var expiresAt = saturatedAdd(clock.getAsLong(), ttlMs);
            var replacements = new LinkedHashMap<ArtifactReference, ArtifactReference>();
            for (var reference : references) {
                replacements.put(reference, new ArtifactReference(reference.uri(), reference.mediaType(),
                        reference.sha256(), reference.sizeBytes(), expiresAt));
            }
            @SuppressWarnings("unchecked")
            var canonical = (Map<String, Object>) canonicalize(output, replacements);
            for (var index = 0; index < entries.size(); index++) {
                var entry = entries.get(index);
                var staged = references.get(index);
                entry.pending.remove(owner);
                entry.publications.put(owner,
                        new Publication(replacements.get(staged), ++publicationSequence));
                publishedEntries.add(entry);
            }
            return new CommitResult(canonical, List.copyOf(replacements.values()));
        } catch (RuntimeException | Error failure) {
            publishedEntries.forEach(entry -> entry.publications.remove(owner));
            discard(caller, owner, epoch, references);
            throw failure;
        }
    }

    private synchronized void discard(String caller, String owner, CallerEpoch epoch,
                                      List<ArtifactReference> references) {
        if (!currentEpoch(caller, epoch)) return;
        var state = callers.get(caller);
        if (state == null) return;
        for (var reference : references) {
            var entry = state.entries.get(reference.sha256());
            if (entry == null) continue;
            entry.pending.remove(owner);
            removeIfUnowned(caller, state, entry);
        }
    }

    private synchronized boolean rollback(String caller, String owner, CallerEpoch epoch,
                                          List<ArtifactReference> references) {
        if (!currentEpoch(caller, epoch)) return false;
        var state = callers.get(caller);
        if (state == null) return false;
        var removed = false;
        for (var reference : references) {
            var entry = state.entries.get(reference.sha256());
            if (entry == null) continue;
            var publication = entry.publications.get(owner);
            if (publication != null && publication.reference.equals(reference)) {
                entry.publications.remove(owner);
                removed = true;
            }
            removeIfUnowned(caller, state, entry);
        }
        return removed;
    }

    private CallerState prune(String caller) {
        var state = callers.get(caller);
        if (state == null) return null;
        var now = clock.getAsLong();
        for (var entry : List.copyOf(state.entries.values())) {
            entry.publications.entrySet().removeIf(value -> value.getValue().reference.expiresAtEpochMs() <= now);
            removeIfUnowned(caller, state, entry);
        }
        return state.entries.isEmpty() ? null : state;
    }

    private List<ArtifactEntry> planCallerEvictions(String caller, CallerState state, int additionalBytes) {
        var candidates = state.entries.values().stream()
                    .filter(entry -> entry.pending.isEmpty() && entry.published())
                    .sorted(Comparator.comparingLong(ArtifactEntry::oldestPublicationSequence))
                    .toList();
        var selected = new ArrayList<ArtifactEntry>();
        var projectedCount = state.entries.size();
        var projectedBytes = state.bytes;
        var candidateIndex = 0;
        while (projectedCount >= maxHashesPerCaller
                || projectedBytes + additionalBytes > maxBytesPerCaller) {
            if (candidateIndex >= candidates.size()) {
                removeEmptyState(caller, state);
                throw new IllegalStateException("caller artifact quota is full with pending content");
            }
            var candidate = candidates.get(candidateIndex++);
            selected.add(candidate);
            projectedCount--;
            projectedBytes -= candidate.bytes.length;
        }
        return List.copyOf(selected);
    }

    private static List<ArtifactOccurrence> validateOutputMetadata(
            Map<String, Object> output, List<ArtifactReference> expected) {
        if (output == null) throw new IllegalArgumentException("artifact output must not be null");
        var found = new ArrayList<ArtifactOccurrence>();
        collectArtifactMetadata(output, found);
        if (found.size() != expected.size()) {
            throw new IllegalArgumentException("output must contain every staged artifact exactly once");
        }
        var remaining = new ArrayList<>(expected);
        for (var occurrence : found) {
            var match = remaining.stream()
                    .filter(reference -> metadataMatches(occurrence.metadata, reference)).findFirst();
            if (match.isEmpty()) {
                throw new IllegalArgumentException("output artifact metadata does not match its staged reference");
            }
            remaining.remove(match.get());
        }
        if (!remaining.isEmpty()) throw new IllegalArgumentException("output is missing staged artifact metadata");
        return found;
    }

    private static void collectArtifactMetadata(Object value, List<ArtifactOccurrence> found) {
        if (value instanceof Map<?, ?> map) {
            if (map.get("artifact") instanceof Map<?, ?> metadata
                    && metadata.get("uri") instanceof String uri && uri.startsWith(URI_PREFIX)) {
                found.add(new ArtifactOccurrence(metadata, map));
                map.forEach((key, nested) -> {
                    if (!"artifact".equals(key)) collectArtifactMetadata(nested, found);
                });
                return;
            }
            if (map.get("uri") instanceof String uri && uri.startsWith(URI_PREFIX)) {
                found.add(new ArtifactOccurrence(map, Map.of()));
                return;
            }
            map.values().forEach(nested -> collectArtifactMetadata(nested, found));
        } else if (value instanceof Iterable<?> iterable) {
            iterable.forEach(nested -> collectArtifactMetadata(nested, found));
        }
    }

    private static boolean metadataMatches(Map<?, ?> metadata, ArtifactReference reference) {
        if (!metadata.keySet().equals(METADATA_FIELDS)) return false;
        return reference.uri().equals(metadata.get("uri"))
                && reference.mediaType().equals(metadata.get("mediaType"))
                && reference.sha256().equals(metadata.get("sha256"))
                && exactLong(metadata.get("sizeBytes"), reference.sizeBytes())
                && exactLong(metadata.get("expiresAtEpochMs"), reference.expiresAtEpochMs());
    }

    private static boolean exactLong(Object value, long expected) {
        if (!(value instanceof Number number)) return false;
        var asDouble = number.doubleValue();
        return Double.isFinite(asDouble) && asDouble == expected && number.longValue() == expected;
    }

    private static Object canonicalize(Object value,
                                       Map<ArtifactReference, ArtifactReference> replacements) {
        if (value instanceof Map<?, ?> map) {
            for (var replacement : replacements.entrySet()) {
                if (metadataMatches(map, replacement.getKey())) return replacement.getValue().toMetadata();
            }
            var copy = new LinkedHashMap<String, Object>();
            map.forEach((key, nested) -> copy.put((String) key, canonicalize(nested, replacements)));
            return java.util.Collections.unmodifiableMap(copy);
        }
        if (value instanceof List<?> list) {
            var copy = new ArrayList<Object>(list.size());
            list.forEach(nested -> copy.add(canonicalize(nested, replacements)));
            return java.util.Collections.unmodifiableList(copy);
        }
        return value;
    }

    private ArtifactReference provisionalReference(String digest, String mediaType, long sizeBytes) {
        return new ArtifactReference(URI_PREFIX + digest, mediaType, digest, sizeBytes,
                saturatedAdd(clock.getAsLong(), ttlMs));
    }

    private static PngInfo validatePng(byte[] bytes, long maxPixels) {
        if (bytes.length < 45 || !startsWith(bytes, PNG_SIGNATURE)) {
            throw new IllegalArgumentException("artifact is not a structurally valid PNG");
        }
        var offset = PNG_SIGNATURE.length;
        var first = true;
        var hasIdat = false;
        var hasIend = false;
        var width = 0;
        var height = 0;
        while (offset <= bytes.length - 12) {
            var length = unsignedInt(bytes, offset);
            if (length > Integer.MAX_VALUE || length > bytes.length - offset - 12L) break;
            var type = new String(bytes, offset + 4, 4, java.nio.charset.StandardCharsets.US_ASCII);
            if (first) {
                if (!"IHDR".equals(type) || length != 13L) break;
                var rawWidth = unsignedInt(bytes, offset + 8);
                var rawHeight = unsignedInt(bytes, offset + 12);
                if (rawWidth < 1L || rawWidth > Integer.MAX_VALUE
                        || rawHeight < 1L || rawHeight > Integer.MAX_VALUE
                        || rawWidth * rawHeight > maxPixels) {
                    throw new IllegalArgumentException("PNG dimensions exceed the bounded pixel limit");
                }
                width = (int) rawWidth;
                height = (int) rawHeight;
                first = false;
            }
            if ("IDAT".equals(type)) hasIdat = true;
            offset += (int) length + 12;
            if ("IEND".equals(type)) {
                hasIend = length == 0L && offset == bytes.length;
                break;
            }
        }
        if (first || !hasIdat || !hasIend) {
            throw new IllegalArgumentException("artifact is not a structurally valid PNG");
        }
        return new PngInfo(width, height);
    }

    private static long unsignedInt(byte[] bytes, int offset) {
        return ((long) bytes[offset] & 0xffL) << 24
                | ((long) bytes[offset + 1] & 0xffL) << 16
                | ((long) bytes[offset + 2] & 0xffL) << 8
                | (long) bytes[offset + 3] & 0xffL;
    }

    private static boolean startsWith(byte[] value, byte[] prefix) {
        if (value.length < prefix.length) return false;
        for (var index = 0; index < prefix.length; index++) {
            if (value[index] != prefix[index]) return false;
        }
        return true;
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static String requireIdentifier(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value.trim();
    }

    private boolean currentEpoch(String caller, CallerEpoch epoch) {
        return !epoch.revoked && callerEpochs.get(caller) == epoch;
    }

    private void requireEpoch(String caller, CallerEpoch epoch) {
        if (!currentEpoch(caller, epoch)) {
            throw new IllegalStateException("artifact sink was revoked when its caller was released");
        }
    }

    private void removeIfUnowned(String caller, CallerState state, ArtifactEntry entry) {
        if (entry.pending.isEmpty() && entry.publications.isEmpty()) remove(caller, state, entry);
    }

    private void remove(String caller, CallerState state, ArtifactEntry entry) {
        if (!state.entries.remove(entry.digest, entry)) return;
        state.bytes -= entry.bytes.length;
        globalBytes -= entry.bytes.length;
        globalHashes--;
        Arrays.fill(entry.bytes, (byte) 0);
        entry.pending.clear();
        entry.publications.clear();
        removeEmptyState(caller, state);
    }

    private void removeEmptyState(String caller, CallerState state) {
        if (state.entries.isEmpty()) callers.remove(caller, state);
    }

    private void wipe(CallerState state) {
        for (var entry : List.copyOf(state.entries.values())) {
            globalBytes -= entry.bytes.length;
            globalHashes--;
            Arrays.fill(entry.bytes, (byte) 0);
            entry.pending.clear();
            entry.publications.clear();
        }
        state.entries.clear();
        state.bytes = 0L;
    }

    private void requireOpen() {
        if (closed) throw new IllegalStateException("artifact store is closed");
    }

    private static long saturatedAdd(long value, long increment) {
        if (value > Long.MAX_VALUE - increment) return Long.MAX_VALUE;
        return value + increment;
    }

    private static long saturatedMultiply(long value, long multiplier) {
        if (value > Long.MAX_VALUE / multiplier) return Long.MAX_VALUE;
        return value * multiplier;
    }

    private static int saturatedMultiply(int value, int multiplier) {
        if (value > Integer.MAX_VALUE / multiplier) return Integer.MAX_VALUE;
        return value * multiplier;
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        callers.values().forEach(this::wipe);
        callers.clear();
        callerEpochs.values().forEach(epoch -> epoch.revoked = true);
        callerEpochs.clear();
        globalBytes = 0L;
        globalHashes = 0;
    }

    private static final class CallerState {
        private final Map<String, ArtifactEntry> entries = new LinkedHashMap<>();
        private long bytes;
    }

    private static final class CallerEpoch {
        private boolean revoked;
    }

    private static final class ArtifactEntry {
        private final String digest;
        private final String mediaType;
        private final byte[] bytes;
        private final int width;
        private final int height;
        private final Map<String, ArtifactReference> pending = new HashMap<>();
        private final Map<String, Publication> publications = new HashMap<>();

        private ArtifactEntry(String digest, String mediaType, byte[] bytes, int width, int height) {
            this.digest = digest;
            this.mediaType = mediaType;
            this.bytes = bytes;
            this.width = width;
            this.height = height;
        }

        private String uri() {
            return URI_PREFIX + digest;
        }

        private boolean published() {
            return !publications.isEmpty();
        }

        private long oldestPublicationSequence() {
            return publications.values().stream().mapToLong(Publication::sequence).min().orElse(Long.MAX_VALUE);
        }
    }

    static final class InvocationArtifactSink implements ArtifactSink {
        private final CallerArtifactStore store;
        private final String caller;
        private final String owner;
        private final CallerEpoch epoch;
        private final Map<String, ArtifactReference> references = new LinkedHashMap<>();
        private List<ArtifactReference> committedReferences = List.of();
        private SinkState state = SinkState.OPEN;

        private InvocationArtifactSink(CallerArtifactStore store, String caller, String owner, CallerEpoch epoch) {
            this.store = store;
            this.caller = caller;
            this.owner = owner;
            this.epoch = epoch;
        }

        @Override
        public synchronized ArtifactReference stage(String mediaType, byte[] content) {
            requireOpen();
            var reference = store.stage(caller, owner, epoch, mediaType, content);
            references.putIfAbsent(reference.sha256(), reference);
            return reference;
        }

        synchronized Map<String, Object> commit(Map<String, Object> output) {
            requireOpen();
            try {
                var committed = store.commit(caller, owner, epoch,
                        List.copyOf(references.values()), output);
                committedReferences = committed.references;
                state = SinkState.COMMITTED;
                return committed.output;
            } catch (RuntimeException | Error failure) {
                state = SinkState.DISCARDED;
                throw failure;
            }
        }

        synchronized boolean discard() {
            if (state != SinkState.OPEN) return false;
            store.discard(caller, owner, epoch, List.copyOf(references.values()));
            state = SinkState.DISCARDED;
            return true;
        }

        synchronized boolean rollback() {
            if (state != SinkState.COMMITTED) return false;
            var removed = store.rollback(caller, owner, epoch, committedReferences);
            state = SinkState.ROLLED_BACK;
            return removed;
        }

        private void requireOpen() {
            if (state != SinkState.OPEN) throw new IllegalStateException("artifact sink is already finalized");
        }
    }

    private enum SinkState {
        OPEN,
        COMMITTED,
        DISCARDED,
        ROLLED_BACK
    }

    private record PngInfo(int width, int height) {
    }

    private record ArtifactOccurrence(Map<?, ?> metadata, Map<?, ?> container) {
    }

    private record Publication(ArtifactReference reference, long sequence) {
    }

    private record CommitResult(Map<String, Object> output, List<ArtifactReference> references) {
    }
}
