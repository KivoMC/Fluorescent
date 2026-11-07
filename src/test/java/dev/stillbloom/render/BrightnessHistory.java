package dev.stillbloom.render;

// I use this CPU reference to check the shader's brightness blend.
final class BrightnessHistory {

    static float brightness(float current, float previous, float weight) {
        if (current <= 0 || previous <= 0) return current;
        return current + (Math.clamp(previous, current * .75f, current * 1.25f) - current) * weight;
    }
}
