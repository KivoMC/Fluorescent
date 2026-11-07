package dev.stillbloom.render;

import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.buffers.*;
import com.mojang.blaze3d.pipeline.*;
import com.mojang.blaze3d.resource.ResourceHandle;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;

import dev.stillbloom.StillBloomClient;
import dev.stillbloom.config.BloomConfig;
import dev.stillbloom.render.source.ExtractionBounds;
import dev.stillbloom.render.source.SourceBounds;
import dev.stillbloom.render.source.SourceMask;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.PostPass;
import net.minecraft.resources.Identifier;

import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.joml.Vector4fc;
import org.lwjgl.system.MemoryStack;

import java.util.*;

// I upload sampler dimensions only when they change.
public final class BloomPass implements AutoCloseable {
    private final RenderPipeline pipeline;
    private final PostPass.Input[] inputs;
    private final GpuTextureView[] views;
    private final GpuSampler[] samplers;
    private final String[] names;
    private final Map<String, GpuBuffer> uniforms;
    private final int[] sizes;
    private GpuBuffer info;
    private final boolean extraction;
    private final int extractionGrid;
    private final int sourceGroup;
    private final Bounds.Stage boundedStage;
    private final int gridSize;
    private final float glowWidth;
    private final boolean diamond;
    private final float flareLength;
    private final boolean cross;
    private final Vector4fc emptyColor;
    private static final ProjectionRegions REGIONS = new ProjectionRegions();

    public BloomPass(
            RenderPipeline pipeline,
            List<PostPass.Input> inputs,
            Map<String, GpuBuffer> uniforms,
            Identifier output) {
        this.pipeline = pipeline;
        this.inputs = inputs.toArray(PostPass.Input[]::new);
        this.uniforms = uniforms;
        String shader =
                pipeline.getShaders()
                        .get(com.mojang.renderpearl.api.pipeline.ShaderType.FRAGMENT)
                        .getPath();
        boolean held = output.getPath().startsWith("held_");
        extraction = !held && shader.startsWith("post/extract");
        extractionGrid =
                PostPipelineCache.threePixelExtraction(BloomGraph.id(shader), output) ? 3 : 2;
        sourceGroup = sourceGroup(output.getPath());
        var style = !held ? StillBloomClient.SOURCE_MASK.groupStyle(sourceGroup) : null;
        String target =
                sourceGroup < 0
                        ? output.getPath()
                        : output.getPath().substring(output.getPath().indexOf('_', 6) + 1);
        int reduction = Bounds.reduction(target);
        boundedStage = held || style == null ? null : stage(shader, target, reduction);
        gridSize =
                boundedStage == Bounds.Stage.REDUCTION
                        ? reduction
                        : boundedStage == null ? 0 : StillBloomClient.config.renderPixelSize();
        glowWidth = style == null ? Float.NaN : style.width();
        diamond = style != null && style.shape() == BloomConfig.Shape.DIAMOND;
        flareLength = style == null ? Float.NaN : style.flareLength();
        cross = style != null && style.flare() == BloomConfig.Flare.CROSS;
        emptyColor = new Vector4f(0, 0, 0, Bounds.clearAlpha(shader));
        views = new GpuTextureView[inputs.size()];
        samplers = new GpuSampler[inputs.size()];
        names = new String[inputs.size()];
        sizes = new int[2 * (inputs.size() + 1)];
        for (int i = 0; i < this.inputs.length; i++) {
            names[i] = this.inputs[i].samplerName() + "Sampler";
            samplers[i] =
                    RenderSystem.getSamplerCache()
                            .getClampToEdge(
                                    this.inputs[i].bilinear()
                                            ? FilterMode.LINEAR
                                            : FilterMode.NEAREST);
        }
    }

    static Bounds.Stage stage(String shader, String target, int reduction) {
        String name =
                shader.endsWith("_distance") ? shader.substring(0, shader.length() - 9) : shader;
        if ((reduction == 2 || reduction == 3) && name.equals("post/stabilize"))
            return Bounds.Stage.REDUCTION;
        if (reduction == 4
                && (name.equals("post/downsample") || name.equals("post/downsample_stable")))
            return Bounds.Stage.REDUCTION;
        if (name.equals("post/flare") && target.equals("flare")) return Bounds.Stage.FLARE;
        if (name.equals("post/starburst") && target.equals("near")) return Bounds.Stage.STARBURST;
        if (!name.equals("post/blur")) return null;
        return switch (target) {
            case "near_x" -> Bounds.Stage.NEAR_X;
            case "near" -> Bounds.Stage.NEAR;
            case "wide_x" -> Bounds.Stage.WIDE_X;
            case "wide" -> Bounds.Stage.WIDE;
            default -> null;
        };
    }

    static int sourceGroup(String output) {
        if (!output.startsWith("group_")) return -1;
        int end = output.indexOf('_', 6);
        if (end < 0) return -1;
        try {
            return Integer.parseInt(output.substring(6, end));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private ExtractionBounds.Rect effectRegion(RenderTarget output) {
        if (!extraction && boundedStage == null) return null;
        var sources = StillBloomClient.SOURCE_MASK;
        var bounds = sources.groupBounds(sourceGroup);
        // I project sources using the original scene size, including the last row at odd
        // resolutions.
        int w = extraction ? views[0].getWidth(0) : StillBloomClient.RENDERER.renderWidth();
        int h = extraction ? views[0].getHeight(0) : StillBloomClient.RENDERER.renderHeight();
        if (w < 1 || h < 1) return null;
        var region = REGIONS.project(sources, bounds, w, h);
        if (extraction && extractionGrid == 3)
            region = Bounds.support(region, w, h, 3, 0, false, 0, false, Bounds.Stage.REDUCTION);
        // I draw the full target when clearing it would cost more than the small area saved.
        var effect =
                extraction
                        ? region
                        : Bounds.support(
                                region,
                                w,
                                h,
                                gridSize,
                                glowWidth,
                                diamond,
                                flareLength,
                                cross,
                                boundedStage);
        int expectedWidth = BloomGraph.reducedSize(w, extraction ? extractionGrid : gridSize),
                expectedHeight = BloomGraph.reducedSize(h, extraction ? extractionGrid : gridSize);
        if (output.width != expectedWidth || output.height != expectedHeight) return null;
        long full = (long) output.width * output.height;
        return (long) effect.width() * effect.height() * 5 >= full * 4 ? null : effect;
    }

    // I share one projection across a style's passes instead of transforming the same boxes
    // repeatedly.
    private static final class ProjectionRegions {
        private SourceMask owner;
        private long revision = Long.MIN_VALUE;
        private int width, height;
        private final Matrix4f view = new Matrix4f();
        private final Map<List<SourceBounds>, ExtractionBounds.Rect> regions =
                new IdentityHashMap<>();

        ExtractionBounds.Rect project(SourceMask sources, List<SourceBounds> bounds, int w, int h) {
            long current = sources.visibilityRevision();
            if (owner != sources || revision != current || width != w || height != h) {
                owner = sources;
                revision = current;
                width = w;
                height = h;
                regions.clear();
                sources.copyView(view);
            }
            return regions.computeIfAbsent(
                    bounds,
                    key -> {
                        var camera =
                                Minecraft.getInstance()
                                        .gameRenderer
                                        .gameRenderState()
                                        .levelRenderState
                                        .cameraRenderState
                                        .pos;
                        return ExtractionBounds.project(
                                key, view, camera.x, camera.y, camera.z, w, h);
                    });
        }
    }

    public void render(
            RenderTarget output,
            GpuBufferSlice projection,
            Map<Identifier, ResourceHandle<RenderTarget>> targets) {
        boolean changed = updateSize(0, output.width, output.height);
        for (int i = 0; i < inputs.length; i++) {
            views[i] = inputs[i].texture(targets);
            changed |= updateSize(2 * (i + 1), views[i].getWidth(0), views[i].getHeight(0));
        }
        if (info == null || changed) {
            if (info != null) {
                info.close();
                info = null;
            }
            try (var stack = MemoryStack.stackPush()) {
                var builder = Std140Builder.onStack(stack, 8 * (inputs.length + 1));
                for (int i = 0; i < sizes.length; i += 2) builder.putVec2(sizes[i], sizes[i + 1]);
                info =
                        RenderSystem.getDevice()
                                .createBuffer(
                                        () -> "Still Bloom sampler sizes",
                                        GpuBuffer.USAGE_UNIFORM,
                                        builder.get());
            }
        }
        var rectangle = effectRegion(output);
        RenderSystem.backupProjectionMatrix();
        try {
            RenderSystem.setProjectionMatrix(projection, ProjectionType.ORTHOGRAPHIC);
            try (var pass =
                    RenderSystem.getDevice()
                            .createCommandEncoder()
                            .createRenderPass(
                                    () -> "Still Bloom " + pipeline.getLocation(),
                                    output.getColorTextureView(),
                                    rectangle == null ? Optional.empty() : Optional.of(emptyColor),
                                    // I leave depth untouched; the final composite may read MAIN
                                    // depth while writing its color.
                                    output.getDepthTexture() != null && pipeline.wantsDepthTexture()
                                            ? output.getDepthTextureView()
                                            : null,
                                    OptionalDouble.empty())) {
                pass.setPipeline(RenderSystem.getCompiledPipeline(pipeline));
                RenderSystem.bindDefaultUniforms(pass);
                pass.setUniform("SamplerInfo", info);
                for (var entry : uniforms.entrySet())
                    pass.setUniform(entry.getKey(), entry.getValue());
                for (int i = 0; i < inputs.length; i++)
                    pass.setUniform(names[i], views[i], samplers[i]);
                if (rectangle == null) pass.draw(3, 1, 0, 0);
                else if (rectangle.width() > 0 && rectangle.height() > 0) {
                    pass.enableScissor(
                            rectangle.x(), rectangle.y(), rectangle.width(), rectangle.height());
                    pass.draw(3, 1, 0, 0);
                }
            }
        } finally {
            RenderSystem.restoreProjectionMatrix();
            // I don't keep texture views after the pass because the frame graph owns them.
            Arrays.fill(views, null);
            for (var input : inputs) input.cleanup(targets);
        }
    }

    private boolean updateSize(int index, int width, int height) {
        boolean changed = sizes[index] != width || sizes[index + 1] != height;
        sizes[index] = width;
        sizes[index + 1] = height;
        return changed;
    }

    @Override
    public void close() {
        if (info != null) info.close();
        info = null;
    }

    static final class Bounds {
        enum Stage {
            REDUCTION,
            NEAR_X,
            NEAR,
            WIDE_X,
            WIDE,
            STARBURST,
            FLARE
        }

        private Bounds() {}

        static int reduction(String target) {
            return switch (target) {
                case "down_2" -> 2;
                case "down_3" -> 3;
                case "down_4" -> 4;
                default -> 0;
            };
        }

        static float clearAlpha(String shader) {
            return shader.endsWith("_distance") ? 0 : 1;
        }

        static ExtractionBounds.Rect support(
                ExtractionBounds.Rect extraction,
                int mainWidth,
                int mainHeight,
                int pixels,
                float glowWidth,
                boolean diamond,
                float flareLength,
                boolean cross,
                Stage stage) {
            if (mainWidth < 1 || mainHeight < 1 || pixels < 1)
                throw new IllegalArgumentException("Invalid bloom dimensions");
            int width = ceilDiv(mainWidth, pixels), height = ceilDiv(mainHeight, pixels);
            var full = new ExtractionBounds.Rect(0, 0, width, height);
            if (stage == null
                    || reduction("down_" + pixels) == 0
                    || !valid(extraction, ceilDiv(mainWidth, 2), ceilDiv(mainHeight, 2)))
                return full;
            // I include a neighboring pixel for interpolation and keep the full draw for unusually
            // large sizes.
            if (mainWidth > 1_048_576 || mainHeight > 1_048_576) return full;
            if (extraction.empty()) return new ExtractionBounds.Rect(0, 0, 0, 0);
            var grid = reduce(extraction, pixels, width, height);
            if (stage == Stage.REDUCTION) return grid;
            if (stage == Stage.FLARE) {
                if (!Float.isFinite(flareLength) || flareLength < 0) return full;
                int radius = radius(flareLength / pixels, Math.max(width, height));
                return expand(grid, radius, cross ? radius : 0, width, height);
            }
            if (!Float.isFinite(glowWidth) || glowWidth < 0) return full;
            float extent = Math.max(1, glowWidth / (2 * pixels));
            if (extent >= Math.max(width, height)) return full;
            int radius = radius(extent, Math.max(width, height));
            if (stage == Stage.STARBURST)
                return expand(grid, radius + 1, radius + 1, width, height);
            int diagonal = (int) Math.ceil(radius * .70710678f) + 1;
            int x = diamond ? diagonal : radius + 1, y = diamond ? diagonal : 1;
            var nearX = expand(grid, x, y, width, height);
            if (stage == Stage.NEAR_X) return nearX;
            var near = expand(nearX, y, x, width, height);
            if (stage == Stage.NEAR) return near;
            var wideX = expand(near, x, y, width, height);
            return stage == Stage.WIDE_X ? wideX : expand(wideX, y, x, width, height);
        }

        private static boolean valid(ExtractionBounds.Rect rect, int width, int height) {
            return rect != null
                    && rect.x() >= 0
                    && rect.y() >= 0
                    && rect.width() >= 0
                    && rect.height() >= 0
                    && (long) rect.x() + rect.width() <= width
                    && (long) rect.y() + rect.height() <= height;
        }

        private static ExtractionBounds.Rect reduce(
                ExtractionBounds.Rect rect, int pixels, int width, int height) {
            // I convert through screen coordinates so dividing by two doesn't shift or clip the 3px
            // grid.
            int x = rect.x() * 2 / pixels, y = rect.y() * 2 / pixels;
            int right = Math.min(width, ceilDiv((rect.x() + rect.width()) * 2, pixels));
            int top = Math.min(height, ceilDiv((rect.y() + rect.height()) * 2, pixels));
            return new ExtractionBounds.Rect(x, y, right - x, top - y);
        }

        private static ExtractionBounds.Rect expand(
                ExtractionBounds.Rect rect, int x, int y, int width, int height) {
            int left = (int) Math.max(0L, (long) rect.x() - x),
                    bottom = (int) Math.max(0L, (long) rect.y() - y);
            int right = (int) Math.min(width, (long) rect.x() + rect.width() + x);
            int top = (int) Math.min(height, (long) rect.y() + rect.height() + y);
            return new ExtractionBounds.Rect(left, bottom, right - left, top - bottom);
        }

        private static int radius(float extent, int limit) {
            return (int) Math.min(Math.ceil(extent), limit);
        }

        private static int ceilDiv(int size, int divisor) {
            return size / divisor + (size % divisor == 0 ? 0 : 1);
        }
    }
}
