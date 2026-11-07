package dev.stillbloom.render.item;

import com.mojang.blaze3d.pipeline.*;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.textures.FilterMode;

import dev.stillbloom.render.BloomGraph;

import java.nio.*;
import java.util.*;

// I copy exact pixels here to avoid the region-copy mismatch in Minecraft 26.2's OpenGL backend.
final class HeldItemBlit implements AutoCloseable {
    // I keep immutable programs through resizing and slider edits; each instance still owns its
    // buffers.
    private static final Map<GpuFormat, RenderPipeline> PIPELINES = new EnumMap<>(GpuFormat.class);
    private final ByteBuffer data = ByteBuffer.allocateDirect(16).order(ByteOrder.nativeOrder());
    private GpuBuffer uniform;

    void copy(
            RenderTarget source,
            RenderTarget destination,
            int x,
            int y,
            int width,
            int height,
            int offsetX,
            int offsetY) {
        if (uniform == null)
            uniform =
                    RenderSystem.getDevice()
                            .createBuffer(
                                    () -> "Still Bloom crop copy",
                                    GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST,
                                    16);
        var pipeline = pipeline(destination.getColorTexture().getFormat());
        data.clear();
        data.putFloat(offsetX).putFloat(offsetY).putFloat(0).putFloat(0).flip();
        var encoder = RenderSystem.getDevice().createCommandEncoder();
        encoder.writeToBuffer(uniform.slice(), data);
        try (var pass =
                encoder.createRenderPass(
                        () -> "Still Bloom held crop copy",
                        destination.getColorTextureView(),
                        Optional.empty())) {
            pass.setPipeline(RenderSystem.getCompiledPipeline(pipeline));
            pass.setUniform("CopyRegion", uniform);
            pass.setUniform(
                    "InSampler",
                    source.getColorTextureView(),
                    RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
            pass.enableScissor(x, y, width, height);
            pass.draw(3, 1, 0, 0);
        }
    }

    static synchronized RenderPipeline pipeline(GpuFormat output) {
        return PIPELINES.computeIfAbsent(
                output,
                format ->
                        RenderPipeline.builder()
                                .withLocation(
                                        BloomGraph.id(
                                                "held_copy_"
                                                        + format.name().toLowerCase(Locale.ROOT)))
                                .withVertexShader("core/screenquad")
                                .withFragmentShader(BloomGraph.id("core/held_copy"))
                                .withBindGroupLayout(
                                        BindGroupLayout.builder()
                                                .withUniform(
                                                        "InSampler",
                                                        UniformType.COMBINED_IMAGE_SAMPLER)
                                                .withUniform(
                                                        "CopyRegion", UniformType.UNIFORM_BUFFER)
                                                .build())
                                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                                .withCull(false)
                                .withDepthStencilState(Optional.empty())
                                .withColorTargetState(
                                        new ColorTargetState(
                                                Optional.empty(),
                                                format,
                                                ColorTargetState.WRITE_ALL))
                                .build());
    }

    public void close() {
        if (uniform != null) uniform.close();
        uniform = null;
    }
}
