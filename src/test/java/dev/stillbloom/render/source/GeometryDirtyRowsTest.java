package dev.stillbloom.render.source;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

class GeometryDirtyRowsTest {
    private record Strip(int first, int count) {}

    private static List<Strip> drain(GeometryTexture.DirtyRows rows) {
        var strips = new ArrayList<Strip>();
        rows.upload((first, count) -> strips.add(new Strip(first, count)));
        return strips;
    }

    @Test
    void wordBoundariesPreserveTheFirstAndLastTexelsOfEveryDirtyRow() {
        var rows = new GeometryTexture.DirtyRows();
        int width = GeometryTexture.DirtyRows.WORDS_PER_ROW;
        rows.markWords(0, 1);
        rows.markWords(width - 1, width + 1);
        rows.markWords(GeometryTexture.DirtyRows.WORDS - 1, GeometryTexture.DirtyRows.WORDS);
        assertEquals(List.of(new Strip(0, 2), new Strip(511, 1)), drain(rows));
        assertFalse(rows.isDirty());
        assertTrue(drain(rows).isEmpty());
        rows.markWords(width, width);
        rows.markWords(GeometryTexture.DirtyRows.WORDS, GeometryTexture.DirtyRows.WORDS);
        assertFalse(rows.isDirty());
        for (int[] bad : new int[][] {{-1, 0}, {1, 0}, {0, GeometryTexture.DirtyRows.WORDS + 1}})
            assertThrows(IllegalArgumentException.class, () -> rows.markWords(bad[0], bad[1]));
    }

    @Test
    void headersDescendingIndicesAndAppendedBodiesCoalesceWithoutBridgingCleanRows() {
        var rows = new GeometryTexture.DirtyRows();
        int width = GeometryTexture.DirtyRows.WORDS_PER_ROW;
        rows.markWords(4, 8);
        rows.markWords(
                8, 16); // I add several model headers to the same row to check merged dirty ranges.
        rows.markWords(102 * width + 40, 103 * width);
        rows.markWords(100 * width + 7, 102 * width + 40);
        rows.markWords(128 * width, 129 * width + 1);
        rows.markWords(129 * width, 131 * width);
        assertEquals(List.of(new Strip(0, 1), new Strip(100, 3), new Strip(128, 3)), drain(rows));
    }

    @Test
    void failedPartialBatchKeepsAllRowsForAnExactRetry() {
        var rows = new GeometryTexture.DirtyRows();
        int width = GeometryTexture.DirtyRows.WORDS_PER_ROW;
        rows.markWords(4, 8);
        rows.markWords(70 * width + 1, 70 * width + 2);
        rows.markWords(130 * width, 131 * width);
        var expected = List.of(new Strip(0, 1), new Strip(70, 1), new Strip(130, 1));
        var attempted = new ArrayList<Strip>();
        assertThrows(
                IllegalStateException.class,
                () ->
                        rows.upload(
                                (first, count) -> {
                                    attempted.add(new Strip(first, count));
                                    if (first == 70)
                                        throw new IllegalStateException(
                                                "Simulated failed upload after an earlier strip succeeded");
                                }));
        assertEquals(expected.subList(0, 2), attempted);
        assertTrue(rows.isDirty());
        assertEquals(expected, drain(rows));
        assertFalse(rows.isDirty());
    }

    @Test
    void resetOrTextureRecreationReplacesSparseUpdatesWithCompleteInitialization() {
        var rows = new GeometryTexture.DirtyRows();
        rows.markWords(4, 8);
        rows.markAll();
        var calls = new AtomicInteger();
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        rows.upload(
                                (first, count) -> {
                                    calls.incrementAndGet();
                                    assertEquals(0, first);
                                    assertEquals(512, count);
                                    throw new IllegalArgumentException("Initial upload failed");
                                }));
        assertEquals(1, calls.get());
        assertTrue(rows.isDirty());
        assertEquals(List.of(new Strip(0, 512)), drain(rows));
        rows.markWords(GeometryTexture.DirtyRows.WORDS - 1, GeometryTexture.DirtyRows.WORDS);
        rows.markAll();
        assertEquals(List.of(new Strip(0, 512)), drain(rows));
    }

    @Test
    void simulatedGpuMatchesTheEntireTableAcrossEditsClearsAndFailedBatches() {
        var rows = new GeometryTexture.DirtyRows();
        var random = new Random(302605);
        int[] cpu = new int[GeometryTexture.DirtyRows.WORDS], gpu = new int[cpu.length];
        Arrays.fill(gpu, 0x7fffffff);
        rows.markAll();
        for (int step = 0; step < 80; step++) {
            if (step % 17 == 0) {
                Arrays.fill(cpu, 0);
                rows.markAll();
            }
            for (int update = 0; update < 7; update++) {
                int from = random.nextInt(cpu.length),
                        length = Math.min(1 + random.nextInt(7000), cpu.length - from);
                for (int i = from; i < from + length; i++) cpu[i] = random.nextInt();
                rows.markWords(from, from + length);
            }
            if (step % 5 == 0) {
                var attempted = new AtomicInteger();
                assertThrows(
                        IllegalStateException.class,
                        () ->
                                rows.upload(
                                        (first, count) -> {
                                            copy(cpu, gpu, first, count);
                                            if (attempted.incrementAndGet() == 1)
                                                throw new IllegalStateException(
                                                        "Retry after first copied strip");
                                        }));
            }
            rows.upload((first, count) -> copy(cpu, gpu, first, count));
            assertArrayEquals(cpu, gpu, "Full GPU contents after update " + step);
            assertFalse(rows.isDirty());
        }
    }

    private static void copy(int[] source, int[] destination, int first, int count) {
        assertTrue(first >= 0 && count > 0 && first + count <= GeometryTexture.DirtyRows.ROWS);
        int start = first * GeometryTexture.DirtyRows.WORDS_PER_ROW;
        System.arraycopy(
                source, start, destination, start, count * GeometryTexture.DirtyRows.WORDS_PER_ROW);
    }
}
