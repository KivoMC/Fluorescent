package dev.stillbloom.render.source;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

class PackedSourceVolumeTest {
    private static SourceVolumePool.PackedVolume builder() {
        return new SourceVolumePool.PackedVolume(() -> new int[SourceVolumePool.CUSTOM_WORDS]);
    }

    private static void reference(int[] words, int voxel, int model) {
        words[voxel >>> 1] |= model << ((voxel & 1) * 16);
    }

    @Test
    void absenceAndRejectedZeroModelsKeepTheirDistinctSourceModes() {
        var allocations = new AtomicInteger();
        var b =
                new SourceVolumePool.PackedVolume(
                        () -> {
                            allocations.incrementAndGet();
                            return new int[SourceVolumePool.CUSTOM_WORDS];
                        });
        assertNull(b.finish(null).words());
        assertEquals(0, allocations.get());
        b.add(12, 0);
        var data = b.finish(null);
        assertNotNull(data.words());
        assertEquals(1, allocations.get());
        assertEquals(0, Arrays.stream(data.words()).filter(x -> x != 0).count());
        var next =
                new SourceVolumePool.PackedVolume(
                        () -> {
                            fail("An unchanged all-zero custom volume is reusable");
                            return null;
                        });
        next.add(900, 0);
        assertSame(data, next.finish(data));
    }

    @Test
    void sparsePackingMatchesOriginalForBothHalfwordsAndEveryModelValue() {
        var b = builder();
        int[] expected = new int[SourceVolumePool.CUSTOM_WORDS];
        int[] voxels = {
            0,
            1,
            2,
            3,
            77,
            78,
            2 * SourceVolumePool.CUSTOM_WORDS - 2,
            2 * SourceVolumePool.CUSTOM_WORDS - 1
        };
        int[] models = {1, 65535, 65535, 1, 32768, 2345, 65534, 32767};
        for (int i = 0; i < voxels.length; i++) {
            b.add(voxels[i], models[i]);
            reference(expected, voxels[i], models[i]);
        }
        assertArrayEquals(expected, b.finish(null).words());
    }

    @Test
    void unchangedMappingReusesOriginalVolumeAndMetadata() {
        var b = builder();
        b.add(4, 17);
        b.add(5, 65535);
        var original = b.finish(null);
        var next =
                new SourceVolumePool.PackedVolume(
                        () -> {
                            fail(
                                    "Identical custom mapping must not allocate or clear dense storage");
                            return null;
                        });
        next.add(4, 17);
        next.add(5, 65535);
        assertSame(original, next.finish(original));
        assertEquals(0xFFFF0011, original.words()[2]);
    }

    @Test
    void recycledSparseVolumesMatchFreshReferenceThroughEditsRemovalsAndMoves() {
        var pool = new SourceVolumePool();
        SourceVolumePool.PackedVolume.Data previous = null;
        var allArrays =
                java.util.Collections.newSetFromMap(
                        new java.util.IdentityHashMap<int[], Boolean>());
        for (int step = 0; step < 30; step++) {
            var old = previous;
            var b =
                    new SourceVolumePool.PackedVolume(
                            () ->
                                    pool.acquire(
                                            SourceVolumePool.CUSTOM_WORDS,
                                            old == null ? null : old.words()));
            int[] expected = new int[SourceVolumePool.CUSTOM_WORDS];
            for (int i = step % 4; i < 250; i++) {
                int voxel = step * 8 + i * 3, model = (step + i * 337) % 65535 + 1;
                b.add(voxel, model);
                reference(expected, voxel, model);
            }
            previous = b.finish(previous);
            allArrays.add(previous.words());
            assertArrayEquals(expected, previous.words());
            if (old != null && old.words() != previous.words()) pool.retirePacked(old);
            assertTrue(pool.retainedBytes() <= SourceVolumePool.MAX_RETAINED_BYTES);
        }
        assertEquals(
                2,
                allArrays.size(),
                "Published/uploaded buffers remain pinned, but older buffers alternate without new dense allocations");
    }

    @Test
    void overflowUsesExactDenseFallbackWithBoundedSparseMetadata() {
        var b = builder();
        int[] expected = new int[SourceVolumePool.CUSTOM_WORDS];
        var random = new Random(7981);
        for (int i = 0; i < SourceVolumePool.PackedVolume.SPARSE_LIMIT * 3; i++) {
            int voxel = random.nextInt(SourceVolumePool.CUSTOM_WORDS * 2),
                    model = random.nextInt(65536);
            b.add(voxel, model);
            reference(expected, voxel, model);
        }
        var data = b.finish(null);
        assertNull(data.entries());
        assertArrayEquals(expected, data.words());
        var pool = new SourceVolumePool();
        pool.retirePacked(data);
        assertSame(data.words(), pool.acquire(SourceVolumePool.CUSTOM_WORDS));
        assertEquals(0, Arrays.stream(data.words()).filter(x -> x != 0).count());
    }

    @Test
    void geometryCompactionResetsBothSparseAndDenseBuilds() {
        for (int count : new int[] {20, SourceVolumePool.PackedVolume.SPARSE_LIMIT + 1}) {
            var b = builder();
            for (int i = 0; i < count; i++) b.add(i, 65535);
            b.reset();
            b.add(9, 4);
            b.add(10, 32768);
            int[] expected = new int[SourceVolumePool.CUSTOM_WORDS];
            reference(expected, 9, 4);
            reference(expected, 10, 32768);
            assertArrayEquals(expected, b.finish(null).words());
        }
    }

    @Test
    void allThreeSparsePoolsStayInsideTheOriginalRetainedBudget() {
        var pool = new SourceVolumePool();
        var b = builder();
        for (int i = 0; i < SourceVolumePool.PackedVolume.SPARSE_LIMIT; i++) b.add(i * 3, 7);
        var custom = b.finish(null);
        pool.retirePacked(custom);
        var entries = new java.util.ArrayList<Long>();
        for (int i = 0; i < SourceVolumePool.SPARSE_CLEAR_LIMIT; i++)
            entries.add(((long) i << 32) | 1);
        for (int j = 0; j < 2; j++) {
            int[] dense = new int[SourceVolumePool.DENSE_WORDS];
            for (int i = 0; i < entries.size(); i++) dense[i] = 1;
            pool.retire(dense, entries);
        }
        assertTrue(pool.retainedBytes() <= SourceVolumePool.MAX_RETAINED_BYTES);
        int[] dense1 = pool.acquire(SourceVolumePool.DENSE_WORDS),
                dense2 = pool.acquire(SourceVolumePool.DENSE_WORDS);
        assertEquals(0, Arrays.stream(dense1).filter(x -> x != 0).count());
        assertEquals(0, Arrays.stream(dense2).filter(x -> x != 0).count());
        assertSame(custom.words(), pool.acquire(SourceVolumePool.CUSTOM_WORDS));
        assertEquals(0, Arrays.stream(custom.words()).filter(x -> x != 0).count());
    }

    @Test
    void sparseCustomUploadComparisonsStayUnmodifiedUntilReleased() {
        var pool = new SourceVolumePool();
        var first = builder();
        first.add(1, 65535);
        var data = first.finish(null);
        pool.retirePacked(data);
        // I keep old packed model IDs available for a delayed upload.
        assertNotSame(data.words(), pool.acquire(SourceVolumePool.CUSTOM_WORDS, data.words()));
        assertEquals(0xFFFF0000, data.words()[0]);
        assertSame(data.words(), pool.acquire(SourceVolumePool.CUSTOM_WORDS));
        assertEquals(0, data.words()[0]);
    }
}
