package dev.stillbloom.render;

import static org.junit.jupiter.api.Assertions.*;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.*;

import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

import org.junit.jupiter.api.*;

import java.util.Optional;

class PostPipelineCacheTest {
    @BeforeEach
    void reset() {
        PostPipelineCache.clear();
    }

    private RenderPipeline pipeline(String label, String shader, String uniform, GpuFormat format) {
        return RenderPipeline.builder(RenderPipelines.POST_PROCESSING_SNIPPET)
                .withLocation(BloomGraph.id(label))
                .withVertexShader("core/screenquad")
                .withFragmentShader(Identifier.parse(shader))
                .withBindGroupLayout(
                        BindGroupLayout.builder()
                                .withUniform(uniform, UniformType.UNIFORM_BUFFER)
                                .build())
                .withColorTargetState(
                        new ColorTargetState(Optional.empty(), format, ColorTargetState.WRITE_ALL))
                .build();
    }

    @Test
    void rebuildsAndHeldWorldPassLabelsReuseTheSameProgram() {
        var first =
                PostPipelineCache.reuse(
                        pipeline(
                                "bloom/2",
                                "stillbloom:post/blur",
                                "BloomConfig",
                                GpuFormat.RGBA8_UNORM));
        for (int i = 0; i < 500; i++)
            assertSame(
                    first,
                    PostPipelineCache.reuse(
                            pipeline(
                                    "held/" + i,
                                    "stillbloom:post/blur",
                                    "BloomConfig",
                                    GpuFormat.RGBA8_UNORM)));
        assertEquals(1, PostPipelineCache.size());
    }

    @Test
    void shaderBindingAndAttachmentDifferencesRemainSeparate() {
        var first =
                PostPipelineCache.reuse(
                        pipeline(
                                "bloom/2",
                                "stillbloom:post/blur",
                                "BloomConfig",
                                GpuFormat.RGBA8_UNORM));
        assertNotSame(
                first,
                PostPipelineCache.reuse(
                        pipeline(
                                "bloom/2",
                                "stillbloom:post/starburst",
                                "BloomConfig",
                                GpuFormat.RGBA8_UNORM)));
        assertNotSame(
                first,
                PostPipelineCache.reuse(
                        pipeline(
                                "bloom/2",
                                "stillbloom:post/blur",
                                "Other",
                                GpuFormat.RGBA8_UNORM)));
        assertNotSame(
                first,
                PostPipelineCache.reuse(
                        pipeline(
                                "bloom/2",
                                "stillbloom:post/blur",
                                "BloomConfig",
                                GpuFormat.RGBA16_FLOAT)));
    }

    @Test
    void foreignAndCoreProgramsKeepTheirOwnIdentity() {
        for (String shader :
                new String[] {
                    "minecraft:post/blur", "other:post/blur", "stillbloom:core/held_source"
                }) {
            var p = pipeline("test", shader, "Config", GpuFormat.RGBA8_UNORM);
            assertSame(p, PostPipelineCache.reuse(p));
        }
        assertEquals(0, PostPipelineCache.size());
    }

    @Test
    void descriptionRetentionIsBounded() {
        for (int i = 0; i < 300; i++)
            PostPipelineCache.reuse(
                    pipeline("test", "stillbloom:post/test" + i, "Config", GpuFormat.RGBA8_UNORM));
        assertEquals(256, PostPipelineCache.size());
    }
}
