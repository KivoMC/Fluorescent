package dev.stillbloom.render.source;

import dev.stillbloom.StillBloomClient;
import dev.stillbloom.config.BloomRange;
import dev.stillbloom.mask.TextureMasks;

import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.*;

// I use baked triangles and UVs so painted masks don't depend on the chunk renderer.
public final class TextureGeometry implements AutoCloseable {
    public static final int SIZE = 1024;
    static final int HEADER_WORDS = 262144, TRI_WORDS = 24, MAX_QUADS = 256;
    final MaskTexture volume = new MaskTexture("custom_volume", SIZE);
    final GeometryTexture data = new GeometryTexture();
    private final int[] table = new int[SIZE * SIZE];
    private int cursor = HEADER_WORDS, nextId = 1;
    // I keep index 65535 empty because it marks unsupported models.
    private int indexCursor = HEADER_WORDS - 4;
    private boolean indicesDisabled;
    private boolean requiredIndices;
    private boolean budgetExceeded;
    private boolean carryingVolume;
    private boolean dirty = true;
    private final GeometryTexture.DirtyRows dirtyRows = new GeometryTexture.DirtyRows();
    private Object models;
    private net.minecraft.world.level.CardinalLighting lighting =
            net.minecraft.world.level.CardinalLighting.DEFAULT;
    private TextureMasks owner;
    private long revision = -1;
    private final Map<Key, Integer> cache = new HashMap<>();
    private final Map<TextureMasks.Mask, Integer> masks = new IdentityHashMap<>();
    private final Map<List<TriangleIndex.Bounds>, Integer> complexIndices = new HashMap<>();
    private final Set<String> warned = new HashSet<>();

    private record PlacedModel(BlockState state, int id) {}

    private final Map<Long, PlacedModel> placed = new HashMap<>();

    private record Key(BlockState state, List<BakedQuad> quads, Vec3 offset) {}

    public void ensure() {
        volume.ensure();
        data.ensure();
    }

    public void invalidate() {
        models = null;
    }

    public boolean prepare() {
        var current = Minecraft.getInstance().getModelManager().getBlockStateModelSet();
        var level = Minecraft.getInstance().level;
        var currentLighting =
                level == null
                        ? net.minecraft.world.level.CardinalLighting.DEFAULT
                        : level.cardinalLighting();
        if (models == current
                && Objects.equals(lighting, currentLighting)
                && owner == StillBloomClient.MASKS
                && revision == StillBloomClient.MASKS.geometryRevision()) return false;
        models = current;
        lighting = currentLighting;
        owner = StillBloomClient.MASKS;
        revision = owner.geometryRevision();
        resetModels();
        return true;
    }

    private void resetModels() {
        cache.clear();
        masks.clear();
        placed.clear();
        complexIndices.clear();
        Arrays.fill(table, 0);
        cursor = HEADER_WORDS;
        nextId = 1;
        indexCursor = HEADER_WORDS - 4;
        indicesDisabled = false;
        requiredIndices = false;
        budgetExceeded = false;
        carryingVolume = false;
        dirty = true;
        dirtyRows.markAll();
    }

    // I keep model-local geometry when the source volume moves and expire only old placements.
    void retainVolume(int x, int y, int z) {
        carryingVolume = true;
        placed.keySet().removeIf(position -> !insideVolume(position, x, y, z));
        // I drop expired rejected models because they have no allocated table data to limit their
        // cache size.
        cache.values().removeIf(id -> id == 65535);
    }

    static boolean insideVolume(long position, int x, int y, int z) {
        int px = BlockPos.getX(position),
                py = BlockPos.getY(position),
                pz = BlockPos.getZ(position);
        return px >= x
                && px < x + BloomRange.VOLUME_SIZE
                && py >= y
                && py < y + BloomRange.VOLUME_SIZE
                && pz >= z
                && pz < z + BloomRange.VOLUME_SIZE;
    }

    // I don't let an old budget hide a model that would fit in a fresh volume.
    boolean compactVolumeIfNeeded() {
        carryingVolume = false;
        if (!budgetExceeded && !indicesDisabled) return false;
        resetModels();
        return true;
    }

    public static List<BakedQuad> quads(BlockState state, BlockPos pos) {
        var model = Minecraft.getInstance().getModelManager().getBlockStateModelSet().get(state);
        List<BlockStateModelPart> parts = new ArrayList<>();
        model.collectParts(RandomSource.create(state.getSeed(pos)), parts);
        List<BakedQuad> result = new ArrayList<>();
        for (var part : parts) {
            result.addAll(part.getQuads(null));
            for (Direction side : Direction.values()) result.addAll(part.getQuads(side));
        }
        return result;
    }

    public static List<TextureAtlasSprite> sprites(net.minecraft.world.level.block.Block block) {
        Map<String, TextureAtlasSprite> result = new TreeMap<>();
        for (var state : block.getStateDefinition().getPossibleStates())
            for (int sample = 0; sample < 4; sample++) {
                for (var q : quads(state, new BlockPos(sample * 19, 0, sample * 31))) {
                    var sprite = q.materialInfo().sprite();
                    result.put(sprite.contents().name().toString(), sprite);
                }
            }
        return List.copyOf(result.values());
    }

    public int model(BlockState state, BlockPos pos) {
        long position = pos.asLong();
        var known = placed.get(position);
        if (known != null && known.state == state) return known.id;
        int result = buildModel(state, pos);
        placed.put(position, new PlacedModel(state, result));
        return result;
    }

    private int buildModel(BlockState state, BlockPos pos) {
        String block = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        var profile = StillBloomClient.MASKS.profile(block);
        if (profile == null || profile.textures.isEmpty()) return 65535;
        var quads = quads(state, pos);
        Key key = new Key(state, quads, state.getOffset(pos));
        Integer existing = cache.get(key);
        if (existing != null) return existing;
        // I never replace an unsupported painted mask with automatic bloom.
        if (quads.size() > MAX_QUADS || quads.isEmpty()) {
            warn(block);
            cache.put(key, 65535);
            return 65535;
        }
        if (nextId >= 65535) {
            budgetExceeded = true;
            if (!carryingVolume) warn(block);
            cache.put(key, 65535);
            return 65535;
        }
        // I upload only masks used by these faces and share storage for linked paint.
        Map<String, TextureMasks.Mask> usedMasks = new HashMap<>();
        Map<TextureAtlasSprite, Integer> rects = new IdentityHashMap<>();
        for (var quad : quads) {
            var sprite = quad.materialInfo().sprite();
            String texture = sprite.contents().name().toString();
            var mask = profile.textures.get(texture);
            if (mask != null) {
                usedMasks.put(texture, mask);
                if (TextureAtlas.LOCATION_BLOCKS.equals(sprite.atlasLocation()))
                    rects.put(sprite, 0);
            }
        }
        int required = quads.size() * 2 * TRI_WORDS + 3 + 4 * rects.size();
        var newMasks = Collections.newSetFromMap(new IdentityHashMap<TextureMasks.Mask, Boolean>());
        for (var mask : usedMasks.values())
            if (!masks.containsKey(mask) && newMasks.add(mask)) required += mask.words.length;
        if (cursor + required >= table.length) {
            budgetExceeded = true;
            if (!carryingVolume) warn(block);
            cache.put(key, 65535);
            return 65535;
        }
        if (!reserveHeader(quads.size() * 2 > TriangleIndex.MAX_CANDIDATES)) {
            if (!carryingVolume) warn(block);
            cache.put(key, 65535);
            return 65535;
        }
        int firstBodyWord = cursor;
        Map<String, Integer> addresses = new HashMap<>();
        for (var e : usedMasks.entrySet()) {
            Integer address = masks.get(e.getValue());
            if (address == null) {
                address = cursor;
                System.arraycopy(e.getValue().words, 0, table, cursor, e.getValue().words.length);
                cursor += e.getValue().words.length;
                masks.put(e.getValue(), address);
            }
            addresses.put(e.getKey(), address);
        }
        cursor = (cursor + 3) & ~3;
        // I keep atlas UVs separate from mask resolution because animations and reloads can change
        // the atlas.
        for (var entry : rects.entrySet()) {
            var sprite = entry.getKey();
            entry.setValue(cursor);
            int w = sprite.contents().width(), h = sprite.contents().height();
            table[cursor++] = contentOrigin(sprite.getU0(), sprite.getU1(), w);
            table[cursor++] = contentOrigin(sprite.getV0(), sprite.getV1(), h);
            table[cursor++] = w;
            table[cursor++] = h;
        }
        int id = nextId++, start = cursor, count = 0;
        List<TriangleIndex.Bounds> triangleBounds = new ArrayList<>();
        float emissionReference =
                Emission.reference(
                        lighting.north(), lighting.south(), lighting.west(), lighting.east());
        for (var quad : quads) {
            var sprite = quad.materialInfo().sprite();
            String texture = sprite.contents().name().toString();
            var mask = profile.textures.get(texture);
            // I use the spare triangle value so this correction is calculated once per cached face.
            var shadeOverride = quad.materialInfo().shadeDirectionOverride();
            int shade =
                    shadeOverride != null
                            ? 255
                            : Math.clamp((int) (lighting.byFace(quad.direction()) * 255), 0, 255);
            int spriteAndShade = (rects.getOrDefault(sprite, 0) << 8) | shade;
            float emissionGain =
                    mask == null
                            ? 1
                            : Emission.compensation(
                                    lighting.byFace(
                                            shadeOverride == null
                                                    ? quad.direction()
                                                    : shadeOverride),
                                    emissionReference,
                                    shadeOverride == null);
            for (int[] tri : new int[][] {{0, 1, 2}, {0, 2, 3}}) {
                var a =
                        new org.joml.Vector3f(quad.position(tri[0]))
                                .add(
                                        (float) key.offset.x,
                                        (float) key.offset.y,
                                        (float) key.offset.z);
                var e = new org.joml.Vector3f(quad.position(tri[1])).sub(quad.position(tri[0]));
                var f = new org.joml.Vector3f(quad.position(tri[2])).sub(quad.position(tri[0]));
                float ee = e.dot(e), ef = e.dot(f), ff = f.dot(f), det = ee * ff - ef * ef;
                var normal = new org.joml.Vector3f(e).cross(f);
                if (normal.length() < 0.000001f || Math.abs(det) < 0.00000001f) continue;
                normal.normalize();
                putRow(normal, -normal.dot(a));
                var u =
                        new org.joml.Vector3f(e)
                                .mul(ff)
                                .sub(new org.joml.Vector3f(f).mul(ef))
                                .div(det);
                var v =
                        new org.joml.Vector3f(f)
                                .mul(ee)
                                .sub(new org.joml.Vector3f(e).mul(ef))
                                .div(det);
                putRow(u, -u.dot(a));
                putRow(v, -v.dot(a));
                float[] us = new float[3], vs = new float[3];
                for (int k = 0; k < 3; k++) {
                    us[k] =
                            (UVPair.unpackU(quad.packedUV(tri[k])) - sprite.getU0())
                                    / (sprite.getU1() - sprite.getU0());
                    vs[k] =
                            (UVPair.unpackV(quad.packedUV(tri[k])) - sprite.getV0())
                                    / (sprite.getV1() - sprite.getV0());
                }
                put(us[0]);
                put(vs[0]);
                put(us[1] - us[0]);
                put(vs[1] - vs[0]);
                // I keep unpainted faces for depth rejection so paint can't leak from an opposing
                // face.
                put(us[2] - us[0]);
                put(vs[2] - vs[0]);
                put(mask == null ? 0 : profile.strength);
                put(emissionGain);
                table[cursor++] = mask == null ? -1 : addresses.get(texture);
                table[cursor++] = mask == null ? 0 : mask.width;
                table[cursor++] = mask == null ? 0 : mask.height;
                table[cursor++] = spriteAndShade;
                triangleBounds.add(
                        TriangleIndex.bounds(
                                a.x, a.y, a.z, a.x + e.x, a.y + e.y, a.z + e.z, a.x + f.x,
                                a.y + f.y, a.z + f.z));
                count++;
            }
        }
        table[id * 4] = start;
        table[id * 4 + 1] = count;
        table[id * 4 + 2] = 0;
        table[id * 4 + 3] = 0;
        boolean needsIndex = count > TriangleIndex.MAX_CANDIDATES;
        // I share matching triangle indices while keeping each oxidation, wax and waterlogged
        // variant's UVs and paint.
        Integer sharedIndex = needsIndex ? complexIndices.get(triangleBounds) : null;
        if (sharedIndex != null) table[id * 4 + 2] = sharedIndex;
        else if (count >= 24 && !indicesDisabled) {
            var cells = TriangleIndex.build(triangleBounds);
            int references = 0;
            for (var cell : cells) references += cell.length;
            int requiredIndex = TriangleIndex.CELLS * 4 + references;
            int index = (indexCursor - requiredIndex) & ~3;
            if ((needsIndex || references < count * TriangleIndex.CELLS * 3 / 4)
                    && index > nextId * 4
                    && (!needsIndex || TriangleIndex.withinShaderLimit(cells))) {
                indexCursor = index;
                int write = index + TriangleIndex.CELLS * 4;
                table[id * 4 + 2] = index;
                for (int i = 0; i < cells.length; i++) {
                    table[index + i * 4] = write;
                    table[index + i * 4 + 1] = cells[i].length;
                    for (int triangle : cells[i]) table[write++] = triangle;
                }
                dirtyRows.markWords(index, write);
                requiredIndices |= needsIndex;
                if (needsIndex) complexIndices.put(List.copyOf(triangleBounds), index);
            }
        }
        if (needsIndex && table[id * 4 + 2] == 0) {
            // I reject over-budget geometry instead of cutting it to 128 triangles, releasing only
            // its own allocations.
            rollbackModel(id, firstBodyWord, newMasks);
            budgetExceeded = true;
            if (!carryingVolume) warn(block);
            cache.put(key, 65535);
            return 65535;
        }
        dirtyRows.markWords(id * 4, id * 4 + 4);
        dirtyRows.markWords(firstBodyWord, cursor);
        cache.put(key, id);
        dirty = true;
        return id;
    }

    // I use baked UV endpoints because getX/getY include the mip gutter outside the image.
    static int contentOrigin(float first, float last, int pixels) {
        return Math.round(first * (pixels / (last - first)));
    }

    // I use the full search for small models and keep the index for complex ones.
    boolean reserveHeader(boolean needsIndex) {
        if (needsIndex && indicesDisabled) {
            budgetExceeded = true;
            return false;
        }
        if ((nextId + 1) * 4 <= indexCursor) return true;
        if (requiredIndices || needsIndex) {
            budgetExceeded = true;
            return false;
        }
        for (int i = 1; i < nextId; i++) {
            table[i * 4 + 2] = 0;
            table[i * 4 + 3] = 0;
        }
        dirtyRows.markWords(4, nextId * 4);
        indexCursor = HEADER_WORDS - 4;
        indicesDisabled = true;
        return true;
    }

    void rollbackModel(int id, int firstBodyWord, Set<TextureMasks.Mask> newMasks) {
        nextId = id;
        cursor = firstBodyWord;
        Arrays.fill(table, id * 4, id * 4 + 4, 0);
        for (var mask : newMasks) masks.remove(mask);
    }

    private void putRow(org.joml.Vector3f row, float offset) {
        put(row.x);
        put(row.y);
        put(row.z);
        put(offset);
    }

    private void put(float value) {
        table[cursor++] = Float.floatToRawIntBits(value);
    }

    private void warn(String block) {
        if (warned.add(block))
            StillBloomClient.LOGGER.warn(
                    "Custom bloom skipped for {}: unsupported/oversized baked model or geometry budget exceeded",
                    block);
    }

    public void upload() {
        ensure();
        if (dirty || data.needsUpload()) {
            data.upload(table, dirtyRows);
            dirty = false;
        }
    }

    @Override
    public void close() {
        volume.close();
        data.close();
        models = null;
        revision = -1;
        dirty = true;
        dirtyRows.markAll();
    }

    static final class Emission {
        static final float MAX_GAIN = 1.61f;

        private Emission() {}

        // I use the brightest existing vertical face as the shared brightness reference.
        static float reference(float north, float south, float west, float east) {
            return Math.max(Math.max(north, south), Math.max(west, east));
        }

        // I leave unshaded models alone and match shaded faces to the chosen reference.
        static float compensation(float faceShade, float referenceShade, boolean shaded) {
            if (!shaded
                    || !Float.isFinite(faceShade)
                    || !Float.isFinite(referenceShade)
                    || faceShade <= 0
                    || faceShade > 1
                    || referenceShade <= 0
                    || referenceShade > 1) return 1;
            // I use the packed shade value: Minecraft rounds 0.5 down to 127, not 127.5.
            int face = (int) (faceShade * 255), reference = (int) (referenceShade * 255);
            if (face == 0 || reference == 0) return 1;
            return Math.min(MAX_GAIN, reference / (float) face);
        }
    }

    // I narrow searches with an 8x8x8 grid, then still check the exact triangles and paint on the
    // GPU.
    static final class TriangleIndex {
        static final int SIDE = 8, CELLS = SIDE * SIDE * SIDE, MAX_CANDIDATES = 128;

        record Bounds(
                double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {}

        private static final Bounds ALL =
                new Bounds(
                        -Double.MAX_VALUE,
                        -Double.MAX_VALUE,
                        -Double.MAX_VALUE,
                        Double.MAX_VALUE,
                        Double.MAX_VALUE,
                        Double.MAX_VALUE);

        static Bounds bounds(
                float ax,
                float ay,
                float az,
                float bx,
                float by,
                float bz,
                float cx,
                float cy,
                float cz) {
            double[] a = {ax, ay, az}, b = {bx, by, bz}, c = {cx, cy, cz};
            for (int i = 0; i < 3; i++)
                if (!Double.isFinite(a[i])
                        || !Double.isFinite(b[i])
                        || !Double.isFinite(c[i])
                        || Math.min(a[i], Math.min(b[i], c[i])) < -2
                        || Math.max(a[i], Math.max(b[i], c[i])) > 3) return ALL;
            double ex = bx - ax,
                    ey = by - ay,
                    ez = bz - az,
                    fx = cx - ax,
                    fy = cy - ay,
                    fz = cz - az;
            double nx = ey * fz - ez * fy, ny = ez * fx - ex * fz, nz = ex * fy - ey * fx;
            // I fall back to the full search when unusual geometry makes the index unreliable.
            if (nx * nx + ny * ny + nz * nz < 1e-5) return ALL;
            double[] lo = new double[3], hi = new double[3];
            for (int i = 0; i < 3; i++) {
                // I include the shader's .006 plane and .001 edge tolerances, plus float rounding.
                double pad = .007 + .003 * (Math.abs(b[i] - a[i]) + Math.abs(c[i] - a[i]));
                lo[i] = Math.min(a[i], Math.min(b[i], c[i])) - pad;
                hi[i] = Math.max(a[i], Math.max(b[i], c[i])) + pad;
            }
            return new Bounds(lo[0], lo[1], lo[2], hi[0], hi[1], hi[2]);
        }

        static int cell(double coordinate) {
            return (int) Math.clamp(Math.floor(coordinate * SIDE), 0, SIDE - 1);
        }

        static int[][] build(List<Bounds> triangles) {
            int[][] cells = new int[CELLS][];
            for (int z = 0; z < SIDE; z++)
                for (int y = 0; y < SIDE; y++)
                    for (int x = 0; x < SIDE; x++) {
                        int[] list = new int[triangles.size()];
                        int n = 0;
                        for (int t = 0; t < triangles.size(); t++) {
                            var b = triangles.get(t);
                            if (x >= cell(b.minX)
                                    && x <= cell(b.maxX)
                                    && y >= cell(b.minY)
                                    && y <= cell(b.maxY)
                                    && z >= cell(b.minZ)
                                    && z <= cell(b.maxZ)) list[n++] = t;
                        }
                        // I keep triangle order so coincident faces still use the last matching
                        // face.
                        cells[x + SIDE * (y + SIDE * z)] = Arrays.copyOf(list, n);
                    }
            return cells;
        }

        static boolean withinShaderLimit(int[][] cells) {
            for (var cell : cells) if (cell.length > MAX_CANDIDATES) return false;
            return true;
        }

        private TriangleIndex() {}
    }
}
