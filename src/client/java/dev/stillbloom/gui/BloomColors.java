package dev.stillbloom.gui;

import java.util.Locale;

final class BloomColors {
    private BloomColors() {}

    static Integer parse(String text) {
        if (text == null) return null;
        String hex = text.trim();
        if (hex.startsWith("#")) hex = hex.substring(1);
        if (!hex.matches("[0-9a-fA-F]{6}")) return null;
        return Integer.parseInt(hex, 16);
    }

    static String hex(int color) {
        return String.format(Locale.ROOT, "%06X", color & 0xFFFFFF);
    }

    static Integer channel(String text) {
        if (text == null) return null;
        String digits = text.trim();
        if (!digits.matches("[0-9]{1,3}")) return null;
        int value = Integer.parseInt(digits);
        return value <= 255 ? value : null;
    }

    // I remember the last hue so editing gray or black doesn't lose the chosen color.
    static float[] hsv(int color, float rememberedHue) {
        float r = ((color >> 16) & 255) / 255f,
                g = ((color >> 8) & 255) / 255f,
                b = (color & 255) / 255f;
        float max = Math.max(r, Math.max(g, b)),
                min = Math.min(r, Math.min(g, b)),
                delta = max - min;
        float hue = rememberedHue;
        if (delta > 0) {
            if (max == r) hue = ((g - b) / delta) % 6f;
            else if (max == g) hue = (b - r) / delta + 2;
            else hue = (r - g) / delta + 4;
            hue /= 6;
            if (hue < 0) hue += 1;
        }
        return new float[] {hue, max == 0 ? 0 : delta / max, max};
    }

    static int rgb(float hue, float saturation, float value) {
        hue = Float.isFinite(hue) ? hue - (float) Math.floor(hue) : 0;
        saturation = Float.isFinite(saturation) ? Math.clamp(saturation, 0, 1) : 0;
        value = Float.isFinite(value) ? Math.clamp(value, 0, 1) : 0;
        float h = hue * 6, c = value * saturation, x = c * (1 - Math.abs(h % 2 - 1)), m = value - c;
        float r = 0, g = 0, b = 0;
        switch ((int) h) {
            case 0 -> {
                r = c;
                g = x;
            }
            case 1 -> {
                r = x;
                g = c;
            }
            case 2 -> {
                g = c;
                b = x;
            }
            case 3 -> {
                g = x;
                b = c;
            }
            case 4 -> {
                r = x;
                b = c;
            }
            default -> {
                r = c;
                b = x;
            }
        }
        return Math.round((r + m) * 255) << 16
                | Math.round((g + m) * 255) << 8
                | Math.round((b + m) * 255);
    }
}
