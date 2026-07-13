// SPDX-License-Identifier: MIT
package dev.lodestone.runtime;

import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class CallerArtifactStoreTest {
    private static final byte[] PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=");

    @Test
    void pendingContentIsInvisibleUntilExactMetadataCommits() {
        var clock = new AtomicLong(1_000L);
        try (var store = new CallerArtifactStore(clock::get, 60_000L, 64L * 1024L * 1024L, 2)) {
            var source = PNG.clone();
            var sink = store.openSink("caller-a", "invocation-a");
            var reference = sink.stage("image/png", source);
            source[20] ^= 1;

            assertTrue(store.read(reference.uri(), "caller-a").isEmpty());
            assertTrue(store.list("caller-a").isEmpty());
            sink.commit(output(reference));

            var content = store.read(reference.uri(), "caller-a").orElseThrow();
            assertTrue(content.binary());
            assertEquals("image/png", content.mimeType());
            assertArrayEquals(PNG, content.bytes());
            var leakedCopy = content.bytes();
            leakedCopy[20] ^= 1;
            assertArrayEquals(PNG, store.read(reference.uri(), "caller-a").orElseThrow().bytes());
            assertEquals(reference.uri(), store.list("caller-a").get(0).uri());
            assertTrue(store.read(reference.uri(), "caller-b").isEmpty());
        }
    }

    @Test
    void identicalHashesRemainIndependentlyOwnedByEachCaller() {
        var clock = new AtomicLong(2_000L);
        try (var store = new CallerArtifactStore(clock::get, 60_000L, 1_000_000L, 2)) {
            var a = store.openSink("caller-a", "a");
            var aRef = a.stage("image/png", PNG);
            a.commit(output(aRef));
            var b = store.openSink("caller-b", "b");
            var bRef = b.stage("image/png", PNG);
            b.commit(output(bRef));

            assertEquals(aRef.uri(), bRef.uri());
            store.releaseCaller("caller-a");
            assertTrue(store.read(aRef.uri(), "caller-a").isEmpty());
            assertArrayEquals(PNG, store.read(bRef.uri(), "caller-b").orElseThrow().bytes());
        }
    }

    @Test
    void rejectsNonPngContentAndCountsPendingUniqueHashesAgainstBothQuotas() {
        var clock = new AtomicLong(3_000L);
        try (var store = new CallerArtifactStore(clock::get, 60_000L, PNG.length * 2L, 2)) {
            var first = store.openSink("caller", "first");
            assertThrows(IllegalArgumentException.class, () -> first.stage("application/octet-stream", PNG));
            assertThrows(IllegalArgumentException.class, () -> first.stage("image/png", new byte[] {1, 2, 3}));

            first.stage("image/png", PNG);
            first.stage("image/png", PNG);
            var secondBytes = variant(1);
            store.openSink("caller", "second").stage("image/png", secondBytes);
            assertThrows(IllegalStateException.class,
                    () -> store.openSink("caller", "third").stage("image/png", variant(2)));

            first.discard();
            store.openSink("caller", "third").stage("image/png", variant(2));
        }

        try (var store = new CallerArtifactStore(clock::get, 60_000L, PNG.length, 2)) {
            var pending = store.openSink("caller", "pending");
            pending.stage("image/png", PNG);
            assertThrows(IllegalStateException.class,
                    () -> store.openSink("caller", "over-budget").stage("image/png", variant(1)));
            pending.discard();
            store.openSink("caller", "after-discard").stage("image/png", variant(1));
        }
    }

    @Test
    void mismatchedOrMissingArtifactMetadataFailsClosedAndDiscardsPendingBytes() {
        var clock = new AtomicLong(4_000L);
        try (var store = new CallerArtifactStore(clock::get, 60_000L, 1_000_000L, 2)) {
            var sink = store.openSink("caller", "invocation");
            var reference = sink.stage("image/png", PNG);
            var changed = new java.util.LinkedHashMap<>(reference.toMetadata());
            changed.put("sizeBytes", reference.sizeBytes() + 1L);

            assertThrows(IllegalArgumentException.class,
                    () -> sink.commit(Map.of("artifact", changed, "width", 1L, "height", 1L)));
            assertTrue(store.read(reference.uri(), "caller").isEmpty());
            assertTrue(store.list("caller").isEmpty());

            var missing = store.openSink("caller", "missing");
            var missingReference = missing.stage("image/png", PNG);
            assertThrows(IllegalArgumentException.class, () -> missing.commit(Map.of("ok", true)));
            assertTrue(store.read(missingReference.uri(), "caller").isEmpty());

            var fake = store.openSink("caller", "fake");
            assertThrows(IllegalArgumentException.class, () -> fake.commit(Map.of("artifact", Map.of(
                    "uri", "lodestone://artifacts/sha256/" + "f".repeat(64),
                    "mediaType", "image/png", "sha256", "f".repeat(64),
                    "sizeBytes", 1L, "expiresAtEpochMs", 1L), "width", 1L, "height", 1L)));
        }
    }

    @Test
    void expirationReleaseAndCloseRemovePublishedAndPendingContent() {
        var clock = new AtomicLong(5_000L);
        var store = new CallerArtifactStore(clock::get, 60_000L, 1_000_000L, 2);
        var published = store.openSink("caller", "published");
        var reference = published.stage("image/png", PNG);
        published.commit(output(reference));
        var pending = store.openSink("caller", "pending");
        pending.stage("image/png", variant(1));

        clock.set(reference.expiresAtEpochMs());
        assertTrue(store.read(reference.uri(), "caller").isEmpty());
        assertTrue(store.list("caller").isEmpty());

        var later = store.openSink("caller", "later");
        var laterReference = later.stage("image/png", PNG);
        later.commit(output(laterReference));
        store.releaseCaller("caller");
        assertTrue(store.read(laterReference.uri(), "caller").isEmpty());

        var closePending = store.openSink("caller", "close-pending");
        var closeReference = closePending.stage("image/png", PNG);
        store.close();
        assertTrue(store.read(closeReference.uri(), "caller").isEmpty());
        assertThrows(IllegalStateException.class,
                () -> store.openSink("caller", "closed").stage("image/png", PNG));
    }

    @Test
    void callerReleaseRevokesAlreadyOpenedSinksWithoutBlockingANewSessionGeneration() {
        var clock = new AtomicLong(5_500L);
        try (var store = new CallerArtifactStore(clock::get, 60_000L, 1_000_000L, 2)) {
            var stale = store.openSink("caller", "stale");
            var staleReference = stale.stage("image/png", PNG);
            store.releaseCaller("caller");

            assertThrows(IllegalStateException.class,
                    () -> stale.stage("image/png", variant(1)));
            assertThrows(IllegalStateException.class,
                    () -> stale.commit(output(staleReference)));

            var fresh = store.openSink("caller", "fresh");
            var freshReference = fresh.stage("image/png", PNG);
            fresh.commit(output(freshReference));
            assertTrue(store.read(freshReference.uri(), "caller").isPresent());
        }
    }

    @Test
    void publicationStartsTheTtlAndCanonicalizesTheReturnedExpiry() {
        var clock = new AtomicLong(10_000L);
        try (var store = new CallerArtifactStore(clock::get, 60_000L, 1_000_000L, 2)) {
            var sink = store.openSink("caller", "invocation");
            var staged = sink.stage("image/png", PNG);
            clock.set(40_000L);

            var canonical = sink.commit(output(staged));
            @SuppressWarnings("unchecked")
            var metadata = (Map<String, Object>) canonical.get("artifact");
            assertEquals(100_000L, ((Number) metadata.get("expiresAtEpochMs")).longValue());
            assertNotEquals(staged.expiresAtEpochMs(), ((Number) metadata.get("expiresAtEpochMs")).longValue());

            clock.set(99_999L);
            assertTrue(store.read(staged.uri(), "caller").isPresent());
            clock.set(100_000L);
            assertTrue(store.read(staged.uri(), "caller").isEmpty());
        }
    }

    @Test
    void enforcesRawPixelAndGlobalPendingPlusCommittedLimits() {
        var clock = new AtomicLong(11_000L);
        try (var rawLimited = new CallerArtifactStore(clock::get, 60_000L,
                PNG.length - 1L, 1_000_000L, 2, 10_000_000L, 10, 16_777_216L)) {
            assertThrows(IllegalStateException.class,
                    () -> rawLimited.openSink("caller", "raw").stage("image/png", PNG));
        }
        try (var pixelLimited = new CallerArtifactStore(clock::get, 60_000L,
                1_000_000L, 1_000_000L, 2, 10_000_000L, 10, 16_777_216L)) {
            assertThrows(IllegalArgumentException.class, () -> pixelLimited.openSink("caller", "pixels")
                    .stage("image/png", withDimensions(8192, 8192)));
        }
        try (var globallyLimited = new CallerArtifactStore(clock::get, 60_000L,
                1_000_000L, 1_000_000L, 2, PNG.length, 1, 16_777_216L)) {
            var first = globallyLimited.openSink("caller-a", "first");
            first.stage("image/png", PNG);
            assertThrows(IllegalStateException.class,
                    () -> globallyLimited.openSink("caller-b", "second").stage("image/png", variant(1)));
            first.discard();
            globallyLimited.openSink("caller-b", "after-discard").stage("image/png", variant(1));
        }
        try (var replacementLimited = new CallerArtifactStore(clock::get, 60_000L,
                1_000_000L, 1_000_000L, 1, PNG.length + 5L, 10, 16_777_216L)) {
            var original = replacementLimited.openSink("caller", "original");
            var originalRef = original.stage("image/png", PNG);
            original.commit(output(originalRef));
            assertThrows(IllegalStateException.class, () -> replacementLimited
                    .openSink("caller", "too-large-replacement").stage("image/png", withPadding(10)));
            assertTrue(replacementLimited.read(originalRef.uri(), "caller").isPresent());
        }
    }

    @Test
    void thirdUniqueScreenshotAtomicallyEvictsTheOldestCommittedArtifactOnly() {
        var clock = new AtomicLong(12_000L);
        try (var store = new CallerArtifactStore(clock::get, 60_000L,
                1_000_000L, 1_000_000L, 2, 10_000_000L, 10, 16_777_216L)) {
            var first = store.openSink("caller", "first");
            var firstRef = first.stage("image/png", PNG);
            first.commit(output(firstRef));
            clock.incrementAndGet();
            var second = store.openSink("caller", "second");
            var secondRef = second.stage("image/png", variant(1));
            second.commit(output(secondRef));
            clock.incrementAndGet();
            var third = store.openSink("caller", "third");
            var thirdRef = third.stage("image/png", variant(2));

            assertTrue(store.read(firstRef.uri(), "caller").isEmpty());
            assertTrue(store.read(secondRef.uri(), "caller").isPresent());
            assertTrue(store.read(thirdRef.uri(), "caller").isEmpty());

            third.commit(output(thirdRef));
            assertEquals(2, store.list("caller").size());
        }

        try (var store = new CallerArtifactStore(clock::get, 60_000L,
                1_000_000L, 1_000_000L, 2, 10_000_000L, 10, 16_777_216L)) {
            store.openSink("caller", "pending-a").stage("image/png", PNG);
            store.openSink("caller", "pending-b").stage("image/png", variant(1));
            assertThrows(IllegalStateException.class,
                    () -> store.openSink("caller", "pending-c").stage("image/png", variant(2)));
        }
    }

    @Test
    void singleHashQuotaReattachesStateAfterReplacingItsOnlyCommittedArtifact() {
        var clock = new AtomicLong(12_500L);
        try (var store = new CallerArtifactStore(clock::get, 60_000L,
                1_000_000L, 1_000_000L, 1, 10_000_000L, 10, 16_777_216L)) {
            var first = store.openSink("caller", "first");
            var firstRef = first.stage("image/png", PNG);
            first.commit(output(firstRef));

            var replacement = store.openSink("caller", "replacement");
            var replacementRef = replacement.stage("image/png", variant(1));
            replacement.commit(output(replacementRef));

            assertTrue(store.read(firstRef.uri(), "caller").isEmpty());
            assertArrayEquals(variant(1), store.read(replacementRef.uri(), "caller").orElseThrow().bytes());
            assertEquals(java.util.List.of(replacementRef.uri()),
                    store.list("caller").stream().map(resource -> resource.uri()).toList());
        }
    }

    @Test
    void declaredDimensionsMustMatchIhdrAndCommittedOwnershipRollsBackIndependently() {
        var clock = new AtomicLong(13_000L);
        try (var store = new CallerArtifactStore(clock::get, 60_000L, 1_000_000L, 2)) {
            var mismatch = store.openSink("caller", "mismatch");
            var mismatchRef = mismatch.stage("image/png", PNG);
            assertThrows(IllegalArgumentException.class,
                    () -> mismatch.commit(Map.of("artifact", mismatchRef.toMetadata(),
                            "width", 2L, "height", 1L)));

            var first = store.openSink("caller", "owner-a");
            var firstRef = first.stage("image/png", PNG);
            first.commit(output(firstRef));
            var second = store.openSink("caller", "owner-b");
            var secondRef = second.stage("image/png", PNG);
            second.commit(output(secondRef));

            assertTrue(first.rollback());
            assertTrue(store.read(firstRef.uri(), "caller").isPresent());
            assertTrue(second.rollback());
            assertTrue(store.read(secondRef.uri(), "caller").isEmpty());
        }
    }

    @Test
    void commitAndDiscardRaceHasExactlyOneWinner() throws Exception {
        var clock = new AtomicLong(6_000L);
        try (var store = new CallerArtifactStore(clock::get, 60_000L, 1_000_000L, 2)) {
            var sink = store.openSink("caller", "race");
            var reference = sink.stage("image/png", PNG);
            var start = new CountDownLatch(1);
            var workers = Executors.newFixedThreadPool(2);
            try {
                var commit = workers.submit(() -> {
                    start.await();
                    try {
                        sink.commit(output(reference));
                        return true;
                    } catch (IllegalStateException ignored) {
                        return false;
                    }
                });
                var discard = workers.submit(() -> {
                    start.await();
                    return sink.discard();
                });
                start.countDown();
                var committed = commit.get(1, TimeUnit.SECONDS);
                var discarded = discard.get(1, TimeUnit.SECONDS);
                assertNotEquals(committed, discarded);
                assertEquals(committed, store.read(reference.uri(), "caller").isPresent());
            } finally {
                workers.shutdownNow();
            }
        }
    }

    private static byte[] variant(int xor) {
        var changed = PNG.clone();
        changed[changed.length - 20] ^= (byte) xor;
        return changed;
    }

    private static byte[] withDimensions(int width, int height) {
        var changed = PNG.clone();
        writeInt(changed, 16, width);
        writeInt(changed, 20, height);
        return changed;
    }

    private static byte[] withPadding(int bytes) {
        var insertion = PNG.length - 12;
        var padded = new byte[PNG.length + bytes + 12];
        System.arraycopy(PNG, 0, padded, 0, insertion);
        writeInt(padded, insertion, bytes);
        padded[insertion + 4] = 't';
        padded[insertion + 5] = 'E';
        padded[insertion + 6] = 'X';
        padded[insertion + 7] = 't';
        System.arraycopy(PNG, insertion, padded, insertion + bytes + 12, 12);
        return padded;
    }

    private static void writeInt(byte[] target, int offset, int value) {
        target[offset] = (byte) (value >>> 24);
        target[offset + 1] = (byte) (value >>> 16);
        target[offset + 2] = (byte) (value >>> 8);
        target[offset + 3] = (byte) value;
    }

    private static Map<String, Object> output(dev.lodestone.adapter.ArtifactReference reference) {
        return Map.of("artifact", reference.toMetadata(), "width", 1L, "height", 1L);
    }
}
