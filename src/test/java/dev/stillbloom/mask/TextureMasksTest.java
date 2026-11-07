package dev.stillbloom.mask;

import static org.junit.jupiter.api.Assertions.*;

import dev.stillbloom.config.BloomOverride;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

class TextureMasksTest {
    @TempDir Path directory;

    @Test
    void brushEditsStayInsideTextureAndUndoCopiesAreIndependent() {
        var m = new TextureMasks.Mask(17, 9);
        m.set(16, 8, true);
        m.set(0, 0, true);
        m.set(-1, 0, true);
        m.set(17, 8, true);
        assertTrue(m.get(16, 8));
        assertTrue(m.get(0, 0));
        assertEquals(2, java.util.Arrays.stream(m.words).map(Integer::bitCount).sum());
        var undo = m.copy();
        m.set(16, 8, false);
        assertTrue(undo.get(16, 8));
        assertFalse(m.get(16, 8));
    }

    @Test
    void masksPersistPerBlockEvenWhenBlocksShareATexture() {
        var masks = new TextureMasks();
        var first = new TextureMasks.Mask(16, 16);
        first.set(3, 5, true);
        masks.put("test:a", "test:shared", first);
        masks.put("test:b", "test:shared", new TextureMasks.Mask(16, 16));
        masks.profile("test:a").strength = 2.5f;
        Path file = directory.resolve("masks.json");
        masks.save(file);
        var loaded = TextureMasks.load(file);
        assertTrue(loaded.mask("test:a", "test:shared").get(3, 5));
        assertFalse(loaded.mask("test:b", "test:shared").get(3, 5));
        assertEquals(2.5f, loaded.profile("test:a").strength);
        loaded.reset("test:a", "test:shared");
        assertNull(loaded.mask("test:a", "test:shared"));
        assertEquals(2.5f, loaded.profile("test:a").strength);
        assertNotNull(loaded.profile("test:b"));
    }

    @Test
    void emptyMaskAndMissingMaskRemainDistinctStoredStates() {
        var masks = new TextureMasks();
        assertNull(masks.mask("test:a", "test:t"));
        masks.put("test:a", "test:t", new TextureMasks.Mask(16, 16));
        assertNotNull(masks.mask("test:a", "test:t"));
        assertFalse(masks.mask("test:a", "test:t").get(0, 0));
        masks.reset("test:a", "test:t");
        assertNull(masks.mask("test:a", "test:t"));
    }

    @Test
    void sourceNeedsActualPaintAndPositiveStrengthEvenWithAnOverride() {
        var profile = new TextureMasks.Profile();
        profile.override = new BloomOverride();
        profile.override.intensity = 8f;
        assertFalse(profile.hasEmission());
        var paint = new TextureMasks.Mask(16, 16);
        profile.textures.put("test:glow", paint);
        assertFalse(profile.hasEmission());
        paint.set(15, 15, true);
        assertTrue(profile.hasEmission());
        for (float strength : new float[] {0, -1, Float.NaN, Float.POSITIVE_INFINITY}) {
            profile.strength = strength;
            assertFalse(profile.hasEmission());
        }
        profile.strength = 2;
        assertTrue(profile.hasEmission());
        paint.set(15, 15, false);
        assertFalse(profile.hasEmission());
    }

    @Test
    void paddingBitsCannotTurnAnEmptyMaskIntoAnEmitter() {
        var paint = new TextureMasks.Mask(3, 3);
        paint.words[0] = ~511;
        assertTrue(paint.valid());
        assertFalse(paint.hasPaint());
        paint.set(2, 2, true);
        assertTrue(paint.hasPaint());
        var aligned = new TextureMasks.Mask(8, 4);
        aligned.set(7, 3, true);
        assertTrue(aligned.hasPaint());
        aligned.words = null;
        assertFalse(aligned.hasPaint());
    }

    @Test
    void savedMasksKeepEmissionButRemovingTheMaskDoesNotRestoreAutomaticBloom() {
        var masks = new TextureMasks();
        var paint = new TextureMasks.Mask(16, 16);
        paint.set(3, 5, true);
        masks.put("test:light", "test:glow", paint);
        masks.profile("test:light").strength = 2.5f;
        Path path = directory.resolve("masked-sources.json");
        assertTrue(masks.save(path));
        var loaded = TextureMasks.load(path);
        assertTrue(loaded.profile("test:light").hasEmission());
        loaded.reset("test:light", "test:glow");
        assertFalse(loaded.profile("test:light").hasEmission());
        assertEquals(2.5f, loaded.profile("test:light").strength);
        loaded.put("test:light", "test:glow", paint.copy());
        assertTrue(loaded.profile("test:light").hasEmission());
        assertEquals(2.5f, loaded.profile("test:light").strength);
    }
}
