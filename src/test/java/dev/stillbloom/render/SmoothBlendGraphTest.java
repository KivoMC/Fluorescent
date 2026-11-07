package dev.stillbloom.render;

import static org.junit.jupiter.api.Assertions.*;

import dev.stillbloom.config.BloomConfig;
import dev.stillbloom.config.BloomStyle;
import dev.stillbloom.render.item.HeldItemGraph;
import dev.stillbloom.render.source.SourceMask;

import net.minecraft.client.renderer.PostChainConfig;
import net.minecraft.client.renderer.UniformValue;

import org.junit.jupiter.api.Test;

import java.util.*;

class SmoothBlendGraphTest {
    @Test
    void zeroBlendRetainsFrozen22GraphExceptStraightBlurSamplingForEveryShapeGridAndOptionalPath() {
        for (var shape : BloomConfig.Shape.values())
            for (int pixels : new int[] {4})
                for (boolean distance : new boolean[] {false, true})
                    for (boolean custom : new boolean[] {false, true})
                        for (boolean colored : new boolean[] {false, true})
                            for (boolean details : new boolean[] {false, true}) {
                                var c = new BloomConfig();
                                c.bloomShape = shape;
                                c.pixelSize = pixels;
                                c.distancePixels = distance;
                                c.farPixelSize = 4;
                                c.flareStyle = BloomConfig.Flare.CROSS;
                                c.preserveDetail = details ? 0 : 1;
                                c.selfLitPaint = false;
                                assertEquals(
                                        BlurSamplingTest.withNearestStraightBlurInputs(
                                                Reference22Graph.createConfig(
                                                        1281, 721, c, custom, colored, details)),
                                        BloomGraph.createConfig(
                                                1281, 721, c, custom, colored, details));
                                var groups =
                                        List.of(
                                                new SourceMask.RenderGroup(
                                                        0, BloomStyle.global(c), custom, colored));
                                assertEquals(
                                        BlurSamplingTest.withNearestStraightBlurInputs(
                                                Reference22Graph.createGroupedConfig(
                                                        1281, 721, c, custom, colored, groups, true,
                                                        details)),
                                        BloomGraph.createGroupedConfig(
                                                1281, 721, c, custom, colored, groups, true,
                                                details));
                            }
    }

    @Test
    void smoothingChangesOnlyCompositeSamplingShaderAndReservedUniform() {
        for (var shape : BloomConfig.Shape.values())
            for (int pixels : new int[] {3, 4})
                for (boolean distance : new boolean[] {false, true})
                    for (boolean details : new boolean[] {false, true})
                        for (float amount : new float[] {.25f, .5f, 1}) {
                            var c = new BloomConfig();
                            c.bloomShape = shape;
                            c.pixelSize = pixels;
                            c.distancePixels = distance;
                            c.farPixelSize = 2;
                            c.flareStyle = BloomConfig.Flare.HORIZONTAL;
                            c.preserveDetail = details ? 0 : 1;
                            var old = BloomGraph.createConfig(1281, 721, c, true, true, details);
                            c.smoothBlend = amount;
                            var blended =
                                    BloomGraph.createConfig(1281, 721, c, true, true, details);
                            assertEquals(old.internalTargets(), blended.internalTargets());
                            assertEquals(old.passes().size(), blended.passes().size());
                            for (int i = 0; i < old.passes().size(); i++) {
                                var before = old.passes().get(i);
                                var after = blended.passes().get(i);
                                if (!before.outputTarget().equals(BloomGraph.id("composite"))) {
                                    assertEquals(before, after);
                                    continue;
                                }
                                String shader = before.fragmentShaderId().getPath();
                                assertEquals(
                                        shader.endsWith("_distance")
                                                ? shader.substring(0, shader.length() - 9)
                                                        + "_smooth_distance"
                                                : shader + "_smooth",
                                        after.fragmentShaderId().getPath());
                                var expected = new HashMap<>(before.uniforms());
                                var values = new ArrayList<>(expected.get("BloomConfig"));
                                values.set(10, new UniformValue.FloatUniform(amount));
                                expected.put("BloomConfig", values);
                                assertEquals(expected, after.uniforms());
                                assertEquals(before.inputs().size(), after.inputs().size());
                                for (int j = 0; j < before.inputs().size(); j++) {
                                    var source = before.inputs().get(j);
                                    var result = after.inputs().get(j);
                                    if (source instanceof PostChainConfig.TargetInput t
                                            && Set.of("Near", "Wide", "Core", "Flare")
                                                    .contains(t.samplerName())) {
                                        assertEquals(
                                                new PostChainConfig.TargetInput(
                                                        t.samplerName(),
                                                        t.targetId(),
                                                        t.useDepthBuffer(),
                                                        true),
                                                result);
                                    } else assertEquals(source, result);
                                }
                            }
                        }
    }

    @Test
    void heldCompositeUsesSameTargetsProtectionAndSmoothVariant() {
        for (int pixels : new int[] {3, 4})
            for (boolean distance : new boolean[] {false, true}) {
                var c = new BloomConfig();
                c.pixelSize = pixels;
                c.distancePixels = distance;
                c.farPixelSize = 2;
                c.flareStyle = BloomConfig.Flare.CROSS;
                var old = HeldItemGraph.create(576, 384, c);
                c.smoothBlend = 1;
                var blended = HeldItemGraph.create(576, 384, c);
                assertEquals(old.internalTargets(), blended.internalTargets());
                assertEquals(old.passes().size(), blended.passes().size());
                for (int i = 0; i < old.passes().size(); i++) {
                    var a = old.passes().get(i);
                    var b = blended.passes().get(i);
                    if (!a.outputTarget().getPath().endsWith("composite")) {
                        assertEquals(a, b);
                        continue;
                    }
                    assertEquals(
                            BloomGraph.id(
                                    "post/composite_held_smooth"
                                            + (c.usesDistancePixels() ? "_distance" : "")),
                            b.fragmentShaderId());
                    var coverage =
                            b.inputs().stream()
                                    .filter(in -> in.samplerName().equals("Coverage"))
                                    .findFirst()
                                    .orElseThrow();
                    assertEquals(
                            new PostChainConfig.TargetInput(
                                    "Coverage", BloomGraph.id("held_coverage"), false, false),
                            coverage);
                    assertEquals(
                            new UniformValue.FloatUniform(1),
                            b.uniforms().get("BloomConfig").get(10));
                }
            }
    }

    @Test
    void smoothingDoesNotSplitSourceStylesOrAddOwnedTargets() {
        var c = new BloomConfig();
        c.pixelSize = 4;
        c.distancePixels = true;
        c.farPixelSize = 2;
        var first = BloomStyle.global(c);
        var second =
                new BloomStyle(BloomConfig.Shape.DIAMOND, 4, 64, BloomConfig.Flare.CROSS, .4f, 64);
        var groups =
                List.of(
                        new SourceMask.RenderGroup(0, first),
                        new SourceMask.RenderGroup(1, second));
        var old = BloomGraph.createGroupedConfig(1920, 1080, c, true, true, groups, true, true);
        long bytes = BloomRenderer.estimateRetainedBytes(1920, 1080, c, groups, old);
        c.smoothBlend = .5f;
        assertEquals(first, BloomStyle.global(c));
        var blended = BloomGraph.createGroupedConfig(1920, 1080, c, true, true, groups, true, true);
        assertEquals(old.internalTargets(), blended.internalTargets());
        assertEquals(old.passes().size(), blended.passes().size());
        assertEquals(bytes, BloomRenderer.estimateRetainedBytes(1920, 1080, c, groups, blended));
    }
}
