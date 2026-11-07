package dev.stillbloom.config;

public final class BloomRange {
    public static final int VOLUME_SIZE = 128;
    public static final int ORIGIN_STEP = 8;
    public static final int MAX_RANGE = 56;

    private BloomRange() {}

    public static int origin(double coordinate) {
        return Math.floorDiv((int) Math.floor(coordinate), ORIGIN_STEP) * ORIGIN_STEP - 60;
    }

    public static int index(int x, int y, int z) {
        return x + VOLUME_SIZE * (y + VOLUME_SIZE * z);
    }

    public static float fade(float distance, float range, float fraction) {
        float start = range * (1 - fraction);
        float t = Math.clamp((distance - start) / (range - start), 0, 1);
        return 1 - t * t * (3 - 2 * t);
    }

    public static double distanceToInterval(double point, double min, double max) {
        return Math.max(Math.max(min - point, point - max), 0);
    }
}
