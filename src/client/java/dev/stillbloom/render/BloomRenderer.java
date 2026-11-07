package dev.stillbloom.render;

import com.mojang.blaze3d.framegraph.FrameGraphBuilder;
import com.mojang.blaze3d.pipeline.RenderTarget;
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
import java.util.ArrayList;
import java.util.Objects;
import java.util.function.Consumer;

// I use Minecraft's rendering API so the same work can run on OpenGL and Vulkan.
public final class BloomRenderer implements AutoCloseable {
    private PostChain chain;
    private ProjectionMatrixBuffer projectionBuffer;
    private final List<GpuBuffer> sourceBuffers = new ArrayList<>();
    private final List<GpuBuffer> stabilityBuffers = new ArrayList<>();
    private final List<GpuBuffer> rainBuffers = new ArrayList<>();
    private final BloomHistory history = new BloomHistory();
    private DistanceTargets distanceTargets = new DistanceTargets();
    private final CameraUniforms cameraUniforms = new CameraUniforms();
    private final ScopedAllocator frameAllocator =
            new ScopedAllocator(32L * 1024 * 1024, 4_000_000_000L);
    private static final long INACTIVE_BYTES = 64L * 1024 * 1024;
    // I retain only unused resources I own, never the frame graph's temporary texture views.
    private final ChainCache<List<SourceMask.RenderGroup>, CachedChain> cachedChains =
            new ChainCache<>(16, INACTIVE_BYTES, 10_000_000_000L, CachedChain::close);
    private long chainBytes;
    private long groupTextureRevision = -1;

    private record CachedChain(
            PostChain chain,
            ProjectionMatrixBuffer projection,
            DistanceTargets distance,
            List<GpuBuffer> sources,
            List<GpuBuffer> stability,
            List<GpuBuffer> rain,
            long bytes) {
        void close() {
            chain.close();
            distance.close();
            projection.close();
        }
    }

    private int width = -1, height = -1;
    private volatile boolean dirty = true;
    private boolean failed;
    private boolean customMode;
    private boolean colorMode;
    private boolean detailMode;
    private boolean directMode;
    private boolean weatherMode;
    private List<SourceMask.RenderGroup> renderGroups = List.of();
    private final BloomActivity activity = new BloomActivity();

    public void invalidate() {
        dirty = true;
        StillBloomClient.WEATHER_SCENE.retry();
        StillBloomClient.HELD_RENDERER.invalidate();
    }

    // I update source data without throwing away unrelated GPU resources.
    public void sourcesChanged() {
        failed = false;
        StillBloomClient.HELD_RENDERER.sourcesChanged();
    }

    int renderWidth() {
        return width;
    }

    int renderHeight() {
        return height;
    }

    public void render(Minecraft client, RenderTarget main, GraphicsResourceAllocator allocator) {
        frameAllocator.trim();
        cachedChains.expire(System.nanoTime());
        history.trimInactive(System.nanoTime(), INACTIVE_BYTES - cachedChains.bytes());
        if (StillBloomClient.config == null
                || !StillBloomClient.config.enabled
                || main.width < 1
                || main.height < 1) {
            frameAllocator.close();
            history.invalidate();
            return;
        }
        if (!StillBloomClient.SOURCE_MASK.hasSources()
                || !StillBloomClient.SOURCE_MASK.hasVisibleSources(client)) {
            history.invalidate();
            return;
        }
        boolean custom = StillBloomClient.SOURCE_MASK.hasCustomSources();
        boolean colored = StillBloomClient.SOURCE_MASK.hasColors();
        boolean details = StillBloomClient.SOURCE_MASK.hasDetails();
        boolean direct = canCompositeDirectly(main.getColorTexture().getFormat());
        RenderTarget weatherScene = StillBloomClient.WEATHER_SCENE.input(main);
        boolean weather = weatherScene != main;
        if (dirty
                || width != main.width
                || height != main.height
                || customMode != custom
                || colorMode != colored
                || detailMode != details
                || directMode != direct
                || weatherMode != weather) {
            close();
            dirty = false;
            failed = false;
            width = main.width;
            height = main.height;
            customMode = custom;
            colorMode = colored;
            detailMode = details;
            directMode = direct;
            weatherMode = weather;
        }
        if (failed) return;
        try {
            if (activity.skip(client, main, allocator, customMode, weatherScene, weatherMode)) {
                history.invalidate();
                return;
            }
            var groups = StillBloomClient.SOURCE_MASK.visibleGroups();
            StillBloomClient.SOURCE_MASK.ensureGroupTextures();
            long textureRevision = StillBloomClient.SOURCE_MASK.groupTextureRevision();
            if (groupTextureRevision != textureRevision) {
                // I discard chains that still refer to replaced textures, since their bindings hold
                // objects rather than names.
                cachedChains.close();
                closeChain();
                renderGroups = List.of();
                groupTextureRevision = textureRevision;
            }
            if (!renderGroups.equals(groups)) {
                switchGroups(groups);
            }
            if (chain == null) {
                StillBloomClient.SOURCE_MASK.ensureTexture(client);
                if (customMode) StillBloomClient.SOURCE_MASK.geometry.ensure();
                if (colorMode) StillBloomClient.SOURCE_MASK.ensureColors();
                if (detailMode) StillBloomClient.SOURCE_MASK.ensureDetails();
                Projection projection = new Projection();
                projection.setupOrtho(0, 1, width, height, true);
                projectionBuffer = new ProjectionMatrixBuffer("Still Bloom projection");
                var config = StillBloomClient.config;
                Set<Identifier> externals =
                        new HashSet<>(
                                config.stabilityMillis > 0
                                        ? history.initialize(
                                                width,
                                                height,
                                                config.renderPixelSize(),
                                                renderGroups)
                                        : Set.of(PostChain.MAIN_TARGET_ID));
                var graph =
                        BloomGraph.createGroupedConfig(
                                width,
                                height,
                                config,
                                customMode,
                                colorMode,
                                renderGroups,
                                directMode,
                                detailMode);
                if (weatherMode) {
                    graph = BloomGraph.forWeather(graph);
                    externals.add(BloomGraph.WEATHER_SCENE);
                }
                chainBytes = estimateRetainedBytes(width, height, config, renderGroups, graph);
                if (config.usesDistancePixels())
                    graph = distanceTargets.initialize(graph, externals);
                chain =
                        PostChain.load(
                                graph,
                                client.getTextureManager(),
                                externals,
                                BloomGraph.id("bloom"),
                                projection,
                                projectionBuffer);
                for (var pass : ((PostChainAccessor) chain).stillbloom$getPasses()) {
                    var uniforms = ((PostPassAccessor) pass).stillbloom$getUniforms();
                    if (uniforms.containsKey("SourceMask")) {
                        var buffer =
                                RenderSystem.getDevice()
                                        .createBuffer(
                                                () -> "Still Bloom source mask",
                                                GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST,
                                                SourceMask.BUFFER_BYTES);
                        uniforms.put("SourceMask", buffer).close();
                        sourceBuffers.add(buffer);
                    }
                    if (uniforms.containsKey("RainFog")) {
                        var buffer =
                                RenderSystem.getDevice()
                                        .createBuffer(
                                                () -> "Still Bloom rain fog",
                                                GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST,
                                                RainFogState.BUFFER_BYTES);
                        uniforms.put("RainFog", buffer).close();
                        rainBuffers.add(buffer);
                    }
                    if (uniforms.containsKey("Stability")) {
                        var buffer =
                                RenderSystem.getDevice()
                                        .createBuffer(
                                                () -> "Still Bloom stability",
                                                GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST,
                                                16);
                        uniforms.put("Stability", buffer).close();
                        stabilityBuffers.add(buffer);
                    }
                }
                StillBloomClient.LOGGER.debug(
                        "Still Bloom ready: {}x{}, {}px screen-locked grid",
                        width,
                        height,
                        StillBloomClient.config.pixelSize);
            }
            StillBloomClient.SOURCE_MASK.upload(client, sourceBuffers, cameraUniforms);
            history.prepare(client, stabilityBuffers);
            RainFogState.upload(rainBuffers);
            FrameGraphBuilder frame = new FrameGraphBuilder();
            PostChain.TargetBundle targets;
            if (!weatherMode && stabilityBuffers.isEmpty() && !distanceTargets.active())
                targets =
                        PostChain.TargetBundle.of(
                                PostChain.MAIN_TARGET_ID, frame.importExternal("main", main));
            else {
                Map<Identifier, ResourceHandle<RenderTarget>> handles = new HashMap<>();
                handles.put(PostChain.MAIN_TARGET_ID, frame.importExternal("main", main));
                history.targets(frame, handles);
                distanceTargets.importInto(frame, handles);
                if (weatherMode)
                    handles.put(
                            BloomGraph.WEATHER_SCENE,
                            frame.importExternal("rain_scene", weatherScene));
                targets =
                        new PostChain.TargetBundle() {
                            public ResourceHandle<RenderTarget> get(Identifier id) {
                                return handles.get(id);
                            }

                            public void replace(
                                    Identifier id, ResourceHandle<RenderTarget> handle) {
                                handles.put(id, handle);
                            }
                        };
            }
            chain.addToFrame(frame, width, height, targets);
            frameAllocator.execute(frame, allocator);
            history.advance();
        } catch (Exception e) {
            failed = true;
            close();
            StillBloomClient.LOGGER.error(
                    "Bloom disabled after a rendering failure. Press F10 to retry.", e);
        }
    }

    // I preserve the RGBA8 rounding of intermediate composites on other output formats.
    static boolean canCompositeDirectly(GpuFormat format) {
        return format == GpuFormat.RGBA8_UNORM;
    }

    // I count only this chain's own targets here; the frame allocator has a separate limit.
    static long estimateRetainedBytes(
            int width,
            int height,
            BloomConfig config,
            List<SourceMask.RenderGroup> groups,
            PostChainConfig graph) {
        // I count history and distance targets in their owning pools, not again in the inactive
        // chain.
        return graph.passes().size() * 16384L;
    }

    private void switchGroups(List<SourceMask.RenderGroup> groups) {
        long now = System.nanoTime();
        // I take the requested chain first so storing the outgoing one can't evict it.
        var reused = cachedChains.take(groups, now);
        if (chain != null) {
            cachedChains.put(
                    renderGroups,
                    new CachedChain(
                            chain,
                            projectionBuffer,
                            distanceTargets,
                            List.copyOf(sourceBuffers),
                            List.copyOf(stabilityBuffers),
                            List.copyOf(rainBuffers),
                            chainBytes),
                    chainBytes,
                    now);
            chain = null;
            projectionBuffer = null;
            sourceBuffers.clear();
            stabilityBuffers.clear();
            rainBuffers.clear();
            distanceTargets = new DistanceTargets();
            chainBytes = 0;
        } else closeChain(); // I also release a partially initialized chain.
        renderGroups = groups;
        cameraUniforms.reset();
        if (reused != null) {
            chain = reused.chain;
            projectionBuffer = reused.projection;
            distanceTargets = reused.distance;
            sourceBuffers.addAll(reused.sources);
            stabilityBuffers.addAll(reused.stability);
            rainBuffers.addAll(reused.rain);
            chainBytes = reused.bytes;
            // I reset brightness history when the visible styles change.
            history.invalidate();
        }
        if (StillBloomClient.config.stabilityMillis > 0) {
            // I bind external targets afresh each frame because cached chains don't own them.
            history.initialize(width, height, StillBloomClient.config.renderPixelSize(), groups);
            history.trimInactive(now, INACTIVE_BYTES - cachedChains.bytes());
        }
    }

    @Override
    public void close() {
        frameAllocator.close();
        activity.close();
        cachedChains.close();
        closeChain();
        renderGroups = List.of();
        groupTextureRevision = -1;
    }

    private void closeChain() {
        if (chain != null) {
            chain.close();
            chain = null;
        }
        sourceBuffers.clear();
        cameraUniforms.reset();
        stabilityBuffers.clear();
        rainBuffers.clear();
        history.close();
        distanceTargets.close();
        if (projectionBuffer != null) {
            projectionBuffer.close();
            projectionBuffer = null;
        }
        chainBytes = 0;
    }

    // I transfer ownership to the renderer when it takes a cached entry.
    static final class ChainCache<K, V> implements AutoCloseable {
        private record Entry<K, V>(K key, V value, long bytes, long stored) {}

        private final ArrayList<Entry<K, V>> entries = new ArrayList<>();
        private final int capacity;
        private final long byteLimit, maxIdle;
        private final Consumer<V> disposer;
        private long bytes;

        ChainCache(int capacity, long byteLimit, long maxIdle, Consumer<V> disposer) {
            if (capacity < 0 || byteLimit < 0 || maxIdle < 0)
                throw new IllegalArgumentException("Negative resource limit");
            this.capacity = capacity;
            this.byteLimit = byteLimit;
            this.maxIdle = maxIdle;
            this.disposer = disposer;
        }

        V take(K key, long now) {
            expire(now);
            for (int i = 0; i < entries.size(); i++)
                if (Objects.equals(entries.get(i).key, key)) {
                    var entry = entries.remove(i);
                    bytes -= entry.bytes;
                    return entry.value;
                }
            return null;
        }

        void put(K key, V value, long weight, long now) {
            if (weight < 0) throw new IllegalArgumentException("Negative resource size");
            expire(now);
            for (int i = 0; i < entries.size(); i++)
                if (Objects.equals(entries.get(i).key, key)) {
                    discard(i);
                    break;
                }
            if (capacity == 0 || weight > byteLimit || maxIdle == 0) {
                disposer.accept(value);
                return;
            }
            // I subtract before comparing so a saturated size estimate can't overflow.
            while (!entries.isEmpty() && (entries.size() >= capacity || bytes > byteLimit - weight))
                discard(0);
            entries.add(new Entry<>(key, value, weight, now));
            bytes += weight;
        }

        void expire(long now) {
            while (!entries.isEmpty() && now - entries.getFirst().stored >= maxIdle) discard(0);
        }

        private void discard(int index) {
            var entry = entries.remove(index);
            bytes -= entry.bytes;
            disposer.accept(entry.value);
        }

        int size() {
            return entries.size();
        }

        long bytes() {
            return bytes;
        }

        @Override
        public void close() {
            while (!entries.isEmpty()) discard(entries.size() - 1);
        }
    }
}
