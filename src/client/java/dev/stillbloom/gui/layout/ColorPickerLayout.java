package dev.stillbloom.gui.layout;

public record ColorPickerLayout(
        int panelX,
        int panelY,
        int panelW,
        int panelH,
        int bodyY,
        int size,
        int rightX,
        int rightW,
        int swatchY,
        boolean compact) {
    public static ColorPickerLayout fit(int width, int height) {
        int w = Math.min(420, width - 12),
                h = Math.min(298, height - 12),
                x = (width - w) / 2,
                y = (height - h) / 2;
        boolean compact = h < 250 || w < 350;
        int size = Math.max(16, Math.min(166, Math.min(h - (compact ? 90 : 122), (w - 88) / 2)));
        int right = x + 12 + size + (compact ? 24 : 30);
        return new ColorPickerLayout(
                x,
                y,
                w,
                h,
                y + (compact ? 42 : 53),
                size,
                right,
                x + w - 12 - right,
                y + h - (compact ? 46 : 59),
                compact);
    }

    public int fieldH() {
        return compact ? 16 : 18;
    }

    public int hexY() {
        return bodyY + (compact ? 20 : 32);
    }

    public int rgbY() {
        return bodyY + (compact ? 42 : 69);
    }

    public int copyY() {
        return bodyY + (compact ? 62 : 96);
    }

    public int swatchH() {
        return compact ? 16 : 19;
    }

    public int footerY() {
        return panelY + panelH - 28;
    }

    public int channelW() {
        return (rightW - 8) / 3;
    }
}
