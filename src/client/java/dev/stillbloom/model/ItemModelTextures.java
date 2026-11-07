package dev.stillbloom.model;

import com.mojang.blaze3d.platform.NativeImage;

import dev.stillbloom.StillBloomClient;
import dev.stillbloom.mixin.ItemRenderStateAccessor;
import dev.stillbloom.mixin.ItemSpriteSourceListAccessor;
import dev.stillbloom.mixin.ItemTextureAtlasAccessor;
import dev.stillbloom.mixin.SpriteContentsAccessor;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.atlas.SpriteResourceLoader;
import net.minecraft.client.renderer.texture.atlas.SpriteSource;
import net.minecraft.client.renderer.texture.atlas.SpriteSourceList;
import net.minecraft.client.resources.metadata.animation.AnimationMetadataSection;
import net.minecraft.client.resources.metadata.texture.TextureMetadataSection;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

import java.lang.reflect.Method;
import java.util.*;
import java.util.function.Function;

// I use the active item models and read texture images only in the editor.
public final class ItemModelTextures {
    public record Texture(
            String id, Identifier target, TextureAtlasSprite sprite, int width, int height) {
        public float maskU(float u) {
            return sprite == null ? u : (u - sprite.getU0()) / (sprite.getU1() - sprite.getU0());
        }

        public float maskV(float v) {
            return sprite == null ? v : (v - sprite.getV0()) / (sprite.getV1() - sprite.getV0());
        }
    }

    private static final LinkedHashMap<String, LinkedHashMap<String, Texture>> OBSERVED =
            new LinkedHashMap<>(32, .75f, true);
    private static final Map<Identifier, List<TextureAtlasSprite>> ATLASES = new HashMap<>();
    private static final Map<Identifier, Texture> STANDALONE = new HashMap<>();
    private static final Map<Identifier, SpriteSourceList> ATLAS_SOURCES = new HashMap<>();

    private record AtlasLookup(Identifier target, int minU, int minV, int maxU, int maxV) {}

    private static final LinkedHashMap<AtlasLookup, Optional<Texture>> ATLAS_LOOKUPS =
            new LinkedHashMap<>(64, .75f, true);
    private static Object models;

    private ItemModelTextures() {}

    public static Texture texture(TextureAtlasSprite sprite) {
        return new Texture(
                sprite.contents().name().toString(),
                sprite.atlasLocation(),
                sprite,
                sprite.contents().width(),
                sprite.contents().height());
    }

    public static void observe(String itemId, Texture texture) {
        if (itemId == null || texture == null) return;
        refresh();
        if (!OBSERVED.containsKey(itemId) && OBSERVED.size() >= 256)
            OBSERVED.remove(OBSERVED.keySet().iterator().next());
        var values = OBSERVED.computeIfAbsent(itemId, key -> new LinkedHashMap<>());
        if (values.size() < 64 || values.containsKey(texture.id()))
            values.put(texture.id(), texture);
    }

    // I prefer hand textures so inventory art can't stand in for the model being painted.
    public static List<Texture> sprites(ItemStack stack) {
        refresh();
        if (stack == null || stack.isEmpty()) return List.of();
        var textures = handTextures(display -> modelTextures(stack, display));
        addPunchyTextures(stack, textures);
        var observed = OBSERVED.get(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
        if (observed != null) textures.putAll(observed);
        return List.copyOf(textures.values());
    }

    static SortedMap<String, Texture> handTextures(
            Function<ItemDisplayContext, ? extends Collection<Texture>> resolver) {
        var textures = new TreeMap<String, Texture>();
        for (var display :
                new ItemDisplayContext[] {
                    ItemDisplayContext.FIRST_PERSON_RIGHT_HAND,
                    ItemDisplayContext.FIRST_PERSON_LEFT_HAND,
                    ItemDisplayContext.THIRD_PERSON_RIGHT_HAND,
                    ItemDisplayContext.THIRD_PERSON_LEFT_HAND
                }) for (var texture : resolver.apply(display)) textures.put(texture.id(), texture);
        // I use the inventory preview only when a special renderer exposes no hand geometry.
        if (textures.isEmpty())
            for (var texture : resolver.apply(ItemDisplayContext.GUI))
                textures.put(texture.id(), texture);
        return textures;
    }

    private static List<Texture> modelTextures(ItemStack stack, ItemDisplayContext display) {
        var client = Minecraft.getInstance();
        var state = new ItemStackRenderState();
        if (client.player != null)
            client.getItemModelResolver().updateForLiving(state, stack, display, client.player);
        else
            client.getItemModelResolver()
                    .updateForTopItem(state, stack, display, client.level, null, 0);
        var access = (ItemRenderStateAccessor) state;
        var layers = access.stillbloom$layers();
        var textures = new ArrayList<Texture>();
        for (int i = 0; i < access.stillbloom$activeLayers(); i++)
            for (var quad :
                    ((dev.stillbloom.mixin.ItemLayerAccessor) layers[i]).stillbloom$quads().all()) {
                textures.add(texture(quad.materialInfo().sprite()));
            }
        return textures;
    }

    // I keep an editor-owned snapshot so later atlas changes don't affect the preview.
    public static NativeImage snapshot(Texture texture) {
        if (texture == null) return null;
        try {
            NativeImage source;
            if (texture.sprite() != null) {
                var contents = texture.sprite().contents();
                source = ((SpriteContentsAccessor) contents).stillbloom$originalImage();
                if (source != null && !source.isClosed()) return firstFrame(contents, source);
                // I rerun only this sprite's source because static atlas images are freed after
                // upload.
                NativeImage reloaded = reloadSprite(texture);
                if (reloaded != null) return reloaded;
                var name = contents.name();
                var file =
                        Identifier.fromNamespaceAndPath(
                                name.getNamespace(), "textures/" + name.getPath() + ".png");
                try (var stream =
                                Minecraft.getInstance()
                                        .getResourceManager()
                                        .getResourceOrThrow(file)
                                        .open();
                        var image = NativeImage.read(stream)) {
                    return firstFrame(contents, image);
                }
            }
            var client = Minecraft.getInstance();
            var active = client.getTextureManager().getTexture(texture.target());
            if (active instanceof DynamicTexture dynamic && dynamic.getPixels() != null)
                return copy(dynamic.getPixels(), texture.width(), texture.height());
            try (var stream =
                    client.getResourceManager().getResourceOrThrow(texture.target()).open()) {
                return NativeImage.read(stream);
            }
        } catch (Exception ignored) {
            return null;
        }
    }

    private static NativeImage firstFrame(SpriteContents contents, NativeImage source) {
        int first = editorFrame(contents.isAnimated(), contents.getUniqueFrames());
        int columns = Math.max(1, source.getWidth() / contents.width());
        return copy(
                source,
                contents.width(),
                contents.height(),
                first % columns * contents.width(),
                first / columns * contents.height());
    }

    // I treat 26.2's static getUniqueFrames result of [1] as a static image, not frame index 1.
    static int editorFrame(boolean animated, it.unimi.dsi.fastutil.ints.IntList frames) {
        return animated && !frames.isEmpty() ? frames.getInt(0) : 0;
    }

    private static NativeImage reloadSprite(Texture texture) {
        var manager = Minecraft.getInstance().getResourceManager();
        String path = texture.target().getPath();
        if (!path.startsWith("textures/atlas/") || !path.endsWith(".png")) return null;
        Identifier atlas =
                Identifier.fromNamespaceAndPath(
                        texture.target().getNamespace(), path.substring(15, path.length() - 4));
        SpriteSourceList sources =
                ATLAS_SOURCES.computeIfAbsent(atlas, key -> SpriteSourceList.load(manager, key));
        final class Selected implements SpriteSource.Output {
            SpriteSource.DiscardableLoader loader;

            @Override
            public void add(Identifier id, SpriteSource.DiscardableLoader candidate) {
                if (id.equals(texture.sprite().contents().name())) {
                    if (loader != null) loader.discard();
                    loader = candidate;
                } else candidate.discard();
            }

            @Override
            public void removeAll(java.util.function.Predicate<Identifier> predicate) {
                if (loader != null && predicate.test(texture.sprite().contents().name())) {
                    loader.discard();
                    loader = null;
                }
            }
        }
        var selected = new Selected();
        try {
            for (var source : ((ItemSpriteSourceListAccessor) sources).stillbloom$sources())
                source.run(manager, selected);
            var loader = selected.loader;
            selected.loader = null;
            if (loader == null) return null;
            try (var contents =
                    loader.get(
                            SpriteResourceLoader.create(
                                    Set.of(
                                            AnimationMetadataSection.TYPE,
                                            TextureMetadataSection.TYPE)))) {
                if (contents == null) return null;
                var image = ((SpriteContentsAccessor) contents).stillbloom$originalImage();
                return firstFrame(contents, image);
            }
        } catch (Exception e) {
            StillBloomClient.LOGGER.debug(
                    "Cannot reload atlas sprite {} for editing", texture.id(), e);
            return null;
        } finally {
            if (selected.loader != null) selected.loader.discard();
        }
    }

    private static NativeImage copy(NativeImage source, int width, int height) {
        return copy(source, width, height, 0, 0);
    }

    private static NativeImage copy(
            NativeImage source, int width, int height, int offsetX, int offsetY) {
        if (source == null
                || source.isClosed()
                || width < 1
                || height < 1
                || offsetX < 0
                || offsetY < 0
                || offsetX + width > source.getWidth()
                || offsetY + height > source.getHeight()) return null;
        var result = new NativeImage(width, height, false);
        for (int y = 0; y < height; y++)
            for (int x = 0; x < width; x++)
                result.setPixel(x, y, source.getPixel(offsetX + x, offsetY + y));
        return result;
    }

    public static Texture standalone(Identifier target) {
        refresh();
        var cached = STANDALONE.get(target);
        if (cached != null) return cached;
        try {
            var gpu = Minecraft.getInstance().getTextureManager().getTexture(target).getTexture();
            var value =
                    new Texture(target.toString(), target, null, gpu.getWidth(0), gpu.getHeight(0));
            if (STANDALONE.size() >= 256) STANDALONE.clear();
            STANDALONE.put(target, value);
            return value;
        } catch (Exception ignored) {
            return null;
        }
    }

    public static boolean isAtlas(Identifier target) {
        return target.equals(TextureAtlas.LOCATION_ITEMS)
                || target.equals(TextureAtlas.LOCATION_BLOCKS);
    }

    // I resolve the submitted UVs without reading texture pixels.
    public static Texture atlasTexture(
            Identifier target, float[] vertices, int offset, int stride) {
        refresh();
        var candidates = ATLASES.get(target);
        if (candidates == null) {
            var active = Minecraft.getInstance().getTextureManager().getTexture(target);
            if (!(active instanceof TextureAtlas atlas)) return null;
            candidates =
                    List.copyOf(((ItemTextureAtlasAccessor) atlas).stillbloom$sprites().values());
            ATLASES.put(target, candidates);
        }
        float minU = Float.POSITIVE_INFINITY,
                minV = minU,
                maxU = Float.NEGATIVE_INFINITY,
                maxV = maxU;
        for (int i = 0; i < 4; i++) {
            int p = offset + i * stride;
            float u = vertices[p + 3], v = vertices[p + 4];
            minU = Math.min(minU, u);
            maxU = Math.max(maxU, u);
            minV = Math.min(minV, v);
            maxV = Math.max(maxV, v);
        }
        var key =
                new AtlasLookup(
                        target,
                        Float.floatToIntBits(minU),
                        Float.floatToIntBits(minV),
                        Float.floatToIntBits(maxU),
                        Float.floatToIntBits(maxV));
        var hit = ATLAS_LOOKUPS.get(key);
        if (hit != null) return hit.orElse(null);
        final float epsilon = .000002f;
        Texture found = null;
        for (var sprite : candidates)
            if (minU >= sprite.getU0() - epsilon
                    && maxU <= sprite.getU1() + epsilon
                    && minV >= sprite.getV0() - epsilon
                    && maxV <= sprite.getV1() + epsilon) {
                found = texture(sprite);
                break;
            }
        if (ATLAS_LOOKUPS.size() >= 1024)
            ATLAS_LOOKUPS.remove(ATLAS_LOOKUPS.keySet().iterator().next());
        ATLAS_LOOKUPS.put(key, Optional.ofNullable(found));
        return found;
    }

    public static Object generation() {
        refresh();
        return models;
    }

    private static void refresh() {
        Object current = Minecraft.getInstance().getModelManager().getBlockStateModelSet();
        if (current != models) {
            clear();
            models = current;
        }
    }

    public static void clear() {
        OBSERVED.clear();
        ATLASES.clear();
        STANDALONE.clear();
        ATLAS_LOOKUPS.clear();
        ATLAS_SOURCES.clear();
        models = null;
    }

    // I use reflection only when the editor opens so Punchy stays optional and adds no per-frame
    // lookup.
    private static void addPunchyTextures(ItemStack stack, Map<String, Texture> out) {
        try {
            Class<?> type = Class.forName("punchy.client.modelparts.ModelPartsSystem");
            Object system = type.getMethod("get").invoke(null);
            Method resolve =
                    Arrays.stream(type.getMethods())
                            .filter(m -> m.getName().equals("resolveItemModelId"))
                            .findFirst()
                            .orElseThrow();
            Object id = resolve.invoke(system, stack, Minecraft.getInstance().player);
            if (id != null) {
                Object model = type.getMethod("getItemModel", Identifier.class).invoke(system, id);
                if (model != null) {
                    Map<?, ?> refs =
                            (Map<?, ?>) model.getClass().getMethod("getTextures").invoke(model);
                    var atlas =
                            Minecraft.getInstance()
                                    .getAtlasManager()
                                    .getAtlasOrThrow(
                                            Identifier.fromNamespaceAndPath("minecraft", "items"));
                    for (Object value : refs.values())
                        if (value instanceof String text) {
                            for (int i = 0; i < 8 && text.startsWith("#"); i++) {
                                Object next = refs.get(text.substring(1));
                                if (!(next instanceof String)) break;
                                text = (String) next;
                            }
                            Identifier spriteId = Identifier.tryParse(text);
                            if (spriteId != null && !text.startsWith("#")) {
                                var sprite = atlas.getSprite(spriteId);
                                if (sprite != atlas.missingSprite()) {
                                    var texture = texture(sprite);
                                    out.put(texture.id(), texture);
                                }
                            }
                        }
                }
            }
        } catch (ReflectiveOperationException | LinkageError | RuntimeException ignored) {
        }
        try {
            Class<?> type = Class.forName("punchy.client.bedrockitems.BedrockItemSystem");
            Object system = type.getMethod("get").invoke(null);
            Method resolve = type.getDeclaredMethod("resolveDefinition", ItemStack.class);
            resolve.setAccessible(true);
            Object definition = resolve.invoke(system, stack);
            if (definition != null) {
                addStandalone(invoke(definition, "getTextureId"), out);
                Object bones = invoke(definition, "getBoneTextures");
                if (bones instanceof Map<?, ?> map)
                    for (Object id : map.values()) addStandalone(id, out);
            }
        } catch (ReflectiveOperationException | LinkageError | RuntimeException ignored) {
        }
    }

    private static Object invoke(Object owner, String name) throws ReflectiveOperationException {
        Method method = owner.getClass().getDeclaredMethod(name);
        method.setAccessible(true);
        return method.invoke(owner);
    }

    private static void addStandalone(Object id, Map<String, Texture> out) {
        if (id instanceof Identifier target) {
            var texture = standalone(target);
            if (texture != null) out.put(texture.id(), texture);
        }
    }
}
