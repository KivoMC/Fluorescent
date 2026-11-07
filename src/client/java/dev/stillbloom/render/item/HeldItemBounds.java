package dev.stillbloom.render.item;

import dev.stillbloom.config.BloomConfig;

final class HeldItemBounds {
    private static final double MIN_W = 0.000001;
    private static final double DIAGONAL = 0.70710678;
    private static final Rect EMPTY = new Rect(0, 0, 0, 0);

    // I store valid copy bounds from the lower-left corner, separately from rounded allocation
    // sizes.
    record Rect(int x, int y, int width, int height) {
        boolean empty() {
            return width == 0 || height == 0;
        }

        boolean full(int width, int height) {
            return x == 0 && y == 0 && this.width == width && this.height == height;
        }
    }

    // I anchor both grids to the framebuffer origin even when the item crop moves.
    static int gridAlignment(BloomConfig config) {
        if (config == null || config.pixelSize < 1 || config.farPixelSize < 1)
            throw new IllegalArgumentException("Invalid pixel grid");
        return config.usesDistancePixels()
                ? lcm(config.pixelSize, config.finePixelSize())
                : config.pixelSize;
    }

    // I align allocation sizes with the pixel grid, including size 3.
    static int allocationAlignment(BloomConfig config) {
        return lcm(64, gridAlignment(config));
    }

    // I may round storage past the framebuffer edge, but copy only the valid rectangle.
    static int allocationSize(int validSize, BloomConfig config) {
        if (validSize < 0) throw new IllegalArgumentException("Negative crop size");
        int alignment = allocationAlignment(config);
        return Math.toIntExact(((long) validSize + alignment - 1) / alignment * alignment);
    }

    // I leave one spare aligned bucket so small equip movements don't keep resizing the target.
    static int grownExtent(int capacity, int required, int screenExtent, BloomConfig config) {
        int aligned = allocationSize(required, config);
        if (capacity == 0) return aligned;
        if (aligned <= capacity) return capacity;
        int limit = allocationSize(screenExtent, config);
        return (int) Math.min(limit, (long) aligned + allocationAlignment(config));
    }

    static int shrunkExtent(int capacity, int required, BloomConfig config) {
        return (int)
                Math.min(
                        capacity,
                        (long) allocationSize(required, config) + allocationAlignment(config));
    }

    // I allow for recent movement and leave the same spare room when shrinking as when growing.
    static final class ShrinkWindow {
        private long started;
        private boolean tracking;
        int peakWidth, peakHeight;

        boolean ready(int width, int height, int capacityWidth, int capacityHeight, long now) {
            if ((long) width * height * 2 >= (long) capacityWidth * capacityHeight) {
                reset();
                return false;
            }
            if (!tracking) {
                tracking = true;
                started = now;
            }
            peakWidth = Math.max(peakWidth, width);
            peakHeight = Math.max(peakHeight, height);
            return now - started >= 1_500_000_000L;
        }

        void reset() {
            tracking = false;
            peakWidth = peakHeight = 0;
        }
    }

    static final class ClipScratch {
        final double[] first = new double[16 * 4];
        final double[] second = new double[16 * 4];
    }

    // I include the full spread of each filter so the crop can't cut off the glow.
    // I add sequential blur spreads and allow room for either flare direction.
    static int padding(BloomConfig config) {
        gridAlignment(config);
        if (config.bloomShape == null
                || config.flareStyle == null
                || !Float.isFinite(config.glowWidth)
                || config.glowWidth < 0
                || !Float.isFinite(config.wideHalo)
                || !Float.isFinite(config.flareLength)
                || config.flareLength < 0
                || !Float.isFinite(config.flareStrength))
            throw new IllegalArgumentException("Invalid bloom support");
        int fine = config.renderPixelSize();
        double radius = Math.ceil(Math.max(1, config.glowWidth / (2.0 * fine)));
        double filterCells;
        if (config.bloomShape == BloomConfig.Shape.STARBURST) filterCells = radius + 1;
        else {
            int stages = config.wideHalo != 0 ? 2 : 1;
            double axisSupport =
                    config.bloomShape == BloomConfig.Shape.DIAMOND ? 2 * DIAGONAL * radius : radius;
            // I include one extra neighbor per pass for interpolation.
            filterCells = stages * (axisSupport + 2);
        }
        if (config.flareStyle != BloomConfig.Flare.OFF && config.flareStrength > 0)
            filterCells = Math.max(filterCells, Math.ceil(config.flareLength / fine));
        // I add two coarse cells for sampling spread and two screen pixels for rounding.
        double pixels = Math.ceil(filterCells * fine) + 2.0 * config.pixelSize + 2;
        return pixels >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) pixels;
    }

    // I clip X, Y and W but leave depth to the GPU, since backends use different depth rules.
    // I fall back to the full screen if malformed geometry makes a smaller crop unsafe.
    static Rect project(
            float[] clipXYZW, int vertexCount, int mainWidth, int mainHeight, BloomConfig config) {
        return project(clipXYZW, vertexCount, mainWidth, mainHeight, config, new ClipScratch());
    }

    static Rect project(
            float[] clipXYZW,
            int vertexCount,
            int mainWidth,
            int mainHeight,
            BloomConfig config,
            ClipScratch scratch) {
        dimensions(mainWidth, mainHeight);
        if (clipXYZW == null
                || vertexCount < 0
                || vertexCount % 4 != 0
                || (long) vertexCount * 4 > clipXYZW.length)
            return new Rect(0, 0, mainWidth, mainHeight);
        if (vertexCount == 0) return EMPTY;
        // I reserve nine vertices because clipping a quad against five planes can add five corners.
        double[] first = scratch.first, second = scratch.second;
        double minX = Double.POSITIVE_INFINITY,
                minY = minX,
                maxX = Double.NEGATIVE_INFINITY,
                maxY = maxX;
        for (int base = 0; base < vertexCount * 4; base += 16) {
            for (int i = 0; i < 16; i++) {
                float v = clipXYZW[base + i];
                if (!Float.isFinite(v)) return new Rect(0, 0, mainWidth, mainHeight);
                first[i] = v;
            }
            double[] input = first, output = second;
            int count = 4;
            for (int plane = 0; plane < 5 && count > 0; plane++) {
                int next = clip(input, count, output, plane);
                var swap = input;
                input = output;
                output = swap;
                count = next;
            }
            for (int i = 0; i < count; i++) {
                int offset = i * 4;
                double w = input[offset + 3];
                double x = (input[offset] / w * .5 + .5) * mainWidth,
                        y = (input[offset + 1] / w * .5 + .5) * mainHeight;
                if (!Double.isFinite(x) || !Double.isFinite(y))
                    return new Rect(0, 0, mainWidth, mainHeight);
                minX = Math.min(minX, x);
                minY = Math.min(minY, y);
                maxX = Math.max(maxX, x);
                maxY = Math.max(maxY, y);
            }
        }
        return minX == Double.POSITIVE_INFINITY
                ? EMPTY
                : crop(minX, minY, maxX, maxY, mainWidth, mainHeight, config);
    }

    // I expand the glow bounds before aligning the origin so neither pixel grid shifts.
    static Rect crop(
            double minX,
            double minY,
            double maxX,
            double maxY,
            int mainWidth,
            int mainHeight,
            BloomConfig config) {
        dimensions(mainWidth, mainHeight);
        if (!Double.isFinite(minX)
                || !Double.isFinite(minY)
                || !Double.isFinite(maxX)
                || !Double.isFinite(maxY)
                || minX > maxX
                || minY > maxY) return new Rect(0, 0, mainWidth, mainHeight);
        if (minX == maxX
                || minY == maxY
                || maxX <= 0
                || maxY <= 0
                || minX >= mainWidth
                || minY >= mainHeight) return EMPTY;
        int alignment = gridAlignment(config), pad = padding(config);
        int x = (int) (Math.max(0, Math.floor(minX - pad)) / alignment) * alignment;
        int y = (int) (Math.max(0, Math.floor(minY - pad)) / alignment) * alignment;
        int right = (int) Math.min(mainWidth, Math.ceil(maxX + pad)),
                top = (int) Math.min(mainHeight, Math.ceil(maxY + pad));
        return new Rect(x, y, right - x, top - y);
    }

    private static int clip(double[] input, int count, double[] output, int plane) {
        int written = 0, previous = (count - 1) * 4;
        double previousDistance = distance(input, previous, plane);
        for (int vertex = 0; vertex < count; vertex++) {
            int current = vertex * 4;
            double currentDistance = distance(input, current, plane);
            boolean previousInside = previousDistance >= 0, currentInside = currentDistance >= 0;
            if (previousInside != currentInside) {
                double t = previousDistance / (previousDistance - currentDistance);
                for (int component = 0; component < 4; component++)
                    output[written * 4 + component] =
                            input[previous + component]
                                    + t
                                            * (input[current + component]
                                                    - input[previous + component]);
                written++;
            }
            if (currentInside) {
                System.arraycopy(input, current, output, written * 4, 4);
                written++;
            }
            previous = current;
            previousDistance = currentDistance;
        }
        return written;
    }

    private static double distance(double[] vertices, int offset, int plane) {
        double w = vertices[offset + 3];
        return switch (plane) {
            case 0 -> w - MIN_W;
            case 1 -> w + vertices[offset];
            case 2 -> w - vertices[offset];
            case 3 -> w + vertices[offset + 1];
            default -> w - vertices[offset + 1];
        };
    }

    private static void dimensions(int width, int height) {
        if (width < 1 || height < 1)
            throw new IllegalArgumentException("Invalid framebuffer dimensions");
    }

    private static int lcm(int a, int b) {
        int x = a, y = b;
        while (y != 0) {
            int rest = x % y;
            x = y;
            y = rest;
        }
        return Math.toIntExact((long) a / x * b);
    }
}
