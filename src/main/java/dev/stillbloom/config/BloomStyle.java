package dev.stillbloom.config;

// I share source masks and pipelines when resolved settings match.
public record BloomStyle(
        BloomConfig.Shape shape,
        float intensity,
        float width,
        BloomConfig.Flare flare,
        float flareStrength,
        float flareLength)
        implements Comparable<BloomStyle> {
    public BloomStyle {
        // I ignore inactive streak settings when grouping sources to avoid extra passes.
        if (flare == BloomConfig.Flare.OFF || flareStrength == 0) {
            flare = BloomConfig.Flare.OFF;
            flareStrength = 0;
            flareLength = 8;
        }
    }

    public static BloomStyle global(BloomConfig config) {
        return new BloomStyle(
                config.bloomShape,
                config.intensity,
                config.glowWidth,
                config.flareStyle,
                config.flareStrength,
                config.flareLength);
    }

    public BloomConfig apply(BloomConfig global) {
        BloomConfig result = global.copy();
        result.bloomShape = shape;
        result.intensity = intensity;
        result.glowWidth = width;
        result.flareStyle = flare;
        result.flareStrength = flareStrength;
        result.flareLength = flareLength;
        return result;
    }

    @Override
    public int compareTo(BloomStyle other) {
        int result = Integer.compare(shape.ordinal(), other.shape.ordinal());
        if (result != 0) return result;
        result = Float.compare(intensity, other.intensity);
        if (result != 0) return result;
        result = Float.compare(width, other.width);
        if (result != 0) return result;
        result = Integer.compare(flare.ordinal(), other.flare.ordinal());
        if (result != 0) return result;
        result = Float.compare(flareStrength, other.flareStrength);
        if (result != 0) return result;
        return Float.compare(flareLength, other.flareLength);
    }
}
