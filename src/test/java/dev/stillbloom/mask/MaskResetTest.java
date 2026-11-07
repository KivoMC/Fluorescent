package dev.stillbloom.mask;

import static org.junit.jupiter.api.Assertions.*;

import dev.stillbloom.config.BloomOverride;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

class MaskResetTest {
    @Test
    void returningMasklessStyleToGlobalRetainsIndependentSourceStrength(@TempDir Path dir) {
        var masks = new TextureMasks();
        var p = new TextureMasks.Profile();
        p.strength = 7;
        p.override = new BloomOverride();
        p.override.color = 0x123456;
        masks.blocks.put("test:lamp", p);
        long before = masks.revision();
        masks.removeOverride("test:lamp");
        assertNull(masks.profile("test:lamp").override);
        assertEquals(7, masks.profile("test:lamp").strength);
        assertTrue(masks.revision() > before);
        Path file = dir.resolve("paint.json");
        assertTrue(masks.save(file));
        assertEquals(7, TextureMasks.load(file).profile("test:lamp").strength);
    }

    @Test
    void automaticThenMaskUndoPreservesStrengthIncludingAChangedCurrentValue() {
        var masks = new TextureMasks();
        var original = new TextureMasks.Mask(16, 16);
        original.set(2, 3, true);
        masks.put("test:lamp", "test:flame", original);
        masks.profile("test:lamp").strength = 8;
        var undo = original.copy();
        masks.reset("test:lamp", "test:flame");
        assertNull(masks.mask("test:lamp", "test:flame"));
        assertEquals(8, masks.profile("test:lamp").strength);
        masks.put("test:lamp", "test:flame", undo.copy());
        assertEquals(8, masks.profile("test:lamp").strength);
        assertTrue(masks.mask("test:lamp", "test:flame").get(2, 3));
        masks.reset("test:lamp", "test:flame");
        masks.profile("test:lamp").strength = 3;
        masks.put("test:lamp", "test:flame", undo.copy());
        assertEquals(
                3,
                masks.profile("test:lamp").strength,
                "Mask undo must not overwrite a newer independent strength edit");
    }

    @Test
    void styleResetKeepsPaintAndDefaultEmptyProfilesStillPrune() {
        var masks = new TextureMasks();
        var mask = new TextureMasks.Mask(16, 16);
        mask.set(4, 5, true);
        masks.put("test:lamp", "test:flame", mask);
        var profile = masks.profile("test:lamp");
        profile.strength = 2;
        profile.override = new BloomOverride();
        profile.override.intensity = 4f;
        masks.removeOverride("test:lamp");
        assertSame(mask, masks.mask("test:lamp", "test:flame"));
        assertNull(profile.override);
        assertEquals(2, profile.strength);
        var plain = new TextureMasks.Profile();
        plain.override = new BloomOverride();
        plain.override.color = 0x112233;
        masks.blocks.put("test:plain", plain);
        masks.removeOverride("test:plain");
        assertNull(masks.profile("test:plain"));
        masks.put("test:plain", "test:flame", new TextureMasks.Mask(16, 16));
        masks.reset("test:plain", "test:flame");
        assertNull(masks.profile("test:plain"));
    }
}
