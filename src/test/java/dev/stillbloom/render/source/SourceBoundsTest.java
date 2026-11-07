package dev.stillbloom.render.source;

import static org.junit.jupiter.api.Assertions.*;

import org.joml.FrustumIntersection;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

class SourceBoundsTest {
    @Test
    void conservativeBoundsKeepVisibleAndEdgeSourcesButRejectBehindAndFar() {
        var frustum =
                new FrustumIntersection(
                        new Matrix4f().perspective((float) Math.PI / 2, 1, .1f, 100));
        assertTrue(new SourceBounds(-1, -1, -5, 1, 1, -4).visible(frustum, 0, 0, 0, 48));
        assertTrue(new SourceBounds(4, 0, -5, 6, 1, -4).visible(frustum, 0, 0, 0, 48));
        assertFalse(new SourceBounds(-1, -1, 5, 1, 1, 6).visible(frustum, 0, 0, 0, 48));
        assertFalse(new SourceBounds(30, -1, -5, 31, 1, -4).visible(frustum, 0, 0, 0, 48));
        assertFalse(new SourceBounds(-1, -1, -60, 1, 1, -59).visible(frustum, 0, 0, 0, 48));
        assertTrue(
                new SourceBounds(29999999, -1, -5, 30000001, 1, -4)
                        .visible(frustum, 30000000, 0, 0, 48));
    }
}
