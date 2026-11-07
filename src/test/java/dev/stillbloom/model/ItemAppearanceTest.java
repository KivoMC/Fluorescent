package dev.stillbloom.model;

import static org.junit.jupiter.api.Assertions.*;

import dev.stillbloom.config.BloomConfig;
import dev.stillbloom.config.BloomOverride;
import dev.stillbloom.mask.TextureMasks;

import org.junit.jupiter.api.Test;

class ItemAppearanceTest {
    @Test
    void fieldsInheritIndependentlyWithoutChangingBlockProfile() {
        var global = new BloomConfig();
        var block = new TextureMasks.Profile();
        block.override = new BloomOverride();
        block.override.shape = BloomConfig.Shape.DIAMOND;
        block.override.color = 0x123456;
        block.override.intensity = 2f;
        block.override.preserveDetail = .9f;
        var item = new TextureMasks.Profile();
        item.override = new BloomOverride();
        item.override.width = 72f;
        item.override.color = 0xABCDEF;
        var resolved = ItemAppearance.resolve(global, block, item);
        assertEquals(BloomConfig.Shape.DIAMOND, resolved.style().shape());
        assertEquals(2, resolved.style().intensity());
        assertEquals(72, resolved.style().width());
        assertEquals(0xABCDEF, resolved.color());
        assertEquals(.9f, resolved.detail());
        assertNull(block.override.width);
        assertEquals(0x123456, block.override.color);
        item.override = null;
        resolved = ItemAppearance.resolve(global, block, item);
        assertEquals(0x123456, resolved.color());
        assertEquals(global.glowWidth, resolved.style().width());
    }

    @Test
    void emptyItemProfilesPreservePreviousAppearanceAndZeroOverrides() {
        var global = new BloomConfig();
        var block = new TextureMasks.Profile();
        block.override = new BloomOverride();
        block.override.flare = BloomConfig.Flare.CROSS;
        block.override.flareStrength = 1f;
        block.override.color = 0x123456;
        var inherited = ItemAppearance.resolve(global, block, new TextureMasks.Profile());
        assertEquals(block.override.resolve(global), inherited.style());
        assertEquals(0x123456, inherited.color());
        assertEquals(global.preserveDetail, inherited.detail());
        var item = new TextureMasks.Profile();
        item.override = new BloomOverride();
        item.override.intensity = 0f;
        item.override.color = 0;
        item.override.preserveDetail = 0f;
        var resolved = ItemAppearance.resolve(global, block, item);
        assertEquals(0, resolved.style().intensity());
        assertEquals(0, resolved.color());
        assertEquals(0, resolved.detail());
        assertEquals(BloomConfig.Flare.CROSS, resolved.style().flare());
    }
}
