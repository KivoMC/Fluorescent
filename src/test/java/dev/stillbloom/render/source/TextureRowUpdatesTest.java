package dev.stillbloom.render.source;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

class TextureRowUpdatesTest {
    @Test
    void indexedMovingSourcesProduceExactlyTheFullComparisonIncludingClears() {
        var random = new Random(107);
        int columns = 1024, rows = 128;
        for (boolean packed : List.of(false, true)) {
            int[] old = new int[columns * rows];
            java.util.BitSet oldRows = new java.util.BitSet();
            for (int generation = 0; generation < 20; generation++) {
                var entries = new java.util.ArrayList<Long>();
                int[] next = new int[old.length];
                for (int i = 0; i < generation * 3; i++) {
                    int voxel = random.nextInt(next.length * (packed ? 2 : 1));
                    int value = 1 + random.nextInt(65534);
                    entries.add((long) voxel << 32 | value);
                    if (packed) next[voxel >>> 1] |= value << ((voxel & 1) * 16);
                    else next[voxel] = value;
                }
                var nextRows =
                        packed
                                ? MaskTexture.RowUpdates.packedRows(
                                        new SourceVolumePool.PackedVolume.Data(
                                                next,
                                                entries.stream()
                                                        .mapToLong(Long::longValue)
                                                        .toArray()))
                                : MaskTexture.RowUpdates.colorOrDetailRows(entries);
                assertEquals(
                        MaskTexture.RowUpdates.plan(old, next, columns, rows),
                        MaskTexture.RowUpdates.plan(old, next, columns, rows, oldRows, nextRows));
                old = next;
                oldRows = nextRows;
            }
            var zero = new int[old.length];
            assertEquals(
                    MaskTexture.RowUpdates.plan(old, zero, columns, rows),
                    MaskTexture.RowUpdates.plan(
                            old, zero, columns, rows, oldRows, new java.util.BitSet()));
            assertEquals(
                    MaskTexture.RowUpdates.plan(old, zero, columns, rows),
                    MaskTexture.RowUpdates.plan(
                            old, zero, columns, rows, null, new java.util.BitSet()));
        }
    }

    @Test
    void firstUploadAndIncompatiblePreviousStorageUseTheCompleteImage() {
        int[] next = new int[32];
        for (int[] previous : new int[][] {null, new int[31], new int[33]}) {
            var plan = MaskTexture.RowUpdates.plan(previous, next, 4, 8);
            assertTrue(plan.fullUpload());
            assertEquals(List.of(new MaskTexture.RowUpdates.Band(0, 8)), plan.bands());
            assertReconstructs(previous, next, 4, 8);
        }
    }

    @Test
    void unchangedContentsDoNotRequireAnyCommands() {
        int[] words = {0, -1, 0xFF010203, Integer.MIN_VALUE};
        assertTrue(MaskTexture.RowUpdates.plan(words, words, 2, 2).bands().isEmpty());
        assertTrue(MaskTexture.RowUpdates.plan(words, words.clone(), 2, 2).bands().isEmpty());
    }

    @Test
    void adjacentDirtyRowsMergeAndBothImageEndpointsAreIncluded() {
        int width = 7, height = 12;
        int[] previous = new int[width * height], next = previous.clone();
        for (int row : new int[] {0, 3, 4, 11})
            next[row * width + (row == 11 ? width - 1 : 0)] = -1;
        var plan = MaskTexture.RowUpdates.plan(previous, next, width, height);
        assertFalse(plan.fullUpload());
        assertEquals(
                List.of(
                        new MaskTexture.RowUpdates.Band(0, 1),
                        new MaskTexture.RowUpdates.Band(3, 2),
                        new MaskTexture.RowUpdates.Band(11, 1)),
                plan.bands());
        assertReconstructs(previous, next, width, height);
    }

    @Test
    void movingAndRemovingSourcesClearTheirOldLocationsIncludingPackedHalfWords() {
        int width = 16, height = 32;
        int[] previous = new int[width * height];
        previous[0] = 0xFFFF1234;
        previous[width * 4 + 5] = 0x1234FFFF;
        previous[previous.length - 1] = 0xFF010203;
        int[] next = new int[previous.length];
        next[width * 4 + 5] = 0x0000FFFF;
        next[width * 7 + 2] = 0x12340000;
        next[width * 20] = Integer.MIN_VALUE;
        assertReconstructs(previous, next, width, height);
        assertReconstructs(next, new int[next.length], width, height);
    }

    @Test
    void denseCoverageFallsBackOnlyAboveHalfTheImage() {
        int[] previous = new int[40], next = previous.clone();
        Arrays.fill(next, 0, 20, -1);
        assertFalse(MaskTexture.RowUpdates.plan(previous, next, 4, 10).fullUpload());
        next[20] = -1;
        assertTrue(MaskTexture.RowUpdates.plan(previous, next, 4, 10).fullUpload());
        assertReconstructs(previous, next, 4, 10);
    }

    @Test
    void tooManySeparatedBandsFallBackWithoutDroppingAnyChanges() {
        int height = MaskTexture.RowUpdates.MAX_BANDS * 4;
        int[] previous = new int[height], next = previous.clone();
        for (int row = 0; row < MaskTexture.RowUpdates.MAX_BANDS * 2; row += 2) next[row] = row + 1;
        var bounded = MaskTexture.RowUpdates.plan(previous, next, 1, height);
        assertFalse(bounded.fullUpload());
        assertEquals(MaskTexture.RowUpdates.MAX_BANDS, bounded.bands().size());
        next[MaskTexture.RowUpdates.MAX_BANDS * 2] = -1;
        assertTrue(MaskTexture.RowUpdates.plan(previous, next, 1, height).fullUpload());
        assertReconstructs(previous, next, 1, height);
    }

    @Test
    void randomSparseAndDenseTransitionsMatchWholeUploadsWithoutMutatingInputs() {
        var random = new Random(0x51B100L);
        for (int iteration = 0; iteration < 100; iteration++) {
            int width = 1 + random.nextInt(32), height = 1 + random.nextInt(257);
            int[] previous = new int[width * height];
            for (int i = 0; i < previous.length; i++) previous[i] = random.nextInt();
            int[] next = previous.clone();
            int updates = iteration % 2 == 0 ? 1 + random.nextInt(8) : next.length;
            for (int i = 0; i < updates; i++)
                next[random.nextInt(next.length)] = random.nextBoolean() ? 0 : random.nextInt();
            int[] originalPrevious = previous.clone(), originalNext = next.clone();
            assertReconstructs(previous, next, width, height);
            assertArrayEquals(originalPrevious, previous);
            assertArrayEquals(originalNext, next);
        }
    }

    @Test
    void realVolumeDimensionsRetainExactUnsignedPayloadsAcrossOriginChanges() {
        for (int height : new int[] {1024, 2048}) {
            int width = 1024;
            int[] previous = new int[width * height];
            for (int step = 0; step < 4; step++) {
                int[] next = new int[previous.length];
                next[step * 128 + 3] = 0xFFFF8000;
                next[(height - 1 - step) * width + width - 1] = 0xFF030201;
                assertReconstructs(previous, next, width, height);
                previous = next;
            }
        }
    }

    @Test
    void invalidDimensionsAndIncompleteNewPayloadsAreRejected() {
        for (int[] size :
                new int[][] {
                    {0, 1},
                    {1, 0},
                    {-1, 1},
                    {1, -1},
                    {-1, -1},
                    {Integer.MAX_VALUE, 2},
                    {1, Integer.MAX_VALUE}
                }) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> MaskTexture.RowUpdates.plan(null, new int[0], size[0], size[1]));
        }
        assertThrows(
                IllegalArgumentException.class,
                () -> MaskTexture.RowUpdates.plan(null, null, 1, 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> MaskTexture.RowUpdates.plan(null, new int[3], 2, 2));
        assertThrows(
                IllegalArgumentException.class,
                () -> MaskTexture.RowUpdates.plan(null, new int[5], 2, 2));
    }

    private static void assertReconstructs(int[] previous, int[] next, int width, int height) {
        var plan = MaskTexture.RowUpdates.plan(previous, next, width, height);
        var nativeImage =
                ByteBuffer.allocate(next.length * Integer.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        if (previous != null && previous.length == next.length)
            nativeImage.asIntBuffer().put(previous);
        byte[] gpu = nativeImage.array().clone();
        int lastRow = 0;
        for (var band : plan.bands()) {
            assertTrue(band.firstRow() >= lastRow);
            assertTrue(band.rowCount() > 0);
            assertTrue(band.firstRow() + band.rowCount() <= height);
            int offset = band.firstRow() * width, count = band.rowCount() * width;
            nativeImage.asIntBuffer().put(offset, next, offset, count);
            nativeImage
                    .slice(offset * Integer.BYTES, count * Integer.BYTES)
                    .get(gpu, offset * Integer.BYTES, count * Integer.BYTES);
            lastRow = band.firstRow() + band.rowCount();
        }
        int[] actual = new int[next.length];
        ByteBuffer.wrap(gpu).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(actual);
        assertArrayEquals(next, actual);
        int[] cpuImage = new int[next.length];
        nativeImage.asIntBuffer().get(cpuImage);
        assertArrayEquals(next, cpuImage);
        assertTrue(plan.fullUpload() || plan.bands().size() <= MaskTexture.RowUpdates.MAX_BANDS);
    }
}
