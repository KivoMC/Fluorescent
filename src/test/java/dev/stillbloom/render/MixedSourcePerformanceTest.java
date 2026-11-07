package dev.stillbloom.render;

import static org.junit.jupiter.api.Assertions.*;

import com.mojang.renderpearl.api.GpuFormat;

import dev.stillbloom.config.BloomConfig;
import dev.stillbloom.config.BloomStyle;
import dev.stillbloom.render.source.SourceMask;

import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.renderer.PostChainConfig;

import org.junit.jupiter.api.Test;

import java.util.*;

class MixedSourcePerformanceTest {
    @Test
    void directCompositeRemovesOnlyFinalCopyAndTemporary() {
        for (var shape : BloomConfig.Shape.values())
            for (boolean distance : new boolean[] {false, true})
                for (boolean stable : new boolean[] {false, true})
                    for (float detail : new float[] {0, .85f, 1})
                        for (int count : new int[] {2, 3}) {
                            var c = new BloomConfig();
                            c.bloomShape = shape;
                            c.pixelSize = 4;
                            c.farPixelSize = 2;
                            c.distancePixels = distance;
                            c.stabilityMillis = stable ? 40 : 0;
                            c.preserveDetail = detail;
                            c.flareStyle = BloomConfig.Flare.CROSS;
                            List<SourceMask.RenderGroup> groups = new ArrayList<>();
                            for (int i = 0; i < count; i++)
                                groups.add(
                                        new SourceMask.RenderGroup(
                                                i * 2, BloomStyle.global(c), i == 0, i == 1));
                            var old =
                                    BloomGraph.createGroupedConfig(
                                            1281, 721, c, true, true, groups, false);
                            var direct =
                                    BloomGraph.createGroupedConfig(
                                            1281, 721, c, true, true, groups, true);
                            assertEquals(old.passes().size() - 1, direct.passes().size());
                            var oldComposite = old.passes().get(old.passes().size() - 2);
                            var last = direct.passes().getLast();
                            assertEquals(
                                    old.passes().subList(0, old.passes().size() - 2),
                                    direct.passes().subList(0, direct.passes().size() - 1));
                            assertEquals(oldComposite.fragmentShaderId(), last.fragmentShaderId());
                            assertEquals(oldComposite.inputs(), last.inputs());
                            assertEquals(oldComposite.uniforms(), last.uniforms());
                            assertEquals(PostChain.MAIN_TARGET_ID, last.outputTarget());
                            var targets = new HashMap<>(old.internalTargets());
                            assertNotNull(targets.remove(oldComposite.outputTarget()));
                            assertEquals(targets, direct.internalTargets());
                            assertEquals(
                                    1,
                                    direct.passes().stream()
                                            .filter(
                                                    p ->
                                                            p.outputTarget()
                                                                    .equals(
                                                                            PostChain
                                                                                    .MAIN_TARGET_ID))
                                            .count());
                            var scene =
                                    (PostChainConfig.TargetInput)
                                            last.inputs().stream()
                                                    .filter(in -> in.samplerName().equals("Scene"))
                                                    .findFirst()
                                                    .orElseThrow();
                            assertNotEquals(
                                    PostChain.MAIN_TARGET_ID,
                                    scene.targetId(),
                                    "Never sample output color");
                            for (var in : last.inputs())
                                if (in instanceof PostChainConfig.TargetInput target
                                        && target.targetId().equals(PostChain.MAIN_TARGET_ID))
                                    assertTrue(target.useDepthBuffer());
                        }
    }

    @Test
    void singleGroupAlwaysRetainsCopyAndMatchingQuantization() {
        for (boolean distance : new boolean[] {false, true}) {
            var c = new BloomConfig();
            c.pixelSize = 4;
            c.farPixelSize = 2;
            c.distancePixels = distance;
            var groups = List.of(new SourceMask.RenderGroup(0, BloomStyle.global(c), true, false));
            assertEquals(
                    BloomGraph.createGroupedConfig(640, 360, c, true, false, groups, false),
                    BloomGraph.createGroupedConfig(640, 360, c, true, false, groups, true));
        }
    }

    @Test
    void directOutputRequiresMatchingFormat() {
        assertTrue(BloomRenderer.canCompositeDirectly(GpuFormat.RGBA8_UNORM));
        assertFalse(BloomRenderer.canCompositeDirectly(GpuFormat.RGBA16_FLOAT));
        assertFalse(BloomRenderer.canCompositeDirectly(GpuFormat.RGBA32_FLOAT));
        assertFalse(BloomRenderer.canCompositeDirectly(null));
    }

    @Test
    void eachGroupUsesOnlyItsRequiredMaskAndColorVariant() {
        for (boolean distance : new boolean[] {false, true})
            for (boolean colored : new boolean[] {false, true}) {
                var c = new BloomConfig();
                c.pixelSize = 4;
                c.farPixelSize = 2;
                c.distancePixels = distance;
                var groups =
                        List.of(
                                new SourceMask.RenderGroup(0, BloomStyle.global(c), false, false),
                                new SourceMask.RenderGroup(1, BloomStyle.global(c), true, false),
                                new SourceMask.RenderGroup(2, BloomStyle.global(c), false, true),
                                new SourceMask.RenderGroup(3, BloomStyle.global(c), true, true));
                var graph =
                        BloomGraph.createGroupedConfig(1281, 721, c, true, colored, groups, true);
                var extracts =
                        graph.passes().stream()
                                .filter(
                                        p ->
                                                p.fragmentShaderId()
                                                        .getPath()
                                                        .startsWith("post/extract"))
                                .toList();
                assertEquals(4, extracts.size());
                for (int i = 0; i < 4; i++) {
                    var g = groups.get(i);
                    var p = extracts.get(i);
                    boolean tint = colored && g.colored();
                    assertEquals(
                            "post/extract"
                                    + (g.custom() ? "_paint" : "_auto")
                                    + (tint ? "_color" : "")
                                    + (distance ? "_distance" : ""),
                            p.fragmentShaderId().getPath());
                    assertEquals(
                            tint,
                            p.inputs().stream().anyMatch(in -> in.samplerName().equals("Colors")));
                    assertEquals(
                            g.custom(),
                            p.inputs().stream().anyMatch(in -> in.samplerName().equals("Custom")));
                }
            }
    }
}
