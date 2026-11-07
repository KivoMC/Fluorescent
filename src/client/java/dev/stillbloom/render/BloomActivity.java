package dev.stillbloom.render;

import com.mojang.blaze3d.framegraph.FrameGraphBuilder;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import com.mojang.blaze3d.resource.ResourceHandle;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;

import dev.stillbloom.StillBloomClient;
import dev.stillbloom.config.BloomConfig;
import dev.stillbloom.mixin.PostChainAccessor;
import dev.stillbloom.mixin.PostPassAccessor;
import dev.stillbloom.render.source.SourceMask;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.*;
import net.minecraft.resources.Identifier;

import java.util.*;

// I read the small visibility result only after the GPU finishes, so I don't stall drawing.
final class BloomActivity implements AutoCloseable {
    static final Identifier RESULT = BloomGraph.id("activity_result");
    private final IdleState state = new IdleState();
    private final ScopedAllocator frameAllocator = new ScopedAllocator();
    private final List<GpuBuffer> uniforms = new ArrayList<>();
    private final List<GpuBuffer> rainUniforms = new ArrayList<>();
    private PostChain chain;
    private ProjectionMatrixBuffer projection;
    private TextureTarget target;
    private GpuBuffer readback;
    private long generation;
    private boolean disabled;

    boolean skip(
            Minecraft client,
            RenderTarget main,
            GraphicsResourceAllocator allocator,
            boolean custom,
            RenderTarget cleanScene,
            boolean weather) {
        if (disabled) return false;
        state.observe(StillBloomClient.SOURCE_MASK.visibilityRevision());
        long now = System.nanoTime();
        if (state.shouldProbe(now)) {
            try {
                StillBloomClient.SOURCE_MASK.ensureActivityTexture();
                if (chain == null) initialize(client, main, custom, weather);
                long ticket = state.begin(now), requestGeneration = generation;
                StillBloomClient.SOURCE_MASK.upload(client, uniforms);
                RainFogState.upload(rainUniforms);
                FrameGraphBuilder frame = new FrameGraphBuilder();
                Map<Identifier, ResourceHandle<RenderTarget>> handles = new HashMap<>();
                handles.put(PostChain.MAIN_TARGET_ID, frame.importExternal("activity_scene", main));
                handles.put(RESULT, frame.importExternal("activity_result", target));
                if (weather)
                    handles.put(
                            BloomGraph.WEATHER_SCENE,
                            frame.importExternal("activity_rain_scene", cleanScene));
                chain.addToFrame(
                        frame,
                        main.width,
                        main.height,
                        new PostChain.TargetBundle() {
                            public ResourceHandle<RenderTarget> get(Identifier id) {
                                return handles.get(id);
                            }

                            public void replace(
                                    Identifier id, ResourceHandle<RenderTarget> handle) {
                                handles.put(id, handle);
                            }
                        });
                frameAllocator.execute(frame, allocator);
                GpuBuffer buffer = readback;
                RenderSystem.getDevice()
                        .createCommandEncoder()
                        .copyTextureToBuffer(
                                target.getColorTexture(),
                                buffer,
                                0,
                                () -> {
                                    if (requestGeneration != generation || buffer.isClosed())
                                        return;
                                    try (var view = buffer.slice().map(true, false)) {
                                        var bytes = view.data();
                                        state.complete(
                                                ticket,
                                                bytes.get(0) == 0
                                                        && bytes.get(1) == 0
                                                        && bytes.get(2) == 0,
                                                System.nanoTime());
                                    } catch (Exception e) {
                                        failOpen(e);
                                    }
                                },
                                0);
            } catch (Exception e) {
                failOpen(e);
            }
        }
        return !disabled && state.idle();
    }

    private void failOpen(Exception e) {
        disabled = true;
        state.reset();
        StillBloomClient.LOGGER.warn(
                "Bloom idle detection unavailable; continuing normal rendering", e);
    }

    private void initialize(Minecraft client, RenderTarget main, boolean custom, boolean weather)
            throws Exception {
        StillBloomClient.SOURCE_MASK.ensureTexture(client);
        if (custom) StillBloomClient.SOURCE_MASK.geometry.ensure();
        StillBloomClient.SOURCE_MASK.ensureColors();
        var ortho = new Projection();
        ortho.setupOrtho(0, 1, main.width, main.height, true);
        projection = new ProjectionMatrixBuffer("Still Bloom activity projection");
        target = new TextureTarget("Still Bloom activity", 1, 1, GpuFormat.RGBA8_UNORM, null);
        readback =
                RenderSystem.getDevice()
                        .createBuffer(
                                () -> "Still Bloom activity readback",
                                GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST,
                                4);
        var graph =
                createConfig(
                        main.width,
                        main.height,
                        StillBloomClient.config,
                        custom,
                        StillBloomClient.SOURCE_MASK.hasColors());
        Set<Identifier> externals = new HashSet<>(Set.of(PostChain.MAIN_TARGET_ID, RESULT));
        if (weather) {
            graph = BloomGraph.forWeather(graph);
            externals.add(BloomGraph.WEATHER_SCENE);
        }
        chain =
                PostChain.load(
                        graph,
                        client.getTextureManager(),
                        externals,
                        BloomGraph.id("activity"),
                        ortho,
                        projection);
        for (var pass : ((PostChainAccessor) chain).stillbloom$getPasses()) {
            var map = ((PostPassAccessor) pass).stillbloom$getUniforms();
            if (map.containsKey("RainFog")) {
                var buffer =
                        RenderSystem.getDevice()
                                .createBuffer(
                                        () -> "Still Bloom activity fog",
                                        GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST,
                                        RainFogState.BUFFER_BYTES);
                map.put("RainFog", buffer).close();
                rainUniforms.add(buffer);
            }
            if (map.containsKey("SourceMask")) {
                var buffer =
                        RenderSystem.getDevice()
                                .createBuffer(
                                        () -> "Still Bloom activity camera",
                                        GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST,
                                        SourceMask.BUFFER_BYTES);
                map.put("SourceMask", buffer).close();
                uniforms.add(buffer);
            }
        }
    }

    static PostChainConfig createConfig(int width, int height, BloomConfig config, boolean custom) {
        return createConfig(width, height, config, custom, false);
    }

    static PostChainConfig createConfig(
            int width, int height, BloomConfig config, boolean custom, boolean colored) {
        // I probe the same fine grid; a 3px average can hide faint paint that still glows at 2px.
        if (config.usesDistancePixels()) {
            config = config.copy();
            config.pixelSize = config.finePixelSize();
            config.distancePixels = false;
        }
        var full = BloomGraph.createConfig(width, height, config, custom, colored);
        var original = full.passes().getFirst();
        List<PostChainConfig.Input> inputs = new ArrayList<>();
        for (var input : original.inputs()) {
            if (input instanceof PostChainConfig.TextureInput texture
                    && texture.samplerName().equals("Sources"))
                inputs.add(
                        new PostChainConfig.TextureInput(
                                "Sources",
                                SourceMask.ACTIVE_TEXTURE_ID,
                                SourceMask.TEXTURE_SIZE,
                                SourceMask.TEXTURE_SIZE,
                                false));
            else inputs.add(input);
        }
        var extraction =
                new PostChainConfig.Pass(
                        original.vertexShaderId(),
                        original.fragmentShaderId(),
                        inputs,
                        original.outputTarget(),
                        original.uniforms());
        List<PostChainConfig.Pass> passes = new ArrayList<>();
        passes.add(extraction);
        Map<Identifier, PostChainConfig.InternalTarget> targets = new LinkedHashMap<>();
        Identifier previous = extraction.outputTarget();
        var first = full.internalTargets().get(previous);
        targets.put(
                previous,
                new PostChainConfig.InternalTarget(
                        first.width(), first.height(), true, first.clearColor()));
        int w = first.width().orElseThrow(), h = first.height().orElseThrow(), step = 0;
        do {
            w = BloomGraph.reducedSize(w, 4);
            h = BloomGraph.reducedSize(h, 4);
            Identifier output = w == 1 && h == 1 ? RESULT : BloomGraph.id("activity_" + (step++));
            // I retain these small probe targets because probes run farther apart than Minecraft's
            // pool keeps them.
            if (!output.equals(RESULT))
                targets.put(
                        output,
                        new PostChainConfig.InternalTarget(
                                Optional.of(w), Optional.of(h), true, 0));
            passes.add(
                    new PostChainConfig.Pass(
                            Identifier.withDefaultNamespace("core/screenquad"),
                            BloomGraph.id("post/activity_max"),
                            List.of(new PostChainConfig.TargetInput("In", previous, false, false)),
                            output,
                            Map.of()));
            previous = output;
        } while (w > 1 || h > 1);
        return new PostChainConfig(targets, passes);
    }

    @Override
    public void close() {
        generation++;
        state.reset();
        disabled = false;
        if (chain != null) chain.close();
        chain = null;
        uniforms.clear();
        rainUniforms.clear();
        if (projection != null) projection.close();
        projection = null;
        if (target != null) target.destroyBuffers();
        target = null;
        if (readback != null) readback.close();
        readback = null;
    }

    static final class IdleState {
        private long revision = Long.MIN_VALUE, epoch, nextProbe;
        private boolean idle, pending;

        void observe(long current) {
            if (revision != current) {
                revision = current;
                epoch++;
                idle = false;
            }
        }

        boolean idle() {
            return idle;
        }

        boolean shouldProbe(long now) {
            return !pending && now >= nextProbe;
        }

        long begin(long now) {
            pending = true;
            nextProbe = now + (idle ? 100_000_000L : 250_000_000L);
            return epoch;
        }

        void complete(long ticket, boolean empty, long now) {
            pending = false;
            if (ticket == epoch) {
                boolean wasIdle = idle;
                idle = empty;
                if (idle && !wasIdle) nextProbe = Math.min(nextProbe, now + 100_000_000L);
            }
        }

        void reset() {
            epoch++;
            revision = Long.MIN_VALUE;
            idle = false;
            pending = false;
            nextProbe = 0;
        }
    }
}
