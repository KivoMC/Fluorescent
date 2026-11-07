package dev.stillbloom.render;

import static org.junit.jupiter.api.Assertions.*;

import dev.stillbloom.config.BloomConfig;
import dev.stillbloom.config.BloomStyle;
import dev.stillbloom.render.item.HeldItemGraph;
import dev.stillbloom.render.source.SourceMask;

import net.minecraft.client.renderer.PostChainConfig;

import org.junit.jupiter.api.Test;

import java.util.List;

// I check that uniform brightness keeps the same batching and amount of render work.
class UniformEmissionGraphTest {
    @Test
    void groupedMasksChangeOnlyTheirExtractionShaderAndKeepEveryBindingAndTarget() {
        for (var shape : BloomConfig.Shape.values())
            for (boolean distance : new boolean[] {false, true})
                for (boolean colored : new boolean[] {false, true})
                    for (boolean rain : new boolean[] {false, true})
                        for (boolean direct : new boolean[] {false, true})
                            for (boolean details : new boolean[] {false, true}) {
                                var off = settings(shape, 4, distance);
                                var on = off.copy();
                                on.uniformEmission = true;
                                var plain = BloomStyle.global(off);
                                var different =
                                        new BloomStyle(
                                                BloomConfig.Shape.STARBURST,
                                                2.1f,
                                                64,
                                                BloomConfig.Flare.HORIZONTAL,
                                                .7f,
                                                72);
                                // I keep automatic, painted, colored and painted-colored groups
                                // distinct in this test.
                                var groups =
                                        List.of(
                                                new SourceMask.RenderGroup(0, plain, false, false),
                                                new SourceMask.RenderGroup(2, plain, true, false),
                                                new SourceMask.RenderGroup(
                                                        5, different, false, true),
                                                new SourceMask.RenderGroup(
                                                        9, different, true, true));
                                var before =
                                        BloomGraph.createGroupedConfig(
                                                1281, 721, off, true, colored, groups, direct,
                                                details);
                                var after =
                                        BloomGraph.createGroupedConfig(
                                                1281, 721, on, true, colored, groups, direct,
                                                details);
                                if (rain) {
                                    before = BloomGraph.forWeather(before);
                                    after = BloomGraph.forWeather(after);
                                }
                                assertOnlyExtractionSelectionChanges(before, after, 2);
                                assertEquals(
                                        4,
                                        extractions(after),
                                        "The option cannot add or split source-style groups");
                                assertEquals(
                                        plain,
                                        BloomStyle.global(on),
                                        "Uniform emission is not a new batching key");
                                assertFalse(off.uniformEmission);
                                assertTrue(on.uniformEmission);
                            }
    }

    @Test
    void automaticWorldSourcesAndHeldItemsRetainTheEntireOriginalGraph() {
        for (var shape : BloomConfig.Shape.values())
            for (int pixels : new int[] {3, 4})
                for (boolean distance : new boolean[] {false, true})
                    for (var flare : BloomConfig.Flare.values())
                        for (boolean colored : new boolean[] {false, true})
                            for (boolean rain : new boolean[] {false, true}) {
                                var off = settings(shape, pixels, distance);
                                off.flareStyle = flare;
                                var on = off.copy();
                                on.uniformEmission = true;
                                var before =
                                        BloomGraph.createConfig(73, 55, off, false, colored, true);
                                var after =
                                        BloomGraph.createConfig(73, 55, on, false, colored, true);
                                if (rain) {
                                    before = BloomGraph.forWeather(before);
                                    after = BloomGraph.forWeather(after);
                                }
                                assertEquals(
                                        before,
                                        after,
                                        "Automatic world sources must not use painted-face correction");
                                assertEquals(
                                        HeldItemGraph.create(73, 55, off),
                                        HeldItemGraph.create(73, 55, on),
                                        "Held emission, coverage, filtering, and compositing retain their existing graph");
                            }
    }

    @Test
    void activityUsesMatchingNonDistanceExtractionAndTheSameReductionAndActiveSources() {
        for (boolean distance : new boolean[] {false, true})
            for (boolean custom : new boolean[] {false, true})
                for (boolean colored : new boolean[] {false, true})
                    for (boolean rain : new boolean[] {false, true}) {
                        var off = settings(BloomConfig.Shape.SOFT, 4, distance);
                        var on = off.copy();
                        on.uniformEmission = true;
                        var before = BloomActivity.createConfig(1919, 721, off, custom, colored);
                        var after = BloomActivity.createConfig(1919, 721, on, custom, colored);
                        if (rain) {
                            before = BloomGraph.forWeather(before);
                            after = BloomGraph.forWeather(after);
                        }
                        assertOnlyExtractionSelectionChanges(before, after, custom ? 1 : 0);
                        assertEquals(1, extractions(after));
                        assertFalse(
                                after.passes()
                                        .getFirst()
                                        .fragmentShaderId()
                                        .getPath()
                                        .endsWith("_distance"),
                                "Eligibility probing must keep its cheaper non-distance extraction");
                        var sources =
                                after.passes().getFirst().inputs().stream()
                                        .filter(input -> input.samplerName().equals("Sources"))
                                        .findFirst()
                                        .orElseThrow();
                        assertEquals(
                                new PostChainConfig.TextureInput(
                                        "Sources",
                                        SourceMask.ACTIVE_TEXTURE_ID,
                                        SourceMask.TEXTURE_SIZE,
                                        SourceMask.TEXTURE_SIZE,
                                        false),
                                sources);
                        assertEquals(
                                before.passes().subList(1, before.passes().size()),
                                after.passes().subList(1, after.passes().size()),
                                "Max reduction and idle result delivery must retain the same work");
                        assertEquals(BloomActivity.RESULT, after.passes().getLast().outputTarget());
                        assertTrue(
                                distance == off.distancePixels && distance == on.distancePixels,
                                "Probe construction must not alter the user's distance setting");
                    }
    }

    @Test
    void keepingBlockDetailChangesNeitherUniformEmissionSelectionNorItsWorkBudget() {
        for (float detail : new float[] {0, .85f, 1})
            for (boolean stable : new boolean[] {false, true}) {
                var off = settings(BloomConfig.Shape.DIAMOND, 3, false);
                off.preserveDetail = detail;
                off.stabilityMillis = stable ? 40 : 0;
                var on = off.copy();
                on.uniformEmission = true;
                var groups =
                        List.of(new SourceMask.RenderGroup(0, BloomStyle.global(off), true, false));
                var before =
                        BloomGraph.createGroupedConfig(640, 360, off, true, false, groups, false);
                var after =
                        BloomGraph.createGroupedConfig(640, 360, on, true, false, groups, false);
                assertOnlyExtractionSelectionChanges(before, after, 1);
                assertEquals(detail, on.preserveDetail);
                assertEquals(off.stabilityMillis, on.stabilityMillis);
            }
    }

    private static BloomConfig settings(BloomConfig.Shape shape, int pixels, boolean distance) {
        var config = new BloomConfig();
        config.uniformEmission = false;
        config.bloomShape = shape;
        config.pixelSize = pixels;
        config.distancePixels = distance;
        config.farPixelSize = 2;
        config.smoothBlend = .5f;
        config.stabilityMillis = 40;
        config.flareStyle = BloomConfig.Flare.CROSS;
        return config;
    }

    private static long extractions(PostChainConfig graph) {
        return graph.passes().stream()
                .filter(pass -> pass.fragmentShaderId().getPath().startsWith("post/extract"))
                .count();
    }

    private static void assertOnlyExtractionSelectionChanges(
            PostChainConfig before, PostChainConfig after, int expectedChanges) {
        assertEquals(
                before.internalTargets(),
                after.internalTargets(),
                "No extra render targets or changed dimensions/formats");
        assertEquals(before.passes().size(), after.passes().size(), "No extra GPU passes");
        int changes = 0;
        for (int i = 0; i < before.passes().size(); i++) {
            var old = before.passes().get(i);
            var next = after.passes().get(i);
            assertEquals(old.vertexShaderId(), next.vertexShaderId());
            assertEquals(old.outputTarget(), next.outputTarget());
            assertEquals(
                    old.inputs(),
                    next.inputs(),
                    "Source, mask, depth, color, scene, and history bindings are preserved");
            assertEquals(
                    old.uniforms(),
                    next.uniforms(),
                    "The option must not add per-pass buffers or alter effect settings");
            if (old.fragmentShaderId().equals(next.fragmentShaderId())) {
                assertEquals(old, next);
                continue;
            }
            String shader = old.fragmentShaderId().getPath();
            assertTrue(
                    shader.startsWith("post/extract") && !shader.startsWith("post/extract_auto"),
                    "Only extraction for painted geometry may change");
            assertEquals(
                    BloomGraph.id(
                            "post/extract_uniform" + shader.substring("post/extract".length())),
                    next.fragmentShaderId());
            changes++;
        }
        assertEquals(expectedChanges, changes);
    }
}
