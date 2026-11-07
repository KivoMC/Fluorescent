package dev.stillbloom.render.source;

import static org.junit.jupiter.api.Assertions.*;

import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

class ExtractionFrustumTest {
    @Test
    void offscreenEyeCrossingsOnEverySideDoNotExpandVisibleSources() {
        int width = 1921, height = 1081;
        var matrix = perspective(width, height);
        var front = new SourceBounds(-1, -1, -24, 1, 1, -22);
        var sides =
                List.of(
                        new SourceBounds(-20, -1, -1, -18, 1, 1),
                        new SourceBounds(18, -1, -1, 20, 1, 1),
                        new SourceBounds(-1, -20, -1, 1, -18, 1),
                        new SourceBounds(-1, 18, -1, 1, 20, 1));
        var expected = project(List.of(front), matrix, 0, 0, 0, width, height);
        assertFalse(expected.empty());
        assertFalse(expected.full(961, 541));
        for (int offset : new int[] {0, 30_000_000, -30_000_000})
            for (var side : sides) {
                var translatedFront = translate(front, offset);
                var translatedSide = translate(side, offset);
                assertTrue(
                        project(
                                        List.of(translatedSide),
                                        matrix,
                                        offset,
                                        offset,
                                        offset,
                                        width,
                                        height)
                                .empty(),
                        side.toString());
                assertEquals(
                        expected,
                        project(
                                List.of(translatedFront, translatedSide),
                                matrix,
                                offset,
                                offset,
                                offset,
                                width,
                                height));
                assertEquals(
                        expected,
                        project(
                                List.of(translatedSide, translatedFront),
                                matrix,
                                offset,
                                offset,
                                offset,
                                width,
                                height));
            }
    }

    @Test
    void onscreenCrossingsAndBoxesSurroundingTheEyeRetainFullCoverage() {
        int width = 1921, height = 1081;
        for (float yaw : new float[] {0, .7f, -.9f, 1.7f})
            for (int offset : new int[] {0, 30_000_000, -30_000_000}) {
                var matrix = perspective(width, height).rotateY(yaw);
                for (var source :
                        List.of(
                                new SourceBounds(-1, -1, -1, 1, 1, 1),
                                new SourceBounds(-2, -2, -3, 2, 2, 0))) {
                    assertTrue(
                            project(
                                            List.of(translate(source, offset)),
                                            matrix,
                                            offset + .25,
                                            offset + .125,
                                            offset + .5,
                                            width,
                                            height)
                                    .full(961, 541));
                }
            }
    }

    @Test
    void expandedViewportBordersAreStrictAndRetainTheFourPixelMargin() {
        // I use W=-Z and this box to test exact boundary contact with the full four-pixel margin.
        var matrix = new Matrix4f().m23(-1).m33(0);
        var sides =
                List.of(
                        new SourceBounds(4, -1, -1, 5, 1, 1),
                        new SourceBounds(-5, -1, -1, -4, 1, 1),
                        new SourceBounds(-1, 4, -1, 1, 5, 1),
                        new SourceBounds(-1, -5, -1, 1, -4, 1));
        double[][] inward = {{.5, 0}, {-.5, 0}, {0, .5}, {0, -.5}};
        double[][] outward = {{-.0001, 0}, {.0001, 0}, {0, -.0001}, {0, .0001}};
        for (int i = 0; i < sides.size(); i++) {
            assertTrue(
                    project(List.of(sides.get(i)), matrix, 0, 0, 0, 16, 16).full(8, 8),
                    "A touching corner must stay");
            assertTrue(
                    project(List.of(sides.get(i)), matrix, inward[i][0], inward[i][1], 0, 16, 16)
                            .full(8, 8),
                    "Outside the viewport but within its extraction margin must stay");
            assertTrue(
                    project(List.of(sides.get(i)), matrix, outward[i][0], outward[i][1], 0, 16, 16)
                            .empty(),
                    "Every corner strictly outside the expanded plane can be skipped");
        }
    }

    @Test
    void oddViewportEdgesRemainCoveredWhenAnOffscreenCrossingIsPresent() {
        int width = 1281, height = 721;
        // I place the source on the last framebuffer row and column to cover odd edges.
        var matrix = new Matrix4f().m23(-1).m33(0);
        var front = new SourceBounds(9, 9, -11, 10, 10, -10);
        var crossing = new SourceBounds(-20, -1, -1, -18, 1, 1);
        var expected = project(List.of(front), matrix, 0, 0, 0, width, height);
        assertTrue(
                expected.x() + expected.width() == 641 && expected.y() + expected.height() == 361);
        assertEquals(expected, project(List.of(front, crossing), matrix, 0, 0, 0, width, height));
        for (int size : new int[] {2, 3, 4}) {
            var support = ExtractionBounds.starburst(expected, width, height, size, 64);
            assertTrue(support.x() + support.width() == (width + size - 1) / size);
            assertTrue(support.y() + support.height() == (height + size - 1) / size);
        }
    }

    @Test
    void nonfiniteClipCoordinatesCannotProveAnEyeCrossingIsOutside() {
        var crossing = new SourceBounds(-1, -1, -1, 1, 1, 1);
        var overflowX = new Matrix4f().m23(-1).m33(0).m00(Float.MAX_VALUE);
        var overflowY = new Matrix4f().m23(-1).m33(0).m11(Float.MAX_VALUE);
        assertTrue(
                project(List.of(crossing), overflowX, Double.MAX_VALUE, 0, 0, 1921, 1081)
                        .full(961, 541));
        assertTrue(
                project(List.of(crossing), overflowY, 0, Double.MAX_VALUE, 0, 1921, 1081)
                        .full(961, 541));
        assertTrue(
                project(List.of(crossing), new Matrix4f().m10(Float.NaN), 0, 0, 0, 1921, 1081)
                        .full(961, 541));
        assertTrue(
                project(
                                List.of(crossing),
                                perspective(1921, 1081),
                                Double.POSITIVE_INFINITY,
                                0,
                                0,
                                1921,
                                1081)
                        .full(961, 541));
    }

    @Test
    void randomizedRotatedCrossingsPreserveEveryVisiblePaddedSample() {
        var random = new Random(340126);
        int checked = 0, rejectedCrossings = 0;
        for (int trial = 0; trial < 2400; trial++) {
            int width = 319 + random.nextInt(1700), height = 239 + random.nextInt(1000);
            var matrix =
                    new Matrix4f()
                            .perspective(
                                    (float) Math.toRadians(30 + random.nextDouble() * 95),
                                    (float) width / height,
                                    .01f,
                                    256)
                            .rotateX((random.nextFloat() - .5f) * 1.5f)
                            .rotateY((random.nextFloat() - .5f) * 2.5f);
            int x = random.nextInt(49) - 24, y = random.nextInt(49) - 24, z = random.nextInt(7) - 3;
            var local =
                    new SourceBounds(
                            x,
                            y,
                            z,
                            x + 1 + random.nextInt(6),
                            y + 1 + random.nextInt(6),
                            z + 1 + random.nextInt(6));
            int offset = trial % 3 == 0 ? 30_000_000 : trial % 3 == 1 ? -30_000_000 : 0;
            var source = translate(local, offset);
            double cx = random.nextDouble(), cy = random.nextDouble(), cz = random.nextDouble();
            var rect =
                    project(
                            List.of(source),
                            matrix,
                            offset + cx,
                            offset + cy,
                            offset + cz,
                            width,
                            height);
            assertEquals(
                    project(List.of(local), matrix, cx, cy, cz, width, height),
                    rect,
                    "Large world translations must not alter bounds");
            float minW = Float.POSITIVE_INFINITY, maxW = Float.NEGATIVE_INFINITY;
            for (int corner = 0; corner < 8; corner++) {
                var p =
                        matrix.transform(
                                new Vector4f(
                                        (float)
                                                (((corner & 1) == 0 ? x - 1 : local.maxX() + 1)
                                                        - cx),
                                        (float)
                                                (((corner & 2) == 0 ? y - 1 : local.maxY() + 1)
                                                        - cy),
                                        (float)
                                                (((corner & 4) == 0 ? z - 1 : local.maxZ() + 1)
                                                        - cz),
                                        1));
                minW = Math.min(minW, p.w);
                maxW = Math.max(maxW, p.w);
            }
            if (minW < 0 && maxW > 0 && rect.empty()) rejectedCrossings++;
            for (int sample = 0; sample < 200; sample++) {
                // I test the padded block volume, not just its center.
                var p =
                        matrix.transform(
                                new Vector4f(
                                        (float)
                                                (x
                                                        - 1
                                                        + random.nextDouble()
                                                                * (local.maxX() - x + 2)
                                                        - cx),
                                        (float)
                                                (y
                                                        - 1
                                                        + random.nextDouble()
                                                                * (local.maxY() - y + 2)
                                                        - cy),
                                        (float)
                                                (z
                                                        - 1
                                                        + random.nextDouble()
                                                                * (local.maxZ() - z + 2)
                                                        - cz),
                                        1));
                if (p.w <= 0) continue;
                double sx = (p.x / p.w * .5 + .5) * width, sy = (p.y / p.w * .5 + .5) * height;
                if (sx < 0 || sx >= width || sy < 0 || sy >= height) continue;
                int cellX = (int) sx / 2, cellY = (int) sy / 2;
                assertTrue(
                        cellX >= rect.x()
                                && cellY >= rect.y()
                                && cellX < rect.x() + rect.width()
                                && cellY < rect.y() + rect.height(),
                        () -> "Visible padded sample omitted by " + rect);
                checked++;
            }
        }
        assertTrue(
                rejectedCrossings > 100,
                "Exercise crossing rejection rather than only front/behind sources");
        assertTrue(checked > 1000, "Exercise visible samples across rotated views");
    }

    private static Matrix4f perspective(int width, int height) {
        return new Matrix4f()
                .perspective((float) Math.toRadians(70), (float) width / height, .05f, 256);
    }

    private static SourceBounds translate(SourceBounds b, int offset) {
        return new SourceBounds(
                b.minX() + offset,
                b.minY() + offset,
                b.minZ() + offset,
                b.maxX() + offset,
                b.maxY() + offset,
                b.maxZ() + offset);
    }

    private static ExtractionBounds.Rect project(
            List<SourceBounds> sources,
            Matrix4f matrix,
            double x,
            double y,
            double z,
            int w,
            int h) {
        return ExtractionBounds.project(sources, matrix, x, y, z, w, h);
    }
}
