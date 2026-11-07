package dev.stillbloom.render;

import static org.junit.jupiter.api.Assertions.*;

import dev.stillbloom.config.BloomConfig;
import dev.stillbloom.render.item.HeldItemGraph;

import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.renderer.PostChainConfig;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

class HeldItemGraphTest {
    @Test
    void allShapesKeepExistingPassCountsDimensionsAndBlurMath() {
        for (var shape : BloomConfig.Shape.values())
            for (int pixels : new int[] {3, 4})
                for (boolean distance : new boolean[] {false, true})
                    for (var flare : BloomConfig.Flare.values()) {
                        var config = new BloomConfig();
                        config.pixelSize = pixels;
                        config.bloomShape = shape;
                        config.flareStyle = flare;
                        config.flareStrength = 0.7f;
                        config.distancePixels = distance;
                        config.farPixelSize = 2;
                        config.stabilityMillis = 80;
                        var original = config.copy();
                        original.preserveDetail = 0;
                        original.stabilityMillis = 0;
                        var base = BloomGraph.createConfig(73, 55, original, false, false, false);
                        var held = HeldItemGraph.create(73, 55, config);
                        assertEquals(base.passes().size(), held.passes().size());
                        assertEquals(base.internalTargets().size(), held.internalTargets().size());
                        base.internalTargets()
                                .forEach(
                                        (id, spec) ->
                                                assertEquals(
                                                        spec,
                                                        held.internalTargets()
                                                                .get(
                                                                        BloomGraph.id(
                                                                                "held_"
                                                                                        + id
                                                                                                .getPath()))));
                        for (int index = 1; index < base.passes().size(); index++) {
                            var expected = base.passes().get(index);
                            var actual = held.passes().get(index);
                            assertEquals(expected.uniforms(), actual.uniforms());
                            if (!expected.outputTarget().equals(BloomGraph.id("composite"))) {
                                assertEquals(
                                        expected.fragmentShaderId(), actual.fragmentShaderId());
                            }
                        }
                        // I check that building a held graph doesn't change saved world stability
                        // or detail protection.
                        assertEquals(80, config.stabilityMillis);
                        assertEquals(0.85f, config.preserveDetail);
                    }
    }

    @Test
    void graphsNeedOnlySceneEmissionAndCoverageAndNeverWorldSources() {
        for (boolean distance : new boolean[] {false, true}) {
            var config = new BloomConfig();
            config.distancePixels = distance;
            config.pixelSize = 4;
            config.farPixelSize = 2;
            var graph = HeldItemGraph.create(144, 96, config);
            Set<net.minecraft.resources.Identifier> known =
                    new HashSet<>(graph.internalTargets().keySet());
            known.addAll(
                    Set.of(
                            PostChain.MAIN_TARGET_ID,
                            BloomGraph.id("held_emission"),
                            BloomGraph.id("held_coverage")));
            assertEquals(1, graph.passes().getFirst().inputs().size());
            var emission =
                    (PostChainConfig.TargetInput) graph.passes().getFirst().inputs().getFirst();
            assertEquals(BloomGraph.id("held_emission"), emission.targetId());
            assertTrue(graph.passes().getFirst().uniforms().isEmpty());
            long composites = 0;
            for (var pass : graph.passes()) {
                assertFalse(pass.uniforms().containsKey("SourceMask"));
                assertFalse(pass.uniforms().containsKey("Stability"));
                assertTrue(known.contains(pass.outputTarget()));
                for (var input : pass.inputs()) {
                    assertInstanceOf(PostChainConfig.TargetInput.class, input);
                    var target = (PostChainConfig.TargetInput) input;
                    assertFalse(target.useDepthBuffer());
                    assertTrue(known.contains(target.targetId()));
                    assertFalse(
                            Set.of("Sources", "Custom", "Geometry", "Colors", "Details", "Depth")
                                    .contains(target.samplerName()));
                }
                if (pass.fragmentShaderId().getPath().startsWith("post/composite_held")) {
                    composites++;
                    assertFalse(DistanceTargets.carriesDistance(pass.outputTarget()));
                    var coverage = (PostChainConfig.TargetInput) pass.inputs().getLast();
                    assertEquals("Coverage", coverage.samplerName());
                    assertEquals(BloomGraph.id("held_coverage"), coverage.targetId());
                    assertFalse(coverage.bilinear());
                }
            }
            assertEquals(1, composites);
            assertEquals(PostChain.MAIN_TARGET_ID, graph.passes().getLast().outputTarget());
            assertTrue(
                    graph.internalTargets().keySet().stream()
                            .allMatch(id -> id.getPath().startsWith("held_")));
        }
    }
}
