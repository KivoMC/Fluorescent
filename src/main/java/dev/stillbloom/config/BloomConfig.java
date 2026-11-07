package dev.stillbloom.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import dev.stillbloom.persistence.JsonFiles;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class BloomConfig implements Cloneable {
    public enum Shape {
        SQUARE,
        SOFT,
        DIAMOND,
        STARBURST
    }

    public enum Flare {
        OFF,
        HORIZONTAL,
        CROSS
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    public boolean enabled = true;
    public int pixelSize = 3;

    // I use zero for the pixelated glow and one for fully smooth sampling.
    public float smoothBlend = 0;

    public float intensity = 3.2f;
    public float threshold = 0.56f;
    public float softKnee = 0.22f;
    public float wideHalo = 0.35f;
    public float nearRadius = 1.35f;
    public float wideRadius = 3.2f;
    public float coreStrength = 0.3f;
    public float saturation = 1.15f;
    public float warmth = 0.12f;
    public float additiveBlend = 0.25f;
    public float bloomRange = 48;
    public float fadeFraction = 0.35f;
    public Shape bloomShape = Shape.SOFT;
    public float glowWidth = 48;
    public float preserveDetail = 0.85f;

    // I correct directional shading only for painted placed-block emission.
    public boolean uniformEmission = false;

    // I take painted emission from the sprite so nearby world lighting doesn't control it.
    public boolean selfLitPaint = true;

    public Flare flareStyle = Flare.OFF;
    public float flareStrength = 0.5f;
    public float flareLength = 64;
    public float stabilityMillis = 40;
    public boolean distancePixels = false;
    public int farPixelSize = 4;
    public float pixelDistanceStart = 8;
    public float pixelDistanceSpan = 20;
    public boolean heldItemBloom = false;
    public boolean otherPlayerItemBloom = false;
    public float heldItemRange = 32;

    public int finePixelSize() {
        return Math.min(pixelSize, farPixelSize);
    }

    public boolean usesDistancePixels() {
        return distancePixels && finePixelSize() < pixelSize;
    }

    public int renderPixelSize() {
        return usesDistancePixels() ? finePixelSize() : pixelSize;
    }

    // I use the same supported sizes for settings, presets and menu choices.
    public static int[] pixelSizes() {
        return new int[] {2, 3, 4};
    }

    public static int validPixelSize(int size) {
        return size >= 2 && size <= 4 ? size : 4;
    }

    // I copy these scalar and enum fields directly to avoid a JSON round trip.
    public BloomConfig copy() {
        try {
            return (BloomConfig) super.clone();
        } catch (CloneNotSupportedException e) {
            throw new AssertionError(e);
        }
    }

    public void sanitize() {
        pixelSize = validPixelSize(pixelSize);
        smoothBlend = finiteClamp(smoothBlend, 0, 1, 0);
        intensity = finiteClamp(intensity, 0, 8, 3.2f);
        threshold = finiteClamp(threshold, 0.1f, 1, 0.56f);
        softKnee = finiteClamp(softKnee, 0.01f, 0.5f, 0.22f);
        wideHalo = finiteClamp(wideHalo, 0, 1, 0.35f);
        nearRadius = finiteClamp(nearRadius, 0.4f, 3, 1.35f);
        wideRadius = finiteClamp(wideRadius, 1, 6, 3.2f);
        coreStrength = finiteClamp(coreStrength, 0, 2, 0.3f);
        saturation = finiteClamp(saturation, 0, 2.5f, 1.15f);
        warmth = finiteClamp(warmth, -1, 1, 0.12f);
        additiveBlend = finiteClamp(additiveBlend, 0, 1, 0.25f);
        bloomRange = finiteClamp(bloomRange, 16, BloomRange.MAX_RANGE, 48);
        fadeFraction = finiteClamp(fadeFraction, 0.1f, 0.75f, 0.35f);
        if (bloomShape == null) bloomShape = Shape.SOFT;
        if (flareStyle == null) flareStyle = Flare.OFF;
        glowWidth = finiteClamp(glowWidth, 8, 128, 48);
        preserveDetail = finiteClamp(preserveDetail, 0, 1, 0.85f);
        flareStrength = finiteClamp(flareStrength, 0, 2, 0.5f);
        flareLength = finiteClamp(flareLength, 8, 128, 64);
        stabilityMillis = finiteClamp(stabilityMillis, 0, 120, 40);
        farPixelSize = validPixelSize(farPixelSize);
        pixelDistanceStart = finiteClamp(pixelDistanceStart, 0, 32, 8);
        pixelDistanceSpan = finiteClamp(pixelDistanceSpan, 4, 24, 20);
        heldItemRange = finiteClamp(heldItemRange, 8, 64, 32);
    }

    public void cyclePixelSize() {
        pixelSize =
                switch (pixelSize) {
                    case 2 -> 3;
                    case 3 -> 4;
                    case 4 -> 2;
                    default -> 4;
                };
    }

    public static BloomConfig preset(int preset) {
        BloomConfig c = new BloomConfig();
        if (preset == 0) {
            c.intensity = 1.5f;
            c.threshold = 0.7f;
            c.coreStrength = 0.1f;
            c.saturation = 1;
            c.warmth = 0;
            c.additiveBlend = 0;
        } else if (preset == 2) {
            c.intensity = 5;
            c.threshold = 0.5f;
            c.coreStrength = 0.6f;
            c.wideHalo = 0.45f;
            c.wideRadius = 4.5f;
            c.additiveBlend = 0.5f;
        }
        return c;
    }

    private static float finiteClamp(float value, float min, float max, float fallback) {
        return Float.isFinite(value) ? Math.clamp(value, min, max) : fallback;
    }

    public static BloomConfig load(Path path) {
        return load(path, null);
    }

    public static BloomConfig load(Path path, BloomConfig previous) {
        if (!Files.notExists(path)) {
            try (var reader = Files.newBufferedReader(path)) {
                BloomConfig config = GSON.fromJson(reader, BloomConfig.class);
                if (config == null) throw new IOException("Empty bloom settings");
                config.sanitize();
                JsonFiles.loaded(path, config);
                return config;
            } catch (Exception e) {
                BloomConfig fallback = previous == null ? new BloomConfig() : previous;
                JsonFiles.unreadable(path, fallback, e);
                return fallback;
            }
        }
        BloomConfig config = new BloomConfig();
        JsonFiles.missing(path);
        config.save(path);
        return config;
    }

    public boolean save(Path path) {
        return JsonFiles.save(path, this);
    }
}
