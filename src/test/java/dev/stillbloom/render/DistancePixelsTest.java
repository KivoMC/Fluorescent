package dev.stillbloom.render;

import static org.junit.jupiter.api.Assertions.*;

import dev.stillbloom.config.BloomConfig;
import dev.stillbloom.config.BloomStyle;
import dev.stillbloom.preset.StylePresets;
import dev.stillbloom.render.source.SourceMask;

import net.minecraft.client.renderer.PostChainConfig;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.*;

class DistancePixelsTest {
    @Test
    void oldSettingsStayOffAndControlsRemainBounded(@TempDir Path dir) throws Exception {
        var path = dir.resolve("config.json");
        Files.writeString(path, "{\"pixelSize\":8}");
        var c = BloomConfig.load(path);
        assertFalse(c.distancePixels);
        assertEquals(4, c.renderPixelSize());
        assertEquals(4, c.farPixelSize);
        c.distancePixels = true;
        c.farPixelSize = 999;
        c.pixelDistanceStart = Float.NaN;
        c.pixelDistanceSpan = -1;
        c.sanitize();
        assertEquals(4, c.farPixelSize);
        assertFalse(c.usesDistancePixels());
        assertEquals(8, c.pixelDistanceStart);
        assertEquals(4, c.pixelDistanceSpan);
        c.farPixelSize = 2;
        c.pixelDistanceStart = 32;
        c.pixelDistanceSpan = 24;
        c.save(path);
        var loaded = BloomConfig.load(path);
        assertTrue(loaded.usesDistancePixels());
        assertEquals(2, loaded.renderPixelSize());
        assertEquals(56, loaded.pixelDistanceStart + loaded.pixelDistanceSpan);
    }

    @Test
    void equalSizesAndDisabledOptionUseExactlyTheOriginalGraph() {
        var c = new BloomConfig();
        c.pixelSize = 4;
        c.farPixelSize = 4;
        var old =
                BloomGraph.createGroupedConfig(
                        1281,
                        721,
                        c,
                        true,
                        true,
                        List.of(new SourceMask.RenderGroup(0, BloomStyle.global(c))));
        c.distancePixels = true;
        assertEquals(
                old,
                BloomGraph.createGroupedConfig(
                        1281,
                        721,
                        c,
                        true,
                        true,
                        List.of(new SourceMask.RenderGroup(0, BloomStyle.global(c)))));
        c.farPixelSize = 2;
        c.distancePixels = false;
        assertEquals(
                old,
                BloomGraph.createGroupedConfig(
                        1281,
                        721,
                        c,
                        true,
                        true,
                        List.of(new SourceMask.RenderGroup(0, BloomStyle.global(c)))));
    }

    @Test
    void allShapesUseOneFinePipelineWithoutExtraDrawPasses() {
        for (var shape : BloomConfig.Shape.values())
            for (int near : new int[] {3, 4})
                for (int far : new int[] {2, 3, 4})
                    for (boolean stable : new boolean[] {false, true}) {
                        if (far >= near) continue;
                        var c = new BloomConfig();
                        c.pixelSize = near;
                        c.farPixelSize = far;
                        c.distancePixels = true;
                        c.bloomShape = shape;
                        c.flareStyle = BloomConfig.Flare.CROSS;
                        c.stabilityMillis = stable ? 40 : 0;
                        var groups =
                                List.of(
                                        new SourceMask.RenderGroup(0, BloomStyle.global(c)),
                                        new SourceMask.RenderGroup(2, BloomStyle.global(c)));
                        var adaptive =
                                BloomGraph.createGroupedConfig(1921, 1081, c, true, true, groups);
                        var fine = c.copy();
                        fine.distancePixels = false;
                        fine.pixelSize = far;
                        var fixed =
                                BloomGraph.createGroupedConfig(
                                        1921, 1081, fine, true, true, groups);
                        assertEquals(fixed.passes().size(), adaptive.passes().size());
                        assertEquals(fixed.internalTargets(), adaptive.internalTargets());
                        assertEquals(
                                stable ? 2 : 0,
                                adaptive.passes().stream()
                                        .filter(p -> p.uniforms().containsKey("Stability"))
                                        .count());
                        for (var p : adaptive.passes())
                            if (!p.fragmentShaderId().getPath().equals("post/copy"))
                                assertTrue(p.fragmentShaderId().getPath().endsWith("_distance"));
                        assertEquals(
                                (1921 + far - 1) / far,
                                adaptive.internalTargets()
                                        .get(BloomGraph.id("group_0_near"))
                                        .width()
                                        .orElseThrow());
                    }
    }

    @Test
    void idleProbeRetainsOriginalEligibilityAndCheapFormat() {
        var c = new BloomConfig();
        c.pixelSize = 4;
        c.farPixelSize = 2;
        for (boolean custom : new boolean[] {false, true})
            for (boolean color : new boolean[] {false, true}) {
                c.distancePixels = false;
                var fixed = BloomActivity.createConfig(1281, 721, c, custom, color);
                c.distancePixels = true;
                assertEquals(fixed, BloomActivity.createConfig(1281, 721, c, custom, color));
            }
    }

    @Test
    void distanceParametersDoNotSplitStyleGroupsOrChangeSavedStyles() {
        var c = new BloomConfig();
        c.pixelSize = 4;
        var style = BloomStyle.global(c);
        var captured = StylePresets.capture(null, c);
        c.distancePixels = true;
        c.farPixelSize = 2;
        c.pixelDistanceStart = 20;
        assertEquals(style, BloomStyle.global(c));
        assertEquals(style, captured.resolve(c));
        var graph = BloomGraph.createConfig(1281, 721, c, false, false);
        assertEquals(8, graph.passes().getFirst().uniforms().get("BloomConfig").size());
        var composite =
                graph.passes().stream()
                        .filter(p -> p.fragmentShaderId().getPath().startsWith("post/composite"))
                        .findFirst()
                        .orElseThrow();
        assertFalse(DistanceTargets.carriesDistance(composite.outputTarget()));
        for (var input : composite.inputs())
            if (input instanceof PostChainConfig.TargetInput target)
                assertEquals(
                        !target.samplerName().equals("Scene")
                                && !target.samplerName().equals("Depth"),
                        target.bilinear());
    }
}
