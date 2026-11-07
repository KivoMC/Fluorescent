package dev.stillbloom.render;

import static org.junit.jupiter.api.Assertions.*;

import dev.stillbloom.config.BloomConfig;
import dev.stillbloom.config.BloomStyle;
import dev.stillbloom.render.item.HeldItemGraph;
import dev.stillbloom.render.source.SourceMask;

import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.renderer.PostChainConfig;
import net.minecraft.resources.Identifier;

import org.junit.jupiter.api.Test;

import java.util.*;

class DistanceTargetLifetimeTest {
    @Test
    void everyTransientDistanceTargetHasAProducerBeforeItsFirstRead() {
        for (var shape : BloomConfig.Shape.values())
            for (var flare : BloomConfig.Flare.values())
                for (boolean stable : new boolean[] {false, true})
                    for (float smooth : new float[] {0, .5f, 1}) {
                        var config = new BloomConfig();
                        config.pixelSize = 4;
                        config.farPixelSize = 2;
                        config.distancePixels = true;
                        config.bloomShape = shape;
                        config.flareStyle = flare;
                        config.stabilityMillis = stable ? 40 : 0;
                        config.smoothBlend = smooth;
                        var groups =
                                List.of(
                                        new SourceMask.RenderGroup(0, BloomStyle.global(config)),
                                        new SourceMask.RenderGroup(2, BloomStyle.global(config)));
                        check(
                                BloomGraph.createGroupedConfig(
                                        1281, 721, config, true, true, groups, true, true));
                        check(HeldItemGraph.create(321, 181, config));
                    }
    }

    private static void check(PostChainConfig original) {
        var distance = new DistanceTargets();
        var externals = new HashSet<Identifier>(Set.of(PostChain.MAIN_TARGET_ID));
        var graph = distance.initialize(original, externals);
        var ids = new HashSet<>(externals);
        ids.remove(PostChain.MAIN_TARGET_ID);
        assertFalse(ids.isEmpty());
        assertTrue(distance.active());
        assertEquals(original.passes(), graph.passes());
        var written = new HashSet<Identifier>();
        for (var pass : graph.passes()) {
            for (var input : pass.inputs())
                if (input instanceof PostChainConfig.TargetInput target
                        && ids.contains(target.targetId()))
                    assertTrue(
                            written.contains(target.targetId()),
                            "Pooled target read before write: " + target.targetId());
            if (ids.contains(pass.outputTarget())) {
                assertTrue(pass.fragmentShaderId().getPath().endsWith("_distance"));
                assertEquals(0, BloomPass.Bounds.clearAlpha(pass.fragmentShaderId().getPath()));
                assertFalse(graph.internalTargets().containsKey(pass.outputTarget()));
                written.add(pass.outputTarget());
            }
        }
        assertEquals(ids, written);
        distance.close();
        distance.close();
        assertFalse(distance.active());
    }

    @Test
    void occasionalActivityIntermediatesRemainOwnedUntilTheirChainCloses() {
        for (int width : new int[] {1, 640, 1281, 1920})
            for (int height : new int[] {1, 360, 721, 1080}) {
                var graph = BloomActivity.createConfig(width, height, new BloomConfig(), false);
                assertFalse(graph.internalTargets().isEmpty());
                for (var target : graph.internalTargets().values()) assertTrue(target.persistent());
            }
    }
}
