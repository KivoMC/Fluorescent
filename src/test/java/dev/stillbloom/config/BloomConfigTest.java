package dev.stillbloom.config;

import static org.junit.jupiter.api.Assertions.*;

import dev.stillbloom.render.BloomGraph;
import dev.stillbloom.render.source.SourceMask;

import org.junit.jupiter.api.Test;

class BloomConfigTest {
    @Test
    void copiedSettingsRetainEveryValueWithoutSharingMutableState() throws Exception {
        var original = new BloomConfig();
        int value = 0;
        for (var field : BloomConfig.class.getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
            var type = field.getType();
            assertTrue(
                    type.isPrimitive() || type.isEnum(),
                    "Copy must handle any future mutable setting: " + field.getName());
            if (type == float.class) field.setFloat(original, ++value * .125f);
            else if (type == int.class) field.setInt(original, ++value);
            else if (type == boolean.class) field.setBoolean(original, ++value % 2 == 0);
            else if (type.isEnum()) {
                var options = type.getEnumConstants();
                field.set(original, options[++value % options.length]);
            }
        }
        var copy = original.copy();
        assertNotSame(original, copy);
        for (var field : BloomConfig.class.getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
            var expected = field.get(original);
            assertEquals(expected, field.get(copy), field.getName());
            if (field.getType() == float.class) field.setFloat(copy, -100);
            else if (field.getType() == int.class) field.setInt(copy, -100);
            else if (field.getType() == boolean.class) field.setBoolean(copy, !(boolean) expected);
            else field.set(copy, null);
            assertEquals(expected, field.get(original), field.getName() + " changed in original");
        }
    }

    @Test
    void starburstSharesInnerAndOuterSamplingPass() {
        var config = new BloomConfig();
        config.bloomShape = BloomConfig.Shape.STARBURST;
        var chain = BloomGraph.createConfig(1281, 721, config);
        assertEquals(
                1,
                chain.passes().stream()
                        .filter(p -> p.fragmentShaderId().equals(BloomGraph.id("post/starburst")))
                        .count());
        assertFalse(chain.internalTargets().containsKey(BloomGraph.id("wide")));
        assertFalse(chain.internalTargets().containsKey(BloomGraph.id("near_x")));
    }

    @Test
    void optionalEffectsDoNotAllocateUnusedPasses() {
        var config = new BloomConfig();
        config.preserveDetail = 0;
        var plain = BloomGraph.createConfig(1281, 721, config);
        assertFalse(plain.internalTargets().containsKey(BloomGraph.id("flare")));
        assertEquals(
                1,
                plain.passes().stream()
                        .filter(p -> p.uniforms().containsKey("SourceMask"))
                        .count());
        config.flareStyle = BloomConfig.Flare.CROSS;
        config.preserveDetail = 1;
        var effects = BloomGraph.createConfig(1281, 721, config);
        assertTrue(effects.internalTargets().containsKey(BloomGraph.id("flare")));
        assertEquals(plain.passes().size() + 1, effects.passes().size());
        assertEquals(
                2,
                effects.passes().stream()
                        .filter(p -> p.uniforms().containsKey("SourceMask"))
                        .count());
        config.flareStrength = 0;
        assertFalse(
                BloomGraph.createConfig(1281, 721, config)
                        .internalTargets()
                        .containsKey(BloomGraph.id("flare")));
    }

    @Test
    void oldConfigurationRetainsSettingsAndNewControlsPersist(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        var file = dir.resolve("stillbloom.json");
        java.nio.file.Files.writeString(
                file, "{\"pixelSize\":6,\"intensity\":4.25,\"wideRadius\":4.5}");
        var config = BloomConfig.load(file);
        assertEquals(4, config.pixelSize);
        assertEquals(4.25f, config.intensity);
        assertEquals(4.5f, config.wideRadius);
        assertEquals(BloomConfig.Shape.SOFT, config.bloomShape);
        assertEquals(BloomConfig.Flare.OFF, config.flareStyle);
        assertFalse(config.heldItemBloom);
        assertFalse(config.otherPlayerItemBloom);
        assertEquals(32, config.heldItemRange);
        config.bloomShape = BloomConfig.Shape.DIAMOND;
        config.flareStyle = BloomConfig.Flare.HORIZONTAL;
        config.glowWidth = 72;
        config.preserveDetail = 1;
        config.flareLength = 100;
        config.save(file);
        var loaded = BloomConfig.load(file);
        assertEquals(config.bloomShape, loaded.bloomShape);
        assertEquals(config.flareStyle, loaded.flareStyle);
        assertEquals(72, loaded.glowWidth);
        assertEquals(1, loaded.preserveDetail);
        assertEquals(100, loaded.flareLength);
    }

    @Test
    void heldItemOptionsPersistAndCopyIndependently(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) {
        var config = new BloomConfig();
        config.heldItemBloom = true;
        config.otherPlayerItemBloom = true;
        config.heldItemRange = 48;
        var file = dir.resolve("held-items.json");
        config.save(file);
        var loaded = BloomConfig.load(file);
        assertTrue(loaded.heldItemBloom);
        assertTrue(loaded.otherPlayerItemBloom);
        assertEquals(48, loaded.heldItemRange);
        var copy = loaded.copy();
        copy.heldItemBloom = false;
        copy.heldItemRange = 8;
        assertTrue(loaded.heldItemBloom);
        assertTrue(copy.otherPlayerItemBloom);
        assertEquals(48, loaded.heldItemRange);
        loaded.heldItemBloom = false;
        loaded.save(file);
        var otherOnly = BloomConfig.load(file);
        assertFalse(otherOnly.heldItemBloom);
        assertTrue(otherOnly.otherPlayerItemBloom);
    }

    @Test
    void heldItemDistanceHasFiniteBoundsAndOptInDefaults() {
        for (int preset = 0; preset < 3; preset++) {
            var config = BloomConfig.preset(preset);
            assertFalse(config.heldItemBloom);
            assertFalse(config.otherPlayerItemBloom);
            assertEquals(32, config.heldItemRange);
        }
        var config = new BloomConfig();
        config.heldItemRange = -1;
        config.sanitize();
        assertEquals(8, config.heldItemRange);
        config.heldItemRange = 999;
        config.sanitize();
        assertEquals(64, config.heldItemRange);
        for (float invalid :
                new float[] {Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY}) {
            config.heldItemRange = invalid;
            config.sanitize();
            assertEquals(32, config.heldItemRange);
        }
    }

    @Test
    void automaticPipelineDoesNotBindCustomGeometryResources() {
        var automatic =
                BloomGraph.createConfig(1281, 721, new BloomConfig(), false).passes().getFirst();
        var custom =
                BloomGraph.createConfig(1281, 721, new BloomConfig(), true).passes().getFirst();
        assertEquals(BloomGraph.id("post/extract_auto"), automatic.fragmentShaderId());
        assertEquals(BloomGraph.id("post/extract_paint"), custom.fragmentShaderId());
        assertEquals(3, automatic.inputs().size());
        assertEquals(5, custom.inputs().size());
        assertEquals(automatic.outputTarget(), custom.outputTarget());
        assertEquals(automatic.uniforms(), custom.uniforms());
    }

    @Test
    void threePixelGridUsesMatchingCoreAndHaloSizes() {
        BloomConfig config = new BloomConfig();
        assertEquals(3, config.pixelSize);
        var chain = BloomGraph.createConfig(1281, 721, config);
        for (String target : new String[] {"down_3", "near_x", "near", "wide_x", "wide"}) {
            var size = chain.internalTargets().get(BloomGraph.id(target));
            assertEquals(427, size.width().orElseThrow());
            assertEquals(241, size.height().orElseThrow());
        }
        assertFalse(chain.internalTargets().containsKey(BloomGraph.id("down_2")));
        assertEquals(
                BloomGraph.id("post/extract_auto"), chain.passes().getFirst().fragmentShaderId());
        config.cyclePixelSize();
        assertEquals(4, config.pixelSize);
        config.cyclePixelSize();
        assertEquals(2, config.pixelSize);
        config.cyclePixelSize();
        assertEquals(3, config.pixelSize);
    }

    @Test
    void invalidSettingsCannotBreakShaderMath() {
        BloomConfig config = new BloomConfig();
        config.pixelSize = -40;
        config.intensity = Float.NaN;
        config.softKnee = 0;
        config.threshold = Float.POSITIVE_INFINITY;
        config.wideHalo = 400;
        config.nearRadius = 0;
        config.wideRadius = Float.NaN;
        config.saturation = Float.NEGATIVE_INFINITY;
        config.additiveBlend = -100;
        config.glowWidth = Float.NaN;
        config.preserveDetail = 2;
        config.bloomShape = null;
        config.flareStyle = null;
        config.flareStrength = Float.POSITIVE_INFINITY;
        config.flareLength = 999;
        config.sanitize();
        assertEquals(4, config.pixelSize);
        assertTrue(Float.isFinite(config.intensity));
        assertTrue(config.softKnee > 0);
        assertTrue(Float.isFinite(config.threshold));
        assertEquals(1, config.wideHalo);
        assertTrue(config.nearRadius > 0);
        assertTrue(Float.isFinite(config.wideRadius));
        assertTrue(Float.isFinite(config.saturation));
        assertEquals(0, config.additiveBlend);
        assertEquals(48, config.glowWidth);
        assertEquals(1, config.preserveDetail);
        assertEquals(BloomConfig.Shape.SOFT, config.bloomShape);
        assertEquals(BloomConfig.Flare.OFF, config.flareStyle);
        assertTrue(Float.isFinite(config.flareStrength));
        assertEquals(128, config.flareLength);
    }

    @Test
    void presetsHaveDistinctValidStrengths() {
        assertTrue(BloomConfig.preset(0).intensity < BloomConfig.preset(1).intensity);
        assertTrue(BloomConfig.preset(1).intensity < BloomConfig.preset(2).intensity);
        assertTrue(SourceMask.BUFFER_BYTES <= 16384);
    }

    @Test
    void oddWindowSizesRetainEveryScreenPixelInTheGrid() {
        for (int width : new int[] {1, 7, 15, 17, 853, 1365, 1919, 2561}) {
            for (int cell : new int[] {2, 3, 4}) {
                int gridWidth = BloomGraph.reducedSize(width, cell);
                assertEquals((width - 1) / cell, gridWidth - 1);
                for (int pixel = 0; pixel < width; pixel++) {
                    assertTrue(pixel / cell < gridWidth);
                }
            }
        }
    }
}
