package dev.stillbloom.render.source;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

class SourceVolumePoolTest {
    private static long entry(int index, int value) {
        return ((long) index << 32) | (value & 0xFFFFFFFFL);
    }

    @Test
    void ordinarySourcesRetainNoUnusedDenseStorage() {
        var pool = new SourceVolumePool();
        assertEquals(0, pool.retainedBytes());
        pool.retire(null, null);
        pool.retire(new int[SourceMask.WORDS], null);
        assertEquals(0, pool.retainedBytes());
    }

    @Test
    void delayedSnapshotsAndUploadComparisonsRemainUntouchedUntilUnpinned() {
        var pool = new SourceVolumePool();
        var current = pool.acquire(SourceVolumePool.DENSE_WORDS);
        current[21] = 0xFF000000;
        pool.retire(current, List.of(entry(21, 0xFF000000)));
        var next = pool.acquire(SourceVolumePool.DENSE_WORDS, current);
        next[17] = 0x80000000;
        pool.retire(next, List.of(entry(17, 0x80000000)));
        var whileBothPinned = pool.acquire(SourceVolumePool.DENSE_WORDS, current, next);
        assertNotSame(current, whileBothPinned);
        assertNotSame(next, whileBothPinned);
        assertEquals(0xFF000000, current[21]);
        assertEquals(0x80000000, next[17]);
        var reusable = pool.acquire(SourceVolumePool.DENSE_WORDS, next);
        assertSame(current, reusable);
        assertEquals(0, reusable[21]);
        assertEquals(
                0x80000000,
                next[17],
                "Retiring one snapshot cannot clear a pending upload from another");
    }

    @Test
    void sparseColorAndDetailReuseMatchesFreshZeroedVolumesAcrossOriginShifts() {
        var pool = new SourceVolumePool();
        int[] previousColor = null, previousDetail = null;
        List<Long> previousColors = List.of(), previousDetails = List.of();
        for (int step = 0; step < 9; step++) {
            int[] color = pool.acquire(SourceVolumePool.DENSE_WORDS, previousColor, previousDetail);
            int[] detail =
                    pool.acquire(SourceVolumePool.DENSE_WORDS, previousColor, previousDetail);
            assertEquals(0, Arrays.stream(color).filter(v -> v != 0).count());
            assertEquals(0, Arrays.stream(detail).filter(v -> v != 0).count());
            // I include endpoints, sign-bit values and neighboring pixels in the upload checks.
            var colors =
                    List.of(entry(step, 0xFF010203), entry(color.length - 1 - step, 0xFF000000));
            var details =
                    List.of(
                            entry(step + 20, 0x80000000),
                            entry(detail.length - 21 - step, 0xBF000000));
            for (long e : colors) color[(int) (e >>> 32)] = (int) e;
            for (long e : details) detail[(int) (e >>> 32)] = (int) e;
            if (previousColor != null) {
                pool.retire(previousColor, previousColors);
                pool.retire(previousDetail, previousDetails);
            }
            assertEquals(0xFF010203, color[step]);
            assertEquals(0x80000000, detail[step + 20]);
            previousColor = color;
            previousDetail = detail;
            previousColors = colors;
            previousDetails = details;
            assertTrue(pool.retainedBytes() <= SourceVolumePool.MAX_RETAINED_BYTES);
        }
    }

    @Test
    void customAndDenseFallbackClearEveryWordIncludingPackedPairs() {
        var pool = new SourceVolumePool();
        int[] custom = pool.acquire(SourceVolumePool.CUSTOM_WORDS);
        Arrays.fill(custom, 0xFFFF1234);
        pool.retire(custom, null);
        assertSame(custom, pool.acquire(SourceVolumePool.CUSTOM_WORDS));
        assertEquals(0, Arrays.stream(custom).filter(v -> v != 0).count());
        int[] dense = pool.acquire(SourceVolumePool.DENSE_WORDS);
        Arrays.fill(dense, 0x80000000);
        var tooMany = new ArrayList<Long>();
        for (int i = 0; i <= SourceVolumePool.SPARSE_CLEAR_LIMIT; i++)
            tooMany.add(entry(i, 0x80000000));
        pool.retire(dense, tooMany);
        assertSame(dense, pool.acquire(SourceVolumePool.DENSE_WORDS));
        assertEquals(0, Arrays.stream(dense).filter(v -> v != 0).count());
    }

    @Test
    void retiredStorageIsBoundedDeduplicatedAndReleasedOnReset() {
        var pool = new SourceVolumePool();
        var indices = new ArrayList<Long>();
        for (int i = 0; i < SourceVolumePool.SPARSE_CLEAR_LIMIT; i++) indices.add(entry(i, -1));
        for (int i = 0; i < 5; i++) {
            int[] custom = new int[SourceVolumePool.CUSTOM_WORDS];
            int[] dense = new int[SourceVolumePool.DENSE_WORDS];
            pool.retire(custom, null);
            pool.retire(dense, indices);
            long bytes = pool.retainedBytes();
            pool.retire(custom, null);
            pool.retire(dense, indices);
            assertEquals(
                    bytes,
                    pool.retainedBytes(),
                    "Retiring the same buffer twice cannot duplicate ownership");
            assertTrue(bytes <= SourceVolumePool.MAX_RETAINED_BYTES);
        }
        assertEquals(SourceVolumePool.MAX_RETAINED_BYTES, pool.retainedBytes());
        pool.clear();
        assertEquals(0, pool.retainedBytes());
    }

    @Test
    void sparseClearingKeepsItsOwnStableIndicesAndInvalidMetadataFailsClosed() {
        var pool = new SourceVolumePool();
        int[] words = pool.acquire(SourceVolumePool.DENSE_WORDS);
        words[12] = 0x80000000;
        var entries = new ArrayList<Long>();
        entries.add(entry(12, 0x80000000));
        pool.retire(words, entries);
        entries.clear();
        entries.add(entry(99, -1));
        assertSame(words, pool.acquire(SourceVolumePool.DENSE_WORDS));
        assertEquals(0, words[12]);
        words[100] = -1;
        pool.retire(words, List.of(entry(-1, -1)));
        assertSame(words, pool.acquire(SourceVolumePool.DENSE_WORDS));
        assertEquals(0, words[100]);
    }
}
