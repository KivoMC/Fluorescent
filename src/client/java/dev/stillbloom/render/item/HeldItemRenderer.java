package dev.stillbloom.render.item;

import com.mojang.blaze3d.framegraph.FrameGraphBuilder;
import com.mojang.blaze3d.pipeline.*;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import com.mojang.blaze3d.resource.ResourceHandle;
import com.mojang.blaze3d.systems.*;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.vertex.VertexFormat;

import dev.stillbloom.StillBloomClient;
import dev.stillbloom.compat.CustomHeldItemCapture;
import dev.stillbloom.config.BloomConfig;
import dev.stillbloom.config.BloomStyle;
import dev.stillbloom.mask.ItemMasks;
import dev.stillbloom.mask.TextureMasks;
import dev.stillbloom.model.ItemAppearance;
import dev.stillbloom.render.BloomGraph;
import dev.stillbloom.render.DistanceTargets;
import dev.stillbloom.render.ScopedAllocator;

import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.resources.Identifier;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector4f;
import org.joml.Vector4fc;

import java.nio.*;
import java.util.*;

// I give held items their own cropped buffers so they don't disturb placed-block caches.
public final class HeldItemRenderer implements AutoCloseable {
    private static final int STRIDE = 80;
    private static final Vector4fc ZERO = new Vector4f();
    private static final VertexFormat FORMAT =
            VertexFormat.builder(0)
                    .addAttribute("ClipPosition", GpuFormat.RGBA32_FLOAT)
                    .addAttribute("TexCoords", GpuFormat.RGBA32_FLOAT)
                    .addAttribute("TintColor", GpuFormat.RGBA32_FLOAT)
                    .addAttribute("BloomColor", GpuFormat.RGBA32_FLOAT)
                    .addAttribute("SourceParams", GpuFormat.RGBA32_FLOAT)
                    .build();

    private static RenderPipeline pipeline(boolean distance, boolean cull) {
        return RenderPipeline.builder()
                .withLocation(
                        BloomGraph.id(
                                (distance ? "held_source_distance" : "held_source")
                                        + (cull ? "_culled" : "")))
                .withVertexShader(BloomGraph.id("core/held_source"))
                .withFragmentShader(BloomGraph.id("core/held_source"))
                .withBindGroupLayout(
                        BindGroupLayout.builder()
                                .withUniform("AtlasSampler", UniformType.COMBINED_IMAGE_SAMPLER)
                                .withUniform("MaskSampler", UniformType.COMBINED_IMAGE_SAMPLER)
                                .withUniform("DepthSampler", UniformType.COMBINED_IMAGE_SAMPLER)
                                .withUniform("HeldFrame", UniformType.UNIFORM_BUFFER)
                                .build())
                .withVertexBinding(0, FORMAT)
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                .withCull(cull)
                .withDepthStencilState(DepthStencilState.DEFAULT)
                .withColorTargetState(
                        0,
                        new ColorTargetState(
                                Optional.empty(),
                                distance ? GpuFormat.RGBA16_FLOAT : GpuFormat.RGBA8_UNORM,
                                ColorTargetState.WRITE_ALL))
                .withColorTargetState(1, ColorTargetState.DEFAULT)
                .build();
    }

    private static final RenderPipeline SOURCE = pipeline(false, false),
            SOURCE_CULLED = pipeline(false, true),
            SOURCE_DISTANCE = pipeline(true, false),
            SOURCE_DISTANCE_CULLED = pipeline(true, true);

    private static RenderPipeline coveragePipeline(boolean cull) {
        return RenderPipeline.builder()
                .withLocation(BloomGraph.id("held_coverage" + (cull ? "_culled" : "")))
                .withVertexShader(BloomGraph.id("core/held_source"))
                .withFragmentShader(BloomGraph.id("core/held_coverage"))
                .withBindGroupLayout(
                        BindGroupLayout.builder()
                                .withUniform("AtlasSampler", UniformType.COMBINED_IMAGE_SAMPLER)
                                .withUniform("DepthSampler", UniformType.COMBINED_IMAGE_SAMPLER)
                                .withUniform("HeldFrame", UniformType.UNIFORM_BUFFER)
                                .build())
                .withVertexBinding(0, FORMAT)
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                .withCull(cull)
                .withDepthStencilState(DepthStencilState.DEFAULT)
                .withColorTargetState(ColorTargetState.DEFAULT)
                .build();
    }

    private static final RenderPipeline COVERAGE = coveragePipeline(false),
            COVERAGE_CULLED = coveragePipeline(true);

    public static List<RenderPipeline> warmupPipelines(BloomConfig config) {
        return List.of(
                config.usesDistancePixels() ? SOURCE_DISTANCE : SOURCE,
                config.usesDistancePixels() ? SOURCE_DISTANCE_CULLED : SOURCE_CULLED,
                COVERAGE,
                COVERAGE_CULLED,
                HeldItemBlit.pipeline(GpuFormat.RGBA8_UNORM));
    }

    private final Stage world = new Stage(), hands = new Stage();
    private final HeldItemBlit blit = new HeldItemBlit();
    private final HeldItemIndices indices = new HeldItemIndices();
    private final HeldItemBounds.ClipScratch clipScratch = new HeldItemBounds.ClipScratch();
    private static final long WARM_NANOS = 4_000_000_000L;
    private static final long WARM_GROUP_BYTES = 16L * 1024 * 1024;
    private long frameTime;
    private final Matrix4f worldProjection = new Matrix4f(),
            handProjection = new Matrix4f(),
            transform = new Matrix4f();
    private final Vector4f vector = new Vector4f();
    private final Map<List<BakedQuad>, Geometry> geometry = new IdentityHashMap<>();
    private final Map<TextureMasks.Mask, DynamicTexture> masks = new IdentityHashMap<>();
    private final Map<String, ItemAppearance.Resolved> appearances = new HashMap<>();
    private float[] customClips = new float[64], customUv = new float[16];
    private DynamicTexture white;
    private Object modelSet, maskOwner;
    private long maskRevision = -1, maskGeometryRevision = -1, frame;
    private Object itemMaskOwner;
    private long itemMaskRevision = -1, itemGeometryRevision = -1;
    private boolean dirty = true, failed;
    private boolean wasEnabled;
    private int mainWidth, mainHeight;
    private GpuBuffer frameUniform;
    private final ScopedAllocator frameAllocator =
            new ScopedAllocator(WARM_GROUP_BYTES, WARM_NANOS);
    private final ByteBuffer frameData =
            ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder());

    public void invalidate() {
        dirty = true;
        failed = false;
    }

    public void sourcesChanged() {
        failed = false;
    }

    public void captureWorldProjection(Matrix4fc value) {
        worldProjection.set(value);
    }

    public void captureHandProjection(Projection value) {
        value.getMatrix(handProjection);
    }

    public void beginFrame() {
        HeldItemCapture.beginFrame();
        frame++;
        frameTime = System.nanoTime();
        frameAllocator.trim();
        boolean enabled = enabled();
        if (wasEnabled && !enabled) closeResources();
        wasEnabled = enabled;
        world.reset();
        hands.reset();
    }

    private boolean enabled() {
        var c = StillBloomClient.config;
        return c != null && c.enabled && (c.heldItemBloom || c.otherPlayerItemBloom);
    }

    private void prepare(Minecraft client, RenderTarget main) {
        Object models = client.getModelManager().getBlockStateModelSet();
        if (dirty
                || mainWidth != main.width
                || mainHeight != main.height
                || modelSet != models
                || maskOwner != StillBloomClient.MASKS
                || maskGeometryRevision != StillBloomClient.MASKS.geometryRevision()
                || itemMaskOwner != StillBloomClient.ITEM_MASKS
                || itemGeometryRevision != StillBloomClient.ITEM_MASKS.geometryRevision()) {
            closeResources();
            dirty = false;
            mainWidth = main.width;
            mainHeight = main.height;
            modelSet = models;
            maskOwner = StillBloomClient.MASKS;
            maskGeometryRevision = StillBloomClient.MASKS.geometryRevision();
            itemMaskOwner = StillBloomClient.ITEM_MASKS;
            itemGeometryRevision = StillBloomClient.ITEM_MASKS.geometryRevision();
        }
        if (maskRevision != StillBloomClient.MASKS.revision()
                || itemMaskRevision != StillBloomClient.ITEM_MASKS.revision()) appearances.clear();
        maskRevision = StillBloomClient.MASKS.revision();
        itemMaskRevision = StillBloomClient.ITEM_MASKS.revision();
        if (frameUniform == null)
            frameUniform =
                    RenderSystem.getDevice()
                            .createBuffer(
                                    () -> "Still Bloom held frame",
                                    GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST,
                                    32);
    }

    // I capture before block bloom changes scene color, while the world's depth is still available.
    public void captureWorld(Minecraft client, RenderTarget main) {
        capture(
                client,
                main,
                HeldItemCapture.worldItems(),
                CustomHeldItemCapture.worldMeshes(),
                worldProjection,
                world);
    }

    // I composite before first-person hands replace world depth and cover the world glow.
    public void renderWorld(
            Minecraft client, RenderTarget main, GraphicsResourceAllocator allocator) {
        finish(client, main, allocator, world);
    }

    public void renderHands(
            Minecraft client, RenderTarget main, GraphicsResourceAllocator allocator) {
        capture(
                client,
                main,
                HeldItemCapture.handItems(),
                CustomHeldItemCapture.handMeshes(),
                handProjection,
                hands);
        finish(client, main, allocator, hands);
    }

    private void capture(
            Minecraft client,
            RenderTarget main,
            List<HeldItemCapture.CapturedItem> items,
            List<CustomHeldItemCapture.Mesh> custom,
            Matrix4fc projection,
            Stage stage) {
        if (!enabled()
                || failed
                || items.isEmpty() && custom.isEmpty()
                || main.width < 1
                || main.height < 1) return;
        try {
            prepare(client, main);
            for (var item : items) append(item, projection, stage);
            for (var mesh : custom) appendCustom(mesh, projection, stage);
            int maxVertices = 0;
            for (var group : stage.groups.values())
                if (group.active)
                    for (var batch : group.batches.values())
                        maxVertices = Math.max(maxVertices, batch.vertices);
            indices.ensure(maxVertices);
            for (var group : stage.groups.values())
                if (group.active) {
                    group.ensure(mainWidth, mainHeight, frameTime);
                    drawSources(client, main, group);
                }
            // I redraw only coverage to protect sources from neighboring styles, without another
            // bloom pass.
            if (stage.groups.values().stream().filter(group -> group.active).count() > 1)
                for (var group : stage.groups.values())
                    if (group.active) drawOtherCoverage(client, main, group, stage);
        } catch (Exception e) {
            fail(e);
        }
    }

    private void frameUniform(RenderTarget main, Group group) {
        frameData.clear();
        frameData
                .putFloat(group.rect.x())
                .putFloat(group.rect.y())
                .putFloat(group.width)
                .putFloat(group.height)
                .putFloat(main.width)
                .putFloat(main.height)
                .putFloat(group.config.threshold)
                .putFloat(group.config.softKnee)
                .flip();
        RenderSystem.getDevice()
                .createCommandEncoder()
                .writeToBuffer(frameUniform.slice(), frameData);
    }

    private void drawOtherCoverage(Minecraft client, RenderTarget main, Group output, Stage stage) {
        frameUniform(main, output);
        try (var pass =
                RenderSystem.getDevice()
                        .createCommandEncoder()
                        .createRenderPass(
                                () -> "Still Bloom held detail",
                                output.coverage.getColorTextureView(),
                                Optional.empty(),
                                output.emission.getDepthTextureView(),
                                OptionalDouble.empty())) {
            pass.setPipeline(RenderSystem.getCompiledPipeline(COVERAGE));
            pass.setUniform("HeldFrame", frameUniform);
            pass.setIndexBuffer(indices.buffer(), indices.type());
            pass.setUniform(
                    "DepthSampler",
                    main.getDepthTextureView(),
                    RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
            for (var group : stage.groups.values())
                if (group.active && group != output)
                    for (var batch : group.batches.values())
                        if (batch.vertices > 0) {
                            pass.setPipeline(
                                    RenderSystem.getCompiledPipeline(
                                            batch.material.cull ? COVERAGE_CULLED : COVERAGE));
                            pass.setUniform(
                                    "AtlasSampler",
                                    client.getTextureManager()
                                            .getTexture(batch.material.atlas)
                                            .getTextureView(),
                                    RenderSystem.getSamplerCache()
                                            .getClampToEdge(FilterMode.NEAREST));
                            pass.setVertexBuffer(
                                    0, batch.gpu.slice(0, (long) batch.vertices * STRIDE));
                            pass.drawIndexed(
                                    HeldItemIndices.indexCount(batch.vertices), 1, 0, 0, 0);
                        }
        }
    }

    private void append(HeldItemCapture.CapturedItem item, Matrix4fc projection, Stage stage) {
        var config = StillBloomClient.config;
        var appearance = appearance(item.itemId(), item.blockId());
        var style = appearance.style();
        if (style.intensity() <= 0) return;
        var profile = appearance.block();
        var own = appearance.item();
        Integer color = appearance.color();
        if (color != null && color == 0) return;
        float range = item.firstPerson() ? 1 : Math.min(config.heldItemRange, config.bloomRange);
        float fade =
                item.firstPerson()
                        ? 1
                        : 1 - smoothstep(range * (1 - config.fadeFraction), range, item.distance());
        if (fade <= 0) return;
        var mesh = geometry.get(item.quads());
        if (mesh == null) {
            if (geometry.size() >= 256) geometry.clear();
            mesh = new Geometry(item.quads());
            geometry.put(item.quads(), mesh);
        }
        transform.set(projection).mul(item.viewPose());
        for (int i = 0; i < mesh.quads.length; i++)
            for (int vertex = 0; vertex < 4; vertex++) {
                var p = mesh.quads[i].quad.position(vertex);
                transform.transform(vector.set(p.x(), p.y(), p.z(), 1));
                int offset = i * 16 + vertex * 4;
                mesh.clips[offset] = vector.x;
                mesh.clips[offset + 1] = vector.y;
                mesh.clips[offset + 2] = vector.z;
                mesh.clips[offset + 3] = vector.w;
            }
        var effective = stage.configs.computeIfAbsent(style, value -> value.apply(config));
        var rect =
                HeldItemBounds.project(
                        mesh.clips,
                        mesh.quads.length * 4,
                        mainWidth,
                        mainHeight,
                        effective,
                        clipScratch);
        if (rect.empty()) return;
        float detail = appearance.detail();
        Group group = null;
        float nearWeight =
                config.usesDistancePixels()
                        ? 1
                                - smoothstep(
                                        config.pixelDistanceStart,
                                        config.pixelDistanceStart + config.pixelDistanceSpan,
                                        item.distance())
                        : 1;
        for (int q = 0; q < mesh.quads.length; q++) {
            var quad = mesh.quads[q];
            var mask = profile == null ? null : profile.textures.get(quad.texture);
            float strength =
                    mask != null
                            ? profile.strength
                            : item.blockState() != null && item.blockState().getLightEmission() > 0
                                    ? 1
                                    : 0;
            var itemMask = own == null ? null : own.textures.get(quad.texture);
            if (itemMask != null) {
                if (ItemMasks.compatibleMask(
                                itemMask,
                                quad.quad.materialInfo().sprite().contents().width(),
                                quad.quad.materialInfo().sprite().contents().height())
                        == null) continue;
                mask = itemMask;
                strength = own.strength;
            }
            if (strength <= 0) continue;
            if (mask != null && !hasPaint(mask)) continue;
            if (group == null) {
                group = stage.groups.computeIfAbsent(style, value -> new Group(effective));
                group.include(rect);
                group.lastUsed = frame;
            }
            var key = new Material(quad.atlas, mask, quad.cull);
            var batch = group.batches.computeIfAbsent(key, Batch::new);
            batch.lastUsed = frame;
            int tintIndex = quad.quad.materialInfo().tintIndex();
            int tint =
                    tintIndex >= 0 && tintIndex < item.tintLayers().length
                            ? item.tintLayers()[tintIndex]
                            : -1;
            batch.quad(
                    mesh.clips, q * 16, quad.uv, tint, color, strength, detail, fade, nearWeight);
        }
    }

    private ItemAppearance.Resolved appearance(String itemId, String blockId) {
        return appearances.computeIfAbsent(
                itemId,
                key ->
                        ItemAppearance.resolve(
                                StillBloomClient.config,
                                blockId == null ? null : StillBloomClient.MASKS.profile(blockId),
                                StillBloomClient.ITEM_MASKS.profile(itemId)));
    }

    // I keep Punchy's already-animated vertices from receiving the pose a second time.
    private void appendCustom(CustomHeldItemCapture.Mesh mesh, Matrix4fc projection, Stage stage) {
        var item = mesh.item();
        var config = StillBloomClient.config;
        var appearance = appearance(item.itemId(), item.blockId());
        var style = appearance.style();
        if (style.intensity() <= 0 || appearance.color() != null && appearance.color() == 0) return;
        var own = appearance.item();
        var texture = mesh.texture();
        var mask =
                own == null
                        ? null
                        : ItemMasks.compatibleMask(
                                own.textures.get(texture.id()), texture.width(), texture.height());
        if (mask == null || own.strength <= 0 || !hasPaint(mask)) return;
        float range = item.firstPerson() ? 1 : Math.min(config.heldItemRange, config.bloomRange);
        float fade =
                item.firstPerson()
                        ? 1
                        : 1 - smoothstep(range * (1 - config.fadeFraction), range, item.distance());
        if (fade <= 0) return;
        int count = mesh.vertexCount(), stride = CustomHeldItemCapture.STRIDE;
        if (customClips.length < count * 4) customClips = new float[count * 4];
        transform.set(projection).mul(mesh.viewPose());
        var vertices = mesh.vertices();
        for (int i = 0; i < count; i++) {
            int p = i * stride;
            transform.transform(vector.set(vertices[p], vertices[p + 1], vertices[p + 2], 1));
            customClips[i * 4] = vector.x;
            customClips[i * 4 + 1] = vector.y;
            customClips[i * 4 + 2] = vector.z;
            customClips[i * 4 + 3] = vector.w;
        }
        var effective = stage.configs.computeIfAbsent(style, value -> value.apply(config));
        var rect =
                HeldItemBounds.project(
                        customClips, count, mainWidth, mainHeight, effective, clipScratch);
        if (rect.empty()) return;
        float nearWeight =
                config.usesDistancePixels()
                        ? 1
                                - smoothstep(
                                        config.pixelDistanceStart,
                                        config.pixelDistanceStart + config.pixelDistanceSpan,
                                        item.distance())
                        : 1;
        Group group = null;
        for (int q = 0; q < count; q += 4) {
            int start = q * stride;
            float alpha = vertices[start + 8];
            if (alpha <= 0) continue;
            if (group == null) {
                group = stage.groups.computeIfAbsent(style, value -> new Group(effective));
                group.include(rect);
                group.lastUsed = frame;
            }
            var batch =
                    group.batches.computeIfAbsent(
                            new Material(texture.target(), mask, false), Batch::new);
            batch.lastUsed = frame;
            for (int i = 0; i < 4; i++) {
                int p = (q + i) * stride;
                float u = vertices[p + 3], v = vertices[p + 4];
                customUv[i * 4] = u;
                customUv[i * 4 + 1] = v;
                customUv[i * 4 + 2] = texture.maskU(u);
                customUv[i * 4 + 3] = texture.maskV(v);
            }
            int tint =
                    Math.round(Math.clamp(vertices[start + 5], 0, 1) * 255) << 16
                            | Math.round(Math.clamp(vertices[start + 6], 0, 1) * 255) << 8
                            | Math.round(Math.clamp(vertices[start + 7], 0, 1) * 255);
            batch.quad(
                    customClips,
                    q * 4,
                    customUv,
                    tint,
                    appearance.color(),
                    own.strength,
                    appearance.detail(),
                    fade * alpha,
                    nearWeight);
        }
    }

    private final Map<TextureMasks.Mask, Boolean> painted = new IdentityHashMap<>();

    private boolean hasPaint(TextureMasks.Mask mask) {
        return painted.computeIfAbsent(
                mask,
                value -> {
                    for (int word : value.words) if (word != 0) return true;
                    return false;
                });
    }

    private void drawSources(Minecraft client, RenderTarget main, Group group) {
        frameData.clear();
        frameData
                .putFloat(group.rect.x())
                .putFloat(group.rect.y())
                .putFloat(group.width)
                .putFloat(group.height)
                .putFloat(main.width)
                .putFloat(main.height)
                .putFloat(group.config.threshold)
                .putFloat(group.config.softKnee)
                .flip();
        var encoder = RenderSystem.getDevice().createCommandEncoder();
        encoder.writeToBuffer(frameUniform.slice(), frameData);
        var descriptor =
                RenderPassDescriptor.builder(() -> "Still Bloom held sources")
                        .withColorAttachment(
                                group.emission.getColorTextureView(), Optional.of(ZERO))
                        .withColorAttachment(
                                group.coverage.getColorTextureView(), Optional.of(ZERO))
                        // I keep the nearest surface so a hidden inner layer can't erase its paint.
                        .withDepthAttachment(
                                group.emission.getDepthTextureView(), OptionalDouble.of(0.0))
                        .withRenderArea(new RenderPass.RenderArea(0, 0, group.width, group.height));
        for (var batch : group.batches.values())
            if (batch.vertices > 0) {
                batch.upload(encoder);
                maskTexture(batch.material.mask);
            }
        try (var pass = encoder.createRenderPass(descriptor.build())) {
            pass.setPipeline(
                    RenderSystem.getCompiledPipeline(
                            group.config.usesDistancePixels() ? SOURCE_DISTANCE : SOURCE));
            pass.setUniform("HeldFrame", frameUniform);
            pass.setIndexBuffer(indices.buffer(), indices.type());
            pass.setUniform(
                    "DepthSampler",
                    main.getDepthTextureView(),
                    RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
            for (var batch : group.batches.values())
                if (batch.vertices > 0) {
                    pass.setPipeline(
                            RenderSystem.getCompiledPipeline(
                                    group.config.usesDistancePixels()
                                            ? (batch.material.cull
                                                    ? SOURCE_DISTANCE_CULLED
                                                    : SOURCE_DISTANCE)
                                            : (batch.material.cull ? SOURCE_CULLED : SOURCE)));
                    var atlas = client.getTextureManager().getTexture(batch.material.atlas);
                    pass.setUniform(
                            "AtlasSampler",
                            atlas.getTextureView(),
                            RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
                    pass.setUniform(
                            "MaskSampler",
                            maskTexture(batch.material.mask).getTextureView(),
                            RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
                    pass.setVertexBuffer(0, batch.gpu.slice(0, (long) batch.vertices * STRIDE));
                    pass.drawIndexed(HeldItemIndices.indexCount(batch.vertices), 1, 0, 0, 0);
                }
        }
    }

    private DynamicTexture maskTexture(TextureMasks.Mask mask) {
        if (mask == null) {
            if (white == null) {
                var pixels = new NativeImage(1, 1, false);
                pixels.setPixel(0, 0, -1);
                white = new DynamicTexture(() -> "Still Bloom white mask", pixels);
            }
            return white;
        }
        return masks.computeIfAbsent(
                mask,
                value -> {
                    var pixels = new NativeImage(value.width, value.height, false);
                    for (int y = 0; y < value.height; y++)
                        for (int x = 0; x < value.width; x++)
                            pixels.setPixel(x, y, value.get(x, y) ? -1 : 0xFF000000);
                    return new DynamicTexture(() -> "Still Bloom held mask", pixels);
                });
    }

    private void finish(
            Minecraft client, RenderTarget main, GraphicsResourceAllocator allocator, Stage stage) {
        if (!enabled() || failed) return;
        try {
            for (var group : stage.groups.values())
                if (group.active && group.emission != null) {
                    int w = group.rect.width(), h = group.rect.height();
                    blit.copy(main, group.scene, 0, 0, w, h, group.rect.x(), group.rect.y());
                    if (group.chain == null) group.initialize(client);
                    FrameGraphBuilder graph = new FrameGraphBuilder();
                    Map<Identifier, ResourceHandle<RenderTarget>> targets = new HashMap<>();
                    targets.put(
                            PostChain.MAIN_TARGET_ID,
                            graph.importExternal("held scene", group.scene));
                    targets.put(
                            HeldItemGraph.EMISSION,
                            graph.importExternal("held emission", group.emission));
                    targets.put(
                            HeldItemGraph.COVERAGE,
                            graph.importExternal("held coverage", group.coverage));
                    group.distanceTargets.importInto(graph, targets);
                    group.chain.addToFrame(
                            graph,
                            group.width,
                            group.height,
                            new PostChain.TargetBundle() {
                                public ResourceHandle<RenderTarget> get(Identifier id) {
                                    return targets.get(id);
                                }

                                public void replace(
                                        Identifier id, ResourceHandle<RenderTarget> handle) {
                                    targets.put(id, handle);
                                }
                            });
                    frameAllocator.execute(graph, allocator);
                    blit.copy(
                            group.scene,
                            main,
                            group.rect.x(),
                            group.rect.y(),
                            w,
                            h,
                            -group.rect.x(),
                            -group.rect.y());
                    stage.remember(group, frameTime);
                }
            stage.retire(frame, frameTime);
            if (frame % 120 == 0) retireMasks();
        } catch (Exception e) {
            fail(e);
        }
    }

    private void retireMasks() {
        var retained = Collections.newSetFromMap(new IdentityHashMap<TextureMasks.Mask, Boolean>());
        for (var stage : List.of(world, hands))
            for (var group : stage.groups.values())
                for (var batch : group.batches.values())
                    if (batch.material.mask != null) retained.add(batch.material.mask);
        var iterator = masks.entrySet().iterator();
        while (iterator.hasNext()) {
            var e = iterator.next();
            if (!retained.contains(e.getKey())) {
                e.getValue().close();
                painted.remove(e.getKey());
                iterator.remove();
            }
        }
    }

    private void fail(Exception e) {
        failed = true;
        closeResources();
        StillBloomClient.LOGGER.error(
                "Held-item bloom disabled after a rendering failure. Press F10 to retry; placed-block bloom is unchanged.",
                e);
    }

    private static float smoothstep(float start, float end, float value) {
        float t = Math.clamp((value - start) / (end - start), 0, 1);
        return t * t * (3 - 2 * t);
    }

    private record Material(Identifier atlas, TextureMasks.Mask mask, boolean cull) {}

    private static final class Quad {
        final BakedQuad quad;
        final Identifier atlas;
        final String texture;
        final boolean cull;
        final float[] uv = new float[16];

        Quad(BakedQuad q) {
            quad = q;
            cull = q.materialInfo().itemRenderType().pipeline().isCull();
            var sprite = q.materialInfo().sprite();
            atlas = sprite.atlasLocation();
            texture = sprite.contents().name().toString();
            for (int i = 0; i < 4; i++) {
                float u = UVPair.unpackU(q.packedUV(i)), v = UVPair.unpackV(q.packedUV(i));
                uv[i * 4] = u;
                uv[i * 4 + 1] = v;
                uv[i * 4 + 2] = (u - sprite.getU0()) / (sprite.getU1() - sprite.getU0());
                uv[i * 4 + 3] = (v - sprite.getV0()) / (sprite.getV1() - sprite.getV0());
            }
        }
    }

    private static final class Geometry {
        final Quad[] quads;
        final float[] clips;

        Geometry(List<BakedQuad> list) {
            quads = list.stream().map(Quad::new).toArray(Quad[]::new);
            clips = new float[quads.length * 16];
        }
    }

    private static final class Batch implements AutoCloseable {
        final Material material;
        ByteBuffer data = ByteBuffer.allocateDirect(STRIDE * 4 * 32).order(ByteOrder.nativeOrder());
        GpuBuffer gpu;
        int vertices;
        long lastUsed;

        Batch(Material material) {
            this.material = material;
        }

        void reset() {
            vertices = 0;
            data.clear();
        }

        void quad(
                float[] clip,
                int offset,
                float[] uv,
                int tint,
                Integer color,
                float strength,
                float detail,
                float fade,
                float near) {
            if (data.remaining() < STRIDE * 4) {
                var grown =
                        ByteBuffer.allocateDirect(data.capacity() * 2)
                                .order(ByteOrder.nativeOrder());
                data.flip();
                grown.put(data);
                data = grown;
            }
            for (int index = 0; index < 4; index++) {
                for (int j = 0; j < 4; j++) data.putFloat(clip[offset + index * 4 + j]);
                for (int j = 0; j < 4; j++) data.putFloat(uv[index * 4 + j]);
                putColor(data, tint, true);
                putColor(data, color == null ? 0 : color, color != null);
                data.putFloat(strength).putFloat(detail).putFloat(fade).putFloat(near);
                vertices++;
            }
        }

        static void putColor(ByteBuffer target, int rgb, boolean active) {
            target.putFloat((rgb >> 16 & 255) / 255f)
                    .putFloat((rgb >> 8 & 255) / 255f)
                    .putFloat((rgb & 255) / 255f)
                    .putFloat(active ? 1 : 0);
        }

        void upload(CommandEncoder encoder) {
            if (gpu == null || gpu.size() < data.capacity()) {
                if (gpu != null) gpu.close();
                gpu =
                        RenderSystem.getDevice()
                                .createBuffer(
                                        () -> "Still Bloom held vertices",
                                        GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST,
                                        data.capacity());
            }
            data.flip();
            encoder.writeToBuffer(gpu.slice(0, data.remaining()), data);
        }

        public void close() {
            if (gpu != null) gpu.close();
            gpu = null;
        }
    }

    private static final class Stage {
        final Map<BloomStyle, Group> groups = new TreeMap<>();
        final Map<BloomStyle, BloomConfig> configs = new HashMap<>();
        private Group recent, previous;

        void remember(Group group, long now) {
            group.lastDraw = now;
            if (group != recent) {
                previous = recent;
                recent = group;
            }
        }

        void retire(long frame, long now) {
            var iterator = groups.entrySet().iterator();
            while (iterator.hasNext()) {
                var entry = iterator.next();
                var group = entry.getValue();
                if (!group.active && group.lastUsed == frame - 1)
                    group.fitsWarmBudget = group.retainedBytes() <= WARM_GROUP_BYTES;
                boolean warm =
                        !group.active
                                && (group == recent || group == previous)
                                && group.fitsWarmBudget
                                && now - group.lastDraw < WARM_NANOS;
                if (warm) continue;
                if (!group.active
                        && (frame - group.lastUsed > 120 || now - group.lastDraw >= WARM_NANOS)) {
                    group.close();
                    configs.remove(entry.getKey());
                    iterator.remove();
                    if (previous == group) previous = null;
                    if (recent == group) recent = null;
                    continue;
                }
                var batches = group.batches.values().iterator();
                while (batches.hasNext()) {
                    var batch = batches.next();
                    if (frame - batch.lastUsed > 120) {
                        batch.close();
                        batches.remove();
                    }
                }
            }
        }

        void reset() {
            for (var group : groups.values()) {
                if (!group.active) group.shrink.reset();
                group.active = false;
                group.rect = null;
                for (var batch : group.batches.values()) batch.reset();
            }
        }

        void close() {
            groups.values().forEach(Group::close);
            groups.clear();
            configs.clear();
            recent = null;
            previous = null;
        }
    }

    private static final class Group implements AutoCloseable {
        final BloomConfig config;
        final Map<Material, Batch> batches = new LinkedHashMap<>();
        final DistanceTargets distanceTargets = new DistanceTargets();
        final HeldItemBounds.ShrinkWindow shrink = new HeldItemBounds.ShrinkWindow();
        HeldItemBounds.Rect rect;
        boolean active, fitsWarmBudget;
        long lastUsed, lastDraw, chainBytes;
        int width, height;
        TextureTarget emission, coverage, scene;
        PostChain chain;
        ProjectionMatrixBuffer projection;

        Group(BloomConfig config) {
            this.config = config;
        }

        void include(HeldItemBounds.Rect value) {
            if (rect == null) rect = value;
            else {
                int x = Math.min(rect.x(), value.x()), y = Math.min(rect.y(), value.y());
                rect =
                        new HeldItemBounds.Rect(
                                x,
                                y,
                                Math.max(rect.x() + rect.width(), value.x() + value.width()) - x,
                                Math.max(rect.y() + rect.height(), value.y() + value.height()) - y);
            }
            active = true;
        }

        long retainedBytes() {
            long total =
                    (long) width * height * (config.usesDistancePixels() ? 20 : 16) + chainBytes;
            for (var batch : batches.values()) {
                total += 2L * batch.data.capacity();
                var mask = batch.material.mask;
                if (mask != null) total += 8L * mask.width * mask.height;
            }
            return total;
        }

        void ensure(int mainWidth, int mainHeight, long now) {
            int w = HeldItemBounds.allocationSize(rect.width(), config),
                    h = HeldItemBounds.allocationSize(rect.height(), config);
            boolean fits = emission != null && width >= w && height >= h;
            if (fits) {
                if (!shrink.ready(w, h, width, height, now)) return;
                w = HeldItemBounds.shrunkExtent(width, shrink.peakWidth, config);
                h = HeldItemBounds.shrunkExtent(height, shrink.peakHeight, config);
            } else {
                w = HeldItemBounds.grownExtent(width, rect.width(), mainWidth, config);
                h = HeldItemBounds.grownExtent(height, rect.height(), mainHeight, config);
            }
            shrink.reset();
            if (fits && width == w && height == h) return;
            closeTargets();
            width = w;
            height = h;
            emission =
                    new TextureTarget(
                            "Still Bloom held emission",
                            w,
                            h,
                            config.usesDistancePixels()
                                    ? GpuFormat.RGBA16_FLOAT
                                    : GpuFormat.RGBA8_UNORM,
                            GpuFormat.D32_FLOAT);
            coverage =
                    new TextureTarget(
                            "Still Bloom held coverage", w, h, GpuFormat.RGBA8_UNORM, null);
            scene = new TextureTarget("Still Bloom held scene", w, h, GpuFormat.RGBA8_UNORM, null);
        }

        void initialize(Minecraft client) throws Exception {
            var ortho = new Projection();
            ortho.setupOrtho(0, 1, width, height, true);
            projection = new ProjectionMatrixBuffer("Still Bloom held projection");
            Set<Identifier> external =
                    new HashSet<>(
                            Set.of(
                                    PostChain.MAIN_TARGET_ID,
                                    HeldItemGraph.EMISSION,
                                    HeldItemGraph.COVERAGE));
            var graph = HeldItemGraph.create(width, height, config);
            chainBytes = graph.passes().size() * 16384L;
            if (config.usesDistancePixels()) graph = distanceTargets.initialize(graph, external);
            chain =
                    PostChain.load(
                            graph,
                            client.getTextureManager(),
                            external,
                            BloomGraph.id("held"),
                            ortho,
                            projection);
        }

        void closeTargets() {
            if (chain != null) chain.close();
            chain = null;
            if (projection != null) projection.close();
            projection = null;
            distanceTargets.close();
            if (emission != null) emission.destroyBuffers();
            emission = null;
            if (coverage != null) coverage.destroyBuffers();
            coverage = null;
            if (scene != null) scene.destroyBuffers();
            scene = null;
        }

        public void close() {
            closeTargets();
            batches.values().forEach(Batch::close);
            batches.clear();
        }
    }

    private void closeResources() {
        frameAllocator.close();
        world.close();
        hands.close();
        blit.close();
        indices.close();
        geometry.clear();
        appearances.clear();
        painted.clear();
        masks.values().forEach(DynamicTexture::close);
        masks.clear();
        if (white != null) white.close();
        white = null;
        if (frameUniform != null) frameUniform.close();
        frameUniform = null;
    }

    @Override
    public void close() {
        closeResources();
        HeldItemCapture.clear();
        modelSet = null;
        maskOwner = null;
        dirty = true;
        failed = false;
    }
}
