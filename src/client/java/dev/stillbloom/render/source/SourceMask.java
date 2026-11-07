package dev.stillbloom.render.source;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;

import dev.stillbloom.StillBloomClient;
import dev.stillbloom.config.BloomRange;
import dev.stillbloom.config.BloomStyle;
import dev.stillbloom.mask.TextureMasks;
import dev.stillbloom.render.BloomGraph;
import dev.stillbloom.render.CameraUniforms;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;

// I rescan changed sections and upload changed volumes, reusing everything else.
public final class SourceMask implements AutoCloseable {
    public static final int SIZE = BloomRange.VOLUME_SIZE,
            WORDS = SIZE * SIZE * SIZE / 32,
            TEXTURE_SIZE = 256,
            BUFFER_BYTES = 80;
    public static final Identifier TEXTURE_ID =
            Identifier.fromNamespaceAndPath("stillbloom", "source_mask");
    private static final Identifier REGISTERED_TEXTURE_ID =
            TEXTURE_ID.withPath(p -> "textures/effect/" + p + ".png");

    private record Snapshot(
            ClientLevel level,
            int x,
            int y,
            int z,
            int[] words,
            int[] custom,
            boolean any,
            List<SourceBounds> bounds,
            List<SourceGroup> groups,
            int[] colors,
            int[] details) {}

    record SourceGroup(
            BloomStyle style,
            int[] words,
            List<SourceBounds> bounds,
            List<SourceBounds> extractionBounds,
            boolean custom,
            boolean colored) {}

    public record RenderGroup(int index, BloomStyle style, boolean custom, boolean colored) {
        // I keep the safe mask/color paths when source metadata isn't available.
        public RenderGroup(int index, BloomStyle style) {
            this(index, style, true, true);
        }

        public RenderGroup(int index, BloomStyle style, boolean custom) {
            this(index, style, custom, true);
        }
    }

    private static final Snapshot EMPTY =
            new Snapshot(
                    null, 0, 0, 0, new int[WORDS], null, false, List.of(), List.of(), null, null);
    public static final Identifier COLORS_ID = BloomGraph.id("source_colors");
    private final MaskTexture colorTexture = new MaskTexture("source_colors", 1024, 2048);
    private int[] uploadedColors;
    private List<Long> previousColors = List.of();
    public static final Identifier DETAILS_ID = BloomGraph.id("source_details");
    private final MaskTexture detailTexture = new MaskTexture("source_details", 1024, 2048);
    private int[] uploadedDetails;
    private List<Long> previousDetails = List.of();
    private final List<MaskTexture> groupTextures = new ArrayList<>();
    private final List<int[]> uploadedGroups = new ArrayList<>();
    private final SourceVolumePool retiredVolumes = new SourceVolumePool();
    private long groupTextureRevision;
    private List<RenderGroup> visibleGroups = List.of();
    private List<?> scannedStyle;
    public static final Identifier ACTIVE_TEXTURE_ID = BloomGraph.id("active_sources");
    private final MaskTexture activeTexture = new MaskTexture("active_sources", TEXTURE_SIZE);
    // I keep only a snapshot token for activity checks, not its large arrays.
    private Object activitySnapshotRevision;
    private Object snapshotRevision = EMPTY;
    private SourceVolumePool.PackedVolume.Data previousCustom;
    private List<RenderGroup> activityGroups = List.of();
    private int[] activityWords;

    private record SectionSources(int[] positions, BlockState[] states) {}

    private record CachedSection(long revision, SectionSources sources) {}

    private record LocatedSources(int x, int y, int z, SectionSources sources) {}

    private final Map<LevelChunkSection, CachedSection> sections = new WeakHashMap<>();
    // I never publish scratch storage where a snapshot could still be reading it.
    private final int[] scannedPositions = new int[4096];
    private final BlockState[] scannedStates = new BlockState[4096];
    private long sourceScanRevision;
    private List<LocatedSources> previousSections = List.of();
    private TextureMasks maskOwner;
    private long maskRevision = -1, maskGeometryRevision = -1;
    private volatile Snapshot snapshot = EMPTY;
    // I track world edits too, since they can change occlusion without changing the lights.
    private Object historyRevision = EMPTY;
    private Snapshot uploaded;
    private int[] uploadedCustom;
    public final TextureGeometry geometry = new TextureGeometry();
    private final Set<net.minecraft.world.level.block.Block> customBlocks = new HashSet<>();
    private long lastTick = Long.MIN_VALUE;
    private float scannedRange = -1;
    private DynamicTexture texture;
    private final Matrix4f inverse = new Matrix4f();
    private final Matrix4f forward = new Matrix4f(), captured = new Matrix4f();
    private final org.joml.FrustumIntersection frustum = new org.joml.FrustumIntersection();
    private long viewRevision, checkedRevision = -1;
    private Object observed;
    private double viewX = Double.NaN, viewY, viewZ;
    private float checkedRange = -1;
    private boolean visible;
    private final ByteBuffer upload =
            ByteBuffer.allocateDirect(BUFFER_BYTES).order(ByteOrder.nativeOrder());

    public void captureProjection(Matrix4fc projection, Matrix4fc rotation) {
        captured.set(projection).mul(rotation);
        if (!forward.equals(captured)) {
            forward.set(captured);
            frustum.set(forward);
            inverse.set(forward).invert();
            viewRevision++;
        }
    }

    public boolean hasCustomSources() {
        return snapshot.custom != null;
    }

    public boolean hasSources() {
        return snapshot.any;
    }

    public boolean hasColors() {
        return snapshot.colors != null;
    }

    public boolean hasDetails() {
        return snapshot.details != null;
    }

    public Object historyRevision() {
        return historyRevision;
    }

    public void copyView(Matrix4f into) {
        into.set(forward);
    }

    public void ensureColors() {
        if (snapshot.colors != null) colorTexture.ensure();
    }

    public void ensureDetails() {
        if (snapshot.details != null) detailTexture.ensure();
    }

    static int packColor(int rgb) {
        return 0xFF000000 | ((rgb & 255) << 16) | (rgb & 0xFF00) | ((rgb >>> 16) & 255);
    }

    // I use the sign bit to distinguish an explicit zero from an inherited value.
    static int packDetail(Float detail, float inherited) {
        return detail == null || detail == inherited
                ? 0
                : Float.floatToRawIntBits(detail) | 0x80000000;
    }

    public long visibilityRevision() {
        return viewRevision;
    }

    public List<RenderGroup> visibleGroups() {
        return visibleGroups;
    }

    public long groupTextureRevision() {
        return groupTextureRevision;
    }

    public List<SourceBounds> groupBounds(int index) {
        return index < 0 || index >= snapshot.groups.size()
                ? snapshot.bounds
                : snapshot.groups.get(index).extractionBounds;
    }

    public List<SourceBounds> coarseGroupBounds(int index) {
        return index < 0 || index >= snapshot.groups.size()
                ? snapshot.bounds
                : snapshot.groups.get(index).bounds;
    }

    public BloomStyle groupStyle(int index) {
        return index >= 0 && index < snapshot.groups.size()
                ? snapshot.groups.get(index).style
                : null;
    }

    public static Identifier groupTexture(int index) {
        return BloomGraph.id("source_group_" + index);
    }

    public boolean hasVisibleSources(Minecraft client) {
        // I check the level again because a dimension packet can replace it between the last tick
        // and draw.
        if (client.level == null || snapshot.level != client.level) {
            visible = false;
            visibleGroups = List.of();
            checkedRevision = -1;
            return false;
        }
        var camera = client.gameRenderer.gameRenderState().levelRenderState.cameraRenderState.pos;
        if (camera.x != viewX
                || camera.y != viewY
                || camera.z != viewZ
                || observed != historyRevision) {
            viewX = camera.x;
            viewY = camera.y;
            viewZ = camera.z;
            observed = historyRevision;
            viewRevision++;
        }
        float range = StillBloomClient.config.bloomRange;
        if (checkedRevision != viewRevision || checkedRange != range) {
            checkedRevision = viewRevision;
            checkedRange = range;
            visible = false;
            List<RenderGroup> groups = new ArrayList<>();
            if (snapshot.level == client.level)
                for (int i = 0; i < snapshot.groups.size(); i++) {
                    var group = snapshot.groups.get(i);
                    for (var bound : group.bounds)
                        if (bound.visible(frustum, viewX, viewY, viewZ, range)) {
                            groups.add(
                                    new RenderGroup(i, group.style, group.custom, group.colored));
                            break;
                        }
                }
            visibleGroups = List.copyOf(groups);
            visible = !groups.isEmpty();
        }
        return visible;
    }

    public void refresh(Minecraft client) {
        if (client.level == null) {
            snapshot = EMPTY;
            snapshotRevision = EMPTY;
            historyRevision = EMPTY;
            previousCustom = null;
            retiredVolumes.clear();
            sections.clear();
            previousSections = List.of();
            lastTick = Long.MIN_VALUE;
            return;
        }
        if (!StillBloomClient.config.enabled) return;
        var camera = client.gameRenderer.mainCamera().position();
        int ox = BloomRange.origin(camera.x),
                oy = BloomRange.origin(camera.y),
                oz = BloomRange.origin(camera.z);
        Snapshot old = snapshot;
        boolean moved = old.level != client.level || old.x != ox || old.y != oy || old.z != oz;
        if (old.level != client.level) geometry.invalidate();
        if (old.level != client.level) {
            retiredVolumes.clear();
            sections.clear();
            previousSections = List.of();
        }
        boolean masksChanged =
                maskOwner != StillBloomClient.MASKS
                        || maskRevision != StillBloomClient.MASKS.revision();
        boolean paintChanged =
                maskOwner != StillBloomClient.MASKS
                        || maskGeometryRevision != StillBloomClient.MASKS.geometryRevision();
        if (masksChanged) {
            maskOwner = StillBloomClient.MASKS;
            maskRevision = maskOwner.revision();
        }
        if (paintChanged) {
            maskGeometryRevision = maskOwner.geometryRevision();
            customBlocks.clear();
            sections.clear();
            retiredVolumes.clear();
            maskOwner
                    .profiles()
                    .forEach(
                            (id, p) -> {
                                var key = Identifier.tryParse(id);
                                if (key != null && p.hasEmission())
                                    net.minecraft.core.registries.BuiltInRegistries.BLOCK
                                            .getOptional(key)
                                            .ifPresent(customBlocks::add);
                            });
        }
        boolean geometryChanged = geometry.prepare();
        if (geometryChanged) retiredVolumes.clear();
        boolean carriedGeometry = moved && !geometryChanged;
        if (carriedGeometry) geometry.retainVolume(ox, oy, oz);
        var config = StillBloomClient.config;
        var globalStyle =
                List.of(
                        config.bloomShape,
                        config.intensity,
                        config.glowWidth,
                        config.flareStyle,
                        config.flareStrength,
                        config.flareLength,
                        config.preserveDetail);
        boolean styleChanged = !globalStyle.equals(scannedStyle);
        long now = client.level.getGameTime();
        float range = StillBloomClient.config.bloomRange;
        if (!moved
                && !masksChanged
                && !geometryChanged
                && !styleChanged
                && scannedRange == range
                && now >= lastTick
                && now - lastTick < 2) return;
        List<LocatedSources> current = new ArrayList<>();
        long previousScanRevision = sourceScanRevision;
        double radiusSquared = (range + 8) * (range + 8);
        for (int cx = Math.floorDiv(ox, 16); cx <= Math.floorDiv(ox + SIZE - 1, 16); cx++) {
            double dx = BloomRange.distanceToInterval(camera.x, cx * 16, cx * 16 + 16);
            for (int cz = Math.floorDiv(oz, 16); cz <= Math.floorDiv(oz + SIZE - 1, 16); cz++) {
                double dz = BloomRange.distanceToInterval(camera.z, cz * 16, cz * 16 + 16);
                if (dx * dx + dz * dz > radiusSquared) continue;
                var chunk = client.level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false);
                if (chunk == null) continue;
                int min = Math.max(chunk.getMinSectionY(), Math.floorDiv(oy, 16)),
                        max = Math.min(chunk.getMaxSectionY(), Math.floorDiv(oy + SIZE - 1, 16));
                for (int sy = min; sy <= max; sy++) {
                    double dy = BloomRange.distanceToInterval(camera.y, sy * 16, sy * 16 + 16);
                    if (dx * dx + dy * dy + dz * dz > radiusSquared) continue;
                    var section = chunk.getSection(chunk.getSectionIndexFromSectionY(sy));
                    var sources = sources(section);
                    if (sources.positions.length > 0)
                        current.add(new LocatedSources(cx * 16, sy * 16, cz * 16, sources));
                }
            }
        }
        lastTick = now;
        scannedRange = range;
        if (!moved
                && !masksChanged
                && !geometryChanged
                && !styleChanged
                && current.equals(previousSections)) {
            // I wake bloom and reset history after source-section edits while keeping unchanged
            // upload caches.
            if (sourceScanRevision != previousScanRevision) historyRevision = new Object();
            return;
        }
        scannedStyle = globalStyle;
        previousSections = current;
        int[] bits = new int[WORDS];
        var customBuilder =
                new SourceVolumePool.PackedVolume(
                        () -> acquireVolume(TextureGeometry.SIZE * TextureGeometry.SIZE));
        List<Long> colorSources = new ArrayList<>();
        List<Long> detailSources = new ArrayList<>();
        boolean any = false;
        List<SourceBounds> bounds = new ArrayList<>();
        Map<BloomStyle, int[]> groupBits = new TreeMap<>();
        Map<BloomStyle, List<SourceBounds>> groupBounds = new TreeMap<>();
        Map<BloomStyle, List<SourceBounds>> extractionBounds = new TreeMap<>();
        Set<BloomStyle> customStyles = new HashSet<>(), coloredStyles = new HashSet<>();
        Map<net.minecraft.world.level.block.Block, BloomStyle> styles = new HashMap<>();
        Map<net.minecraft.world.level.block.Block, Integer> blockColors = new HashMap<>();
        Map<net.minecraft.world.level.block.Block, Integer> blockDetails = new HashMap<>();
        var pos = new BlockPos.MutableBlockPos();
        for (var located : current) {
            var sources = located.sources;
            int minX = Integer.MAX_VALUE,
                    minY = minX,
                    minZ = minX,
                    maxX = Integer.MIN_VALUE,
                    maxY = maxX,
                    maxZ = maxX;
            Map<BloomStyle, SourceBounds.Clusters> sectionBounds = new HashMap<>();
            for (int i = 0; i < sources.positions.length; i++) {
                int local = sources.positions[i];
                int x = located.x + (local & 15),
                        z = located.z + ((local >> 4) & 15),
                        y = located.y + (local >> 8);
                int vx = x - ox, vy = y - oy, vz = z - oz;
                if (vx < 0 || vy < 0 || vz < 0 || vx >= SIZE || vy >= SIZE || vz >= SIZE) continue;
                minX = Math.min(minX, x);
                minY = Math.min(minY, y);
                minZ = Math.min(minZ, z);
                maxX = Math.max(maxX, x + 1);
                maxY = Math.max(maxY, y + 1);
                maxZ = Math.max(maxZ, z + 1);
                int index = BloomRange.index(vx, vy, vz);
                bits[index >>> 5] |= 1 << (index & 31);
                any = true;
                var state = sources.states[i];
                BloomStyle style =
                        styles.computeIfAbsent(
                                state.getBlock(),
                                block ->
                                        maskOwner.style(
                                                net.minecraft.core.registries.BuiltInRegistries
                                                        .BLOCK
                                                        .getKey(block)
                                                        .toString(),
                                                StillBloomClient.config));
                if (style.intensity() > 0) {
                    // I classify each snapshot once without splitting styles or reviving erased and
                    // unsupported masks.
                    if (customBlocks.contains(state.getBlock())) customStyles.add(style);
                    int color =
                            blockColors.computeIfAbsent(
                                    state.getBlock(),
                                    block -> {
                                        var p =
                                                maskOwner.profile(
                                                        net.minecraft.core.registries
                                                                .BuiltInRegistries.BLOCK
                                                                .getKey(block)
                                                                .toString());
                                        return p != null
                                                        && p.override != null
                                                        && p.override.color != null
                                                ? packColor(p.override.color)
                                                : 0;
                                    });
                    if (color != 0) {
                        colorSources.add(((long) index << 32) | (color & 0xFFFFFFFFL));
                        coloredStyles.add(style);
                    }
                    int detail =
                            blockDetails.computeIfAbsent(
                                    state.getBlock(),
                                    block -> {
                                        var p =
                                                maskOwner.profile(
                                                        net.minecraft.core.registries
                                                                .BuiltInRegistries.BLOCK
                                                                .getKey(block)
                                                                .toString());
                                        return packDetail(
                                                p != null && p.override != null
                                                        ? p.override.preserveDetail
                                                        : null,
                                                config.preserveDetail);
                                    });
                    if (detail != 0)
                        detailSources.add(((long) index << 32) | (detail & 0xFFFFFFFFL));
                    groupBits.computeIfAbsent(style, k -> new int[WORDS])[index >>> 5] |=
                            1 << (index & 31);
                    sectionBounds
                            .computeIfAbsent(
                                    style,
                                    k -> new SourceBounds.Clusters(located.x, located.y, located.z))
                            .add(x, y, z);
                }
                if (customBlocks.contains(state.getBlock())) {
                    customBuilder.add(index, geometry.model(state, pos.set(x, y, z)));
                }
            }
            if (minX != Integer.MAX_VALUE)
                bounds.add(new SourceBounds(minX, minY, minZ, maxX, maxY, maxZ));
            sectionBounds.forEach(
                    (style, b) -> {
                        // I keep full-section visibility for history and style changes; only
                        // drawing uses smaller clusters.
                        groupBounds.computeIfAbsent(style, k -> new ArrayList<>()).add(b.coarse());
                        extractionBounds
                                .computeIfAbsent(style, k -> new ArrayList<>())
                                .addAll(b.extraction());
                    });
        }
        if (carriedGeometry && geometry.compactVolumeIfNeeded()) {
            // I rebuild in the original source order when the model budget fills, so caching
            // doesn't hide models.
            customBuilder.reset();
            for (var located : current)
                for (int i = 0; i < located.sources.positions.length; i++) {
                    int local = located.sources.positions[i];
                    int x = located.x + (local & 15),
                            z = located.z + ((local >> 4) & 15),
                            y = located.y + (local >> 8);
                    int vx = x - ox, vy = y - oy, vz = z - oz;
                    if (vx < 0 || vy < 0 || vz < 0 || vx >= SIZE || vy >= SIZE || vz >= SIZE)
                        continue;
                    var state = located.sources.states[i];
                    if (customBlocks.contains(state.getBlock())) {
                        int index = BloomRange.index(vx, vy, vz);
                        customBuilder.add(index, geometry.model(state, pos.set(x, y, z)));
                    }
                }
        }
        var oldCustom = previousCustom;
        previousCustom = customBuilder.finish(old.level == client.level ? oldCustom : null);
        int[] custom = previousCustom.words();
        List<SourceGroup> groups = new ArrayList<>();
        groupBits.forEach(
                (style, words) ->
                        groups.add(
                                new SourceGroup(
                                        style,
                                        words,
                                        List.copyOf(groupBounds.get(style)),
                                        List.copyOf(extractionBounds.get(style)),
                                        customStyles.contains(style),
                                        coloredStyles.contains(style))));
        // I avoid rebuilding the 8 MiB color volume for unrelated section changes.
        var oldColorSources = previousColors;
        var oldDetailSources = previousDetails;
        int[] colors = old.colors;
        if ((old.colors == null && !colorSources.isEmpty())
                || !colorSources.equals(previousColors)) {
            colors = colorSources.isEmpty() ? null : acquireVolume(SIZE * SIZE * SIZE);
            for (long entry : colorSources) colors[(int) (entry >>> 32)] = (int) entry;
            previousColors = List.copyOf(colorSources);
        }
        // I keep unchanged detail data and skip allocating a volume when detail inherits.
        int[] details = old.details;
        if ((old.details == null && !detailSources.isEmpty())
                || !detailSources.equals(previousDetails)) {
            details = detailSources.isEmpty() ? null : acquireVolume(SIZE * SIZE * SIZE);
            for (long entry : detailSources) details[(int) (entry >>> 32)] = (int) entry;
            previousDetails = List.copyOf(detailSources);
        }
        snapshot =
                new Snapshot(
                        client.level,
                        ox,
                        oy,
                        oz,
                        bits,
                        custom,
                        any,
                        List.copyOf(bounds),
                        List.copyOf(groups),
                        colors,
                        details);
        snapshotRevision = new Object();
        // I give history only an identity token so it doesn't retain the large source arrays.
        historyRevision = new Object();
        if (old.level == client.level && !paintChanged && !geometryChanged) {
            if (old.custom != custom) retiredVolumes.retirePacked(oldCustom);
            if (old.colors != colors) retiredVolumes.retire(old.colors, oldColorSources);
            if (old.details != details) retiredVolumes.retire(old.details, oldDetailSources);
        }
    }

    private int[] acquireVolume(int length) {
        // I keep old upload inputs alive until comparisons finish; refresh and drawing share the
        // client thread.
        Snapshot current = snapshot, drawn = uploaded;
        return retiredVolumes.acquire(
                length,
                current.custom,
                current.colors,
                current.details,
                drawn == null ? null : drawn.custom,
                drawn == null ? null : drawn.colors,
                drawn == null ? null : drawn.details,
                uploadedCustom,
                uploadedColors,
                uploadedDetails);
    }

    private SectionSources sources(LevelChunkSection section) {
        long version = ((SectionRevision) section).stillbloom$revision();
        var cached = sections.get(section);
        if (cached != null && cached.revision == version) return cached.sources;
        int count = 0;
        if (section.maybeHas(this::isSource)) {
            for (int y = 0; y < 16; y++)
                for (int z = 0; z < 16; z++)
                    for (int x = 0; x < 16; x++) {
                        var state = section.getBlockState(x, y, z);
                        if (isSource(state)) {
                            scannedPositions[count] = x + (z << 4) + (y << 8);
                            scannedStates[count++] = state;
                        }
                    }
        }
        var previous = cached == null ? null : cached.sources;
        SectionSources sources;
        if (previous != null
                && previous.positions.length == count
                && Arrays.equals(previous.positions, 0, count, scannedPositions, 0, count)
                && Arrays.equals(previous.states, 0, count, scannedStates, 0, count))
            sources = previous;
        else
            sources =
                    new SectionSources(
                            Arrays.copyOf(scannedPositions, count),
                            Arrays.copyOf(scannedStates, count));
        Arrays.fill(scannedStates, 0, count, null);
        if (count > 0) sourceScanRevision++;
        sections.put(section, new CachedSection(version, sources));
        return sources;
    }

    private boolean isSource(BlockState state) {
        return customBlocks.contains(state.getBlock())
                && state.getRenderShape() == net.minecraft.world.level.block.RenderShape.MODEL;
    }

    public void ensureTexture(Minecraft client) {
        if (texture == null) {
            texture =
                    new DynamicTexture(
                            () -> "Still Bloom source volume",
                            new NativeImage(TEXTURE_SIZE, TEXTURE_SIZE, true));
            client.getTextureManager().register(REGISTERED_TEXTURE_ID, texture);
            uploaded = null;
        }
    }

    public void ensureGroupTextures() {
        int previousCount = groupTextures.size();
        while (groupTextures.size() > snapshot.groups.size()) {
            groupTextures.removeLast().close();
            uploadedGroups.removeLast();
        }
        while (groupTextures.size() < snapshot.groups.size()) {
            groupTextures.add(
                    new MaskTexture("source_group_" + groupTextures.size(), TEXTURE_SIZE));
            uploadedGroups.add(null);
        }
        if (previousCount != groupTextures.size()) groupTextureRevision++;
        for (int i = 0; i < groupTextures.size(); i++) {
            int[] words = snapshot.groups.get(i).words;
            if (uploadedGroups.get(i) != words) {
                if (!Arrays.equals(uploadedGroups.get(i), words))
                    groupTextures.get(i).upload(words);
                uploadedGroups.set(i, words);
            }
        }
    }

    public void ensureActivityTexture() {
        if (activitySnapshotRevision == snapshotRevision && activityGroups.equals(visibleGroups))
            return;
        int[] words = new int[WORDS];
        for (var group : visibleGroups) {
            var bits = snapshot.groups.get(group.index).words;
            for (int i = 0; i < WORDS; i++) words[i] |= bits[i];
        }
        if (!Arrays.equals(activityWords, words)) {
            activeTexture.upload(words);
            activityWords = words;
        }
        activitySnapshotRevision = snapshotRevision;
        activityGroups = visibleGroups;
    }

    public void upload(Minecraft client, List<GpuBuffer> buffers) {
        upload(client, buffers, null);
    }

    public void upload(Minecraft client, List<GpuBuffer> buffers, CameraUniforms cameraCache) {
        ensureTexture(client);
        Snapshot s = snapshot;
        if (s.level != client.level) s = EMPTY;
        if (uploaded != s) {
            if (uploaded == null || !Arrays.equals(uploaded.words, s.words)) {
                texture.getPixels()
                        .getPixelBytes()
                        .order(ByteOrder.LITTLE_ENDIAN)
                        .asIntBuffer()
                        .put(s.words);
                texture.upload();
            }
            if (s.custom != null && uploadedCustom != s.custom) {
                geometry.volume.upload(
                        s.custom,
                        uploadedCustom,
                        MaskTexture.RowUpdates.packedRows(previousCustom));
                uploadedCustom = s.custom;
            }
            if (s.colors != null && uploadedColors != s.colors) {
                colorTexture.upload(
                        s.colors,
                        uploadedColors,
                        MaskTexture.RowUpdates.colorOrDetailRows(previousColors));
                uploadedColors = s.colors;
            }
            if (s.colors == null) {
                colorTexture.close();
                uploadedColors = null;
            }
            if (s.details != null && uploadedDetails != s.details) {
                detailTexture.upload(
                        s.details,
                        uploadedDetails,
                        MaskTexture.RowUpdates.colorOrDetailRows(previousDetails));
                uploadedDetails = s.details;
            }
            if (s.details == null) {
                detailTexture.close();
                uploadedDetails = null;
            }
            uploaded = s;
        }
        if (s.custom != null) geometry.upload();
        var camera = client.gameRenderer.gameRenderState().levelRenderState.cameraRenderState.pos;
        upload.clear();
        inverse.get(0, upload);
        upload.position(64);
        upload.putFloat((float) (camera.x - s.x))
                .putFloat((float) (camera.y - s.y))
                .putFloat((float) (camera.z - s.z));
        upload.putFloat(RenderSystem.getDevice().getDeviceInfo().isZZeroToOne() ? 1 : 0);
        upload.flip();
        if (cameraCache != null && !cameraCache.changed(upload)) return;
        var encoder = RenderSystem.getDevice().createCommandEncoder();
        for (var buffer : buffers) encoder.writeToBuffer(buffer.slice(), upload.duplicate());
    }

    @Override
    public void close() {
        if (texture != null)
            Minecraft.getInstance().getTextureManager().release(REGISTERED_TEXTURE_ID);
        texture = null;
        geometry.close();
        retiredVolumes.clear();
        uploadedCustom = null;
        uploaded = null;
        snapshot = EMPTY;
        snapshotRevision = EMPTY;
        previousCustom = null;
        historyRevision = EMPTY;
        sections.clear();
        previousSections = List.of();
        lastTick = Long.MIN_VALUE;
        groupTextureRevision++;
        groupTextures.forEach(MaskTexture::close);
        groupTextures.clear();
        uploadedGroups.clear();
        visibleGroups = List.of();
        scannedStyle = null;
        activeTexture.close();
        activitySnapshotRevision = null;
        activityGroups = List.of();
        activityWords = null;
        colorTexture.close();
        uploadedColors = null;
        previousColors = List.of();
        detailTexture.close();
        uploadedDetails = null;
        previousDetails = List.of();
    }
}
