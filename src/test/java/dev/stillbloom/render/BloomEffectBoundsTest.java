package dev.stillbloom.render;

import static org.junit.jupiter.api.Assertions.*;

import dev.stillbloom.render.source.ExtractionBounds;

import org.junit.jupiter.api.Test;

import java.util.Random;

class BloomEffectBoundsTest {
    @Test
    void reductionsAndFourBlurPassesContainEverySampleDependency() {
        var random = new Random(205001);
        int partial = 0, dependencies = 0;
        for (int pixels : new int[] {2, 3, 4})
            for (boolean diamond : new boolean[] {false, true})
                for (int trial = 0; trial < 28; trial++) {
                    int width = 101 + random.nextInt(150), height = 71 + random.nextInt(110);
                    boolean[][] input = new boolean[ceilDiv(height, 2)][ceilDiv(width, 2)];
                    var extraction = paint(input, random, trial);
                    boolean[][] grid = pixels == 3 ? threeGrid(input, width, height) : input;
                    if (pixels == 6) {
                        grid = reduce(grid, 3);
                        dependencies +=
                                contains(
                                        bounds(
                                                extraction,
                                                width,
                                                height,
                                                6,
                                                48,
                                                diamond,
                                                64,
                                                false,
                                                BloomPass.Bounds.Stage.REDUCTION),
                                        grid);
                    } else
                        for (int factor = 4; factor <= pixels; factor *= 2) {
                            grid = reduce(grid, 2);
                            dependencies +=
                                    contains(
                                            bounds(
                                                    extraction,
                                                    width,
                                                    height,
                                                    factor,
                                                    48,
                                                    diamond,
                                                    64,
                                                    false,
                                                    BloomPass.Bounds.Stage.REDUCTION),
                                            grid);
                        }
                    float glowWidth =
                            trial % 3 == 0
                                    ? 8
                                    : trial % 3 == 1 ? 128 : 8 + random.nextFloat() * 120;
                    float extent = Math.max(1, glowWidth / (2 * pixels));
                    float dx = diamond ? .70710678f : 1, dy = diamond ? .70710678f : 0;
                    var nearX = blur(grid, dx, dy, extent);
                    var near = blur(nearX, dy, diamond ? -dx : dx, extent);
                    var wideX = blur(near, dx, dy, extent);
                    var wide = blur(wideX, dy, diamond ? -dx : dx, extent);
                    var stages =
                            new BloomPass.Bounds.Stage[] {
                                BloomPass.Bounds.Stage.NEAR_X,
                                BloomPass.Bounds.Stage.NEAR,
                                BloomPass.Bounds.Stage.WIDE_X,
                                BloomPass.Bounds.Stage.WIDE
                            };
                    boolean[][][] images = {nearX, near, wideX, wide};
                    for (int i = 0; i < stages.length; i++) {
                        var rect =
                                bounds(
                                        extraction,
                                        width,
                                        height,
                                        pixels,
                                        glowWidth,
                                        diamond,
                                        64,
                                        false,
                                        stages[i]);
                        dependencies += contains(rect, images[i]);
                        if (!rect.full(grid[0].length, grid.length)) partial++;
                    }
                }
        assertTrue(
                partial > 300, "Exercise sparse filter regions as well as conservative full draws");
        assertTrue(dependencies > 100_000, "Check actual filter dependency cells");
    }

    @Test
    void flareAndStarburstContainClampedAndDiagonalDependencies() {
        var random = new Random(205002);
        int partial = 0, dependencies = 0;
        for (int pixels : new int[] {2, 3, 4})
            for (int trial = 0; trial < 45; trial++) {
                int width = 111 + random.nextInt(120), height = 71 + random.nextInt(100);
                boolean[][] input = new boolean[ceilDiv(height, 2)][ceilDiv(width, 2)];
                var extraction = paint(input, random, trial);
                boolean[][] grid = pixels == 3 ? threeGrid(input, width, height) : input;
                if (pixels == 6) grid = reduce(grid, 3);
                else for (int factor = 4; factor <= pixels; factor *= 2) grid = reduce(grid, 2);
                float widthSetting = trial % 2 == 0 ? 8 : 8 + random.nextFloat() * 120;
                float flareLength = trial % 2 == 0 ? 128 : 8 + random.nextFloat() * 120;
                for (boolean cross : new boolean[] {false, true}) {
                    var rect =
                            bounds(
                                    extraction,
                                    width,
                                    height,
                                    pixels,
                                    widthSetting,
                                    false,
                                    flareLength,
                                    cross,
                                    BloomPass.Bounds.Stage.FLARE);
                    dependencies += contains(rect, flare(grid, flareLength / pixels, cross));
                    if (!rect.full(grid[0].length, grid.length)) partial++;
                }
                var rect =
                        bounds(
                                extraction,
                                width,
                                height,
                                pixels,
                                widthSetting,
                                false,
                                flareLength,
                                false,
                                BloomPass.Bounds.Stage.STARBURST);
                dependencies +=
                        contains(rect, starburst(grid, Math.max(1, widthSetting / (2 * pixels))));
                if (!rect.full(grid[0].length, grid.length)) partial++;
            }
        assertTrue(partial > 250);
        assertTrue(
                dependencies > 10_000,
                "Check actual ray and streak dependency cells: " + dependencies);
    }

    // I check every screen pixel against an independent 3px grid so a rounded source cell can't
    // hide gaps.
    private static boolean[][] threeGrid(boolean[][] half, int width, int height) {
        var grid = new boolean[ceilDiv(height, 3)][ceilDiv(width, 3)];
        for (int y = 0; y < height; y++)
            for (int x = 0; x < width; x++) if (half[y / 2][x / 2]) grid[y / 3][x / 3] = true;
        return grid;
    }

    @Test
    void stableReductionsCannotCreateSupportFromPreviousHistory() {
        for (float weight : new float[] {0, .5f, 1}) {
            assertEquals(0, BrightnessHistory.brightness(0, 10, weight));
            assertEquals(1, BrightnessHistory.brightness(1, 0, weight));
        }
        // I expect zero emission to match a clear, with opaque normal alpha and zero distance mass.
        for (String stage :
                new String[] {
                    "extract",
                    "extract_auto",
                    "downsample",
                    "downsample_stable",
                    "blur",
                    "starburst",
                    "flare"
                }) {
            assertEquals(1, BloomPass.Bounds.clearAlpha("post/" + stage));
            assertEquals(0, BloomPass.Bounds.clearAlpha("post/" + stage + "_distance"));
        }
    }

    @Test
    void invalidUnknownAndEmptyBoundsHaveSafeFallbacks() {
        var empty = new ExtractionBounds.Rect(0, 0, 0, 0);
        var small = new ExtractionBounds.Rect(40, 20, 2, 2);
        for (var stage : BloomPass.Bounds.Stage.values()) {
            assertTrue(bounds(empty, 1281, 721, 4, 48, false, 64, true, stage).empty());
            assertTrue(bounds(null, 1281, 721, 4, 48, false, 64, true, stage).full(321, 181));
            assertTrue(
                    bounds(
                                    new ExtractionBounds.Rect(-1, 0, 1, 1),
                                    1281,
                                    721,
                                    4,
                                    48,
                                    false,
                                    64,
                                    true,
                                    stage)
                            .full(321, 181));
            assertTrue(bounds(small, 1281, 721, 5, 48, false, 64, true, stage).full(257, 145));
        }
        assertTrue(
                bounds(
                                small,
                                1281,
                                721,
                                4,
                                Float.NaN,
                                false,
                                64,
                                false,
                                BloomPass.Bounds.Stage.NEAR)
                        .full(321, 181));
        assertTrue(
                bounds(
                                small,
                                1281,
                                721,
                                4,
                                Float.MAX_VALUE,
                                true,
                                64,
                                false,
                                BloomPass.Bounds.Stage.WIDE)
                        .full(321, 181));
        assertTrue(
                bounds(
                                small,
                                1281,
                                721,
                                4,
                                48,
                                false,
                                Float.NaN,
                                true,
                                BloomPass.Bounds.Stage.FLARE)
                        .full(321, 181));
        assertThrows(
                IllegalArgumentException.class,
                () -> bounds(small, 0, 721, 4, 48, false, 64, false, BloomPass.Bounds.Stage.NEAR));
    }

    @Test
    void recognizedStagesRequireTheMatchingShaderAndTarget() {
        for (String suffix : new String[] {"", "_distance"}) {
            for (String shader : new String[] {"downsample", "downsample_stable"})
                for (int factor : new int[] {4})
                    assertEquals(
                            BloomPass.Bounds.Stage.REDUCTION,
                            BloomPass.stage("post/" + shader + suffix, "down_" + factor, factor));
            assertEquals(
                    BloomPass.Bounds.Stage.NEAR_X,
                    BloomPass.stage("post/blur" + suffix, "near_x", 0));
            assertEquals(
                    BloomPass.Bounds.Stage.NEAR, BloomPass.stage("post/blur" + suffix, "near", 0));
            assertEquals(
                    BloomPass.Bounds.Stage.WIDE_X,
                    BloomPass.stage("post/blur" + suffix, "wide_x", 0));
            assertEquals(
                    BloomPass.Bounds.Stage.WIDE, BloomPass.stage("post/blur" + suffix, "wide", 0));
            assertEquals(
                    BloomPass.Bounds.Stage.STARBURST,
                    BloomPass.stage("post/starburst" + suffix, "near", 0));
            assertEquals(
                    BloomPass.Bounds.Stage.FLARE,
                    BloomPass.stage("post/flare" + suffix, "flare", 0));
            assertNull(BloomPass.stage("post/downsample" + suffix, "down_6", 6));
            assertNull(BloomPass.stage("post/downsample" + suffix, "down_2", 2));
            assertNull(BloomPass.stage("post/blur" + suffix, "composite", 0));
            assertNull(BloomPass.stage("post/starburst" + suffix, "wide", 0));
            assertNull(BloomPass.stage("post/flare" + suffix, "near", 0));
            assertNull(BloomPass.stage("post/composite" + suffix, "composite", 0));
        }
        assertNull(BloomPass.stage("post/unknown", "near", 0));
        assertNull(BloomPass.stage("post/blur_distance_distance", "near", 0));
    }

    private static ExtractionBounds.Rect bounds(
            ExtractionBounds.Rect extraction,
            int width,
            int height,
            int pixels,
            float glowWidth,
            boolean diamond,
            float flareLength,
            boolean cross,
            BloomPass.Bounds.Stage stage) {
        return BloomPass.Bounds.support(
                extraction, width, height, pixels, glowWidth, diamond, flareLength, cross, stage);
    }

    private static ExtractionBounds.Rect paint(boolean[][] image, Random random, int trial) {
        int width = image[0].length, height = image.length;
        int x = trial % 4 == 0 ? 0 : trial % 4 == 1 ? width - 1 : random.nextInt(width);
        int y = trial % 4 == 0 ? 0 : trial % 4 == 1 ? height - 1 : random.nextInt(height);
        int rw = 1 + random.nextInt(Math.min(5, width - x)),
                rh = 1 + random.nextInt(Math.min(5, height - y));
        for (int yy = y; yy < y + rh; yy++)
            for (int xx = x; xx < x + rw; xx++) image[yy][xx] = true;
        return new ExtractionBounds.Rect(x, y, rw, rh);
    }

    private static boolean[][] reduce(boolean[][] input, int factor) {
        int width = input[0].length, height = input.length;
        boolean[][] output = new boolean[ceilDiv(height, factor)][ceilDiv(width, factor)];
        for (int y = 0; y < output.length; y++)
            for (int x = 0; x < output[0].length; x++)
                for (int dy = 0; dy < factor; dy++)
                    for (int dx = 0; dx < factor; dx++)
                        output[y][x] |=
                                input[Math.min(y * factor + dy, height - 1)][
                                        Math.min(x * factor + dx, width - 1)];
        return output;
    }

    private static boolean[][] blur(boolean[][] input, float dx, float dy, float extent) {
        boolean[][] output = new boolean[input.length][input[0].length];
        int radius = (int) Math.ceil(extent);
        for (int y = 0; y < output.length; y++)
            for (int x = 0; x < output[0].length; x++)
                for (int i = -radius; i <= radius; i++)
                    output[y][x] |= linear(input, x + .5f + dx * i, y + .5f + dy * i);
        return output;
    }

    private static boolean[][] flare(boolean[][] input, float extent, boolean cross) {
        boolean[][] output = new boolean[input.length][input[0].length];
        int radius = (int) Math.ceil(extent);
        for (int y = 0; y < output.length; y++)
            for (int x = 0; x < output[0].length; x++)
                for (int i = -radius; i <= radius; i++) {
                    output[y][x] |= fetch(input, x + i, y);
                    if (cross) output[y][x] |= fetch(input, x, y + i);
                }
        return output;
    }

    private static boolean[][] starburst(boolean[][] input, float extent) {
        boolean[][] output = new boolean[input.length][input[0].length];
        int radius = (int) Math.ceil(extent);
        for (int y = 0; y < output.length; y++)
            for (int x = 0; x < output[0].length; x++) {
                output[y][x] = linear(input, x + .5f, y + .5f);
                for (int i = 1; i <= radius; i++)
                    for (int axis = 0; axis < 4; axis++) {
                        float dx = axis == 0 ? 1 : axis == 1 ? 0 : .70710678f;
                        float dy =
                                axis == 0
                                        ? 0
                                        : axis == 1 ? 1 : axis == 2 ? .70710678f : -.70710678f;
                        output[y][x] |=
                                linear(input, x + .5f + dx * i, y + .5f + dy * i)
                                        || linear(input, x + .5f - dx * i, y + .5f - dy * i);
                    }
            }
        return output;
    }

    private static boolean linear(boolean[][] input, float x, float y) {
        int sx = (int) Math.floor((x / input[0].length) * input[0].length - .5f);
        int sy = (int) Math.floor((y / input.length) * input.length - .5f);
        return fetch(input, sx, sy)
                || fetch(input, sx + 1, sy)
                || fetch(input, sx, sy + 1)
                || fetch(input, sx + 1, sy + 1);
    }

    private static boolean fetch(boolean[][] image, int x, int y) {
        return image[Math.clamp(y, 0, image.length - 1)][Math.clamp(x, 0, image[0].length - 1)];
    }

    private static int contains(ExtractionBounds.Rect bounds, boolean[][] image) {
        int count = 0;
        for (int y = 0; y < image.length; y++)
            for (int x = 0; x < image[0].length; x++)
                if (image[y][x]) {
                    int px = x, py = y;
                    assertTrue(
                            x >= bounds.x()
                                    && y >= bounds.y()
                                    && x < bounds.x() + bounds.width()
                                    && y < bounds.y() + bounds.height(),
                            () -> bounds + " misses dependency " + px + "," + py);
                    count++;
                }
        return count;
    }

    private static int ceilDiv(int size, int factor) {
        return size / factor + (size % factor == 0 ? 0 : 1);
    }
}
