package dev.stillbloom.gui.layout;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class MaskEditorLayoutTest {
    @Test
    void textureStaysInsideItsStageAndOutsideTheToolRailAtSupportedGuiSizes() {
        for (int width : new int[] {280, 360, 600, 880})
            for (int height : new int[] {90, 124, 220, 420})
                for (int tw : new int[] {16, 32, 128, 256})
                    for (int th : new int[] {16, 64, 256}) {
                        int x = 20, top = 80, bottom = top + height;
                        var layout = MaskEditorLayout.fit(x, top, width, bottom, tw, th);
                        assertTrue(layout.stageX() >= x + layout.toolW() + 8);
                        assertTrue(layout.previewW() > 0 && layout.previewH() > 0);
                        assertTrue(layout.previewX() >= layout.stageX() + 8);
                        assertTrue(layout.previewX() + layout.previewW() <= x + width - 8);
                        assertTrue(layout.previewY() >= top + 14);
                        assertTrue(layout.previewY() + layout.previewH() <= bottom - 18);
                    }
    }

    @Test
    void lowResolutionTextureUsesIntegerPixelZoomWhenItFits() {
        var layout = MaskEditorLayout.fit(10, 70, 600, 350, 16, 16);
        assertEquals(0, layout.previewW() % 16);
        assertEquals(layout.previewW(), layout.previewH());
        assertTrue(layout.previewW() >= 16);
        var narrow = MaskEditorLayout.fit(10, 70, 280, 170, 256, 128);
        assertTrue(narrow.previewW() < 256 && narrow.previewH() < 128);
        assertEquals(narrow.previewW() / 2, narrow.previewH(), 1);
    }

    @Test
    void embeddedCanvasKeepsPaintablePixelsOutsideAdvancedToolRail() {
        for (int width : new int[] {140, 196, 220, 260, 400, 600})
            for (int height : new int[] {75, 110, 190, 330})
                for (boolean rail : new boolean[] {false, true}) {
                    if (rail && width < 220) continue;
                    var layout =
                            MaskEditorLayout.embedded(20, 70, width, 70 + height, 16, 16, rail);
                    assertTrue(layout.previewX() >= 20 + (rail ? 106 : 0));
                    assertTrue(layout.previewX() + layout.previewW() <= 20 + width - 6);
                    assertTrue(layout.previewY() >= 83);
                    assertTrue(layout.previewY() + layout.previewH() <= 70 + height - 17);
                    assertTrue(
                            layout.previewW() >= 32,
                            "Native low-resolution masks need at least 2 GUI pixels per texel");
                    assertEquals(0, layout.previewW() % 16);
                }
    }
}
