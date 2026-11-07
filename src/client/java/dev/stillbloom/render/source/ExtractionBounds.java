package dev.stillbloom.render.source;

import org.joml.Matrix4fc;

import java.util.List;

// I keep these bounds conservative because extraction still needs neighboring source pixels.
public final class ExtractionBounds {
    private static final double WORLD_PADDING = 1.0, PIXEL_PADDING = 2.0, MIN_W = 0.00001;
    // I leave the extraction margin and one output cell for rounding at screen edges.
    private static final double CLIP_PADDING = 4.0;

    // I clear an empty rectangle's target even though it needs no draw.
    public record Rect(int x, int y, int width, int height) {
        public boolean empty() {
            return width == 0 || height == 0;
        }

        public boolean full(int outputWidth, int outputHeight) {
            return x == 0 && y == 0 && width == outputWidth && height == outputHeight;
        }
    }

    public static Rect project(
            List<SourceBounds> sources,
            Matrix4fc forward,
            double cameraX,
            double cameraY,
            double cameraZ,
            int mainWidth,
            int mainHeight) {
        if (mainWidth < 1 || mainHeight < 1)
            throw new IllegalArgumentException("Invalid scene dimensions");
        int outputWidth = mainWidth / 2 + mainWidth % 2,
                outputHeight = mainHeight / 2 + mainHeight % 2;
        Rect full = new Rect(0, 0, outputWidth, outputHeight);
        if (sources == null
                || forward == null
                || !forward.isFinite()
                || !Double.isFinite(cameraX)
                || !Double.isFinite(cameraY)
                || !Double.isFinite(cameraZ)) return full;
        if (sources.isEmpty()) return new Rect(0, 0, 0, 0);
        double minX = Double.POSITIVE_INFINITY,
                minY = minX,
                maxX = Double.NEGATIVE_INFINITY,
                maxY = maxX;
        for (var bounds : sources) {
            if (bounds == null
                    || bounds.minX() > bounds.maxX()
                    || bounds.minY() > bounds.maxY()
                    || bounds.minZ() > bounds.maxZ()) return full;
            double minimumW = Double.POSITIVE_INFINITY, maximumW = Double.NEGATIVE_INFINITY;
            for (int corner = 0; corner < 8; corner++) {
                double x =
                        ((corner & 1) == 0
                                        ? bounds.minX() - WORLD_PADDING
                                        : bounds.maxX() + WORLD_PADDING)
                                - cameraX;
                double y =
                        ((corner & 2) == 0
                                        ? bounds.minY() - WORLD_PADDING
                                        : bounds.maxY() + WORLD_PADDING)
                                - cameraY;
                double z =
                        ((corner & 4) == 0
                                        ? bounds.minZ() - WORLD_PADDING
                                        : bounds.maxZ() + WORLD_PADDING)
                                - cameraZ;
                double w =
                        forward.m03() * x + forward.m13() * y + forward.m23() * z + forward.m33();
                if (!Double.isFinite(w)) return full;
                minimumW = Math.min(minimumW, w);
                maximumW = Math.max(maximumW, w);
            }
            // I reject boxes wholly behind the eye so they don't force visible sources into a
            // full-screen pass.
            if (maximumW <= 0) continue;
            // I reject boxes outside X/Y even if they cross the eye plane; other crossings keep the
            // safe fallback.
            if (minimumW <= MIN_W) {
                if (outsideViewport(
                        bounds, forward, cameraX, cameraY, cameraZ, mainWidth, mainHeight))
                    continue;
                return full;
            }
            for (int corner = 0; corner < 8; corner++) {
                // I subtract the camera in double precision before converting large world
                // coordinates to floats.
                double x =
                        ((corner & 1) == 0
                                        ? bounds.minX() - WORLD_PADDING
                                        : bounds.maxX() + WORLD_PADDING)
                                - cameraX;
                double y =
                        ((corner & 2) == 0
                                        ? bounds.minY() - WORLD_PADDING
                                        : bounds.maxY() + WORLD_PADDING)
                                - cameraY;
                double z =
                        ((corner & 4) == 0
                                        ? bounds.minZ() - WORLD_PADDING
                                        : bounds.maxZ() + WORLD_PADDING)
                                - cameraZ;
                double w =
                        forward.m03() * x + forward.m13() * y + forward.m23() * z + forward.m33();
                // I keep the full pass when a box crosses the camera plane, where corner projection
                // is unsafe.
                if (!Double.isFinite(w) || w <= MIN_W) return full;
                double px =
                        ((forward.m00() * x + forward.m10() * y + forward.m20() * z + forward.m30())
                                                / w
                                                * 0.5
                                        + 0.5)
                                * mainWidth;
                double py =
                        ((forward.m01() * x + forward.m11() * y + forward.m21() * z + forward.m31())
                                                / w
                                                * 0.5
                                        + 0.5)
                                * mainHeight;
                if (!Double.isFinite(px) || !Double.isFinite(py)) return full;
                minX = Math.min(minX, px);
                minY = Math.min(minY, py);
                maxX = Math.max(maxX, px);
                maxY = Math.max(maxY, py);
            }
        }
        if (minX == Double.POSITIVE_INFINITY) return new Rect(0, 0, 0, 0);
        // I use the exact 2*p and 2*p+1 source pixels so odd framebuffer edges aren't shifted.
        int x0 = clampCell(Math.floor((minX - PIXEL_PADDING) / 2.0), outputWidth);
        int y0 = clampCell(Math.floor((minY - PIXEL_PADDING) / 2.0), outputHeight);
        int x1 = clampCell(Math.ceil((maxX + PIXEL_PADDING) / 2.0), outputWidth);
        int y1 = clampCell(Math.ceil((maxY + PIXEL_PADDING) / 2.0), outputHeight);
        if (x1 <= x0 || y1 <= y0) return new Rect(0, 0, 0, 0);
        return new Rect(x0, y0, x1 - x0, y1 - y0);
    }

    // I test X/Y planes without depending on a backend's depth convention.
    private static boolean outsideViewport(
            SourceBounds bounds,
            Matrix4fc forward,
            double cameraX,
            double cameraY,
            double cameraZ,
            int mainWidth,
            int mainHeight) {
        boolean left = true, right = true, bottom = true, top = true;
        double scaleX = 1.0 + 2.0 * CLIP_PADDING / mainWidth,
                scaleY = 1.0 + 2.0 * CLIP_PADDING / mainHeight;
        for (int corner = 0; corner < 8; corner++) {
            double x =
                    ((corner & 1) == 0
                                    ? bounds.minX() - WORLD_PADDING
                                    : bounds.maxX() + WORLD_PADDING)
                            - cameraX;
            double y =
                    ((corner & 2) == 0
                                    ? bounds.minY() - WORLD_PADDING
                                    : bounds.maxY() + WORLD_PADDING)
                            - cameraY;
            double z =
                    ((corner & 4) == 0
                                    ? bounds.minZ() - WORLD_PADDING
                                    : bounds.maxZ() + WORLD_PADDING)
                            - cameraZ;
            double w = forward.m03() * x + forward.m13() * y + forward.m23() * z + forward.m33();
            double px = forward.m00() * x + forward.m10() * y + forward.m20() * z + forward.m30();
            double py = forward.m01() * x + forward.m11() * y + forward.m21() * z + forward.m31();
            double edgeX = w * scaleX, edgeY = w * scaleY;
            if (!Double.isFinite(edgeX)
                    || !Double.isFinite(edgeY)
                    || !Double.isFinite(px)
                    || !Double.isFinite(py)) return false;
            // I reject a box only when every corner is outside the same plane, keeping touching
            // corners.
            left &= px < -edgeX;
            right &= px > edgeX;
            bottom &= py < -edgeY;
            top &= py > edgeY;
        }
        return left || right || bottom || top;
    }

    // I include every reduction and ray sample when carrying extraction bounds through the filters.
    public static Rect starburst(
            Rect extraction, int mainWidth, int mainHeight, int pixelSize, float glowWidth) {
        if (mainWidth < 1 || mainHeight < 1 || pixelSize < 1)
            throw new IllegalArgumentException("Invalid bloom dimensions");
        int outputWidth = ceilDiv(mainWidth, pixelSize),
                outputHeight = ceilDiv(mainHeight, pixelSize);
        Rect full = new Rect(0, 0, outputWidth, outputHeight);
        // I keep the full draw for unknown grids so I don't clip glow.
        if ((pixelSize < 2 || pixelSize > 4)
                || extraction == null
                || !Float.isFinite(glowWidth)
                || glowWidth < 0) return full;
        int halfWidth = ceilDiv(mainWidth, 2), halfHeight = ceilDiv(mainHeight, 2);
        if (extraction.x() < 0
                || extraction.y() < 0
                || extraction.width() < 0
                || extraction.height() < 0
                || (long) extraction.x() + extraction.width() > halfWidth
                || (long) extraction.y() + extraction.height() > halfHeight) return full;
        if (extraction.empty()) return new Rect(0, 0, 0, 0);
        float extent = Math.max(1, glowWidth / (2 * pixelSize));
        if (extent >= Math.max(outputWidth, outputHeight)) return full;
        int radius = (int) Math.ceil(extent) + 1;
        // I round the child-cell interval outward, including clamped cells at odd edges.
        int x0 = (int) ((long) extraction.x() * 2 / pixelSize);
        int y0 = (int) ((long) extraction.y() * 2 / pixelSize);
        int x1 =
                (int)
                        ((((long) extraction.x() + extraction.width()) * 2 + pixelSize - 1)
                                / pixelSize);
        int y1 =
                (int)
                        ((((long) extraction.y() + extraction.height()) * 2 + pixelSize - 1)
                                / pixelSize);
        // I add a cell beyond each ray's reach for interpolation and edge clamping.
        x0 = (int) Math.max(0L, (long) x0 - radius);
        y0 = (int) Math.max(0L, (long) y0 - radius);
        x1 = (int) Math.min(outputWidth, (long) x1 + radius);
        y1 = (int) Math.min(outputHeight, (long) y1 + radius);
        return new Rect(x0, y0, x1 - x0, y1 - y0);
    }

    private static int clampCell(double value, int size) {
        return (int) Math.clamp(value, 0.0, (double) size);
    }

    private static int ceilDiv(int size, int divisor) {
        return size / divisor + (size % divisor == 0 ? 0 : 1);
    }
}
