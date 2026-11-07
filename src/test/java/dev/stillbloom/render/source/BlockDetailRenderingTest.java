package dev.stillbloom.render.source;

import static org.junit.jupiter.api.Assertions.*;

import dev.stillbloom.config.BloomConfig;
import dev.stillbloom.config.BloomStyle;
import dev.stillbloom.render.BloomGraph;

import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.renderer.PostChainConfig;

import org.junit.jupiter.api.Test;

import java.util.*;

class BlockDetailRenderingTest {
    @Test
    void explicitZeroAndEveryDetailValueRoundTripWithoutQuantization() {
        assertEquals(0, SourceMask.packDetail(null, .85f));
        assertEquals(0, SourceMask.packDetail(.85f, .85f));
        assertEquals(0, SourceMask.packDetail(-0.0f, 0));
        assertEquals(0x80000000, SourceMask.packDetail(0f, .85f));
        var random = new Random(1600);
        for (int i = 0; i < 10000; i++) {
            float detail = i == 0 ? 0 : i == 1 ? 1 : random.nextFloat();
            int packed = SourceMask.packDetail(detail, detail == 0 ? 1 : 0);
            assertNotEquals(0, packed);
            // I check that RGBA8 preserves all four little-endian bytes.
            int decoded =
                    (packed & 255)
                            | (((packed >>> 8) & 255) << 8)
                            | (((packed >>> 16) & 255) << 16)
                            | (((packed >>> 24) & 255) << 24);
            assertEquals(Float.floatToRawIntBits(detail), decoded & 0x7FFFFFFF);
        }
    }

    @Test
    void disabledDetailUsesTheExistingGraphAcrossAllRenderingModes() {
        for (var shape : BloomConfig.Shape.values())
            for (boolean distance : new boolean[] {false, true})
                for (boolean stable : new boolean[] {false, true})
                    for (float detail : new float[] {0, .85f, 1}) {
                        var config = new BloomConfig();
                        config.bloomShape = shape;
                        config.pixelSize = 4;
                        config.farPixelSize = 2;
                        config.distancePixels = distance;
                        config.stabilityMillis = stable ? 40 : 0;
                        config.preserveDetail = detail;
                        var groups = groups(config);
                        assertEquals(
                                BloomGraph.createGroupedConfig(
                                        1281, 721, config, true, true, groups, true),
                                BloomGraph.createGroupedConfig(
                                        1281, 721, config, true, true, groups, true, false));
                    }
    }

    @Test
    void localProtectionChangesOnlyCompositeShaderAndBinding() {
        for (var shape : BloomConfig.Shape.values())
            for (boolean distance : new boolean[] {false, true})
                for (boolean stable : new boolean[] {false, true})
                    for (boolean direct : new boolean[] {false, true}) {
                        var config = new BloomConfig();
                        config.bloomShape = shape;
                        config.pixelSize = 4;
                        config.farPixelSize = 2;
                        config.distancePixels = distance;
                        config.stabilityMillis = stable ? 40 : 0;
                        config.preserveDetail = .85f;
                        config.flareStyle = BloomConfig.Flare.CROSS;
                        var groups = groups(config);
                        var inherited =
                                BloomGraph.createGroupedConfig(
                                        1281, 721, config, true, true, groups, direct, false);
                        var local =
                                BloomGraph.createGroupedConfig(
                                        1281, 721, config, true, true, groups, direct, true);
                        assertEquals(inherited.internalTargets(), local.internalTargets());
                        assertEquals(
                                inherited.passes().size(),
                                local.passes().size(),
                                "No additional draw passes");
                        int composites = 0;
                        for (int i = 0; i < inherited.passes().size(); i++) {
                            var before = inherited.passes().get(i);
                            var after = local.passes().get(i);
                            if (!before.fragmentShaderId().getPath().startsWith("post/composite")) {
                                assertEquals(before, after);
                                continue;
                            }
                            composites++;
                            assertEquals(
                                    "post/composite_detail" + (distance ? "_distance" : ""),
                                    after.fragmentShaderId().getPath());
                            assertEquals(before.vertexShaderId(), after.vertexShaderId());
                            assertEquals(before.outputTarget(), after.outputTarget());
                            assertEquals(before.uniforms(), after.uniforms());
                            assertEquals(
                                    before.inputs(),
                                    after.inputs().subList(0, before.inputs().size()));
                            assertEquals(before.inputs().size() + 1, after.inputs().size());
                            assertEquals(
                                    new PostChainConfig.TextureInput(
                                            "Details", SourceMask.DETAILS_ID, 1024, 2048, false),
                                    after.inputs().getLast());
                            var source =
                                    (PostChainConfig.TextureInput)
                                            after.inputs().stream()
                                                    .filter(
                                                            in ->
                                                                    in.samplerName()
                                                                            .equals("Sources"))
                                                    .findFirst()
                                                    .orElseThrow();
                            assertEquals(
                                    SourceMask.TEXTURE_ID,
                                    source.location(),
                                    "Protect all source blocks from each style's glow");
                        }
                        assertEquals(
                                groups.size(), composites, "Detail never splits render groups");
                    }
    }

    @Test
    void zeroGlobalDetailStillSupportsPositiveLocalProtectionAndDirectComposite() {
        for (boolean distance : new boolean[] {false, true}) {
            var config = new BloomConfig();
            config.pixelSize = 4;
            config.farPixelSize = 2;
            config.distancePixels = distance;
            config.preserveDetail = 0;
            var groups = groups(config);
            var inherited =
                    BloomGraph.createGroupedConfig(
                            1281, 721, config, true, true, groups, true, false);
            var local =
                    BloomGraph.createGroupedConfig(
                            1281, 721, config, true, true, groups, true, true);
            assertEquals(inherited.internalTargets(), local.internalTargets());
            assertEquals(inherited.passes().size(), local.passes().size());
            for (var pass : local.passes())
                if (pass.fragmentShaderId().getPath().startsWith("post/composite")) {
                    assertTrue(pass.uniforms().containsKey("SourceMask"));
                    var depth =
                            (PostChainConfig.TargetInput)
                                    pass.inputs().stream()
                                            .filter(in -> in.samplerName().equals("Depth"))
                                            .findFirst()
                                            .orElseThrow();
                    assertTrue(depth.useDepthBuffer());
                    assertEquals(PostChain.MAIN_TARGET_ID, depth.targetId());
                    assertTrue(
                            pass.inputs().stream()
                                    .anyMatch(in -> in.samplerName().equals("Sources")));
                    assertTrue(
                            pass.inputs().stream()
                                    .anyMatch(in -> in.samplerName().equals("Details")));
                }
            var last = local.passes().getLast();
            assertEquals(PostChain.MAIN_TARGET_ID, last.outputTarget());
            for (var input : last.inputs())
                if (input instanceof PostChainConfig.TargetInput target
                        && target.targetId().equals(PostChain.MAIN_TARGET_ID))
                    assertTrue(
                            target.useDepthBuffer(),
                            "No MAIN color feedback on the direct composite");
        }
    }

    private static List<SourceMask.RenderGroup> groups(BloomConfig config) {
        var second = config.copy();
        second.glowWidth = config.glowWidth + 8;
        return List.of(
                new SourceMask.RenderGroup(0, BloomStyle.global(config), false, true),
                new SourceMask.RenderGroup(2, BloomStyle.global(second), true, false));
    }
}
