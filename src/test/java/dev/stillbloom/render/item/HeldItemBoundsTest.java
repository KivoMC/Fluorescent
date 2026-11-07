package dev.stillbloom.render.item;

import static org.junit.jupiter.api.Assertions.*;

import dev.stillbloom.config.BloomConfig;

import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;

import java.util.Random;

class HeldItemBoundsTest {
    @Test
    void lanternShrinkLeavesRoomForTheWalkingAnimationWithoutKeepingTheLargestBuffer() {
        var config = new BloomConfig();
        config.pixelSize = 3;
        var window = new HeldItemBounds.ShrinkWindow();
        assertFalse(window.ready(576, 384, 768, 768, 0));
        assertFalse(window.ready(576, 384, 768, 768, 1_499_999_999L));
        assertTrue(window.ready(576, 384, 768, 768, 1_500_000_000L));
        int width = HeldItemBounds.shrunkExtent(768, window.peakWidth, config);
        int height = HeldItemBounds.shrunkExtent(768, window.peakHeight, config);
        assertTrue(width >= 576 && height >= 576);
        assertTrue((long) width * height < 768L * 768);
        assertEquals(576L * 768, (long) width * height);
        window.reset();
        for (int frame = 0; frame < 1000; frame++) {
            int requiredHeight = frame % 90 < 45 ? 384 : 576;
            assertFalse(window.ready(576, requiredHeight, width, height, frame * 16_666_667L));
        }
    }

    @Test
    void shrinkingUsesRecentPeakDimensionsAndResetsAfterLargeOrInactiveCrops() {
        for (int pixel : new int[] {2, 3, 4}) {
            var config = new BloomConfig();
            config.pixelSize = pixel;
            var window = new HeldItemBounds.ShrinkWindow();
            assertFalse(window.ready(300, 150, 1200, 1200, 0));
            assertFalse(window.ready(150, 350, 1200, 1200, 700_000_000L));
            assertTrue(window.ready(150, 150, 1200, 1200, 1_500_000_000L));
            assertEquals(300, window.peakWidth);
            assertEquals(350, window.peakHeight);
            for (int peak : new int[] {window.peakWidth, window.peakHeight}) {
                int capacity = HeldItemBounds.allocationSize(1200, config);
                int shrink = HeldItemBounds.shrunkExtent(capacity, peak, config);
                assertTrue(shrink >= peak && shrink <= capacity);
                assertEquals(0, shrink % HeldItemBounds.gridAlignment(config));
            }
            assertFalse(window.ready(1200, 1200, 1200, 1200, 2_000_000_000L));
            assertFalse(window.ready(150, 150, 1200, 1200, 4_000_000_000L));
            window.reset();
            assertFalse(window.ready(150, 150, 1200, 1200, 9_000_000_000L));
        }
    }

    @Test
    void animatedCropsStayOnGridWithBoundedGrowthAndCoverTheViewportEdge() {
        for (int pixel : new int[] {2, 3, 4}) {
            var config = new BloomConfig();
            config.pixelSize = pixel;
            int alignment = HeldItemBounds.allocationAlignment(config), capacity = 0;
            for (int required = 1; required <= 1009; required += 3) {
                int old = capacity;
                capacity = HeldItemBounds.grownExtent(old, required, 1009, config);
                assertTrue(
                        capacity >= required
                                && capacity <= HeldItemBounds.allocationSize(1009, config));
                assertEquals(0, capacity % alignment);
                if (old >= required) assertEquals(old, capacity);
                else
                    assertTrue(
                            capacity
                                    <= HeldItemBounds.allocationSize(required, config) + alignment);
            }
        }
    }

    @Test
    void projectedBoundsAreLowerLeftUnionAllQuadsAndIgnoreDepthConvention() {
        var config = new BloomConfig();
        config.wideHalo = 0;
        config.glowWidth = 8;
        var lower = quad(-.8f, -.8f, -.6f, -.6f, 1);
        var upper = quad(.6f, .6f, .8f, .8f, 1);
        var a = HeldItemBounds.project(lower, 4, 1281, 721, config);
        var b = HeldItemBounds.project(upper, 4, 1281, 721, config);
        assertTrue(a.x() < b.x());
        assertTrue(a.y() < b.y());
        float[] both = new float[32];
        System.arraycopy(lower, 0, both, 0, 16);
        System.arraycopy(upper, 0, both, 16, 16);
        var all = HeldItemBounds.project(both, 8, 1281, 721, config);
        contains(all, a);
        contains(all, b);
        for (float z : new float[] {-10, -1, 0, .5f, 1, 10}) {
            for (int i = 2; i < lower.length; i += 4) lower[i] = z;
            assertEquals(a, HeldItemBounds.project(lower, 4, 1281, 721, config));
        }
    }

    @Test
    void homogeneousClippingSkipsOffscreenAndBehindCameraWithoutDividingByZero() {
        var config = new BloomConfig();
        assertTrue(HeldItemBounds.project(new float[0], 0, 1920, 1080, config).empty());
        for (var q :
                new float[][] {
                    quad(2, -.5f, 3, .5f, 1),
                    quad(-3, -.5f, -2, .5f, 1),
                    quad(-.5f, 2, .5f, 3, 1),
                    quad(-.5f, -3, .5f, -2, 1),
                    quad(-.5f, -.5f, .5f, .5f, -1),
                    quad(0, 0, 0, 0, 0)
                }) assertTrue(HeldItemBounds.project(q, 4, 1920, 1080, config).empty());
        float[] crosses = {-.5f, -.5f, 0, 1, .5f, -.5f, 0, 1, .5f, .25f, 0, -1, -.5f, .25f, 0, -1};
        var crossing = HeldItemBounds.project(crosses, 4, 1920, 1080, config);
        assertFalse(crossing.empty());
        assertTrue(crossing.width() <= 1920 && crossing.height() <= 1080);
        assertTrue(crossing.x() >= 0 && crossing.y() >= 0);
        var edge = HeldItemBounds.project(quad(.99f, .99f, 1.5f, 1.5f, 1), 4, 1281, 721, config);
        assertCell(edge, 1280, 720);
    }

    @Test
    void malformedGeometryFallsBackAndInvalidFramebufferIsRejected() {
        var config = new BloomConfig();
        for (var q : new float[][] {null, new float[15]})
            assertTrue(HeldItemBounds.project(q, 4, 321, 241, config).full(321, 241));
        assertTrue(HeldItemBounds.project(new float[16], 3, 321, 241, config).full(321, 241));
        assertTrue(
                HeldItemBounds.project(new float[16], Integer.MAX_VALUE, 321, 241, config)
                        .full(321, 241));
        var q = quad(-.5f, -.5f, .5f, .5f, 1);
        q[0] = Float.NaN;
        assertTrue(HeldItemBounds.project(q, 4, 321, 241, config).full(321, 241));
        assertTrue(HeldItemBounds.crop(Double.NaN, 0, 1, 1, 321, 241, config).full(321, 241));
        assertTrue(HeldItemBounds.crop(2, 0, 1, 1, 321, 241, config).full(321, 241));
        assertThrows(
                IllegalArgumentException.class, () -> HeldItemBounds.project(q, 4, 0, 241, config));
        assertThrows(
                IllegalArgumentException.class,
                () -> HeldItemBounds.crop(0, 0, 1, 1, 321, -1, config));
    }

    @Test
    void movingCropRetainsBothFixedPixelGridOrigins() {
        for (int near : new int[] {2, 3, 4})
            for (int far : new int[] {2, 3, 4}) {
                var config = new BloomConfig();
                config.pixelSize = near;
                config.farPixelSize = far;
                config.distancePixels = true;
                int alignment = HeldItemBounds.gridAlignment(config);
                assertEquals(0, alignment % near);
                assertEquals(0, alignment % config.finePixelSize());
                for (int source = 1; source < 1280; source += 7) {
                    var crop =
                            HeldItemBounds.crop(source, 300, source + .25, 301, 1281, 721, config);
                    assertEquals(0, crop.x() % alignment);
                    assertEquals(0, crop.y() % alignment);
                    assertCell(crop, source, 300);
                    for (int grid : new int[] {near, config.finePixelSize()})
                        for (int pixel = crop.x(); pixel < crop.x() + crop.width(); pixel++)
                            assertEquals(
                                    pixel / grid * grid,
                                    crop.x() + (pixel - crop.x()) / grid * grid);
                }
            }
    }

    @Test
    void allocationBucketsPreserveThreePixelAndAdaptiveGridsAndValidCopyEdges() {
        var config = new BloomConfig();
        config.pixelSize = 3;
        assertEquals(3, HeldItemBounds.gridAlignment(config));
        assertEquals(192, HeldItemBounds.allocationAlignment(config));
        config.pixelSize = 4;
        config.distancePixels = true;
        config.farPixelSize = 3;
        assertEquals(12, HeldItemBounds.gridAlignment(config));
        assertEquals(192, HeldItemBounds.allocationAlignment(config));
        for (int valid : new int[] {0, 1, 63, 64, 65, 191, 192, 193, 1281}) {
            int allocated = HeldItemBounds.allocationSize(valid, config);
            assertTrue(allocated >= valid);
            assertTrue(allocated - valid < 192);
            assertEquals(0, allocated % 192);
        }
        var edge = HeldItemBounds.crop(1280, 720, 1281, 721, 1281, 721, config);
        assertEquals(1281, edge.x() + edge.width());
        assertEquals(721, edge.y() + edge.height());
        assertTrue(edge.x() + HeldItemBounds.allocationSize(edge.width(), config) >= 1281);
        assertThrows(
                IllegalArgumentException.class, () -> HeldItemBounds.allocationSize(-1, config));
        assertThrows(
                ArithmeticException.class,
                () -> HeldItemBounds.allocationSize(Integer.MAX_VALUE, config));
        assertTrue(HeldItemBounds.crop(-50, 1, -1, 2, 1281, 721, config).empty());
        assertTrue(HeldItemBounds.crop(1281, 1, 1300, 2, 1281, 721, config).empty());
    }

    @Test
    void randomizedProjectedInteriorSamplesStayInsideConservativeCrops() {
        var random = new Random(190019);
        int visible = 0, partial = 0;
        for (int trial = 0; trial < 500; trial++) {
            int width = 319 + random.nextInt(1600), height = 239 + random.nextInt(1000);
            var matrix =
                    new Matrix4f()
                            .perspective(
                                    (float) Math.toRadians(30 + random.nextFloat() * 90),
                                    (float) width / height,
                                    .01f,
                                    128)
                            .rotateX((random.nextFloat() - .5f) * 2)
                            .rotateY((random.nextFloat() - .5f) * 2);
            float x = (random.nextFloat() - .5f) * 12,
                    y = (random.nextFloat() - .5f) * 8,
                    z = -random.nextFloat() * 12;
            float size = .1f + random.nextFloat() * 4;
            float[] q = new float[16];
            for (int corner = 0; corner < 4; corner++) {
                float dx = corner == 1 || corner == 2 ? size : 0, dy = corner >= 2 ? size : 0;
                var v = matrix.transform(new Vector4f(x + dx, y + dy, z, 1));
                q[corner * 4] = v.x;
                q[corner * 4 + 1] = v.y;
                q[corner * 4 + 2] = v.z;
                q[corner * 4 + 3] = v.w;
            }
            var config = new BloomConfig();
            config.pixelSize = trial % 2 == 0 ? 6 : 8;
            config.distancePixels = true;
            var crop = HeldItemBounds.project(q, 4, width, height, config);
            if (!crop.empty() && !crop.full(width, height)) partial++;
            for (int sample = 0; sample < 100; sample++) {
                var v =
                        matrix.transform(
                                new Vector4f(
                                        x + random.nextFloat() * size,
                                        y + random.nextFloat() * size,
                                        z,
                                        1));
                if (v.w < .000001f) continue;
                double sx = (v.x / (double) v.w * .5 + .5) * width,
                        sy = (v.y / (double) v.w * .5 + .5) * height;
                if (sx >= 0 && sx < width && sy >= 0 && sy < height) {
                    assertCell(crop, (int) sx, (int) sy);
                    visible++;
                }
            }
        }
        assertTrue(partial > 50);
        assertTrue(visible > 2000);
    }

    @Test
    void paddingContainsFiniteFilterSupportIncludingCumulativeDiamondAndFlares() {
        for (var shape : BloomConfig.Shape.values())
            for (int grid : new int[] {2, 3, 4})
                for (float width : new float[] {8, 35.5f, 128})
                    for (boolean wide : new boolean[] {false, true}) {
                        var config = new BloomConfig();
                        config.bloomShape = shape;
                        config.pixelSize = grid;
                        config.glowWidth = width;
                        config.wideHalo = wide ? .35f : 0;
                        int radius = (int) Math.ceil(Math.max(1, width / (2 * grid)));
                        // I enumerate sample offsets independently, including both interpolation
                        // neighbors and sequential passes.
                        double filter =
                                shape == BloomConfig.Shape.STARBURST
                                        ? maxSampleOffset(radius, 1)
                                        : shape == BloomConfig.Shape.DIAMOND
                                                ? 2 * maxSampleOffset(radius, .70710678)
                                                : maxSampleOffset(radius, 1) + 1;
                        if (wide && shape != BloomConfig.Shape.STARBURST) filter *= 2;
                        assertTrue(HeldItemBounds.padding(config) >= filter * grid + 2 * grid);
                        int noFlare = HeldItemBounds.padding(config);
                        config.flareStyle = BloomConfig.Flare.CROSS;
                        config.flareLength = 127.5f;
                        config.flareStrength = 1;
                        assertTrue(
                                HeldItemBounds.padding(config)
                                        >= Math.ceil(127.5 / grid) * grid + 2 * grid);
                        config.flareStrength = 0;
                        assertEquals(noFlare, HeldItemBounds.padding(config));
                    }
        var config = new BloomConfig();
        config.glowWidth = Float.MAX_VALUE;
        assertEquals(Integer.MAX_VALUE, HeldItemBounds.padding(config));
        assertTrue(HeldItemBounds.crop(100, 100, 101, 101, 1281, 721, config).full(1281, 721));
        config.glowWidth = Float.NaN;
        assertThrows(IllegalArgumentException.class, () -> HeldItemBounds.padding(config));
    }

    private static double maxSampleOffset(int radius, double direction) {
        double max = 0;
        for (int i = -radius; i <= radius; i++) {
            double sample = i * direction;
            max =
                    Math.max(
                            max,
                            Math.max(
                                    Math.abs(Math.floor(sample)),
                                    Math.abs(Math.floor(sample) + 1)));
        }
        return max;
    }

    private static float[] quad(float x0, float y0, float x1, float y1, float w) {
        return new float[] {x0, y0, 0, w, x1, y0, 0, w, x1, y1, 0, w, x0, y1, 0, w};
    }

    private static void assertCell(HeldItemBounds.Rect rect, int x, int y) {
        assertTrue(
                x >= rect.x()
                        && x < rect.x() + rect.width()
                        && y >= rect.y()
                        && y < rect.y() + rect.height(),
                () -> rect + " excludes " + x + "," + y);
    }

    private static void contains(HeldItemBounds.Rect outer, HeldItemBounds.Rect inner) {
        assertCell(outer, inner.x(), inner.y());
        assertCell(outer, inner.x() + inner.width() - 1, inner.y() + inner.height() - 1);
    }
}
