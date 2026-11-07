package dev.stillbloom.preset;

import dev.stillbloom.config.BloomConfig;
import dev.stillbloom.mask.ItemMasks;
import dev.stillbloom.mask.TextureMasks;

import java.util.List;
import java.util.Locale;

// I keep bundled looks separate from the editable library.
public final class BuiltinPresets {

    public static final class Look {
        public final String title, description, badge, help;
        private final java.util.function.Supplier<BloomConfig> settings;

        Look(
                String title,
                String description,
                String badge,
                String help,
                java.util.function.Supplier<BloomConfig> settings) {
            this.title = java.util.Objects.requireNonNull(title);
            this.description = java.util.Objects.requireNonNull(description);
            this.badge = java.util.Objects.requireNonNull(badge);
            this.help = java.util.Objects.requireNonNull(help);
            this.settings = java.util.Objects.requireNonNull(settings);
        }
    }

    private static final Look DEFAULT =
            new Look(
                    "Default",
                    "I use a soft 3 px glow with my painted blocks and held items.",
                    "Complete setup",
                    "Includes global settings, linked blocks, source styles and painted masks.",
                    DefaultSetup::settings);
    private static final List<Look> LOOKS = List.of(DEFAULT);

    private BuiltinPresets() {}

    public static List<Look> looks() {
        return LOOKS;
    }

    public static Look first() {
        return LOOKS.isEmpty() ? null : LOOKS.getFirst();
    }

    public static boolean contains(Look look) {
        return look != null && LOOKS.contains(look);
    }

    public static boolean includesSourceProfiles(Look look) {
        return look == DEFAULT;
    }

    // I return detached settings so editing a look can't change the bundled original.
    public static BloomConfig settings(Look look) {
        if (look == null) throw new IllegalArgumentException("Select a built-in preset.");
        return java.util.Objects.requireNonNull(look.settings.get(), "Preset settings").copy();
    }

    // I keep source preferences for appearance-only looks; Default carries the whole setup.
    public static BloomConfig forCurrent(Look look, BloomConfig current) {
        var c = settings(look);
        c.enabled = current.enabled;
        if (includesSourceProfiles(look)) return c;
        // I preserve these source and distance preferences when applying just a look.
        c.uniformEmission = current.uniformEmission;
        c.selfLitPaint = current.selfLitPaint;
        c.distancePixels = current.distancePixels;
        c.farPixelSize = current.farPixelSize;
        c.pixelDistanceStart = current.pixelDistanceStart;
        c.pixelDistanceSpan = current.pixelDistanceSpan;
        c.bloomRange = current.bloomRange;
        c.fadeFraction = current.fadeFraction;
        c.heldItemBloom = current.heldItemBloom;
        c.otherPlayerItemBloom = current.otherPlayerItemBloom;
        c.heldItemRange = current.heldItemRange;
        return c;
    }

    public static SetupPresets.Setup setup(
            Look look, BloomConfig current, TextureMasks blocks, ItemMasks items) {
        if (includesSourceProfiles(look)) {
            var setup = DefaultSetup.copy();
            setup.settings().enabled = current.enabled;
            return setup;
        }
        return SetupPresets.capture(forCurrent(look, current), blocks, items);
    }

    public static String summary(Look look) {
        var c = settings(look);
        String shape = c.bloomShape.name().toLowerCase(Locale.ROOT);
        return c.pixelSize
                + " px / "
                + Character.toUpperCase(shape.charAt(0))
                + shape.substring(1)
                + " / "
                + (c.flareStyle == BloomConfig.Flare.OFF
                        ? "no flare"
                        : c.flareStyle.name().toLowerCase(Locale.ROOT) + " flare");
    }
}
