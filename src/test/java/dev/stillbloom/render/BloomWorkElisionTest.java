package dev.stillbloom.render;

import static org.junit.jupiter.api.Assertions.*;

import dev.stillbloom.config.BloomConfig;
import dev.stillbloom.config.BloomStyle;
import dev.stillbloom.render.source.SourceMask;

import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.renderer.PostChainConfig;

import org.junit.jupiter.api.Test;

import java.util.*;

class BloomWorkElisionTest {
    @Test
    void mixedGroupsSelectMatchingExtractionWithoutExtraPasses() {
        for (boolean distance : new boolean[] {false, true})
            for (boolean color : new boolean[] {false, true}) {
                var c = new BloomConfig();
                c.pixelSize = 4;
                c.farPixelSize = 2;
                c.distancePixels = distance;
                var groups =
                        List.of(
                                new SourceMask.RenderGroup(0, BloomStyle.global(c), false),
                                new SourceMask.RenderGroup(3, BloomStyle.global(c), true));
                var graph = BloomGraph.createGroupedConfig(1281, 721, c, true, color, groups);
                var extracts =
                        graph.passes().stream()
                                .filter(
                                        p ->
                                                p.fragmentShaderId()
                                                        .getPath()
                                                        .startsWith("post/extract"))
                                .toList();
                assertEquals(2, extracts.size());
                String suffix = (color ? "_color" : "") + (distance ? "_distance" : "");
                assertEquals(
                        "post/extract_auto" + suffix, extracts.get(0).fragmentShaderId().getPath());
                assertEquals(
                        "post/extract_paint" + suffix,
                        extracts.get(1).fragmentShaderId().getPath());
                for (int i = 0; i < 2; i++) {
                    var p = extracts.get(i);
                    final boolean masked = i == 1;
                    assertEquals(
                            masked,
                            p.inputs().stream().anyMatch(in -> in.samplerName().equals("Custom")));
                    assertEquals(
                            masked,
                            p.inputs().stream()
                                    .anyMatch(in -> in.samplerName().equals("Geometry")));
                    assertEquals(
                            PostChain.MAIN_TARGET_ID,
                            ((PostChainConfig.TargetInput) p.inputs().getFirst()).targetId());
                }
                var conservative =
                        BloomGraph.createGroupedConfig(
                                1281,
                                721,
                                c,
                                true,
                                color,
                                List.of(
                                        new SourceMask.RenderGroup(0, BloomStyle.global(c)),
                                        new SourceMask.RenderGroup(3, BloomStyle.global(c))));
                assertEquals(conservative.passes().size(), graph.passes().size());
                assertEquals(conservative.internalTargets(), graph.internalTargets());
            }
    }

    @Test
    void zeroHaloRemovesOnlyTwoBlurPassesAndTheirTargets() {
        for (var shape : BloomConfig.Shape.values())
            for (boolean distance : new boolean[] {false, true})
                for (int size : new int[] {3, 4}) {
                    var c = new BloomConfig();
                    c.bloomShape = shape;
                    c.pixelSize = size;
                    c.distancePixels = distance;
                    c.wideHalo = .5f;
                    var original = BloomGraph.createConfig(1281, 721, c, true, true);
                    c.wideHalo = 0;
                    var trimmed = BloomGraph.createConfig(1281, 721, c, true, true);
                    int removed = shape == BloomConfig.Shape.STARBURST ? 0 : 2;
                    assertEquals(original.passes().size() - removed, trimmed.passes().size());
                    assertEquals(
                            original.internalTargets().size() - removed,
                            trimmed.internalTargets().size());
                    assertFalse(trimmed.internalTargets().containsKey(BloomGraph.id("wide")));
                    assertFalse(trimmed.internalTargets().containsKey(BloomGraph.id("wide_x")));
                    var composite =
                            trimmed.passes().stream()
                                    .filter(
                                            p ->
                                                    p.outputTarget()
                                                            .equals(BloomGraph.id("composite")))
                                    .findFirst()
                                    .orElseThrow();
                    var near =
                            composite.inputs().stream()
                                    .filter(in -> in.samplerName().equals("Near"))
                                    .findFirst()
                                    .orElseThrow();
                    var wide =
                            composite.inputs().stream()
                                    .filter(in -> in.samplerName().equals("Wide"))
                                    .findFirst()
                                    .orElseThrow();
                    assertEquals(
                            ((PostChainConfig.TargetInput) near).targetId(),
                            ((PostChainConfig.TargetInput) wide).targetId());
                    assertEquals(
                            ((PostChainConfig.TargetInput) near).bilinear(),
                            ((PostChainConfig.TargetInput) wide).bilinear());
                }
    }

    @Test
    void smallestPositiveHaloRetainsWidePasses() {
        var c = new BloomConfig();
        c.wideHalo = Float.MIN_VALUE;
        assertTrue(
                BloomGraph.createConfig(640, 360, c)
                        .internalTargets()
                        .containsKey(BloomGraph.id("wide")));
        c.wideHalo = -0f;
        assertFalse(
                BloomGraph.createConfig(640, 360, c)
                        .internalTargets()
                        .containsKey(BloomGraph.id("wide")));
    }

    @Test
    void groupedDistanceAndHistoryHaveNoDanglingInputs() {
        for (boolean distance : new boolean[] {false, true})
            for (boolean stable : new boolean[] {false, true}) {
                var c = new BloomConfig();
                c.pixelSize = 4;
                c.farPixelSize = 2;
                c.distancePixels = distance;
                c.stabilityMillis = stable ? 40 : 0;
                c.wideHalo = 0;
                c.flareStyle = BloomConfig.Flare.CROSS;
                var groups =
                        List.of(
                                new SourceMask.RenderGroup(0, BloomStyle.global(c), false),
                                new SourceMask.RenderGroup(2, BloomStyle.global(c), true));
                var graph = BloomGraph.createGroupedConfig(1281, 721, c, true, true, groups);
                var seen = new HashSet<>(Set.of(PostChain.MAIN_TARGET_ID));
                if (stable)
                    for (var group : groups) {
                        seen.add(BloomGraph.id("group_" + group.index() + "_history"));
                        seen.add(
                                BloomGraph.id(
                                        "group_" + group.index() + "_down_" + c.renderPixelSize()));
                    }
                for (var p : graph.passes()) {
                    for (var in : p.inputs())
                        if (in instanceof PostChainConfig.TargetInput target)
                            assertTrue(
                                    seen.contains(target.targetId()), target.targetId().toString());
                    seen.add(p.outputTarget());
                }
                assertEquals(
                        1,
                        graph.passes().stream()
                                .filter(p -> p.outputTarget().equals(PostChain.MAIN_TARGET_ID))
                                .count());
            }
    }

    @Test
    void maskClassificationParticipatesInRendererInvalidation() {
        var style = BloomStyle.global(new BloomConfig());
        assertNotEquals(
                new SourceMask.RenderGroup(0, style, false),
                new SourceMask.RenderGroup(0, style, true));
        assertTrue(new SourceMask.RenderGroup(0, style).custom());
    }
}
