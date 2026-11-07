package dev.stillbloom.compat;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;

import dev.stillbloom.StillBloomClient;
import dev.stillbloom.mask.TextureMasks;
import dev.stillbloom.mixin.ItemRenderSetupAccessor;
import dev.stillbloom.mixin.ItemRenderTypeAccessor;
import dev.stillbloom.mixin.ItemTextureBindingAccessor;
import dev.stillbloom.model.ItemModelTextures;
import dev.stillbloom.render.item.HeldItemCapture;

import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

import org.joml.Matrix4f;

import java.util.*;

// I capture Punchy's painted model as it draws, so I don't draw it twice.
public final class CustomHeldItemCapture {
    public static final int STRIDE = 9;

    public record Mesh(
            HeldItemCapture.ContextSnapshot item,
            Matrix4f viewPose,
            ItemModelTextures.Texture texture,
            float[] vertices,
            int vertexCount) {}

    private static final List<Mesh> WORLD = new ArrayList<>(), HANDS = new ArrayList<>();
    private static final List<Mesh> WORLD_VIEW = Collections.unmodifiableList(WORLD),
            HANDS_VIEW = Collections.unmodifiableList(HANDS);
    private static final Map<RenderType, Identifier> TARGETS = new IdentityHashMap<>();
    private static final Set<RenderType> UNSUPPORTED =
            Collections.newSetFromMap(new IdentityHashMap<>());
    private static final ArrayDeque<Recorder> RECORDERS = new ArrayDeque<>();
    private static final ArrayDeque<float[]> BUFFERS = new ArrayDeque<>();
    private static int bufferedFloats;
    private static final Map<TextureMasks.Mask, Boolean> PAINTED = new IdentityHashMap<>();
    private static Object masksOwner;
    private static long masksRevision;
    private static String[] discovery = new String[4];
    private static int depth;
    private static Object generation;

    private CustomHeldItemCapture() {}

    public static void beginFrame() {
        for (var mesh : WORLD) recycle(mesh.vertices());
        for (var mesh : HANDS) recycle(mesh.vertices());
        WORLD.clear();
        HANDS.clear();
        Arrays.fill(discovery, null);
        depth = 0;
    }

    public static List<Mesh> worldMeshes() {
        return WORLD_VIEW;
    }

    public static List<Mesh> handMeshes() {
        return HANDS_VIEW;
    }

    public static void beginCustom(ItemStack stack) {
        if (depth == discovery.length) discovery = Arrays.copyOf(discovery, depth * 2);
        discovery[depth++] =
                stack == null || stack.isEmpty()
                        ? null
                        : BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    public static void endCustom() {
        if (depth > 0) discovery[--depth] = null;
    }

    public static SubmitNodeCollector.CustomGeometryRenderer wrap(
            RenderType type, SubmitNodeCollector.CustomGeometryRenderer renderer) {
        if (depth == 0
                || discovery[depth - 1] == null
                || type.primitiveTopology() != PrimitiveTopology.QUADS) return renderer;
        Object current = ItemModelTextures.generation();
        if (current != generation) {
            TARGETS.clear();
            UNSUPPORTED.clear();
            RECORDERS.clear();
            generation = current;
        }
        Identifier target = target(type);
        if (target == null) return renderer;
        String itemId = discovery[depth - 1];
        if (!ItemModelTextures.isAtlas(target))
            ItemModelTextures.observe(itemId, ItemModelTextures.standalone(target));
        var item = HeldItemCapture.contextSnapshot();
        var profile = StillBloomClient.ITEM_MASKS.profile(itemId);
        if (item == null || profile == null || profile.strength <= 0 || profile.textures.isEmpty())
            return renderer;
        if (StillBloomClient.ITEM_MASKS != masksOwner
                || StillBloomClient.ITEM_MASKS.geometryRevision() != masksRevision) {
            PAINTED.clear();
            masksOwner = StillBloomClient.ITEM_MASKS;
            masksRevision = StillBloomClient.ITEM_MASKS.geometryRevision();
        }
        if (!ItemModelTextures.isAtlas(target)) {
            if (!painted(profile.textures.get(target.toString()))) return renderer;
        } else {
            boolean any = false;
            for (var mask : profile.textures.values())
                if (painted(mask)) {
                    any = true;
                    break;
                }
            if (!any) return renderer;
        }
        Matrix4f view = new Matrix4f(RenderSystem.getModelViewStack());
        return (pose, consumer) -> {
            var recorder = RECORDERS.pollFirst();
            if (recorder == null) recorder = new Recorder();
            recorder.reset(consumer);
            try {
                renderer.render(pose, recorder);
                recorder.finish(item, view, target, profile);
            } finally {
                recorder.delegate = null;
                if (RECORDERS.size() < 8) RECORDERS.addFirst(recorder);
            }
        };
    }

    private static Identifier target(RenderType type) {
        if (UNSUPPORTED.contains(type)) return null;
        var cached = TARGETS.get(type);
        if (cached != null) return cached;
        // I take only Punchy's main model pass; its extra glow passes and particles would duplicate
        // it.
        String pipeline = type.pipeline().getLocation().getPath();
        if (!pipeline.equals("pipeline/entity_translucent")
                && !pipeline.equals("entity_translucent")) {
            unsupported(type);
            return null;
        }
        var setup = ((ItemRenderTypeAccessor) type).stillbloom$setup();
        Object binding =
                ((ItemRenderSetupAccessor) (Object) setup).stillbloom$textures().get("Sampler0");
        if (!(binding instanceof ItemTextureBindingAccessor access)) {
            unsupported(type);
            return null;
        }
        var result = access.stillbloom$location();
        if (result == null || result.getPath().contains("particle")) {
            unsupported(type);
            return null;
        }
        if (TARGETS.size() > 256) {
            TARGETS.clear();
            UNSUPPORTED.clear();
        }
        TARGETS.put(type, result);
        return result;
    }

    private static void unsupported(RenderType type) {
        if (UNSUPPORTED.size() >= 256) UNSUPPORTED.clear();
        UNSUPPORTED.add(type);
    }

    private static boolean painted(TextureMasks.Mask mask) {
        if (mask == null) return false;
        var cached = PAINTED.get(mask);
        if (cached != null) return cached;
        boolean result = false;
        for (int word : mask.words)
            if (word != 0) {
                result = true;
                break;
            }
        if (PAINTED.size() >= 1024) PAINTED.clear();
        PAINTED.put(mask, result);
        return result;
    }

    private static float[] borrow(int size) {
        var iterator = BUFFERS.iterator();
        while (iterator.hasNext()) {
            var value = iterator.next();
            if (value.length >= size && value.length <= Math.max(64, size * 2)) {
                iterator.remove();
                bufferedFloats -= value.length;
                return value;
            }
        }
        return new float[size];
    }

    private static void recycle(float[] value) {
        if (BUFFERS.size() < 64 && bufferedFloats + value.length <= 1_048_576) {
            BUFFERS.addLast(value);
            bufferedFloats += value.length;
        }
    }

    public static void clear() {
        beginFrame();
        TARGETS.clear();
        UNSUPPORTED.clear();
        RECORDERS.clear();
        BUFFERS.clear();
        PAINTED.clear();
        masksOwner = null;
        bufferedFloats = 0;
        generation = null;
        ItemModelTextures.clear();
    }

    private static final class Recorder implements VertexConsumer {
        VertexConsumer delegate;
        float[] data = new float[STRIDE * 64];
        int count;
        boolean pending, overflow;
        final Map<ItemModelTextures.Texture, Indices> groups = new LinkedHashMap<>();
        float x, y, z, u, v, r = 1, g = 1, b = 1, a = 1;

        void reset(VertexConsumer delegate) {
            this.delegate = delegate;
            count = 0;
            pending = overflow = false;
            for (var list : groups.values()) list.size = 0;
        }

        private void commit() {
            if (!pending || overflow) return;
            pending = false;
            if (count >= 65536) {
                overflow = true;
                return;
            }
            int p = count * STRIDE;
            if (p + STRIDE > data.length)
                data = Arrays.copyOf(data, Math.min(65536 * STRIDE, data.length * 2));
            data[p] = x;
            data[p + 1] = y;
            data[p + 2] = z;
            data[p + 3] = u;
            data[p + 4] = v;
            data[p + 5] = r;
            data[p + 6] = g;
            data[p + 7] = b;
            data[p + 8] = a;
            count++;
        }

        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            delegate.addVertex(x, y, z);
            commit();
            this.x = x;
            this.y = y;
            this.z = z;
            u = v = 0;
            r = g = b = a = 1;
            pending = true;
            return this;
        }

        @Override
        public VertexConsumer setColor(int r, int g, int b, int a) {
            delegate.setColor(r, g, b, a);
            this.r = r / 255f;
            this.g = g / 255f;
            this.b = b / 255f;
            this.a = a / 255f;
            return this;
        }

        @Override
        public VertexConsumer setColor(int color) {
            delegate.setColor(color);
            r = (color >> 16 & 255) / 255f;
            g = (color >> 8 & 255) / 255f;
            b = (color & 255) / 255f;
            a = (color >>> 24) / 255f;
            return this;
        }

        @Override
        public VertexConsumer setUv(float u, float v) {
            delegate.setUv(u, v);
            this.u = u;
            this.v = v;
            return this;
        }

        @Override
        public VertexConsumer setUv1(int u, int v) {
            delegate.setUv1(u, v);
            return this;
        }

        @Override
        public VertexConsumer setUv2(int u, int v) {
            delegate.setUv2(u, v);
            return this;
        }

        @Override
        public VertexConsumer setUv3(float u, float v) {
            delegate.setUv3(u, v);
            return this;
        }

        @Override
        public VertexConsumer setNormal(float x, float y, float z) {
            delegate.setNormal(x, y, z);
            return this;
        }

        @Override
        public VertexConsumer setLineWidth(float width) {
            delegate.setLineWidth(width);
            return this;
        }

        void finish(
                HeldItemCapture.ContextSnapshot item,
                Matrix4f view,
                Identifier target,
                TextureMasks.Profile profile) {
            commit();
            if (overflow || count == 0 || count % 4 != 0) return;
            for (int q = 0; q < count; q += 4) {
                boolean finite = true;
                for (int p = q * STRIDE; p < (q + 4) * STRIDE; p++)
                    if (!Float.isFinite(data[p])) {
                        finite = false;
                        break;
                    }
                if (!finite) continue;
                var texture =
                        ItemModelTextures.isAtlas(target)
                                ? ItemModelTextures.atlasTexture(target, data, q * STRIDE, STRIDE)
                                : ItemModelTextures.standalone(target);
                if (texture == null) continue;
                ItemModelTextures.observe(item.itemId(), texture);
                if (!profile.textures.containsKey(texture.id())) continue;
                if (!groups.containsKey(texture) && groups.size() >= 64) continue;
                groups.computeIfAbsent(texture, key -> new Indices()).add(q);
            }
            for (var entry : groups.entrySet()) {
                var indices = entry.getValue();
                if (indices.size == 0) continue;
                int size = indices.size * 4;
                float[] vertices = borrow(size * STRIDE);
                int p = 0;
                for (int i = 0; i < indices.size; i++) {
                    int q = indices.values[i];
                    System.arraycopy(data, q * STRIDE, vertices, p, 4 * STRIDE);
                    p += 4 * STRIDE;
                }
                (item.firstPerson() ? HANDS : WORLD)
                        .add(new Mesh(item, view, entry.getKey(), vertices, size));
            }
        }
    }

    private static final class Indices {
        int[] values = new int[16];
        int size;

        void add(int value) {
            if (size == values.length) values = Arrays.copyOf(values, size * 2);
            values[size++] = value;
        }
    }
}
