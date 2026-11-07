package dev.stillbloom.gui.layout;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class MaskCanvasTransformTest {
    @Test
    void cursorAnchorStaysOnTheSameTexturePointWhenZooming() {
        var fit = MaskCanvasTransform.fit(40, 90, 120, 80, 64, 64, 1, .5, .5, 16, 16);
        double px = 103.5,
                py = 127.5,
                u = (px - fit.x()) / fit.width(),
                v = (py - fit.y()) / fit.height();
        var zoom = fit.zoomAt(64, 64, 8, px, py);
        assertEquals(u, (px - zoom.x()) / zoom.width(), 1.0 / zoom.width());
        assertEquals(v, (py - zoom.y()) / zoom.height(), 1.0 / zoom.height());
        assertTrue(zoom.width() > zoom.viewportW());
    }

    @Test
    void imageOutsideTheViewportNeverBecomesAPaintTarget() {
        var view = MaskCanvasTransform.fit(40, 90, 120, 80, 64, 64, 16, .5, .5, 16, 16);
        assertFalse(view.contains(39, 120));
        assertFalse(view.contains(160, 120));
        assertFalse(view.contains(100, 89));
        assertFalse(view.contains(100, 170));
        assertTrue(view.contains(40, 90));
        assertTrue(view.contains(159.99, 169.99));
    }

    @Test
    void zoomedNonSquareTextureMapsVisibleTexelCentersExactly() {
        var view = MaskCanvasTransform.fit(10, 20, 180, 100, 128, 64, 8, .63, .42, 256, 128);
        for (int y = 0; y < 128; y++)
            for (int x = 0; x < 256; x++) {
                double px = view.x() + (x + .5) * view.width() / 256.0,
                        py = view.y() + (y + .5) * view.height() / 128.0;
                if (view.contains(px, py)) {
                    assertEquals(x, view.pixelX(px, 256));
                    assertEquals(y, view.pixelY(py, 128));
                }
            }
    }

    @Test
    void panClampsToImageEdgesAndFitRecentersAfterResize() {
        var view = MaskCanvasTransform.fit(20, 30, 150, 90, 80, 80, 16, .5, .5, 16, 16);
        var edge = view.pan(80, 80, 100_000, -100_000);
        assertTrue(edge.x() <= edge.left());
        assertTrue(edge.x() + edge.width() >= edge.left() + edge.viewportW());
        assertTrue(edge.y() <= edge.top());
        assertTrue(edge.y() + edge.height() >= edge.top() + edge.viewportH());
        var fit =
                MaskCanvasTransform.fit(
                        20, 30, 100, 60, 48, 48, 1, edge.centerU(), edge.centerV(), 16, 16);
        assertEquals(.5, fit.centerU());
        assertEquals(.5, fit.centerV());
        assertEquals(46, fit.x());
        assertEquals(36, fit.y());
    }

    @Test
    void highResolutionTextureCanBePaintedAtMinimumGuiCanvasSize() {
        var view = MaskCanvasTransform.fit(10, 20, 160, 60, 52, 52, 16, .5, .5, 256, 256);
        assertTrue(
                view.width() / 256.0 >= 3,
                "256px pack textures need magnification for individual-pixel painting");
        var max = view.zoomAt(52, 52, 1000, 90, 50);
        assertEquals(64, max.zoom());
        var min = view.zoomAt(52, 52, 0, 90, 50);
        assertEquals(1, min.zoom());
    }

    @Test
    void magnifiedPixelBordersMatchTheExactPaintTarget() {
        var view = MaskCanvasTransform.fit(10, 20, 160, 60, 52, 52, 16, .5, .5, 256, 256);
        assertEquals(0, view.width() % 256);
        assertEquals(0, view.height() % 256);
        for (int x = 1; x < 255; x++) {
            double border = view.x() + x * view.width() / 256.0;
            assertEquals(x - 1, view.pixelX(border - .01, 256));
            assertEquals(x, view.pixelX(border + .01, 256));
        }
        for (int y = 1; y < 255; y++) {
            double border = view.y() + y * view.height() / 256.0;
            assertEquals(y - 1, view.pixelY(border - .01, 256));
            assertEquals(y, view.pixelY(border + .01, 256));
        }
    }
}
