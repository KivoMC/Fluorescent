package dev.stillbloom.gui.layout;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.BitSet;

class BrushFootprintTest {
    @Test
    void footprintMatchesLegacyBrushIncludingEvenSizeBiasAndAllTextureEdges() {
        for (int width : new int[] {1, 2, 16, 256})
            for (int height : new int[] {1, 3, 16, 128})
                for (int size = 1; size <= 4; size++)
                    for (int y : new int[] {0, height / 2, height - 1})
                        for (int x : new int[] {0, width / 2, width - 1}) {
                            BitSet expected = new BitSet(width * height),
                                    actual = new BitSet(width * height);
                            for (int by = 0; by < size; by++)
                                for (int bx = 0; bx < size; bx++) {
                                    int px = x + bx - (size - 1) / 2, py = y + by - (size - 1) / 2;
                                    if (px >= 0 && py >= 0 && px < width && py < height)
                                        expected.set(px + py * width);
                                }
                            var footprint = BrushFootprint.at(x, y, size, width, height);
                            for (int py = footprint.top(); py < footprint.bottom(); py++)
                                for (int px = footprint.left(); px < footprint.right(); px++)
                                    actual.set(px + py * width);
                            assertEquals(
                                    expected,
                                    actual,
                                    "Brush footprint must preserve the existing paint algorithm");
                            assertEquals(actual.cardinality(), footprint.area());
                        }
    }

    @Test
    void evenBrushesKeepTheExistingBottomRightBias() {
        assertEquals(new BrushFootprint(8, 8, 10, 10), BrushFootprint.at(8, 8, 2, 16, 16));
        assertEquals(new BrushFootprint(7, 7, 11, 11), BrushFootprint.at(8, 8, 4, 16, 16));
        assertEquals(new BrushFootprint(0, 0, 3, 3), BrushFootprint.at(0, 0, 4, 16, 16));
        assertEquals(new BrushFootprint(14, 14, 16, 16), BrushFootprint.at(15, 15, 4, 16, 16));
    }

    @Test
    void magnifiedPreviewBoundsCoverExactlyThePaintedPixelsAfterPan() {
        for (int size = 1; size <= 4; size++)
            for (int scale : new int[] {1, 3, 8, 13}) {
                var footprint = BrushFootprint.at(127, 63, size, 256, 128);
                var rect = footprint.canvasBounds(-420, -90, 256 * scale, 128 * scale, 256, 128);
                assertEquals(-420 + footprint.left() * scale, rect.left());
                assertEquals(-90 + footprint.top() * scale, rect.top());
                assertEquals(
                        (footprint.right() - footprint.left()) * scale, rect.right() - rect.left());
                assertEquals(
                        (footprint.bottom() - footprint.top()) * scale, rect.bottom() - rect.top());
            }
    }

    @Test
    void footprintAtFitZoomRemainsVisibleWithoutEscapingTheImage() {
        for (int size = 1; size <= 4; size++)
            for (int x : new int[] {0, 127, 255}) {
                var footprint = BrushFootprint.at(x, 255, size, 256, 256);
                var rect = footprint.canvasBounds(10, 20, 52, 52, 256, 256);
                assertTrue(rect.left() >= 10 && rect.top() >= 20);
                assertTrue(rect.right() <= 62 && rect.bottom() <= 72);
                assertTrue(rect.right() > rect.left() && rect.bottom() > rect.top());
            }
    }
}
