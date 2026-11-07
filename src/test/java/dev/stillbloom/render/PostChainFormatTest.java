package dev.stillbloom.render;

import static org.junit.jupiter.api.Assertions.*;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;

import dev.stillbloom.config.BloomConfig;
import dev.stillbloom.config.BloomStyle;
import dev.stillbloom.mixin.PostChainFormatMixin;
import dev.stillbloom.render.source.SourceMask;

import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.renderer.PostChainConfig;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.Identifier;

import org.junit.jupiter.api.Test;

import java.util.*;

// I exercise the actual pass hook so the test can't pass with a different copy of its rules.
class PostChainFormatTest {
    private static final Identifier VERTEX = Identifier.withDefaultNamespace("core/screenquad");
    private static final Identifier LOCATION = BloomGraph.id("format_test");

    @Test
    void oneToOneAndFourPixelHistoryPipelinesActuallyBuildAsRgba32() throws Exception {
        for (String name :
                List.of(
                        "stabilize",
                        "stabilize_distance",
                        "downsample_stable",
                        "downsample_stable_distance")) {
            var shader = BloomGraph.id("post/" + name);
            var pipeline = build(pass(shader), GpuFormat.RGBA8_UNORM);
            assertEquals(
                    new ColorTargetState(
                            Optional.empty(), GpuFormat.RGBA32_FLOAT, ColorTargetState.WRITE_ALL),
                    pipeline.getColorTargetStates().getFirst(),
                    name);
            assertUnchangedPipelineSettings(pipeline, shader);
        }
    }

    @Test
    void distanceIntermediatesUseRgba16WhileSceneCompositesKeepTheirExistingFormat()
            throws Exception {
        for (String name :
                List.of(
                        "extract_auto_distance",
                        "extract_uniform_color_distance",
                        "downsample_distance",
                        "blur_distance",
                        "starburst_distance",
                        "flare_distance")) {
            var shader = BloomGraph.id("post/" + name);
            var pipeline = build(pass(shader), GpuFormat.RGBA8_UNORM);
            assertEquals(
                    GpuFormat.RGBA16_FLOAT,
                    pipeline.getColorTargetStates().getFirst().format(),
                    name);
            assertEquals(
                    ColorTargetState.WRITE_ALL,
                    pipeline.getColorTargetStates().getFirst().writeMask());
            assertTrue(pipeline.getColorTargetStates().getFirst().blendFunction().isEmpty());
            assertUnchangedPipelineSettings(pipeline, shader);
        }
        for (String name :
                List.of(
                        "composite_distance",
                        "composite_preserve_distance",
                        "composite_detail_smooth_distance",
                        "composite_held_distance"))
            assertEquals(
                    GpuFormat.RGBA8_UNORM,
                    build(pass(BloomGraph.id("post/" + name)), GpuFormat.RGBA8_UNORM)
                            .getColorTargetStates()
                            .getFirst()
                            .format(),
                    name);
    }

    @Test
    void normalShadersAndOtherNamespacesDoNotHaveTheirTargetFormatReplaced() throws Exception {
        var shaders = new ArrayList<Identifier>();
        for (String name :
                List.of(
                        "extract_auto",
                        "extract_uniform_color",
                        "downsample",
                        "blur",
                        "starburst",
                        "flare",
                        "composite",
                        "copy")) shaders.add(BloomGraph.id("post/" + name));
        for (String namespace : List.of("minecraft", "another_mod"))
            for (String name :
                    List.of(
                            "stabilize",
                            "stabilize_distance",
                            "downsample_stable",
                            "blur_distance"))
                shaders.add(Identifier.fromNamespaceAndPath(namespace, "post/" + name));
        for (var shader : shaders)
            for (var existing : List.of(GpuFormat.RGBA8_UNORM, GpuFormat.RGBA16_FLOAT)) {
                var pipeline = build(pass(shader), existing);
                assertEquals(
                        new ColorTargetState(
                                Optional.empty(), existing, ColorTargetState.WRITE_ALL),
                        pipeline.getColorTargetStates().getFirst(),
                        shader.toString());
                assertUnchangedPipelineSettings(pipeline, shader);
            }
    }

    @Test
    void actualGraphPipelineFormatsMatchHalfSizeHistoryAndDistanceTargetResources()
            throws Exception {
        for (int pixel : new int[] {2, 3, 4})
            for (boolean distance : new boolean[] {false, true})
                for (boolean stable : new boolean[] {false, true}) {
                    var config = new BloomConfig();
                    config.pixelSize = distance ? 4 : pixel;
                    config.farPixelSize = pixel;
                    config.distancePixels = distance;
                    config.stabilityMillis = stable ? 40 : 0;
                    config.uniformEmission = true;
                    config.smoothBlend = .5f;
                    config.flareStyle = BloomConfig.Flare.CROSS;
                    var groups =
                            List.of(
                                    new SourceMask.RenderGroup(
                                            3, BloomStyle.global(config), true, true));
                    var graph =
                            BloomGraph.createGroupedConfig(
                                    73, 55, config, true, true, groups, false, true);
                    Identifier historyCurrent =
                            BloomGraph.id("group_3_down_" + config.renderPixelSize());
                    for (var pass : graph.passes()) {
                        boolean writesHistory =
                                stable && pass.outputTarget().equals(historyCurrent);
                        boolean floatIntermediate =
                                config.usesDistancePixels()
                                        && graph.internalTargets().containsKey(pass.outputTarget())
                                        && DistanceTargets.carriesDistance(pass.outputTarget());
                        var expected =
                                writesHistory
                                        ? GpuFormat.RGBA32_FLOAT
                                        : floatIntermediate
                                                ? GpuFormat.RGBA16_FLOAT
                                                : GpuFormat.RGBA8_UNORM;
                        var pipeline = build(pass, GpuFormat.RGBA8_UNORM);
                        assertEquals(
                                expected,
                                pipeline.getColorTargetStates().getFirst().format(),
                                pass.fragmentShaderId() + " -> " + pass.outputTarget());
                        if (writesHistory)
                            assertFalse(
                                    graph.internalTargets().containsKey(pass.outputTarget()),
                                    "RGBA32 history is externally allocated");
                        assertUnchangedPipelineSettings(pipeline, pass.fragmentShaderId());
                    }
                }
    }

    private static PostChainConfig.Pass pass(Identifier shader) {
        return new PostChainConfig.Pass(
                VERTEX, shader, List.of(), PostChain.MAIN_TARGET_ID, Map.of());
    }

    private static RenderPipeline build(PostChainConfig.Pass pass, GpuFormat existing)
            throws Exception {
        // I need no GPU buffers here because screen-quad vertices come from the shader.
        var builder =
                RenderPipeline.builder()
                        .withLocation(LOCATION)
                        .withVertexShader(pass.vertexShaderId())
                        .withFragmentShader(pass.fragmentShaderId())
                        .withPrimitiveTopology(PrimitiveTopology.TRIANGLE_STRIP)
                        .withCull(false)
                        .withColorTargetState(
                                new ColorTargetState(
                                        Optional.empty(), existing, ColorTargetState.WRITE_ALL));
        var method =
                PostChainFormatMixin.class.getDeclaredMethod(
                        "stillbloom$historyFormat",
                        RenderPipeline.Builder.class,
                        TextureManager.class,
                        PostChainConfig.Pass.class,
                        Identifier.class);
        method.setAccessible(true);
        return (RenderPipeline) method.invoke(null, builder, null, pass, LOCATION);
    }

    private static void assertUnchangedPipelineSettings(
            RenderPipeline pipeline, Identifier shader) {
        assertEquals(LOCATION, pipeline.getLocation());
        assertEquals(
                VERTEX,
                pipeline.getShaders().get(com.mojang.renderpearl.api.pipeline.ShaderType.VERTEX));
        assertEquals(
                shader,
                pipeline.getShaders().get(com.mojang.renderpearl.api.pipeline.ShaderType.FRAGMENT));
        assertEquals(PrimitiveTopology.TRIANGLE_STRIP, pipeline.getPrimitiveTopology());
        assertFalse(pipeline.isCull());
        assertEquals(1, pipeline.getColorTargetStates().size());
    }
}
