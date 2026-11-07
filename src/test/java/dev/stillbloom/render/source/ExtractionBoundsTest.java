package dev.stillbloom.render.source;

import static org.junit.jupiter.api.Assertions.*;

import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

class ExtractionBoundsTest {
    @Test
    void emptyOffscreenAndInvalidInputsAreSafe() {
        var matrix = perspective(1920, 1080);
        assertTrue(project(List.of(), matrix, 1920, 1080).empty());
        assertTrue(
                project(List.of(new SourceBounds(100, 0, -10, 101, 1, -9)), matrix, 1920, 1080)
                        .empty());
        for (var bounds :
                List.of(
                        new SourceBounds(-1, -1, -1, 1, 1, 1),
                        new SourceBounds(0, 0, -2, 1, 1, -1),
                        new SourceBounds(2, 0, -10, 1, 1, -9)))
            assertTrue(project(List.of(bounds), matrix, 1920, 1080).full(960, 540));
        assertTrue(project(null, matrix, 1920, 1080).full(960, 540));
        assertTrue(
                project(
                                List.of(new SourceBounds(0, 0, -10, 1, 1, -9)),
                                new Matrix4f().m00(Float.NaN),
                                1920,
                                1080)
                        .full(960, 540));
        assertTrue(
                ExtractionBounds.project(List.of(), matrix, Double.NaN, 0, 0, 1920, 1080)
                        .full(960, 540));
        assertThrows(IllegalArgumentException.class, () -> project(List.of(), matrix, 0, 1080));
    }

    @Test
    void whollyBehindSourcesDoNotExpandAVisibleSourcesRectangle() {
        int width = 1921, height = 1081;
        for (float yaw : new float[] {0, .7f, -.9f, 1.7f})
            for (int offset : new int[] {0, 30_000_000, -30_000_000}) {
                var matrix = perspective(width, height).rotateY(yaw);
                int x = Math.round((float) Math.sin(yaw) * 24),
                        z = Math.round(-(float) Math.cos(yaw) * 24);
                var front =
                        new SourceBounds(
                                offset + x, 1, offset + z, offset + x + 1, 2, offset + z + 1);
                var behind =
                        new SourceBounds(
                                offset - x, 1, offset - z, offset - x + 1, 2, offset - z + 1);
                var expected =
                        ExtractionBounds.project(
                                List.of(front),
                                matrix,
                                offset + .25,
                                .5,
                                offset + .125,
                                width,
                                height);
                assertFalse(expected.empty());
                assertFalse(expected.full(961, 541));
                assertEquals(
                        expected,
                        ExtractionBounds.project(
                                List.of(front, behind),
                                matrix,
                                offset + .25,
                                .5,
                                offset + .125,
                                width,
                                height));
                assertEquals(
                        expected,
                        ExtractionBounds.project(
                                List.of(behind, front),
                                matrix,
                                offset + .25,
                                .5,
                                offset + .125,
                                width,
                                height));
                assertTrue(
                        ExtractionBounds.project(
                                        List.of(behind),
                                        matrix,
                                        offset + .25,
                                        .5,
                                        offset + .125,
                                        width,
                                        height)
                                .empty());
            }
    }

    @Test
    void eyeCrossingsTinyPositiveWAndNonfiniteCornersRetainFullFallback() {
        int width = 1921, height = 1081;
        for (float yaw : new float[] {0, .7f, -.9f, 1.7f}) {
            var matrix = perspective(width, height).rotateY(yaw);
            var front = new SourceBounds(-1, 0, -25, 0, 1, -24);
            var crossing = new SourceBounds(-1, -1, -1, 1, 1, 1);
            assertTrue(project(List.of(front, crossing), matrix, width, height).full(961, 541));
        }
        var tinyW = new Matrix4f().m33(.000001f);
        assertTrue(
                project(List.of(new SourceBounds(0, 0, -10, 1, 1, -9)), tinyW, width, height)
                        .full(961, 541));
        var invalid = new Matrix4f().m03(Float.NaN);
        assertTrue(
                project(List.of(new SourceBounds(0, 0, 2, 1, 1, 3)), invalid, width, height)
                        .full(961, 541));
        assertTrue(
                project(
                                List.of(new SourceBounds(0, 0, 2, 1, 1, 3)),
                                perspective(width, height),
                                width,
                                height)
                        .empty());
    }

    @Test
    void rectanglesUseLowerLeftCoordinatesAndUnionEverySource() {
        var matrix = perspective(1920, 1080);
        var lower = new SourceBounds(-5, -5, -25, -4, -4, -24);
        var upper = new SourceBounds(4, 4, -25, 5, 5, -24);
        var a = project(List.of(lower), matrix, 1920, 1080);
        var b = project(List.of(upper), matrix, 1920, 1080);
        var union = project(List.of(lower, upper), matrix, 1920, 1080);
        assertTrue(a.x() < b.x());
        assertTrue(a.y() < b.y());
        assertContains(union, a);
        assertContains(union, b);
        assertFalse(union.full(960, 540));
    }

    @Test
    void oddDimensionsKeepEverySampleAtTheLastFramebufferEdge() {
        int width = 1281, height = 721;
        var matrix = new Matrix4f().ortho(0, width, 0, height, -100, 100);
        for (var source :
                List.of(
                        new SourceBounds(width - 1, 0, -1, width, 1, 0),
                        new SourceBounds(0, height - 1, -1, 1, height, 0),
                        new SourceBounds(width - 1, height - 1, -1, width, height, 0))) {
            var rect = project(List.of(source), matrix, width, height);
            for (int y = source.minY(); y < source.maxY(); y++)
                for (int x = source.minX(); x < source.maxX(); x++) assertCell(rect, x / 2, y / 2);
        }
        assertEquals(
                new ExtractionBounds.Rect(638, 358, 3, 3),
                project(
                        List.of(new SourceBounds(1280, 720, -1, 1281, 721, 0)),
                        matrix,
                        width,
                        height));
    }

    @Test
    void subtractsWorldCoordinatesBeforeConvertingToFloat() {
        var matrix = perspective(1921, 1081);
        var original = new SourceBounds(-4, 2, -20, -3, 3, -19);
        var expected =
                ExtractionBounds.project(List.of(original), matrix, .25, .75, .125, 1921, 1081);
        for (int offset : new int[] {30_000_000, -30_000_000}) {
            var translated =
                    new SourceBounds(
                            original.minX() + offset,
                            original.minY(),
                            original.minZ() + offset,
                            original.maxX() + offset,
                            original.maxY(),
                            original.maxZ() + offset);
            assertEquals(
                    expected,
                    ExtractionBounds.project(
                            List.of(translated),
                            matrix,
                            offset + .25,
                            .75,
                            offset + .125,
                            1921,
                            1081));
        }
    }

    @Test
    void randomizedPerspectiveProjectionsContainAllVisibleSourceSamples() {
        var random = new Random(170016);
        int checked = 0, partial = 0;
        for (int trial = 0; trial < 1000; trial++) {
            int width = 319 + random.nextInt(2000), height = 239 + random.nextInt(1200);
            var matrix =
                    new Matrix4f()
                            .perspective(
                                    (float) Math.toRadians(30 + random.nextDouble() * 95),
                                    (float) width / height,
                                    .01f,
                                    256)
                            .rotateX((random.nextFloat() - .5f) * .5f)
                            .rotateY((random.nextFloat() - .5f) * .8f);
            int x = random.nextInt(61) - 30,
                    y = random.nextInt(41) - 20,
                    z = -4 - random.nextInt(57);
            var source =
                    new SourceBounds(
                            x,
                            y,
                            z,
                            x + 1 + random.nextInt(8),
                            y + 1 + random.nextInt(8),
                            z + 1 + random.nextInt(2));
            double cameraX = random.nextDouble(),
                    cameraY = random.nextDouble(),
                    cameraZ = random.nextDouble();
            var rect =
                    ExtractionBounds.project(
                            List.of(source), matrix, cameraX, cameraY, cameraZ, width, height);
            assertTrue(
                    rect.x() >= 0
                            && rect.y() >= 0
                            && rect.x() + rect.width() <= (width + 1) / 2
                            && rect.y() + rect.height() <= (height + 1) / 2);
            if (!rect.empty() && !rect.full((width + 1) / 2, (height + 1) / 2)) partial++;
            for (int sample = 0; sample < 100; sample++) {
                // I include extraction's .003 inward nudge when testing points just outside a
                // block.
                float px =
                        (float)
                                (source.minX()
                                        - .004
                                        + random.nextDouble()
                                                * (source.maxX() - source.minX() + .008)
                                        - cameraX);
                float py =
                        (float)
                                (source.minY()
                                        - .004
                                        + random.nextDouble()
                                                * (source.maxY() - source.minY() + .008)
                                        - cameraY);
                float pz =
                        (float)
                                (source.minZ()
                                        - .004
                                        + random.nextDouble()
                                                * (source.maxZ() - source.minZ() + .008)
                                        - cameraZ);
                var clip = matrix.transform(new Vector4f(px, py, pz, 1));
                if (clip.w <= 0) continue;
                double sx = (clip.x / clip.w * .5 + .5) * width,
                        sy = (clip.y / clip.w * .5 + .5) * height;
                if (sx < 0 || sx >= width || sy < 0 || sy >= height) continue;
                assertCell(rect, (int) sx / 2, (int) sy / 2);
                checked++;
            }
        }
        assertTrue(partial > 500, "Exercise actual scissor rectangles, not only fallback");
        assertTrue(checked > 20000, "Exercise visible source samples");
    }

    @Test
    void starburstBoundsHandleEmptyFullUnknownAndLastEdgeCells() {
        for (int pixels : new int[] {2, 3, 4}) {
            int width = 1281,
                    height = 721,
                    outWidth = ceilDiv(width, pixels),
                    outHeight = ceilDiv(height, pixels);
            assertTrue(
                    ExtractionBounds.starburst(
                                    new ExtractionBounds.Rect(0, 0, 0, 0),
                                    width,
                                    height,
                                    pixels,
                                    48)
                            .empty());
            assertTrue(
                    ExtractionBounds.starburst(
                                    new ExtractionBounds.Rect(0, 0, 641, 361),
                                    width,
                                    height,
                                    pixels,
                                    48)
                            .full(outWidth, outHeight));
            var edge =
                    ExtractionBounds.starburst(
                            new ExtractionBounds.Rect(640, 360, 1, 1), width, height, pixels, 48);
            assertCell(edge, outWidth - 1, outHeight - 1);
            assertTrue(edge.width() < outWidth && edge.height() < outHeight);
        }
        assertTrue(
                ExtractionBounds.starburst(new ExtractionBounds.Rect(-1, 0, 2, 2), 1281, 721, 4, 48)
                        .full(321, 181));
        assertTrue(ExtractionBounds.starburst(null, 1281, 721, 4, 48).full(321, 181));
        assertTrue(
                ExtractionBounds.starburst(
                                new ExtractionBounds.Rect(1, 1, 2, 2), 1281, 721, 4, Float.NaN)
                        .full(321, 181));
        assertTrue(
                ExtractionBounds.starburst(
                                new ExtractionBounds.Rect(1, 1, 2, 2),
                                1281,
                                721,
                                4,
                                Float.MAX_VALUE)
                        .full(321, 181));
        assertTrue(
                ExtractionBounds.starburst(new ExtractionBounds.Rect(1, 1, 2, 2), 1281, 721, 5, 48)
                        .full(257, 145));
        // I check arithmetic beyond practical GPU sizes to catch integer overflow.
        var large =
                ExtractionBounds.starburst(
                        new ExtractionBounds.Rect(1_073_741_823, 1_073_741_823, 1, 1),
                        Integer.MAX_VALUE,
                        Integer.MAX_VALUE,
                        4,
                        48);
        assertCell(large, 536_870_911, 536_870_911);
    }

    @Test
    void starburstScissorsContainBruteForceReductionAndBilinearRaySupport() {
        var random = new Random(170017);
        int checked = 0, partial = 0;
        for (int pixels : new int[] {2, 3, 4})
            for (int trial = 0; trial < 160; trial++) {
                int width = 31 + random.nextInt(230), height = 29 + random.nextInt(150);
                int halfWidth = ceilDiv(width, 2), halfHeight = ceilDiv(height, 2);
                int x =
                        trial % 4 == 0
                                ? 0
                                : trial % 4 == 1 ? halfWidth - 1 : random.nextInt(halfWidth);
                int y =
                        trial % 4 == 0
                                ? 0
                                : trial % 4 == 1 ? halfHeight - 1 : random.nextInt(halfHeight);
                int rw = 1 + random.nextInt(Math.min(6, halfWidth - x)),
                        rh = 1 + random.nextInt(Math.min(6, halfHeight - y));
                var extraction = new ExtractionBounds.Rect(x, y, rw, rh);
                float glowWidth =
                        trial % 3 == 0 ? 8 : trial % 3 == 1 ? 128 : 8 + random.nextFloat() * 120;
                var rect = ExtractionBounds.starburst(extraction, width, height, pixels, glowWidth);
                boolean[][] support = new boolean[ceilDiv(height, pixels)][ceilDiv(width, pixels)];
                for (int yy = y * 2; yy < Math.min(height, (y + rh) * 2); yy++)
                    for (int xx = x * 2; xx < Math.min(width, (x + rw) * 2); xx++)
                        support[yy / pixels][xx / pixels] = true;
                assertEquals(ceilDiv(height, pixels), support.length);
                assertEquals(ceilDiv(width, pixels), support[0].length);
                if (!rect.full(support[0].length, support.length)) partial++;
                float extent = Math.max(1, glowWidth / (2 * pixels));
                for (int yy = 0; yy < support.length; yy++)
                    for (int xx = 0; xx < support[0].length; xx++) {
                        boolean lit = support[yy][xx];
                        for (int i = 1; !lit && i <= Math.ceil(extent); i++)
                            for (int axis = 0; !lit && axis < 4; axis++) {
                                float dx = axis == 0 ? 1 : axis == 1 ? 0 : .70710678f;
                                float dy =
                                        axis == 0
                                                ? 0
                                                : axis == 1
                                                        ? 1
                                                        : axis == 2 ? .70710678f : -.70710678f;
                                lit =
                                        sampled(support, xx + .5f + dx * i, yy + .5f + dy * i)
                                                || sampled(
                                                        support,
                                                        xx + .5f - dx * i,
                                                        yy + .5f - dy * i);
                            }
                        if (lit) {
                            assertCell(rect, xx, yy);
                            checked++;
                        }
                    }
            }
        assertTrue(partial > 200, "Exercise actual sparse ray regions");
        assertTrue(checked > 10000, "Exercise nonzero convolution support");
    }

    private static boolean sampled(boolean[][] input, float x, float y) {
        int width = input[0].length, height = input.length;
        // I include interpolation neighbors even when an edge weight rounds to zero.
        int sx = (int) Math.floor((x / width) * width - .5f),
                sy = (int) Math.floor((y / height) * height - .5f);
        return input[Math.clamp(sy, 0, height - 1)][Math.clamp(sx, 0, width - 1)]
                || input[Math.clamp(sy + 1, 0, height - 1)][Math.clamp(sx, 0, width - 1)]
                || input[Math.clamp(sy, 0, height - 1)][Math.clamp(sx + 1, 0, width - 1)]
                || input[Math.clamp(sy + 1, 0, height - 1)][Math.clamp(sx + 1, 0, width - 1)];
    }

    private static int ceilDiv(int size, int divisor) {
        return (size + divisor - 1) / divisor;
    }

    private static Matrix4f perspective(int width, int height) {
        return new Matrix4f()
                .perspective((float) Math.toRadians(70), (float) width / height, .05f, 256);
    }

    private static ExtractionBounds.Rect project(
            List<SourceBounds> bounds, Matrix4f matrix, int width, int height) {
        return ExtractionBounds.project(bounds, matrix, 0, 0, 0, width, height);
    }

    private static void assertCell(ExtractionBounds.Rect rect, int x, int y) {
        assertTrue(
                x >= rect.x()
                        && x < rect.x() + rect.width()
                        && y >= rect.y()
                        && y < rect.y() + rect.height(),
                () -> rect + " does not contain " + x + "," + y);
    }

    private static void assertContains(ExtractionBounds.Rect outer, ExtractionBounds.Rect inner) {
        assertCell(outer, inner.x(), inner.y());
        assertCell(outer, inner.x() + inner.width() - 1, inner.y() + inner.height() - 1);
    }
}
