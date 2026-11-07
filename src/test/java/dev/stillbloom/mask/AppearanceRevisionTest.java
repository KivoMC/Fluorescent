package dev.stillbloom.mask;

import static org.junit.jupiter.api.Assertions.*;

import dev.stillbloom.config.BloomConfig;
import dev.stillbloom.config.BloomOverride;
import dev.stillbloom.model.ItemAppearance;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

class AppearanceRevisionTest {
    private static final String TORCH = "minecraft:torch",
            WALL = "minecraft:wall_torch",
            TEXTURE = "minecraft:block/torch";

    @Test
    void colorShapeAndResetKeepBakedPaintValid() {
        var masks = new TextureMasks();
        var paint = new TextureMasks.Mask(16, 16);
        paint.set(3, 2, true);
        masks.put(TORCH, TEXTURE, paint);
        long geometry = masks.geometryRevision(), appearance = masks.revision();
        var style = new BloomOverride();
        style.color = 0x00FF00;
        masks.setOverride(TORCH, style);
        assertTrue(masks.revision() > appearance);
        assertEquals(geometry, masks.geometryRevision());
        style.shape = BloomConfig.Shape.STARBURST;
        masks.setOverride(TORCH, style);
        assertEquals(BloomConfig.Shape.STARBURST, masks.style(TORCH, new BloomConfig()).shape());
        masks.removeOverride(TORCH);
        assertEquals(geometry, masks.geometryRevision());
        assertSame(paint, masks.mask(TORCH, TEXTURE));
        assertTrue(paint.get(3, 2));
        masks.reset(TORCH, TEXTURE);
        assertTrue(masks.geometryRevision() > geometry);
    }

    @Test
    void bulkStyleAndUndoKeepLinkedPaintAndRefreshResolvedProfiles() {
        var masks = new TextureMasks();
        var paint = new TextureMasks.Mask(16, 16);
        paint.set(3, 2, true);
        masks.put(TORCH, TEXTURE, paint);
        BlockLinks.link(masks, List.of(TORCH, WALL), TORCH, false, Map.of());
        var original = masks.mask(WALL, TEXTURE);
        long geometry = masks.geometryRevision();
        var style = new BloomOverride();
        style.color = 0x00FF00;
        var result =
                BulkOverrides.apply(
                        masks, List.of(TORCH), style, BulkOverrides.Part.COLOR, staged -> true);
        assertEquals(geometry, masks.geometryRevision());
        assertEquals(0x00FF00, masks.profile(WALL).override.color);
        assertSame(original, masks.mask(WALL, TEXTURE));
        result.undo().apply(masks, staged -> true);
        assertEquals(geometry, masks.geometryRevision());
        assertNull(masks.profile(WALL).override);
        assertSame(original, masks.mask(WALL, TEXTURE));
        masks.put(WALL, TEXTURE, new TextureMasks.Mask(16, 16));
        assertTrue(masks.geometryRevision() > geometry);
    }

    @Test
    void heldAppearanceChangesPreserveMaskUploadsButPaintingExpiresThem() {
        var items = new ItemMasks();
        var paint = new TextureMasks.Mask(16, 16);
        items.put(TORCH, TEXTURE, paint);
        long geometry = items.geometryRevision();
        var style = new BloomOverride();
        style.color = 0x00FF00;
        items.profile(TORCH).override = style;
        items.stylesChanged();
        assertEquals(
                0x00FF00,
                ItemAppearance.resolve(new BloomConfig(), null, items.profile(TORCH)).color());
        items.removeOverride(TORCH);
        assertEquals(geometry, items.geometryRevision());
        assertSame(paint, items.mask(TORCH, TEXTURE));
        paint.set(3, 2, true);
        items.changed();
        assertTrue(items.geometryRevision() > geometry);
    }
}
