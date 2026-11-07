package dev.stillbloom.config;

import static org.junit.jupiter.api.Assertions.*;

import dev.stillbloom.mask.TextureMasks;
import dev.stillbloom.render.BloomGraph;
import dev.stillbloom.render.source.SourceMask;

import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.renderer.PostChainConfig;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.*;

class BloomOverrideTest {
    @Test
    void overridesPersistAndInheritFutureGlobalChanges(@TempDir Path dir) throws Exception {
        var path = dir.resolve("masks.json");
        Files.writeString(
                path, "{\"blocks\":{\"minecraft:torch\":{\"strength\":2,\"textures\":{}}}}");
        var masks = TextureMasks.load(path);
        var p = masks.profile("minecraft:torch");
        assertNull(p.override);
        p.override = new BloomOverride();
        p.override.shape = BloomConfig.Shape.STARBURST;
        p.override.flare = BloomConfig.Flare.HORIZONTAL;
        p.override.width = 32f;
        assertTrue(masks.save(path));
        masks = TextureMasks.load(path);
        var global = new BloomConfig();
        global.intensity = 2;
        var style = masks.style("minecraft:torch", global);
        assertEquals(BloomConfig.Shape.STARBURST, style.shape());
        assertEquals(32, style.width());
        assertEquals(2, style.intensity());
        global.intensity = 6;
        global.flareLength = 120;
        style = masks.style("minecraft:torch", global);
        assertEquals(6, style.intensity());
        assertEquals(120, style.flareLength());
        assertEquals(global.bloomShape, masks.style("minecraft:lantern", global).shape());
        masks.profile("minecraft:torch").override.intensity = 0f;
        assertEquals(0, masks.style("minecraft:torch", global).intensity());
    }

    @Test
    void masksAndOverridesResetIndependently() {
        var masks = new TextureMasks();
        var mask = new TextureMasks.Mask(16, 16);
        mask.set(2, 3, true);
        masks.put("torch", "texture", mask);
        var p = masks.profile("torch");
        p.override = new BloomOverride();
        p.override.shape = BloomConfig.Shape.DIAMOND;
        masks.reset("torch", "texture");
        assertSame(p, masks.profile("torch"));
        assertNotNull(p.override);
        masks.put("torch", "texture", mask);
        masks.removeOverride("torch");
        assertNull(p.override);
        assertTrue(masks.mask("torch", "texture").get(2, 3));
        masks.reset("torch", "texture");
        assertNull(masks.profile("torch"));
    }

    @Test
    void invalidOverridesInheritAndEqualEffectiveStylesBatch() {
        var o = new BloomOverride();
        o.intensity = Float.NaN;
        o.width = -1f;
        o.flareStrength = Float.POSITIVE_INFINITY;
        o.flareLength = 900f;
        o.sanitize();
        assertNull(o.intensity);
        assertNull(o.flareStrength);
        assertEquals(8, o.width);
        assertEquals(128, o.flareLength);
        var g = new BloomConfig();
        o = new BloomOverride();
        o.shape = g.bloomShape;
        o.width = g.glowWidth;
        o.flareLength = 128f;
        assertEquals(
                BloomStyle.global(g), o.resolve(g)); // I ignore flare reach when the flare is off.
        o.flare = BloomConfig.Flare.CROSS;
        assertNotEquals(BloomStyle.global(g), o.resolve(g));
        var resolved = o.resolve(g).apply(g);
        assertEquals(BloomConfig.Flare.CROSS, resolved.flareStyle);
        assertEquals(BloomConfig.Flare.OFF, g.flareStyle);
        assertEquals(g.preserveDetail, resolved.preserveDetail);
        assertEquals(g.pixelSize, resolved.pixelSize);
    }

    @Test
    void mixedStylesExtractOriginalSceneAndProtectAllSourceBlocks() {
        var config = new BloomConfig();
        config.stabilityMillis = 0;
        var o = new BloomOverride();
        o.shape = BloomConfig.Shape.STARBURST;
        o.flare = BloomConfig.Flare.HORIZONTAL;
        var groups =
                List.of(
                        new SourceMask.RenderGroup(0, BloomStyle.global(config)),
                        new SourceMask.RenderGroup(2, o.resolve(config)));
        var graph = BloomGraph.createGroupedConfig(1281, 721, config, true, groups);
        int composites = 0, extracts = 0, copies = 0;
        var seen = new HashSet<>(Set.of(PostChain.MAIN_TARGET_ID));
        for (var pass : graph.passes()) {
            for (var in : pass.inputs())
                if (in instanceof PostChainConfig.TargetInput target)
                    assertTrue(
                            seen.contains(target.targetId()),
                            "Input must exist before use: " + target.targetId());
            String shader = pass.fragmentShaderId().getPath();
            if (shader.equals("post/extract_paint")) {
                assertEquals(0, composites, "Every extraction must precede every composite");
                var scene = (PostChainConfig.TargetInput) pass.inputs().getFirst();
                assertEquals(PostChain.MAIN_TARGET_ID, scene.targetId());
                int group = extracts++ == 0 ? 0 : 2;
                var sources =
                        pass.inputs().stream()
                                .filter(in -> in.samplerName().equals("Sources"))
                                .findFirst()
                                .orElseThrow();
                assertEquals(
                        SourceMask.groupTexture(group),
                        ((PostChainConfig.TextureInput) sources).location());
            }
            if (shader.equals("post/composite_preserve")) {
                composites++;
                var sources =
                        pass.inputs().stream()
                                .filter(in -> in.samplerName().equals("Sources"))
                                .findFirst()
                                .orElseThrow();
                assertEquals(
                        SourceMask.TEXTURE_ID, ((PostChainConfig.TextureInput) sources).location());
            }
            if (pass.outputTarget().equals(PostChain.MAIN_TARGET_ID)) {
                copies++;
                assertSame(graph.passes().getLast(), pass);
            }
            seen.add(pass.outputTarget());
        }
        assertEquals(2, extracts);
        assertEquals(2, composites);
        assertEquals(1, copies);
        assertEquals(
                1,
                graph.passes().stream()
                        .filter(p -> p.fragmentShaderId().getPath().equals("post/flare"))
                        .count());
    }
}
