package dev.stillbloom.render;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.*;
import com.mojang.renderpearl.api.vertex.VertexFormat;

import dev.stillbloom.StillBloomClient;
import dev.stillbloom.config.BloomStyle;
import dev.stillbloom.render.item.HeldItemGraph;
import dev.stillbloom.render.item.HeldItemRenderer;

import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.ShaderDefines;
import net.minecraft.resources.Identifier;

import java.util.*;

// I reuse pipeline descriptions so Minecraft's identity cache can reuse the compiled programs.
public final class PostPipelineCache {
    private static final int LIMIT = 256;
    private static final LinkedHashMap<Key, RenderPipeline> PIPELINES =
            new LinkedHashMap<>(64, .75f, true);

    private PostPipelineCache() {}

    public static RenderPipeline finish(RenderPipeline.Builder builder, Identifier shader) {
        return finish(builder, shader, null);
    }

    // I specialize extraction once at compile time and leave the 2/4px shader paths unchanged.
    public static RenderPipeline finish(
            RenderPipeline.Builder builder, Identifier shader, Identifier output) {
        if (threePixelExtraction(shader, output)) builder.withShaderDefine("BLOOM_GRID_THREE");
        if (shader.getNamespace().equals("stillbloom")) {
            String path = shader.getPath();
            if (path.equals("post/downsample_stable")
                    || path.equals("post/downsample_stable_distance")
                    || path.equals("post/stabilize")
                    || path.equals("post/stabilize_distance"))
                builder.withColorTargetState(
                        new ColorTargetState(
                                Optional.empty(),
                                GpuFormat.RGBA32_FLOAT,
                                ColorTargetState.WRITE_ALL));
            else if (path.endsWith("_distance") && !path.startsWith("post/composite"))
                builder.withColorTargetState(
                        new ColorTargetState(
                                Optional.empty(),
                                GpuFormat.RGBA16_FLOAT,
                                ColorTargetState.WRITE_ALL));
        }
        return reuse(builder.build());
    }

    static boolean threePixelExtraction(Identifier shader, Identifier output) {
        if (output == null
                || !shader.getNamespace().equals("stillbloom")
                || !output.getNamespace().equals("stillbloom")) return false;
        String path = shader.getPath(), target = output.getPath();
        return (path.startsWith("post/extract") || path.startsWith("post/held_downsample"))
                && (target.endsWith("down_3") || target.endsWith("extracted_3"));
    }

    // I let Minecraft compile these descriptions during its normal resource reload.
    public static List<RenderPipeline> heldWarmup() {
        var global = StillBloomClient.config;
        if (global == null
                || !global.enabled
                || !global.heldItemBloom && !global.otherPlayerItemBloom) return List.of();
        var pipelines = new LinkedHashSet<RenderPipeline>(HeldItemRenderer.warmupPipelines(global));
        var styles = new LinkedHashSet<BloomStyle>();
        styles.add(BloomStyle.global(global));
        for (var profile : StillBloomClient.MASKS.profiles().values())
            if (profile.override != null && styles.size() < 16)
                styles.add(profile.override.resolve(global));
        for (var profile : StillBloomClient.ITEM_MASKS.items.values())
            if (profile.override != null && styles.size() < 32)
                styles.add(profile.override.resolve(global));
        int index = 0;
        for (var style : styles)
            for (var pass : HeldItemGraph.create(64, 64, style.apply(global)).passes()) {
                var bindings = BindGroupLayout.builder();
                for (var input : pass.inputs())
                    bindings.withUniform(
                            input.samplerName() + "Sampler", UniformType.COMBINED_IMAGE_SAMPLER);
                bindings.withUniform("SamplerInfo", UniformType.UNIFORM_BUFFER);
                for (var name : pass.uniforms().keySet())
                    bindings.withUniform(name, UniformType.UNIFORM_BUFFER);
                pipelines.add(
                        finish(
                                RenderPipeline.builder(RenderPipelines.POST_PROCESSING_SNIPPET)
                                        .withLocation(BloomGraph.id("warmup/held_" + index++))
                                        .withVertexShader(pass.vertexShaderId())
                                        .withFragmentShader(pass.fragmentShaderId())
                                        .withBindGroupLayout(bindings.build())
                                        .withColorTargetState(ColorTargetState.DEFAULT),
                                pass.fragmentShaderId(),
                                pass.outputTarget()));
            }
        return List.copyOf(pipelines);
    }

    // I exclude labels and uniform values from the key, but include all GPU state and binding
    // order.
    private record Key(
            Map<ShaderType, Identifier> shaders,
            ShaderDefines defines,
            List<BindGroupLayout> bindings,
            List<ColorTargetState> colors,
            DepthStencilState depth,
            PolygonMode polygon,
            boolean cull,
            List<VertexFormat> vertices,
            PrimitiveTopology topology,
            int pushConstants) {
        // I copy these lists so equal entries compare by value, keeping empty slots in their
        // original positions.
        Key(RenderPipeline p) {
            this(
                    p.getShaders(),
                    p.getShaderDefines(),
                    p.getBindGroupLayouts(),
                    snapshot(p.getColorTargetStates()),
                    p.getDepthStencilState(),
                    p.getPolygonMode(),
                    p.isCull(),
                    snapshot(p.getVertexFormatBindings()),
                    p.getPrimitiveTopology(),
                    p.pushConstantSize());
        }
    }

    private static <T> List<T> snapshot(List<T> values) {
        return Collections.unmodifiableList(new ArrayList<>(values));
    }

    public static synchronized RenderPipeline reuse(RenderPipeline candidate) {
        var fragment = candidate.getShaders().get(ShaderType.FRAGMENT);
        if (fragment == null
                || !fragment.getNamespace().equals("stillbloom")
                || !fragment.getPath().startsWith("post/")) return candidate;
        var key = new Key(candidate);
        var previous = PIPELINES.get(key);
        if (previous != null) return previous;
        if (PIPELINES.size() >= LIMIT) PIPELINES.remove(PIPELINES.keySet().iterator().next());
        PIPELINES.put(key, candidate);
        return candidate;
    }

    // I cache descriptions only; Minecraft still owns shader compilation, disposal and reload.
    static synchronized int size() {
        return PIPELINES.size();
    }

    static synchronized void clear() {
        PIPELINES.clear();
    }
}
