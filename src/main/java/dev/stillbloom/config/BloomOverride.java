package dev.stillbloom.config;

import java.util.Objects;

// I use null to inherit the current global value instead of freezing a copy.
public final class BloomOverride {
    public BloomConfig.Shape shape;
    public Float intensity, width;
    public BloomConfig.Flare flare;
    public Float flareStrength, flareLength;

    // I keep texture color for null; an RGB override changes hue while keeping brightness.
    public Integer color;

    // I apply detail to the block surface, not its halo; null follows the global value.
    public Float preserveDetail;

    public void sanitize() {
        intensity = valid(intensity, 0, 8);
        width = valid(width, 8, 128);
        flareStrength = valid(flareStrength, 0, 2);
        flareLength = valid(flareLength, 8, 128);
        preserveDetail = valid(preserveDetail, 0, 1);
        if (color != null && (color < 0 || color > 0xFFFFFF)) color = null;
    }

    private static Float valid(Float value, float min, float max) {
        return value == null || !Float.isFinite(value) ? null : Math.clamp(value, min, max);
    }

    public boolean empty() {
        return shape == null
                && intensity == null
                && width == null
                && flare == null
                && flareStrength == null
                && flareLength == null
                && color == null
                && preserveDetail == null;
    }

    public BloomStyle resolve(BloomConfig global) {
        return new BloomStyle(
                shape == null ? global.bloomShape : shape,
                intensity == null ? global.intensity : intensity,
                width == null ? global.glowWidth : width,
                flare == null ? global.flareStyle : flare,
                flareStrength == null ? global.flareStrength : flareStrength,
                flareLength == null ? global.flareLength : flareLength);
    }

    // I preserve null so a copy still inherits later global changes.
    public static BloomOverride copyOf(BloomOverride source) {
        var result = new BloomOverride();
        if (source == null) return result;
        result.shape = source.shape;
        result.intensity = source.intensity;
        result.width = source.width;
        result.flare = source.flare;
        result.flareStrength = source.flareStrength;
        result.flareLength = source.flareLength;
        result.color = source.color;
        result.preserveDetail = source.preserveDetail;
        return result;
    }

    // I treat null and an empty override as full inheritance.
    public static boolean sameValues(BloomOverride a, BloomOverride b) {
        if (a == null) return b == null || b.empty();
        if (b == null) return a.empty();
        return a.shape == b.shape
                && Objects.equals(a.intensity, b.intensity)
                && Objects.equals(a.width, b.width)
                && a.flare == b.flare
                && Objects.equals(a.flareStrength, b.flareStrength)
                && Objects.equals(a.flareLength, b.flareLength)
                && Objects.equals(a.color, b.color)
                && Objects.equals(a.preserveDetail, b.preserveDetail);
    }
}
