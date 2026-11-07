package dev.stillbloom.model;

import static org.junit.jupiter.api.Assertions.*;

import it.unimi.dsi.fastutil.ints.IntList;

import net.minecraft.world.item.ItemDisplayContext;

import org.junit.jupiter.api.Test;

import java.util.*;

class ItemModelTexturesTest {
    @Test
    void staticSpriteSentinelDoesNotSelectANonexistentSecondFrame() {
        // I cover 26.2's static [1] frame result even for a single 16x16 image.
        assertEquals(0, ItemModelTextures.editorFrame(false, IntList.of(1)));
        assertEquals(3, ItemModelTextures.editorFrame(true, IntList.of(3, 0, 2)));
        assertEquals(0, ItemModelTextures.editorFrame(true, IntList.of()));
    }

    @Test
    void separateInventoryArtworkDoesNotHideThePaintableHandTextures() {
        // I cover packs that use different inventory and hand textures, including Weskerson's and
        // Refined Torches.
        var inventory = texture("minecraft:item/torch", 16, 16);
        var flame = texture("minecraft:item/torch_flame", 16, 16);
        var gradient = texture("minecraft:item/torch_gradient_flame", 256, 256);
        var handle = texture("minecraft:item/torch_hand", 16, 16);
        var left = texture("test:item/left_hand_only", 32, 16);
        var calls = EnumSet.noneOf(ItemDisplayContext.class);
        var selected =
                ItemModelTextures.handTextures(
                        display -> {
                            calls.add(display);
                            return switch (display) {
                                case GUI -> List.of(inventory);
                                case FIRST_PERSON_LEFT_HAND ->
                                        List.of(flame, gradient, handle, left);
                                case FIRST_PERSON_RIGHT_HAND,
                                        THIRD_PERSON_RIGHT_HAND,
                                        THIRD_PERSON_LEFT_HAND ->
                                        List.of(flame, gradient, handle);
                                default -> List.of();
                            };
                        });
        assertEquals(Set.of(flame.id(), gradient.id(), handle.id(), left.id()), selected.keySet());
        assertSame(gradient, selected.get(gradient.id()));
        assertFalse(calls.contains(ItemDisplayContext.GUI));
        assertTrue(
                calls.containsAll(
                        EnumSet.of(
                                ItemDisplayContext.FIRST_PERSON_LEFT_HAND,
                                ItemDisplayContext.FIRST_PERSON_RIGHT_HAND,
                                ItemDisplayContext.THIRD_PERSON_LEFT_HAND,
                                ItemDisplayContext.THIRD_PERSON_RIGHT_HAND)));
    }

    @Test
    void specialRendererWithoutBakedHandQuadsStillHasItsInventoryFallback() {
        var inventory = texture("test:item/special_preview", 16, 32);
        var selected =
                ItemModelTextures.handTextures(
                        display ->
                                display == ItemDisplayContext.GUI ? List.of(inventory) : List.of());
        assertEquals(Map.of(inventory.id(), inventory), selected);
    }

    private static ItemModelTextures.Texture texture(String id, int width, int height) {
        return new ItemModelTextures.Texture(id, null, null, width, height);
    }
}
