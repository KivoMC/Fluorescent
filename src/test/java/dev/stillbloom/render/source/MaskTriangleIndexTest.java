package dev.stillbloom.render.source;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.*;

class MaskTriangleIndexTest {
    @Test
    void acceptedPlaneAndBarycentricTolerancePointsRemainCandidates() {
        var r = new Random(2471);
        int checked = 0;
        for (int triangle = 0; triangle < 500; triangle++) {
            float[] a = {
                r.nextFloat() * 1.4f - .2f, r.nextFloat() * 1.4f - .2f, r.nextFloat() * 1.4f - .2f
            };
            float[] b = {
                r.nextFloat() * 1.4f - .2f, r.nextFloat() * 1.4f - .2f, r.nextFloat() * 1.4f - .2f
            };
            float[] c = {
                r.nextFloat() * 1.4f - .2f, r.nextFloat() * 1.4f - .2f, r.nextFloat() * 1.4f - .2f
            };
            var bound =
                    TextureGeometry.TriangleIndex.bounds(
                            a[0], a[1], a[2], b[0], b[1], b[2], c[0], c[1], c[2]);
            var cells = TextureGeometry.TriangleIndex.build(List.of(bound));
            double[] e = {b[0] - a[0], b[1] - a[1], b[2] - a[2]},
                    f = {c[0] - a[0], c[1] - a[1], c[2] - a[2]};
            double[] n = {
                e[1] * f[2] - e[2] * f[1], e[2] * f[0] - e[0] * f[2], e[0] * f[1] - e[1] * f[0]
            };
            double length = Math.sqrt(n[0] * n[0] + n[1] * n[1] + n[2] * n[2]);
            if (length < 1e-8) continue;
            for (int sample = 0; sample < 1000; sample++) {
                double u =
                        sample < 3
                                ? new double[] {-.001, 1.002, -.001}[sample]
                                : r.nextDouble() * 1.003 - .001;
                double v =
                        sample < 3
                                ? new double[] {-.001, -.001, 1.002}[sample]
                                : r.nextDouble() * (1.002 - u) - .001;
                double distance = sample % 2 == 0 ? .006 : -.006;
                int x =
                        TextureGeometry.TriangleIndex.cell(
                                a[0] + u * e[0] + v * f[0] + distance * n[0] / length);
                int y =
                        TextureGeometry.TriangleIndex.cell(
                                a[1] + u * e[1] + v * f[1] + distance * n[1] / length);
                int z =
                        TextureGeometry.TriangleIndex.cell(
                                a[2] + u * e[2] + v * f[2] + distance * n[2] / length);
                assertArrayEquals(new int[] {0}, cells[x + 8 * (y + 8 * z)]);
                checked++;
            }
        }
        assertEquals(500_000, checked);
    }

    @Test
    void boundariesAndSlightlyOutsideVoxelCoordinatesAreConservative() {
        for (float plane : new float[] {-.003f, 0, .25f, .5f, .75f, 1, 1.003f}) {
            var cells =
                    TextureGeometry.TriangleIndex.build(
                            List.of(
                                    TextureGeometry.TriangleIndex.bounds(
                                            plane, 0, 0, plane, 1, 0, plane, 0, 1)));
            for (double shift : new double[] {-.006, 0, .006})
                assertArrayEquals(
                        new int[] {0},
                        cells[TextureGeometry.TriangleIndex.cell(plane + shift) + 8 * (1 + 8)]);
        }
    }

    @Test
    void coincidentTrianglesKeepOriginalTieOrderAndDistantCellsPrune() {
        var b = TextureGeometry.TriangleIndex.bounds(0, 0, 0, .2f, 0, 0, 0, .2f, 0);
        var cells = TextureGeometry.TriangleIndex.build(List.of(b, b, b));
        assertArrayEquals(new int[] {0, 1, 2}, cells[0]);
        assertEquals(0, cells[511].length);
    }

    @Test
    void illConditionedAndUnusualModelsFallBackToAllCells() {
        var tiny = TextureGeometry.TriangleIndex.bounds(0, 0, 0, .00001f, 0, 0, 0, .00001f, 0);
        var huge = TextureGeometry.TriangleIndex.bounds(-100, 0, 0, 100, 0, 0, 0, 100, 0);
        for (var cell : TextureGeometry.TriangleIndex.build(List.of(tiny, huge)))
            assertArrayEquals(new int[] {0, 1}, cell);
    }

    @Test
    void complexModelsRetainTrianglesBeyondTheOldLimitWithinBoundedCellLists() {
        var triangles = new ArrayList<TextureGeometry.TriangleIndex.Bounds>();
        for (int i = 0; i < 204; i++) {
            float x = (i % 6) / 6f, y = (i / 6 % 6) / 6f, z = (i / 36) / 6f;
            triangles.add(
                    TextureGeometry.TriangleIndex.bounds(x, y, z, x + .08f, y, z, x, y + .08f, z));
        }
        var cells = TextureGeometry.TriangleIndex.build(triangles);
        assertTrue(TextureGeometry.TriangleIndex.withinShaderLimit(cells));
        var found = new BitSet();
        for (var cell : cells) for (int index : cell) found.set(index);
        assertEquals(204, found.cardinality());
        assertTrue(found.get(203));
    }

    @Test
    void denseCellCannotSilentlyTruncateTheLastTriangles() {
        var triangle = TextureGeometry.TriangleIndex.bounds(0, 0, 0, .2f, 0, 0, 0, .2f, 0);
        var atLimit = TextureGeometry.TriangleIndex.build(Collections.nCopies(128, triangle));
        var tooDense = TextureGeometry.TriangleIndex.build(Collections.nCopies(129, triangle));
        assertTrue(TextureGeometry.TriangleIndex.withinShaderLimit(atLimit));
        assertFalse(TextureGeometry.TriangleIndex.withinShaderLimit(tooDense));
        assertEquals(128, tooDense[0][128]);
    }
}
