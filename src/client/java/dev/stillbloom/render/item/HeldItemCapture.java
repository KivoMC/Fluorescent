package dev.stillbloom.render.item;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;

import dev.stillbloom.StillBloomClient;
import dev.stillbloom.compat.CustomHeldItemCapture;
import dev.stillbloom.config.BloomConfig;
import dev.stillbloom.mask.TextureMasks;
import dev.stillbloom.model.ItemModelTextures;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.state.ArmedEntityRenderState;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

// I capture the final animated item pose so the glow follows the model that actually draws.
public final class HeldItemCapture {
    // I apply only the projection here because viewPose already includes the model-view transform.
    public record CapturedItem(
            String itemId,
            String blockId,
            BlockState blockState,
            Matrix4f viewPose,
            List<BakedQuad> quads,
            int[] tintLayers,
            float distance,
            boolean firstPerson) {
        public CapturedItem(
                String blockId,
                BlockState state,
                Matrix4f pose,
                List<BakedQuad> quads,
                int[] tints,
                float distance,
                boolean firstPerson) {
            this(blockId, blockId, state, pose, quads, tints, distance, firstPerson);
        }
    }

    public record ContextSnapshot(
            String itemId,
            String blockId,
            BlockState blockState,
            float distance,
            boolean firstPerson) {}

    private static final List<CapturedItem> WORLD = new ArrayList<>();
    private static final List<CapturedItem> HANDS = new ArrayList<>();
    private static final List<CapturedItem> WORLD_VIEW = Collections.unmodifiableList(WORLD);
    private static final List<CapturedItem> HANDS_VIEW = Collections.unmodifiableList(HANDS);
    private static final Map<Block, String> BLOCK_IDS = new IdentityHashMap<>();
    private static final Map<Item, String> ITEM_IDS = new IdentityHashMap<>();
    // I cache geometry by its baked quads; Minecraft creates a new mutable item list every frame.
    private static final Map<BakedQuad, List<List<BakedQuad>>> GEOMETRY = new IdentityHashMap<>();
    private static int geometryEntries;
    private static Object models;
    private static Context[] contexts = new Context[4];
    private static int contextDepth;

    private static final class Context {
        String itemId, blockId;
        BlockState state;
        ContextSnapshot snapshot;
        float distance;
        boolean firstPerson;
    }

    private HeldItemCapture() {}

    // I clear the frame's contents without discarding list capacity or cached geometry.
    public static void beginFrame() {
        WORLD.clear();
        HANDS.clear();
        clearContexts();
        CustomHeldItemCapture.beginFrame();
    }

    // I consume these borrowed lists before beginFrame clears them.
    public static List<CapturedItem> worldItems() {
        return WORLD_VIEW;
    }

    public static List<CapturedItem> handItems() {
        return HANDS_VIEW;
    }

    public static ContextSnapshot contextSnapshot() {
        if (contextDepth == 0) return null;
        var context = contexts[contextDepth - 1];
        if (context.itemId == null) return null;
        if (context.snapshot == null)
            context.snapshot =
                    new ContextSnapshot(
                            context.itemId,
                            context.blockId,
                            context.state,
                            context.distance,
                            context.firstPerson);
        return context.snapshot;
    }

    public static void pushCustom(LivingEntity owner, ItemStack stack, ItemDisplayContext display) {
        beginFirstPerson(owner, stack, display);
    }

    public static void beginFirstPerson(
            LivingEntity owner, ItemStack stack, ItemDisplayContext display) {
        Context context = push();
        BloomConfig config = StillBloomClient.config;
        // I identify first person by the call site because Punchy uses third-person model
        // transforms for it.
        if (config == null || !config.enabled || !config.heldItemBloom || !handDisplay(display))
            return;
        Minecraft client = Minecraft.getInstance();
        if (owner != client.player) return;
        fill(context, stack, 0, true);
    }

    public static void beginPlayer(ArmedEntityRenderState state, ItemStack stack) {
        Context context = push();
        BloomConfig config = StillBloomClient.config;
        if (config == null || !config.enabled || !(state instanceof AvatarRenderState player))
            return;
        Minecraft client = Minecraft.getInstance();
        boolean ownPlayer = client.player != null && player.id == client.player.getId();
        if (!(ownPlayer ? config.heldItemBloom : config.otherPlayerItemBloom)) return;
        if (!Double.isFinite(player.distanceToCameraSq)
                || player.distanceToCameraSq < 0
                || player.distanceToCameraSq > config.heldItemRange * config.heldItemRange) return;
        fill(context, stack, (float) Math.sqrt(player.distanceToCameraSq), false);
    }

    public static void endItem() {
        if (contextDepth == 0) return;
        Context context = contexts[--contextDepth];
        context.state = null;
        context.blockId = null;
        context.itemId = null;
        context.snapshot = null;
    }

    // I capture at the final submission point so all model and animation transforms are included.
    public static void capture(
            PoseStack poses, ItemDisplayContext display, int[] tintLayers, List<BakedQuad> quads) {
        if (contextDepth == 0 || quads.isEmpty()) return;
        Context context = contexts[contextDepth - 1];
        if (context.itemId == null || !handDisplay(display)) return;
        Matrix4f viewPose = new Matrix4f(RenderSystem.getModelViewStack()).mul(poses.last().pose());
        if (!viewPose.isFinite()) return;
        List<BakedQuad> geometry = geometry(quads);
        int[] tints = tintLayers.length == 0 ? tintLayers : tintLayers.clone();
        CapturedItem captured =
                new CapturedItem(
                        context.itemId,
                        context.blockId,
                        context.state,
                        viewPose,
                        geometry,
                        tints,
                        context.distance,
                        context.firstPerson);
        (context.firstPerson ? HANDS : WORLD).add(captured);
    }

    private static boolean handDisplay(ItemDisplayContext display) {
        return display.firstPerson()
                || display == ItemDisplayContext.THIRD_PERSON_LEFT_HAND
                || display == ItemDisplayContext.THIRD_PERSON_RIGHT_HAND;
    }

    private static Context push() {
        if (contextDepth == contexts.length)
            contexts = java.util.Arrays.copyOf(contexts, contexts.length * 2);
        Context context = contexts[contextDepth];
        if (context == null) contexts[contextDepth] = context = new Context();
        contextDepth++;
        context.state = null;
        context.blockId = null;
        context.itemId = null;
        context.snapshot = null;
        return context;
    }

    private static void fill(
            Context context, ItemStack stack, float distance, boolean firstPerson) {
        if (stack.isEmpty()) return;
        String itemId =
                ITEM_IDS.computeIfAbsent(
                        stack.getItem(), value -> BuiltInRegistries.ITEM.getKey(value).toString());
        String blockId = null;
        BlockState state = null;
        TextureMasks.Profile profile = null;
        if (stack.getItem() instanceof BlockItem item) {
            Block block = item.getBlock();
            state = block.defaultBlockState();
            var properties = stack.get(DataComponents.BLOCK_STATE);
            if (properties != null) state = properties.apply(state);
            blockId =
                    BLOCK_IDS.computeIfAbsent(
                            block, value -> BuiltInRegistries.BLOCK.getKey(value).toString());
            profile = StillBloomClient.MASKS.profile(blockId);
        }
        var own = StillBloomClient.ITEM_MASKS.profile(itemId);
        if ((state == null || state.getLightEmission() <= 0)
                && (profile == null || profile.textures.isEmpty())
                && (own == null || own.textures.isEmpty())) return;
        context.itemId = itemId;
        context.blockId = blockId;
        context.state = state;
        context.distance = distance;
        context.firstPerson = firstPerson;
    }

    private static List<BakedQuad> geometry(List<BakedQuad> quads) {
        Object currentModels = Minecraft.getInstance().getModelManager().getBlockStateModelSet();
        if (models != currentModels) {
            models = currentModels;
            GEOMETRY.clear();
            geometryEntries = 0;
        }
        var variants = GEOMETRY.get(quads.getFirst());
        if (variants != null)
            for (var candidate : variants) {
                if (sameGeometry(candidate, quads)) return candidate;
            }
        if (geometryEntries >= 256) {
            GEOMETRY.clear();
            geometryEntries = 0;
        }
        List<BakedQuad> result = List.copyOf(quads);
        GEOMETRY.computeIfAbsent(quads.getFirst(), ignored -> new ArrayList<>()).add(result);
        geometryEntries++;
        return result;
    }

    private static boolean sameGeometry(List<BakedQuad> first, List<BakedQuad> second) {
        if (first.size() != second.size()) return false;
        for (int index = 0; index < first.size(); index++)
            if (first.get(index) != second.get(index)) return false;
        return true;
    }

    private static void clearContexts() {
        while (contextDepth > 0) endItem();
    }

    public static void clear() {
        beginFrame();
        GEOMETRY.clear();
        geometryEntries = 0;
        BLOCK_IDS.clear();
        ITEM_IDS.clear();
        models = null;
        CustomHeldItemCapture.clear();
        ItemModelTextures.clear();
    }
}
