package dev.stillbloom.gui.layout;

public record MaskEditorLayout(
        int toolW, int stageX, int stageW, int previewX, int previewY, int previewW, int previewH) {
    public static int toolWidth(int width) {
        return width < 320 ? 96 : 104;
    }

    public static MaskEditorLayout fit(
            int x, int top, int width, int bottom, int textureWidth, int textureHeight) {
        int tools = toolWidth(width),
                stageX = x + tools + 10,
                stageW = Math.max(1, width - tools - 10);
        int availableW = Math.max(1, stageW - 16), availableH = Math.max(1, bottom - top - 32);
        int tw = Math.max(1, textureWidth), th = Math.max(1, textureHeight);
        double scale = Math.min(availableW / (double) tw, availableH / (double) th);
        if (scale >= 1) scale = Math.floor(scale);
        int w = Math.max(1, Math.min(availableW, (int) (tw * scale))),
                h = Math.max(1, Math.min(availableH, (int) (th * scale)));
        return new MaskEditorLayout(
                tools,
                stageX,
                stageW,
                stageX + 8 + (availableW - w) / 2,
                top + 14 + (availableH - h) / 2,
                w,
                h);
    }

    public static MaskEditorLayout embedded(
            int x, int top, int width, int bottom, int tw, int th, boolean rail) {
        int tools = rail ? 96 : 0, gap = rail ? 10 : 0;
        int stageX = x + tools + gap, stageW = Math.max(1, width - tools - gap);
        int availableW = Math.max(1, stageW - 12), availableH = Math.max(1, bottom - top - 30);
        double scale =
                Math.min(
                        availableW / (double) Math.max(1, tw),
                        availableH / (double) Math.max(1, th));
        if (scale >= 1) scale = Math.floor(scale);
        int w = Math.max(1, (int) (tw * scale)), h = Math.max(1, (int) (th * scale));
        return new MaskEditorLayout(
                tools,
                stageX,
                stageW,
                stageX + 6 + (availableW - w) / 2,
                top + 13 + (availableH - h) / 2,
                w,
                h);
    }
}
