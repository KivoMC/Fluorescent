package dev.stillbloom.gui.layout;

// I exclude the right and bottom edges so neighboring brush areas don't overlap.
public record BrushFootprint(int left, int top, int right, int bottom) {
    public static BrushFootprint at(int x, int y, int size, int textureWidth, int textureHeight) {
        int side = Math.clamp(size, 1, 4), bias = (side - 1) / 2;
        int left = Math.clamp(x - bias, 0, Math.max(0, textureWidth)),
                top = Math.clamp(y - bias, 0, Math.max(0, textureHeight));
        int right = Math.clamp(x - bias + side, 0, Math.max(0, textureWidth)),
                bottom = Math.clamp(y - bias + side, 0, Math.max(0, textureHeight));
        return new BrushFootprint(left, top, right, bottom);
    }

    public boolean empty() {
        return left >= right || top >= bottom;
    }

    public int area() {
        return Math.max(0, right - left) * Math.max(0, bottom - top);
    }

    public CanvasBounds canvasBounds(
            int x, int y, int width, int height, int textureWidth, int textureHeight) {
        int x0 = x + left * width / textureWidth, y0 = y + top * height / textureHeight;
        // I allow several texture pixels per screen pixel at fit zoom; zooming makes picking
        // precise.
        return new CanvasBounds(
                x0,
                y0,
                Math.max(x0 + 1, x + right * width / textureWidth),
                Math.max(y0 + 1, y + bottom * height / textureHeight));
    }

    public record CanvasBounds(int left, int top, int right, int bottom) {}
}
