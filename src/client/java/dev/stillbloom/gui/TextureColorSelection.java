package dev.stillbloom.gui;

import dev.stillbloom.mask.TextureMasks;

import java.util.Arrays;
import java.util.BitSet;

// I keep selection separate from the saved paint mask.
final class TextureColorSelection {
    private TextureColorSelection() {}

    // I compare every candidate with the starting color, using only edge-connected neighbors.
    static BitSet select(
            int[] argb,
            int width,
            int height,
            int seedX,
            int seedY,
            int tolerance,
            boolean connected) {
        checkSize(width, height);
        if (argb == null || argb.length != width * height)
            throw new IllegalArgumentException("Pixel dimensions do not match");
        var selected = new BitSet(argb.length);
        if (seedX < 0 || seedY < 0 || seedX >= width || seedY >= height) return selected;
        int seed = seedX + seedY * width, color = argb[seed], limit = Math.clamp(tolerance, 0, 255);
        if ((color >>> 24) == 0) return selected;
        if (!connected) {
            for (int i = 0; i < argb.length; i++)
                if (matches(argb[i], color, limit)) selected.set(i);
            return selected;
        }
        var visited = new BitSet(argb.length);
        int[] queue = new int[argb.length];
        int head = 0, tail = 1;
        queue[0] = seed;
        visited.set(seed);
        while (head < tail) {
            int i = queue[head++];
            if (!matches(argb[i], color, limit)) continue;
            selected.set(i);
            int x = i % width, y = i / width;
            if (x > 0 && !visited.get(i - 1)) {
                visited.set(i - 1);
                queue[tail++] = i - 1;
            }
            if (x + 1 < width && !visited.get(i + 1)) {
                visited.set(i + 1);
                queue[tail++] = i + 1;
            }
            if (y > 0 && !visited.get(i - width)) {
                visited.set(i - width);
                queue[tail++] = i - width;
            }
            if (y + 1 < height && !visited.get(i + width)) {
                visited.set(i + width);
                queue[tail++] = i + width;
            }
        }
        return selected;
    }

    private static boolean matches(int color, int seed, int tolerance) {
        return (color >>> 24) != 0
                && Math.abs((color >>> 16 & 255) - (seed >>> 16 & 255)) <= tolerance
                && Math.abs((color >>> 8 & 255) - (seed >>> 8 & 255)) <= tolerance
                && Math.abs((color & 255) - (seed & 255)) <= tolerance;
    }

    // I keep the original mask for a no-op; edits get their own copy.
    static TextureMasks.Mask apply(
            TextureMasks.Mask current, int width, int height, BitSet selected, boolean erase) {
        return apply(current, width, height, selected, erase, true);
    }

    static TextureMasks.Mask apply(
            TextureMasks.Mask current,
            int width,
            int height,
            BitSet selected,
            boolean erase,
            boolean fillOnFirstErase) {
        checkSize(width, height);
        if (selected == null
                || selected.nextSetBit(0) < 0
                || selected.nextSetBit(0) >= width * height) return current;
        boolean compatible =
                current != null
                        && current.valid()
                        && current.width == width
                        && current.height == height;
        var result = compatible ? current.copy() : new TextureMasks.Mask(width, height);
        if (!compatible && erase && fillOnFirstErase) Arrays.fill(result.words, -1);
        for (int i = selected.nextSetBit(0);
                i >= 0 && i < width * height;
                i = selected.nextSetBit(i + 1)) result.set(i % width, i / width, !erase);
        if (compatible && Arrays.equals(current.words, result.words)) return current;
        return result;
    }

    private static void checkSize(int width, int height) {
        if (width < 1 || height < 1 || width > 256 || height > 256)
            throw new IllegalArgumentException("Texture must be 1 to 256 pixels per side");
    }
}
