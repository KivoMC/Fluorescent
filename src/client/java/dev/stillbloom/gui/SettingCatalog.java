package dev.stillbloom.gui;

import java.util.*;

final class SettingCatalog {
    record Entry(
            String id, int page, String group, String title, String description, String keywords) {
        String location() {
            return switch (page) {
                case 4 -> "Held Items";
                case 3 -> "Performance";
                case 2 -> "Blocks";
                case 0 -> "Looks";
                default -> "Glow / " + groupTitle(group);
            };
        }

        boolean matches(String query) {
            String aliases =
                    switch (page) {
                        case 0 -> "preset presets";
                        case 4 -> "held hands items bloom settings";
                        case 1 -> "bloom settings";
                        case 2 -> "block override overrides";
                        default -> "";
                    };
            String hay =
                    (title + " " + description + " " + keywords + " " + location() + " " + aliases)
                            .toLowerCase(Locale.ROOT);
            return Arrays.stream(query.toLowerCase(Locale.ROOT).trim().split("\\s+"))
                    .allMatch(hay::contains);
        }
    }

    static final List<String> GROUPS = List.of("look", "color", "shape", "response", "distance");

    static String groupTitle(String key) {
        return switch (key) {
            case "look" -> "Appearance";
            case "color" -> "Color";
            case "shape" -> "Shapes";
            case "response" -> "Light response";
            case "distance" -> "Distance sizing";
            case "items" -> "Held items";
            default -> key;
        };
    }

    static final List<Entry> ALL =
            List.of(
                    e(
                            "enabled",
                            1,
                            "look",
                            "Bloom",
                            "Turn the whole effect on or off. F8 is the shortcut.",
                            "master toggle enable disable"),
                    e(
                            "intensity",
                            1,
                            "look",
                            "Strength",
                            "How bright the glow appears.",
                            "intensity"),
                    e(
                            "pixelSize",
                            1,
                            "look",
                            "Pixel size",
                            "Smaller squares show more detail. Larger squares look chunkier and can improve FPS.",
                            "near grid resolution fine balanced chunky"),
                    e(
                            "smoothBlend",
                            1,
                            "look",
                            "Glow smoothness",
                            "Slide right to soften the square edges of the glow.",
                            "pixelated blend smooth"),
                    e(
                            "preserveDetail",
                            1,
                            "look",
                            "Keep block detail",
                            "Turn up to keep the glowing block's texture easier to see.",
                            "preserve protection surface"),
                    e(
                            "saturation",
                            1,
                            "color",
                            "Saturation",
                            "Turn down to remove color from the glow.",
                            "colour hue"),
                    e(
                            "warmth",
                            1,
                            "color",
                            "Warmth",
                            "Slide left for cooler colors, or right for warmer colors.",
                            "color colour temperature"),
                    e(
                            "bloomShape",
                            1,
                            "shape",
                            "Bloom shape",
                            "Choose the shape of the glow around a light.",
                            "rays"),
                    e(
                            "glowWidth",
                            1,
                            "look",
                            "Glow width",
                            "How far the glow spreads around each light.",
                            "radius size halo"),
                    e(
                            "flareStyle",
                            1,
                            "shape",
                            "Flare",
                            "Add a streak or cross of light.",
                            "off lens effect"),
                    e(
                            "flareStrength",
                            1,
                            "shape",
                            "Flare strength",
                            "How bright the flare is. Set to 0 to turn it off.",
                            "lens intensity"),
                    e(
                            "flareLength",
                            1,
                            "shape",
                            "Flare reach",
                            "How far the flare stretches from the light.",
                            "lens length distance"),
                    e(
                            "wideHalo",
                            1,
                            "shape",
                            "Halo balance",
                            "Slide left for a tight glow, or right for a wider glow.",
                            "wide near blend"),
                    e(
                            "nearRadius",
                            1,
                            "shape",
                            "Inner falloff",
                            "Turn up to spread the inner glow. Used by Soft and Starburst.",
                            "radius"),
                    e(
                            "wideRadius",
                            1,
                            "shape",
                            "Outer falloff",
                            "Turn up to spread the outer glow. Used by Soft and Starburst.",
                            "radius"),
                    e(
                            "selfLitPaint",
                            1,
                            "response",
                            "Self-lit paint",
                            "Let painted areas glow even in darkness.",
                            "emissive eyes ambient torch dark self lit independent mask"),
                    e(
                            "threshold",
                            1,
                            "response",
                            "Brightness cutoff",
                            "Raise this to make only brighter areas glow. Self-lit paint still glows.",
                            "threshold minimum"),
                    e(
                            "softKnee",
                            1,
                            "response",
                            "Soft cutoff",
                            "Make the change from no glow to glow more gradual.",
                            "knee threshold popping"),
                    e(
                            "coreStrength",
                            1,
                            "response",
                            "Core glow",
                            "Add extra glow directly over the light.",
                            "intensity center"),
                    e(
                            "additiveBlend",
                            1,
                            "response",
                            "Additive blend",
                            "Turn up for a brighter glow that can look washed out.",
                            "highlight exposure"),
                    e(
                            "uniformEmission",
                            1,
                            "response",
                            "Uniform emission",
                            "Make painted sides glow more evenly, even when one side is darker.",
                            "uniform emission light brightness painted mask face shading directional"),
                    e(
                            "stabilityMillis",
                            1,
                            "response",
                            "Brightness smoothing",
                            "Reduce small brightness flickers. Higher values respond more slowly. Set to 0 to turn off.",
                            "stability flicker milliseconds temporal"),
                    e(
                            "distancePixels",
                            1,
                            "distance",
                            "Distance pixels",
                            "Make glow squares smaller as you move away.",
                            "adaptive sizing"),
                    e(
                            "farPixelSize",
                            1,
                            "distance",
                            "Far pixel size",
                            "The glow square size for distant lights. Cannot exceed your main pixel size.",
                            "adaptive grid resolution"),
                    e(
                            "pixelDistanceStart",
                            1,
                            "distance",
                            "Shrink starts",
                            "How far away a light must be before its glow squares start shrinking.",
                            "adaptive blocks"),
                    e(
                            "pixelDistanceSpan",
                            1,
                            "distance",
                            "Transition length",
                            "A longer distance makes the change to smaller squares more gradual.",
                            "adaptive blocks span"),
                    e(
                            "heldItemBloom",
                            4,
                            "items",
                            "Your held items",
                            "Show bloom on supported items in your hands.",
                            "first person main offhand toggle"),
                    e(
                            "otherPlayerItemBloom",
                            4,
                            "items",
                            "Other players' items",
                            "Show bloom on supported items other players hold.",
                            "multiplayer toggle hands"),
                    e(
                            "heldItemRange",
                            4,
                            "items",
                            "Other-player distance",
                            "How far away you can see bloom on other players' items.",
                            "range performance blocks"),
                    e(
                            "editItems",
                            4,
                            "items",
                            "Item masks and styles",
                            "Choose which parts of held items glow, and how they look.",
                            "paint resource pack model"),
                    e(
                            "bloomRange",
                            3,
                            "performance",
                            "Bloom distance",
                            "Only show bloom on placed lights within this distance.",
                            "range performance loaded sections render distance draw distance"),
                    e(
                            "fadeFraction",
                            3,
                            "performance",
                            "Distance fade",
                            "Fade out bloom as lights approach the distance limit.",
                            "range fraction performance"),
                    e(
                            "blockBrowser",
                            2,
                            "blocks",
                            "Find a block",
                            "Choose a block to change its glow or paint a mask.",
                            "override torch lantern campfire block color paint mask bulk style"),
                    e(
                            "presets",
                            0,
                            "presets",
                            "Looks",
                            "Save your glow settings and masks, or try a shared setup.",
                            "preset saved create rename delete import export undo preview"));

    private static Entry e(
            String id, int page, String group, String title, String description, String keywords) {
        return new Entry(id, page, group, title, description, keywords);
    }

    static Entry get(String id) {
        return ALL.stream().filter(e -> e.id.equals(id)).findFirst().orElseThrow();
    }

    static List<Entry> search(String text) {
        if (text == null || text.isBlank()) return ALL;
        return ALL.stream().filter(e -> e.matches(text)).toList();
    }

    private SettingCatalog() {}
}
