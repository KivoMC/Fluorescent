package dev.stillbloom.render;

import static org.junit.jupiter.api.Assertions.*;

import com.mojang.renderpearl.api.pipeline.*;

import dev.stillbloom.config.*;
import dev.stillbloom.preset.DefaultSetup;
import dev.stillbloom.render.item.HeldItemGraph;
import dev.stillbloom.render.source.SourceMask;

import net.minecraft.client.renderer.*;
import net.minecraft.resources.Identifier;

import org.junit.jupiter.api.Test;

import java.util.*;

class PixelThreeTest {
    @Test
    void defaultAndControlsUseThreeAndUnsupportedGridsFallBackToFour() {
        assertArrayEquals(new int[] {2, 3, 4}, BloomConfig.pixelSizes());
        assertEquals(3, new BloomConfig().pixelSize);
        assertEquals(3, DefaultSetup.copy().settings().pixelSize);
        for (int legacy : new int[] {6, 8, 16, 32}) {
            var c = new BloomConfig();
            c.pixelSize = legacy;
            c.sanitize();
            assertEquals(4, c.pixelSize);
            c.pixelSize = legacy;
            c.cyclePixelSize();
            assertEquals(4, c.pixelSize);
            c.cyclePixelSize();
            assertEquals(2, c.pixelSize);
            c.cyclePixelSize();
            assertEquals(3, c.pixelSize);
        }
    }

    @Test
    void shapesMasksColorsRainAndSmoothnessRetainBindingsOnActualThreePixelTargets() {
        for (var shape : BloomConfig.Shape.values())
            for (boolean stable : new boolean[] {false, true})
                for (boolean distance : new boolean[] {false, true})
                    for (boolean painted : new boolean[] {false, true})
                        for (boolean colored : new boolean[] {false, true})
                            for (boolean uniform : new boolean[] {false, true})
                                for (float smooth : new float[] {0, .35f, 1}) {
                                    var c = new BloomConfig();
                                    c.pixelSize = distance ? 4 : 3;
                                    c.farPixelSize = 3;
                                    c.distancePixels = distance;
                                    c.stabilityMillis = stable ? 40 : 0;
                                    c.bloomShape = shape;
                                    c.smoothBlend = smooth;
                                    c.uniformEmission = uniform;
                                    c.flareStyle = BloomConfig.Flare.CROSS;
                                    var groups =
                                            List.of(
                                                    new SourceMask.RenderGroup(
                                                            2,
                                                            BloomStyle.global(c),
                                                            painted,
                                                            colored));
                                    var graph =
                                            BloomGraph.createGroupedConfig(
                                                    1282, 722, c, true, true, groups, false, true);
                                    var extract = graph.passes().getFirst();
                                    assertEquals(
                                            BloomGraph.id(
                                                    "group_2_"
                                                            + (stable ? "extracted_3" : "down_3")),
                                            extract.outputTarget());
                                    assertTrue(
                                            PostPipelineCache.threePixelExtraction(
                                                    extract.fragmentShaderId(),
                                                    extract.outputTarget()));
                                    assertEquals(
                                            1,
                                            extract.inputs().stream()
                                                    .filter(i -> i.samplerName().equals("Depth"))
                                                    .count());
                                    assertEquals(
                                            painted,
                                            extract.inputs().stream()
                                                    .anyMatch(
                                                            i ->
                                                                    i.samplerName()
                                                                            .equals("Geometry")));
                                    assertEquals(
                                            colored,
                                            extract.inputs().stream()
                                                    .anyMatch(
                                                            i -> i.samplerName().equals("Colors")));
                                    assertTrue(extract.uniforms().containsKey("SourceMask"));
                                    assertEquals(
                                            stable ? 1 : 0,
                                            graph.passes().stream()
                                                    .filter(
                                                            p ->
                                                                    p.uniforms()
                                                                            .containsKey(
                                                                                    "Stability"))
                                                    .count());
                                    assertFalse(
                                            graph.internalTargets()
                                                    .containsKey(BloomGraph.id("group_2_down_2")));
                                    if (stable) {
                                        var history = graph.passes().get(1);
                                        assertEquals(
                                                BloomGraph.id(
                                                        "post/stabilize"
                                                                + (distance ? "_distance" : "")),
                                                history.fragmentShaderId());
                                        assertEquals(
                                                extract.outputTarget(),
                                                ((PostChainConfig.TargetInput)
                                                                history.inputs().getFirst())
                                                        .targetId());
                                        assertEquals(
                                                BloomGraph.id("group_2_down_3"),
                                                history.outputTarget());
                                    }
                                    graph.internalTargets()
                                            .forEach(
                                                    (id, target) -> {
                                                        boolean full =
                                                                id.getPath().endsWith("composite");
                                                        assertEquals(
                                                                full ? 1282 : 428,
                                                                target.width().orElseThrow());
                                                        assertEquals(
                                                                full ? 722 : 241,
                                                                target.height().orElseThrow());
                                                    });
                                    var wet = BloomGraph.forWeather(graph).passes().getFirst();
                                    assertTrue(
                                            PostPipelineCache.threePixelExtraction(
                                                    wet.fragmentShaderId(), wet.outputTarget()));
                                    assertTrue(wet.uniforms().containsKey("RainFog"));
                                    assertEquals(extract.outputTarget(), wet.outputTarget());
                                }
    }

    @Test
    void distanceUsesOneFineGridAndHeldItemsUseTheSameSamplingWithoutHistory() {
        for (int near : BloomConfig.pixelSizes())
            for (int far : BloomConfig.pixelSizes())
                for (var shape : BloomConfig.Shape.values()) {
                    var c = new BloomConfig();
                    c.pixelSize = near;
                    c.farPixelSize = far;
                    c.distancePixels = true;
                    c.bloomShape = shape;
                    int fine = Math.min(near, far);
                    for (int[] size :
                            List.of(
                                    new int[] {1, 1},
                                    new int[] {4, 4},
                                    new int[] {1279, 719},
                                    new int[] {1281, 721},
                                    new int[] {1921, 1081})) {
                        var graph = HeldItemGraph.create(size[0], size[1], c);
                        var first = graph.passes().getFirst();
                        assertEquals(
                                BloomGraph.id("held_down_" + (fine == 3 ? 3 : 2)),
                                first.outputTarget());
                        assertEquals(
                                fine == 3,
                                PostPipelineCache.threePixelExtraction(
                                        first.fragmentShaderId(), first.outputTarget()));
                        assertFalse(
                                graph.passes().stream()
                                        .anyMatch(p -> p.uniforms().containsKey("Stability")));
                        var target = graph.internalTargets().get(BloomGraph.id("held_near"));
                        assertEquals((size[0] + fine - 1) / fine, target.width().orElseThrow());
                        assertEquals((size[1] + fine - 1) / fine, target.height().orElseThrow());
                    }
                }
    }

    @Test
    void specializationIsCachedSeparatelyAndDoesNotTouchTwoFourOrForeignPasses() {
        for (String shader :
                new String[] {
                    "extract_auto",
                    "extract_paint_color_rain_distance",
                    "held_downsample",
                    "held_downsample_distance"
                }) {
            var id = BloomGraph.id("post/" + shader);
            var three =
                    PostPipelineCache.finish(builder(id), id, BloomGraph.id("group_7_extracted_3"));
            assertSame(
                    three, PostPipelineCache.finish(builder(id), id, BloomGraph.id("held_down_3")));
            var two = PostPipelineCache.finish(builder(id), id, BloomGraph.id("down_2"));
            assertNotEquals(two.getShaderDefines(), three.getShaderDefines());
            assertSame(
                    two,
                    PostPipelineCache.finish(builder(id), id, BloomGraph.id("group_0_down_4")));
        }
        assertFalse(
                PostPipelineCache.threePixelExtraction(
                        Identifier.parse("other:post/extract"), BloomGraph.id("down_3")));
        assertFalse(
                PostPipelineCache.threePixelExtraction(
                        BloomGraph.id("post/blur"), BloomGraph.id("down_3")));
    }

    @Test
    void idleReductionUsesActualThreeGridDimensionsAndCoversOddLastCells() {
        for (int width : new int[] {1, 4, 1279, 1281, 1282, 1921}) {
            var graph = BloomActivity.createConfig(width, 721, new BloomConfig(), true, true);
            int w = (width + 2) / 3, h = (721 + 2) / 3;
            assertEquals(
                    w, graph.internalTargets().get(BloomGraph.id("down_3")).width().orElseThrow());
            for (var pass : graph.passes().subList(1, graph.passes().size())) {
                w = (w + 3) / 4;
                h = (h + 3) / 4;
                if (pass.outputTarget().equals(BloomActivity.RESULT)) {
                    assertEquals(1, w);
                    assertEquals(1, h);
                } else {
                    var target = graph.internalTargets().get(pass.outputTarget());
                    assertEquals(w, target.width().orElseThrow());
                    assertEquals(h, target.height().orElseThrow());
                }
            }
        }
    }

    @Test
    void distanceIdleProbeNeverAveragesMoreCoarselyThanLiveEmission() {
        for (int[] grids : List.of(new int[] {3, 2}, new int[] {4, 3}, new int[] {4, 2})) {
            var c = new BloomConfig();
            c.pixelSize = grids[0];
            c.farPixelSize = grids[1];
            c.distancePixels = true;
            var fine = c.copy();
            fine.pixelSize = grids[1];
            fine.distancePixels = false;
            assertEquals(
                    BloomActivity.createConfig(1281, 721, fine, true, true),
                    BloomActivity.createConfig(1281, 721, c, true, true));
        }
    }

    private static RenderPipeline.Builder builder(Identifier shader) {
        return RenderPipeline.builder(RenderPipelines.POST_PROCESSING_SNIPPET)
                .withLocation(BloomGraph.id("test/grid"))
                .withVertexShader("core/screenquad")
                .withFragmentShader(shader)
                .withColorTargetState(ColorTargetState.DEFAULT);
    }
}
