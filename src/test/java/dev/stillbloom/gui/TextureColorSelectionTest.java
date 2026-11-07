package dev.stillbloom.gui;

import static org.junit.jupiter.api.Assertions.*;

import dev.stillbloom.mask.TextureMasks;

import org.junit.jupiter.api.Test;

import java.util.BitSet;

class TextureColorSelectionTest {
    private static BitSet bits(int... indices) {
        var b = new BitSet();
        for (int i : indices) b.set(i);
        return b;
    }

    @Test
    void exactColorIgnoresFullyTransparentPixelsAndAllowsVisibleAlpha() {
        int[] pixels = {0xFFFF8020, 0x00FF8020, 0x01FF8020, 0xFFFE8020, 0xFFFF8021};
        assertEquals(bits(0, 2), TextureColorSelection.select(pixels, 5, 1, 0, 0, 0, false));
        assertTrue(TextureColorSelection.select(pixels, 5, 1, 1, 0, 255, false).isEmpty());
    }

    @Test
    void toleranceUsesInclusiveSeedRgbDistanceWithoutGradientWalking() {
        int[] pixels = {0xFF202020, 0xFF2A2A2A, 0xFF342A2A, 0xFF20202B};
        assertEquals(bits(0, 1), TextureColorSelection.select(pixels, 4, 1, 0, 0, 10, false));
        assertEquals(bits(0, 1), TextureColorSelection.select(pixels, 4, 1, 0, 0, 10, true));
        assertEquals(bits(0, 1, 2, 3), TextureColorSelection.select(pixels, 4, 1, 0, 0, 255, true));
    }

    @Test
    void connectedSelectionDoesNotCrossDiagonalsTransparencyOrRowEdges() {
        int c = 0xFFFF0000;
        int[] pixels = {c, 0, c, 0, c, c, c, 0, c};
        assertEquals(bits(0), TextureColorSelection.select(pixels, 3, 3, 0, 0, 0, true));
        assertEquals(bits(2, 4, 5, 8), TextureColorSelection.select(pixels, 3, 3, 2, 0, 0, true));
        assertEquals(
                bits(0, 2, 4, 5, 6, 8), TextureColorSelection.select(pixels, 3, 3, 0, 0, 0, false));
    }

    @Test
    void selectionHandlesBoundariesClampingAndMaximumTexture() {
        int[] pixels = new int[256 * 256];
        java.util.Arrays.fill(pixels, 0xFFFFFFFF);
        assertEquals(
                pixels.length,
                TextureColorSelection.select(pixels, 256, 256, 255, 255, 999, true).cardinality());
        assertEquals(
                pixels.length,
                TextureColorSelection.select(pixels, 256, 256, 0, 0, -1, false).cardinality());
        assertTrue(TextureColorSelection.select(pixels, 256, 256, 256, 0, 0, true).isEmpty());
        assertThrows(
                IllegalArgumentException.class,
                () -> TextureColorSelection.select(new int[1], 2, 1, 0, 0, 0, true));
        assertThrows(
                IllegalArgumentException.class,
                () -> TextureColorSelection.select(new int[0], 0, 0, 0, 0, 0, true));
    }

    @Test
    void automaticPaintStartsBlankAndEraseStartsFullWithoutMutatingUndoSnapshot() {
        var paint = TextureColorSelection.apply(null, 3, 2, bits(1, 5), false);
        assertTrue(paint.get(1, 0));
        assertTrue(paint.get(2, 1));
        assertFalse(paint.get(0, 0));
        var erase = TextureColorSelection.apply(null, 3, 2, bits(1, 5), true);
        assertFalse(erase.get(1, 0));
        assertFalse(erase.get(2, 1));
        assertTrue(erase.get(0, 0));
        var changed = TextureColorSelection.apply(paint, 3, 2, bits(0), false);
        assertNotSame(paint, changed);
        assertFalse(paint.get(0, 0));
        assertTrue(changed.get(0, 0));
        assertSame(changed, TextureColorSelection.apply(changed, 3, 2, bits(0), false));
    }

    @Test
    void emptyAndOutOfBoundsSelectionsDoNotCreateMasksAndDimensionChangesAreSafe() {
        assertNull(TextureColorSelection.apply(null, 2, 2, new BitSet(), false));
        assertNull(TextureColorSelection.apply(null, 2, 2, bits(100), true));
        var original = new TextureMasks.Mask(4, 4);
        original.set(3, 3, true);
        var resized = TextureColorSelection.apply(original, 2, 2, bits(0, 100), false);
        assertEquals(2, resized.width);
        assertTrue(resized.get(0, 0));
        assertTrue(original.get(3, 3));
        assertSame(original, TextureColorSelection.apply(original, 2, 2, new BitSet(), false));
    }

    @Test
    void firstEraseOnAnUnpaintedBlockCannotLightTheUnselectedPixels() {
        var blank = TextureColorSelection.apply(null, 3, 2, bits(1, 5), true, false);
        assertFalse(blank.hasPaint());
        var painted = TextureColorSelection.apply(blank, 3, 2, bits(0, 1, 5), false, false);
        var erased = TextureColorSelection.apply(painted, 3, 2, bits(1, 5), true, false);
        assertTrue(erased.get(0, 0));
        assertFalse(erased.get(1, 0));
        assertFalse(erased.get(2, 1));
        assertTrue(painted.get(1, 0));
        assertTrue(painted.get(2, 1));
        assertTrue(
                TextureColorSelection.apply(null, 3, 2, bits(1, 5), true, true).get(0, 0),
                "Held-item inheritance keeps its existing erase behavior");
    }
}
