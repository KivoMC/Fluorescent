package dev.stillbloom.render.source;

import static org.junit.jupiter.api.Assertions.*;

import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

class SourceClustersTest {
    @Test
    void sourcesAcrossEmptySectionSpaceKeepTheirOwnExtractionBoxes() {
        var bounds = new SourceBounds.Clusters(0, 0, -16);
        bounds.add(2, 1, -15);
        bounds.add(3, 2, -14);
        bounds.add(14, 10, -3);
        assertEquals(new SourceBounds(2, 1, -15, 15, 11, -2), bounds.coarse());
        assertEquals(
                List.of(
                        new SourceBounds(2, 1, -15, 4, 3, -13),
                        new SourceBounds(14, 10, -3, 15, 11, -2)),
                bounds.extraction());
        assertThrows(UnsupportedOperationException.class, () -> bounds.extraction().clear());
    }

    @Test
    void denseSectionsKeepOneProjectionWithoutLosingLateSources() {
        var bounds = new SourceBounds.Clusters(-16, -32, 16);
        for (int y = -32; y < -16; y++)
            for (int z = 16; z < 32; z++) for (int x = -16; x < 0; x++) bounds.add(x, y, z);
        assertEquals(new SourceBounds(-16, -32, 16, 0, -16, 32), bounds.coarse());
        assertEquals(List.of(bounds.coarse()), bounds.extraction());
    }

    @Test
    void emptyAndSingleClusterHaveNoUnnecessaryExtraBounds() {
        var bounds = new SourceBounds.Clusters(0, 0, 0);
        assertTrue(bounds.extraction().isEmpty());
        assertThrows(IllegalStateException.class, bounds::coarse);
        assertThrows(IllegalArgumentException.class, () -> bounds.add(16, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> bounds.add(0, -1, 0));
        bounds.add(0, 0, 0);
        bounds.add(3, 3, 3);
        assertEquals(List.of(bounds.coarse()), bounds.extraction());
    }

    @Test
    void walkingThroughTheEmptyGapDoesNotForceTheWholeScreen() {
        int width = 1921, height = 1009;
        var matrix =
                new Matrix4f()
                        .perspective((float) Math.toRadians(70), (float) width / height, .05f, 256);
        var bounds = new SourceBounds.Clusters(0, 0, -16);
        bounds.add(8, 8, -15);
        bounds.add(8, 8, -1);
        var coarse =
                ExtractionBounds.project(
                        List.of(bounds.coarse()), matrix, 8.5, 8.5, -8, width, height);
        var clustered =
                ExtractionBounds.project(bounds.extraction(), matrix, 8.5, 8.5, -8, width, height);
        assertTrue(coarse.full((width + 1) / 2, (height + 1) / 2));
        assertFalse(clustered.empty());
        assertFalse(clustered.full((width + 1) / 2, (height + 1) / 2));
        assertTrue(
                (long) clustered.width() * clustered.height()
                        < (long) coarse.width() * coarse.height() / 4,
                "The camera lies between separated lights, not inside either padded source");
        // I check that entering a padded light box still keeps the full-screen safety fallback.
        assertTrue(
                ExtractionBounds.project(bounds.extraction(), matrix, 8.5, 8.5, -14, width, height)
                        .full((width + 1) / 2, (height + 1) / 2));
    }

    @Test
    void randomizedClustersCoverEveryIndividuallyPaddedSource() {
        var random = new Random(302604);
        int partial = 0;
        for (int trial = 0; trial < 1200; trial++) {
            int offset = trial % 3 == 0 ? 30_000_000 : trial % 3 == 1 ? -30_000_000 : 0;
            int sx = offset + 16 * (random.nextInt(7) - 3),
                    sy = 16 * (random.nextInt(5) - 2),
                    sz = offset - 16 * (1 + random.nextInt(4));
            var clustered = new SourceBounds.Clusters(sx, sy, sz);
            var blocks = new ArrayList<SourceBounds>();
            int n = 1 + random.nextInt(trial % 2 == 0 ? 8 : 100);
            for (int i = 0; i < n; i++) {
                int x = sx + random.nextInt(16),
                        y = sy + random.nextInt(16),
                        z = sz + random.nextInt(16);
                clustered.add(x, y, z);
                blocks.add(new SourceBounds(x, y, z, x + 1, y + 1, z + 1));
            }
            var pieces = clustered.extraction();
            assertTrue(pieces.size() >= 1 && pieces.size() <= 8);
            var coarse = clustered.coarse();
            for (var block : blocks) {
                assertTrue(contains(coarse, block));
                assertTrue(pieces.stream().anyMatch(piece -> contains(piece, block)));
            }
            int width = 319 + random.nextInt(1700), height = 239 + random.nextInt(1000);
            var matrix =
                    new Matrix4f()
                            .perspective(
                                    (float) Math.toRadians(35 + random.nextDouble() * 85),
                                    (float) width / height,
                                    .01f,
                                    256)
                            .rotateX((random.nextFloat() - .5f) * .8f)
                            .rotateY((random.nextFloat() - .5f) * 2);
            double cx = offset + random.nextDouble() * 8,
                    cy = random.nextDouble() * 8,
                    cz = offset + random.nextDouble() * 8;
            var actual = ExtractionBounds.project(pieces, matrix, cx, cy, cz, width, height);
            var required = ExtractionBounds.project(blocks, matrix, cx, cy, cz, width, height);
            if (!actual.empty() && !actual.full((width + 1) / 2, (height + 1) / 2)) partial++;
            if (!required.empty()) assertContains(actual, required);
        }
        assertTrue(
                partial > 100,
                "Exercise partial rectangles across rotated views and world offsets");
    }

    private static boolean contains(SourceBounds outer, SourceBounds inner) {
        return outer.minX() <= inner.minX()
                && outer.minY() <= inner.minY()
                && outer.minZ() <= inner.minZ()
                && outer.maxX() >= inner.maxX()
                && outer.maxY() >= inner.maxY()
                && outer.maxZ() >= inner.maxZ();
    }

    private static void assertContains(ExtractionBounds.Rect outer, ExtractionBounds.Rect inner) {
        assertTrue(
                outer.x() <= inner.x()
                        && outer.y() <= inner.y()
                        && outer.x() + outer.width() >= inner.x() + inner.width()
                        && outer.y() + outer.height() >= inner.y() + inner.height(),
                () -> outer + " does not contain " + inner);
    }
}
