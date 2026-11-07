package dev.stillbloom.mask;

import static org.junit.jupiter.api.Assertions.*;

import dev.stillbloom.config.BloomConfig;
import dev.stillbloom.config.BloomOverride;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;

class ItemMasksTest {
    @TempDir Path directory;

    @Test
    void itemPaintDoesNotChangeBlocksOrOtherItemsSharingTheSprite() {
        var blocks = new TextureMasks();
        var items = new ItemMasks();
        String sprite = "pack:item/shared";
        var placed = new TextureMasks.Mask(16, 16);
        placed.set(1, 1, true);
        blocks.put("minecraft:torch", sprite, placed);
        var held = new TextureMasks.Mask(16, 16);
        held.set(7, 9, true);
        items.put("minecraft:torch", sprite, held);
        items.put("minecraft:lantern", sprite, new TextureMasks.Mask(16, 16));
        assertTrue(items.mask("minecraft:torch", sprite).get(7, 9));
        assertFalse(items.mask("minecraft:lantern", sprite).get(7, 9));
        assertTrue(blocks.mask("minecraft:torch", sprite).get(1, 1));
        assertFalse(blocks.mask("minecraft:torch", sprite).get(7, 9));
        items.reset("minecraft:torch", sprite);
        assertNull(items.profile("minecraft:torch"));
        assertNotNull(items.profile("minecraft:lantern"));
        assertNotNull(blocks.profile("minecraft:torch"));
    }

    @Test
    void emptyPaintSuppressesAutomaticEmissionAndResetRestoresInheritance() {
        var items = new ItemMasks();
        assertNull(items.mask("minecraft:stick", "pack:item/stick"));
        items.put("minecraft:stick", "pack:item/stick", new TextureMasks.Mask(32, 16));
        assertNotNull(items.mask("minecraft:stick", "pack:item/stick"));
        assertEquals(
                0,
                java.util.Arrays.stream(items.mask("minecraft:stick", "pack:item/stick").words)
                        .sum());
        long revision = items.revision();
        items.reset("minecraft:stick", "pack:item/stick");
        assertNull(items.mask("minecraft:stick", "pack:item/stick"));
        assertEquals(revision + 1, items.revision());
        items.reset("minecraft:stick", "pack:item/stick");
        assertEquals(revision + 1, items.revision());
    }

    @Test
    void paintAndStylePersistForNonBlockItemsAndPackSprites() {
        var items = new ItemMasks();
        var mask = new TextureMasks.Mask(64, 32);
        mask.set(63, 31, true);
        items.put("minecraft:stick", "example:item/fancy_stick", mask);
        var p = items.profile("minecraft:stick");
        p.strength = 2.25f;
        p.override = new BloomOverride();
        p.override.shape = BloomConfig.Shape.STARBURST;
        p.override.color = 0xABCDEF;
        Path file = directory.resolve("items.json");
        assertTrue(items.save(file));
        var loaded = ItemMasks.load(file);
        assertEquals(0, loaded.revision());
        assertTrue(loaded.mask("minecraft:stick", "example:item/fancy_stick").get(63, 31));
        assertEquals(2.25f, loaded.profile("minecraft:stick").strength);
        assertEquals(0xABCDEF, loaded.profile("minecraft:stick").override.color);
        assertEquals(
                BloomConfig.Shape.STARBURST,
                loaded.style("minecraft:stick", new BloomConfig()).shape());
        assertNull(loaded.profile("minecraft:stick").override.intensity);
    }

    @Test
    void resettingTextureKeepsIndependentBrightnessAndOverride() {
        var items = new ItemMasks();
        items.put("minecraft:torch", "minecraft:item/torch", new TextureMasks.Mask(16, 16));
        var p = items.profile("minecraft:torch");
        p.strength = 2;
        p.override = new BloomOverride();
        p.override.intensity = 5f;
        items.reset("minecraft:torch", "minecraft:item/torch");
        assertSame(p, items.profile("minecraft:torch"));
        items.removeOverride("minecraft:torch");
        assertSame(p, items.profile("minecraft:torch"));
        assertEquals(2, p.strength);
        items.put("minecraft:torch", "minecraft:item/torch", new TextureMasks.Mask(16, 16));
        p.strength = 1;
        items.reset("minecraft:torch", "minecraft:item/torch");
        assertNull(items.profile("minecraft:torch"));
    }

    @Test
    void resolutionChangesDoNotStretchPaintOntoAnotherTexelGrid() {
        var mask = new TextureMasks.Mask(16, 32);
        assertSame(mask, ItemMasks.compatibleMask(mask, 16, 32));
        assertNull(ItemMasks.compatibleMask(mask, 32, 64));
        assertNull(ItemMasks.compatibleMask(mask, 32, 16));
        assertNull(ItemMasks.compatibleMask(null, 16, 32));
        mask.words = null;
        assertNull(ItemMasks.compatibleMask(mask, 16, 32));
    }

    @Test
    void invalidLocalEntriesAreSanitizedWithoutEditingTheFile() throws Exception {
        var file = directory.resolve("items.json");
        String source =
                """
            {"items":{"minecraft:stick":{"strength":99,"textures":{
              "pack:item/valid":{"width":1,"height":1,"words":[1]},
              "pack:item/broken":{"width":257,"height":1,"words":[1]},
              "../invalid":{"width":1,"height":1,"words":[1]}},"override":{"color":16777216}},
              "bad/id":{"strength":1,"textures":{}},"minecraft:air":null}}
            """;
        Files.writeString(file, source);
        var loaded = ItemMasks.load(file);
        assertEquals(1, loaded.items.size());
        assertEquals(8, loaded.profile("minecraft:stick").strength);
        assertEquals(1, loaded.profile("minecraft:stick").textures.size());
        assertNull(loaded.profile("minecraft:stick").override.color);
        assertEquals(source, Files.readString(file));
    }

    @Test
    void corruptItemFileIsPreserved() throws Exception {
        var file = directory.resolve("items.json");
        Files.writeString(file, "{broken}");
        assertTrue(ItemMasks.load(file).items.isEmpty());
        assertEquals("{broken}", Files.readString(file));
    }
}
