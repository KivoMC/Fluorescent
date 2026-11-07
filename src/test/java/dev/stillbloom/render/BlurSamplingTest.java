package dev.stillbloom.render;

import static org.junit.jupiter.api.Assertions.*;

import dev.stillbloom.config.BloomConfig;
import dev.stillbloom.config.BloomStyle;
import dev.stillbloom.render.item.HeldItemGraph;
import dev.stillbloom.render.source.SourceMask;

import net.minecraft.client.renderer.PostChainConfig;
import net.minecraft.client.renderer.UniformValue;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

class BlurSamplingTest {
    @Test
    void ordinaryAndDistanceGraphsInterpolateOnlyFractionalKernels() {
        for (var shape : BloomConfig.Shape.values())
            for (int pixels : new int[] {2, 3, 4})
                for (boolean distance : new boolean[] {false, true})
                    for (boolean stable : new boolean[] {false, true})
                        for (boolean smooth : new boolean[] {false, true})
                            for (float halo : new float[] {0, .5f}) {
                                var config = settings(pixels, distance, stable, smooth);
                                config.bloomShape = shape;
                                config.wideHalo = halo;
                                var graph =
                                        BloomGraph.createConfig(
                                                1921, 1081, config, true, true, true);
                                assertKernel(graph, "", shape, config);
                                var composite =
                                        graph.passes().stream()
                                                .filter(
                                                        p ->
                                                                p.outputTarget()
                                                                        .equals(
                                                                                BloomGraph.id(
                                                                                        "composite")))
                                                .findFirst()
                                                .orElseThrow();
                                for (var in : composite.inputs())
                                    if (in instanceof PostChainConfig.TargetInput target) {
                                        boolean glow =
                                                List.of("Near", "Wide", "Core", "Flare")
                                                        .contains(target.samplerName());
                                        assertEquals(
                                                glow && (smooth || config.usesDistancePixels()),
                                                target.bilinear(),
                                                "Composite smoothing remains independent of blur sampling");
                                    }
                            }
    }

    @Test
    void groupedOverridesRetainEachShapesSamplerAcrossStabilityAndDistanceTransforms() {
        for (int pixels : new int[] {2, 3, 4})
            for (boolean distance : new boolean[] {false, true})
                for (boolean stable : new boolean[] {false, true})
                    for (boolean direct : new boolean[] {false, true}) {
                        var config = settings(pixels, distance, stable, true);
                        var groups = new ArrayList<SourceMask.RenderGroup>();
                        for (var shape : BloomConfig.Shape.values()) {
                            var style =
                                    new BloomStyle(
                                            shape, 1, 31.968254f, BloomConfig.Flare.OFF, 0, 8);
                            groups.add(new SourceMask.RenderGroup(2 + shape.ordinal() * 3, style));
                        }
                        var graph =
                                BloomGraph.createGroupedConfig(
                                        1921, 1081, config, true, true, groups, direct, true);
                        for (var group : groups)
                            assertKernel(
                                    graph,
                                    "group_" + group.index() + "_",
                                    group.style().shape(),
                                    config);
                        assertEquals(
                                stable ? groups.size() : 0,
                                graph.passes().stream()
                                        .filter(p -> p.uniforms().containsKey("Stability"))
                                        .count());
                    }
    }

    @Test
    void heldGraphsKeepNearestStraightAxesAndLinearDiamondAndStarburstInputs() {
        for (var shape : BloomConfig.Shape.values())
            for (int pixels : new int[] {2, 3, 4})
                for (boolean distance : new boolean[] {false, true})
                    for (boolean smooth : new boolean[] {false, true}) {
                        var config = settings(pixels, distance, true, smooth);
                        config.bloomShape = shape;
                        var graph = HeldItemGraph.create(1921, 1081, config);
                        assertKernel(graph, "held_", shape, config);
                        assertTrue(
                                graph.passes().stream()
                                        .noneMatch(p -> p.uniforms().containsKey("Stability")));
                        assertEquals(
                                120,
                                config.stabilityMillis,
                                "Building the held graph must not change world stability");
                    }
    }

    private static BloomConfig settings(
            int pixels, boolean distance, boolean stable, boolean smooth) {
        var config = new BloomConfig();
        config.pixelSize = distance ? 4 : pixels;
        config.farPixelSize = pixels;
        config.distancePixels = distance;
        config.stabilityMillis = stable ? 120 : 0;
        config.smoothBlend = smooth ? 1 : 0;
        config.wideHalo = .5f;
        config.glowWidth = 31.968254f;
        return config;
    }

    // I allow only rc.6's straight-blur sampling change when comparing frozen graphs.
    static PostChainConfig withNearestStraightBlurInputs(PostChainConfig frozen) {
        var passes = new ArrayList<PostChainConfig.Pass>();
        for (var pass : frozen.passes()) {
            String shader = pass.fragmentShaderId().getPath();
            var values = pass.uniforms().get("BloomConfig");
            boolean straight =
                    (shader.equals("post/blur") || shader.equals("post/blur_distance"))
                            && values != null
                            && ((values.get(0).equals(new UniformValue.FloatUniform(1))
                                            && values.get(1)
                                                    .equals(new UniformValue.FloatUniform(0)))
                                    || (values.get(0).equals(new UniformValue.FloatUniform(0))
                                            && values.get(1)
                                                    .equals(new UniformValue.FloatUniform(1))));
            if (!straight) {
                passes.add(pass);
                continue;
            }
            var inputs =
                    pass.inputs().stream()
                            .map(
                                    in -> {
                                        if (in instanceof PostChainConfig.TargetInput target
                                                && target.samplerName().equals("In")) {
                                            assertTrue(
                                                    target.bilinear(),
                                                    "Frozen reference should keep its original interpolated sampler");
                                            return (PostChainConfig.Input)
                                                    new PostChainConfig.TargetInput(
                                                            target.samplerName(),
                                                            target.targetId(),
                                                            target.useDepthBuffer(),
                                                            false);
                                        }
                                        return in;
                                    })
                            .toList();
            passes.add(
                    new PostChainConfig.Pass(
                            pass.vertexShaderId(),
                            pass.fragmentShaderId(),
                            inputs,
                            pass.outputTarget(),
                            pass.uniforms()));
        }
        return new PostChainConfig(frozen.internalTargets(), passes);
    }

    private static void assertKernel(
            PostChainConfig graph, String prefix, BloomConfig.Shape shape, BloomConfig config) {
        var passes =
                graph.passes().stream()
                        .filter(
                                p ->
                                        p.outputTarget().getPath().startsWith(prefix)
                                                && (p.fragmentShaderId()
                                                                .getPath()
                                                                .startsWith("post/blur")
                                                        || p.fragmentShaderId()
                                                                .getPath()
                                                                .startsWith("post/starburst")))
                        .toList();
        boolean starburst = shape == BloomConfig.Shape.STARBURST,
                diamond = shape == BloomConfig.Shape.DIAMOND;
        int count = starburst ? 1 : config.wideHalo == 0 ? 2 : 4;
        assertEquals(count, passes.size());
        String shader =
                "post/"
                        + (starburst ? "starburst" : "blur")
                        + (config.usesDistancePixels() ? "_distance" : "");
        String[] outputs =
                starburst
                        ? new String[] {"near"}
                        : new String[] {"near_x", "near", "wide_x", "wide"};
        String[] inputs = {"down_" + config.renderPixelSize(), "near_x", "near", "wide_x"};
        for (int i = 0; i < passes.size(); i++) {
            var pass = passes.get(i);
            assertEquals(BloomGraph.id(shader), pass.fragmentShaderId());
            assertEquals(BloomGraph.id(prefix + outputs[i]), pass.outputTarget());
            assertEquals(1, pass.inputs().size());
            var input =
                    assertInstanceOf(PostChainConfig.TargetInput.class, pass.inputs().getFirst());
            assertEquals("In", input.samplerName());
            assertFalse(input.useDepthBuffer());
            assertEquals(BloomGraph.id(prefix + inputs[i]), input.targetId());
            assertEquals(
                    diamond || starburst,
                    input.bilinear(),
                    "Only fractional kernels should interpolate: " + pass.outputTarget());
            if (!starburst) {
                // I keep both blur passes' original axes and kernel modes in this comparison.
                float x = diamond ? .70710678f : i % 2 == 0 ? 1 : 0;
                float y = diamond ? (i % 2 == 0 ? .70710678f : -.70710678f) : i % 2 == 0 ? 0 : 1;
                var values = pass.uniforms().get("BloomConfig");
                assertEquals(new UniformValue.FloatUniform(x), values.get(0));
                assertEquals(new UniformValue.FloatUniform(y), values.get(1));
                assertEquals(
                        new UniformValue.FloatUniform(shape == BloomConfig.Shape.SOFT ? 0 : 1),
                        values.get(4));
            }
        }
    }
}
