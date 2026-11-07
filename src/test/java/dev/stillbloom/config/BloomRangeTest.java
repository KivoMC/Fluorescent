package dev.stillbloom.config;

import static org.junit.jupiter.api.Assertions.*;

import dev.stillbloom.render.source.SourceMask;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

class BloomRangeTest {
    @Test
    void fullFadeSphereStaysInsideMaskAcrossPositiveAndNegativeOriginShifts() {
        for (double coordinate = -150; coordinate <= 150; coordinate += 0.125) {
            int origin = BloomRange.origin(coordinate);
            assertTrue(coordinate - BloomRange.MAX_RANGE - origin >= 4);
            assertTrue(origin + BloomRange.VOLUME_SIZE - coordinate - BloomRange.MAX_RANGE >= 4);
        }
    }

    @Test
    void everyVoxelMapsToItsOwnBitAndSurvivesTextureByteEncoding() {
        int[] words = new int[SourceMask.WORDS];
        int[][] positions = {
            {0, 0, 0},
            {31, 0, 0},
            {32, 0, 0},
            {127, 0, 0},
            {0, 1, 0},
            {0, 0, 1},
            {127, 127, 127},
            {64, 63, 62}
        };
        for (int[] p : positions) {
            int index = BloomRange.index(p[0], p[1], p[2]);
            words[index >>> 5] |= 1 << (index & 31);
        }
        assertEquals(positions.length, java.util.Arrays.stream(words).map(Integer::bitCount).sum());
        ByteBuffer rgba = ByteBuffer.allocate(words.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        rgba.asIntBuffer().put(words);
        for (int[] p : positions) {
            int index = BloomRange.index(p[0], p[1], p[2]);
            int word = index >>> 5;
            int texel = (word & 255) + (word >>> 8) * 256;
            int decoded =
                    Byte.toUnsignedInt(rgba.get(texel * 4))
                            | Byte.toUnsignedInt(rgba.get(texel * 4 + 1)) << 8
                            | Byte.toUnsignedInt(rgba.get(texel * 4 + 2)) << 16
                            | Byte.toUnsignedInt(rgba.get(texel * 4 + 3)) << 24;
            assertNotEquals(0, decoded & (1 << (index & 31)));
        }
        assertEquals(SourceMask.TEXTURE_SIZE * SourceMask.TEXTURE_SIZE, SourceMask.WORDS);
    }

    @Test
    void fadeIsContinuousMonotonicAndZeroAtTheRangeBoundary() {
        for (float range : new float[] {16, 48, 56})
            for (float fraction : new float[] {0.1f, 0.35f, 0.75f}) {
                float start = range * (1 - fraction);
                assertEquals(1, BloomRange.fade(start, range, fraction), 0.00001);
                assertEquals(0.5, BloomRange.fade((start + range) / 2, range, fraction), 0.00001);
                assertEquals(0, BloomRange.fade(range, range, fraction), 0.00001);
                assertEquals(0, BloomRange.fade(range + 10, range, fraction), 0.00001);
                float previous = 1;
                for (int i = 0; i <= 1000; i++) {
                    float value = BloomRange.fade(range * i / 1000, range, fraction);
                    assertTrue(value <= previous + 0.000001);
                    assertTrue(value >= 0 && value <= 1);
                    previous = value;
                }
            }
    }

    @Test
    void rangeSettingsCannotEliminateFadeOrEscapeCoverage() {
        BloomConfig config = new BloomConfig();
        config.bloomRange = Float.POSITIVE_INFINITY;
        config.fadeFraction = 0;
        config.sanitize();
        assertEquals(48, config.bloomRange);
        assertTrue(config.fadeFraction > 0);
        config.bloomRange = 10000;
        config.fadeFraction = Float.NaN;
        config.sanitize();
        assertEquals(BloomRange.MAX_RANGE, config.bloomRange);
        assertTrue(Float.isFinite(config.fadeFraction));
    }
}
