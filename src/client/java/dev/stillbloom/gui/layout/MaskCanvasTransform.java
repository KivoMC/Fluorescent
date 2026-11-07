package dev.stillbloom.gui.layout;

// I clip input to the viewport even when the zoomed texture extends beyond it.
public record MaskCanvasTransform(
        int left,
        int top,
        int viewportW,
        int viewportH,
        int x,
        int y,
        int width,
        int height,
        double zoom,
        double centerU,
        double centerV,
        int textureW,
        int textureH) {
    public static MaskCanvasTransform fit(
            int left,
            int top,
            int viewportW,
            int viewportH,
            int baseW,
            int baseH,
            double zoom,
            double u,
            double v,
            int textureW,
            int textureH) {
        viewportW = Math.max(1, viewportW);
        viewportH = Math.max(1, viewportH);
        zoom = Math.clamp(zoom, 1, 64);
        int[] size = dimensions(baseW, baseH, zoom, textureW, textureH);
        int w = size[0], h = size[1];
        double marginU = Math.min(.5, viewportW / (2.0 * w)),
                marginV = Math.min(.5, viewportH / (2.0 * h));
        u = Math.clamp(u, marginU, 1 - marginU);
        v = Math.clamp(v, marginV, 1 - marginV);
        int x = (int) Math.round(left + viewportW / 2.0 - u * w),
                y = (int) Math.round(top + viewportH / 2.0 - v * h);
        return new MaskCanvasTransform(
                left, top, viewportW, viewportH, x, y, w, h, zoom, u, v, textureW, textureH);
    }

    private static int[] dimensions(int baseW, int baseH, double zoom, int tw, int th) {
        int pixels = (int) Math.floor(Math.min(baseW / (double) tw, baseH / (double) th) * zoom);
        // I use the same pixel boundaries for drawing and picking, including rectangular textures.
        if (pixels >= 1) return new int[] {tw * pixels, th * pixels};
        return new int[] {
            Math.max(1, (int) Math.round(baseW * zoom)), Math.max(1, (int) Math.round(baseH * zoom))
        };
    }

    public MaskCanvasTransform zoomAt(
            int baseW, int baseH, double next, double anchorX, double anchorY) {
        double u = (anchorX - x) / width, v = (anchorY - y) / height;
        double target = Math.clamp(next, 1, 64);
        int[] size = dimensions(baseW, baseH, target, textureW, textureH);
        int w = size[0], h = size[1];
        return fit(
                left,
                top,
                viewportW,
                viewportH,
                baseW,
                baseH,
                target,
                u - (anchorX - left - viewportW / 2.0) / w,
                v - (anchorY - top - viewportH / 2.0) / h,
                textureW,
                textureH);
    }

    public MaskCanvasTransform pan(int baseW, int baseH, double dx, double dy) {
        return fit(
                left,
                top,
                viewportW,
                viewportH,
                baseW,
                baseH,
                zoom,
                centerU - dx / width,
                centerV - dy / height,
                textureW,
                textureH);
    }

    public boolean contains(double px, double py) {
        return px >= left
                && py >= top
                && px < left + viewportW
                && py < top + viewportH
                && px >= x
                && py >= y
                && px < x + width
                && py < y + height;
    }

    public int pixelX(double px, int textureW) {
        return Math.clamp((int) Math.floor((px - x) * textureW / width), 0, textureW - 1);
    }

    public int pixelY(double py, int textureH) {
        return Math.clamp((int) Math.floor((py - y) * textureH / height), 0, textureH - 1);
    }
}
