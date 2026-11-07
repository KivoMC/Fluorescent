package dev.stillbloom.model;

import dev.stillbloom.mask.BlockLinks;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.*;

// I inspect models only while the mask editor is open, spreading the work across ticks.
public final class BlockMaskVariants {
    private static Object generation;
    private static final Map<Block, Query> QUERIES = new IdentityHashMap<>();
    private static final Map<Block, Set<Sprite>> SIGNATURES = new IdentityHashMap<>();
    private static final Map<Block, Set<Sprite>> PROBES = new IdentityHashMap<>();
    private static final Map<Block, TextureQuery> TEXTURES = new IdentityHashMap<>();

    private record Sprite(String atlas, String id, int width, int height) {
        Sprite(TextureAtlasSprite s) {
            this(
                    s.atlasLocation().toString(),
                    s.contents().name().toString(),
                    s.contents().width(),
                    s.contents().height());
        }

        boolean valid() {
            return !id.equals("minecraft:missingno");
        }
    }

    private BlockMaskVariants() {}

    public static void invalidate() {
        generation = null;
        QUERIES.clear();
        SIGNATURES.clear();
        PROBES.clear();
        TEXTURES.clear();
        BlockCatalog.invalidate();
    }

    private static void checkGeneration() {
        Object models = Minecraft.getInstance().getModelManager().getBlockStateModelSet();
        if (generation != models) {
            invalidate();
            generation = models;
        }
    }

    public static TextureQuery inspect(Block block) {
        checkGeneration();
        return TEXTURES.computeIfAbsent(block, TextureQuery::new);
    }

    public static Query request(Block block, List<TextureAtlasSprite> textures) {
        checkGeneration();
        return QUERIES.computeIfAbsent(block, key -> new Query(block, textures));
    }

    public static final class TextureQuery {
        private final Object owner = generation;
        private final Iterator<BlockState> states;
        private final Map<String, TextureAtlasSprite> textures = new TreeMap<>();
        private final Map<String, Set<String>> uses = new HashMap<>();
        private final List<BlockStateModelPart> parts = new ArrayList<>();
        private BlockState state;
        private String stateKey;
        private int sample;
        private boolean complete;

        TextureQuery(Block block) {
            states = block.getStateDefinition().getPossibleStates().iterator();
        }

        public boolean stale() {
            return owner != generation;
        }

        public boolean ready() {
            return complete && !stale();
        }

        public List<TextureAtlasSprite> sprites() {
            return List.copyOf(textures.values());
        }

        public List<BlockLinks.Texture> descriptions() {
            return textures.values().stream()
                    .map(
                            s ->
                                    new BlockLinks.Texture(
                                            s.contents().name().toString(),
                                            s.contents().width(),
                                            s.contents().height(),
                                            uses.get(s.contents().name().toString())))
                    .toList();
        }

        private void record(net.minecraft.client.resources.model.geometry.BakedQuad quad) {
            var sprite = quad.materialInfo().sprite();
            String id = sprite.contents().name().toString();
            textures.put(id, sprite);
            uses.computeIfAbsent(id, k -> new HashSet<>()).add(stateKey + "|" + quad.direction());
        }

        public void advance() {
            if (complete || stale()) return;
            long deadline = System.nanoTime() + 1_500_000;
            for (int i = 0; i < 256 && System.nanoTime() < deadline && !complete; i++)
                try {
                    if (state == null) {
                        if (!states.hasNext()) {
                            complete = true;
                            break;
                        }
                        state = states.next();
                        sample = 0;
                        stateKey =
                                state.getValues()
                                        .map(
                                                value ->
                                                        value.property().getName()
                                                                + "="
                                                                + value.valueName())
                                        .sorted()
                                        .collect(java.util.stream.Collectors.joining(","));
                    }
                    parts.clear();
                    model(state)
                            .collectParts(
                                    RandomSource.create(
                                            state.getSeed(
                                                    new BlockPos(sample * 19, 0, sample * 31))),
                                    parts);
                    for (var p : parts) {
                        for (var q : p.getQuads(null)) record(q);
                        for (var direction : Direction.values())
                            for (var q : p.getQuads(direction)) record(q);
                    }
                    if (++sample == 4) state = null;
                } catch (RuntimeException unsupported) {
                    textures.clear();
                    uses.clear();
                    complete = true;
                }
        }
    }

    private static BlockStateModel model(BlockState state) {
        return Minecraft.getInstance().getModelManager().getBlockStateModelSet().get(state);
    }

    public static final class Query {
        private final Object owner = generation;
        private final Block source;
        private final Set<Sprite> signature = new HashSet<>();
        private final Iterator<Block> blocks = BuiltInRegistries.BLOCK.iterator();
        private final List<String> matches = new ArrayList<>();
        private Iterator<BlockState> states;
        private Block candidate;
        private final Set<Sprite> candidateTextures = new HashSet<>();
        private final List<BlockStateModelPart> parts = new ArrayList<>();
        private boolean complete;
        private int samples;
        private BlockState state;

        Query(Block source, List<TextureAtlasSprite> textures) {
            this.source = source;
            textures.forEach(s -> signature.add(new Sprite(s)));
            matches.add(BuiltInRegistries.BLOCK.getKey(source).toString());
            SIGNATURES.put(source, Set.copyOf(signature));
            if (signature.isEmpty() || signature.stream().anyMatch(s -> !s.valid()))
                complete = true;
        }

        public boolean ready() {
            return complete && owner == generation;
        }

        public boolean stale() {
            return owner != generation;
        }

        public List<String> matches() {
            return List.copyOf(matches);
        }

        // I stop after 512 state samples or about 1.5 ms so discovery doesn't stall the menu.
        public void advance() {
            if (complete || owner != generation) return;
            long deadline = System.nanoTime() + 1_500_000;
            for (int work = 0; work < 512 && System.nanoTime() < deadline && !complete; work++)
                try {
                    if (candidate == null) {
                        if (!blocks.hasNext()) {
                            matches.sort(String::compareTo);
                            complete = true;
                            for (var id : matches)
                                QUERIES.putIfAbsent(
                                        BuiltInRegistries.BLOCK.getValue(Identifier.parse(id)),
                                        this);
                            break;
                        }
                        var next = blocks.next();
                        if (next == source) continue;
                        // I include the earlier first sample, but ignore particle textures because
                        // packs can change them separately.
                        var probe = PROBES.get(next);
                        if (probe != null && !signature.containsAll(probe)) continue;
                        var known = SIGNATURES.get(next);
                        if (known != null) {
                            if (known.equals(signature))
                                matches.add(BuiltInRegistries.BLOCK.getKey(next).toString());
                            continue;
                        }
                        candidate = next;
                        states = next.getStateDefinition().getPossibleStates().iterator();
                        candidateTextures.clear();
                        state = null;
                    }
                    if (state == null) {
                        if (!states.hasNext()) {
                            var found = Set.copyOf(candidateTextures);
                            SIGNATURES.put(candidate, found);
                            if (found.equals(signature))
                                matches.add(BuiltInRegistries.BLOCK.getKey(candidate).toString());
                            candidate = null;
                            continue;
                        }
                        state = states.next();
                        samples = 0;
                    }
                    var pos = new BlockPos(samples * 19, 0, samples * 31);
                    parts.clear();
                    model(state).collectParts(RandomSource.create(state.getSeed(pos)), parts);
                    for (var part : parts) {
                        for (var quad : part.getQuads(null))
                            candidateTextures.add(new Sprite(quad.materialInfo().sprite()));
                        for (var direction : Direction.values())
                            for (var quad : part.getQuads(direction))
                                candidateTextures.add(new Sprite(quad.materialInfo().sprite()));
                    }
                    if (!PROBES.containsKey(candidate))
                        PROBES.put(candidate, Set.copyOf(candidateTextures));
                    if (++samples == 4) state = null;
                    // I stop early when a different texture already rules out an exact match.
                    if (!signature.containsAll(candidateTextures)) candidate = null;
                } catch (RuntimeException failure) {
                    // I leave special models unmatched if they can't be inspected safely.
                    candidate = null;
                }
        }
    }
}
