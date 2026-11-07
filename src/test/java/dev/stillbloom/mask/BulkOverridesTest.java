package dev.stillbloom.mask;

import static org.junit.jupiter.api.Assertions.*;

import dev.stillbloom.config.BloomConfig;
import dev.stillbloom.config.BloomOverride;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

class BulkOverridesTest {
    private static TextureMasks.Profile profile(TextureMasks masks, String id) {
        return masks.blocks.computeIfAbsent(id, k -> new TextureMasks.Profile());
    }

    @Test
    void colorBatchPreservesOtherFieldsMasksStrengthAndUnrelatedProfilesWithOneSave() {
        var masks = new TextureMasks();
        var a = profile(masks, "a");
        var b = profile(masks, "b");
        var untouched = profile(masks, "untouched");
        a.strength = .35f;
        a.override = new BloomOverride();
        a.override.shape = BloomConfig.Shape.STARBURST;
        a.override.intensity = 3f;
        a.override.width = 128f;
        a.override.flare = BloomConfig.Flare.CROSS;
        a.override.flareStrength = .6f;
        a.override.flareLength = 80f;
        a.override.preserveDetail = .75f;
        var mask = new TextureMasks.Mask(16, 16);
        mask.set(4, 7, true);
        a.textures.put("flame", mask);
        b.override = new BloomOverride();
        b.override.width = 55f;
        var source = new BloomOverride();
        source.color = 0xAADDFF;
        source.shape = BloomConfig.Shape.DIAMOND;
        var saves = new AtomicInteger();
        long revision = masks.revision();
        var result =
                BulkOverrides.apply(
                        masks,
                        List.of("a", "b", "a"),
                        source,
                        BulkOverrides.Part.COLOR,
                        staged -> {
                            saves.incrementAndGet();
                            assertSame(a, masks.profile("a"));
                            assertNull(a.override.color);
                            assertEquals(0xAADDFF, staged.profile("a").override.color);
                            return true;
                        });
        assertEquals(2, result.changed());
        assertEquals(1, saves.get());
        assertEquals(revision + 1, masks.revision());
        var changed = masks.profile("a");
        assertSame(a.textures, changed.textures);
        assertSame(mask, changed.textures.get("flame"));
        assertEquals(.35f, changed.strength);
        assertEquals(BloomConfig.Shape.STARBURST, changed.override.shape);
        assertEquals(3f, changed.override.intensity);
        assertEquals(128f, changed.override.width);
        assertEquals(BloomConfig.Flare.CROSS, changed.override.flare);
        assertEquals(.6f, changed.override.flareStrength);
        assertEquals(80f, changed.override.flareLength);
        assertEquals(.75f, changed.override.preserveDetail);
        assertEquals(55f, masks.profile("b").override.width);
        assertSame(untouched, masks.profile("untouched"));
        assertNotSame(source, changed.override);
        assertNotSame(changed.override, masks.profile("b").override);
        source.color = 0;
        changed.override.color = 1;
        assertEquals(0xAADDFF, masks.profile("b").override.color);
    }

    @Test
    void shapeAndFullStyleKeepInheritanceAndNaturalColor() {
        var masks = new TextureMasks();
        var p = profile(masks, "a");
        p.override = new BloomOverride();
        p.override.shape = BloomConfig.Shape.SQUARE;
        p.override.color = 0x123456;
        p.override.intensity = 2f;
        var source = new BloomOverride();
        source.color = 0xABCDEF;
        BulkOverrides.apply(masks, List.of("a"), source, BulkOverrides.Part.SHAPE, m -> true);
        assertNull(masks.profile("a").override.shape);
        assertEquals(0x123456, masks.profile("a").override.color);
        assertEquals(2f, masks.profile("a").override.intensity);
        BulkOverrides.apply(
                masks, List.of("a"), new BloomOverride(), BulkOverrides.Part.COLOR, m -> true);
        assertNull(masks.profile("a").override.color);
        assertEquals(2f, masks.profile("a").override.intensity);
        source.preserveDetail = null;
        source.width = 80f;
        BulkOverrides.apply(masks, List.of("a", "b"), source, BulkOverrides.Part.STYLE, m -> true);
        var a = masks.profile("a").override;
        var b = masks.profile("b").override;
        assertNull(a.shape);
        assertNull(a.intensity);
        assertNull(a.preserveDetail);
        assertNull(a.flare);
        assertEquals(80f, a.width);
        assertNotSame(a, b);
        assertNotSame(a, source);
        source.width = 100f;
        assertEquals(80f, a.width);
    }

    @Test
    void noopsAndEmptySelectionDoNotSaveOrInvalidate() {
        var masks = new TextureMasks();
        var saves = new AtomicInteger();
        var empty =
                BulkOverrides.apply(
                        masks,
                        List.of(),
                        new BloomOverride(),
                        BulkOverrides.Part.STYLE,
                        m -> {
                            saves.incrementAndGet();
                            return true;
                        });
        var same =
                BulkOverrides.apply(
                        masks,
                        List.of("automatic"),
                        new BloomOverride(),
                        BulkOverrides.Part.COLOR,
                        m -> {
                            saves.incrementAndGet();
                            return true;
                        });
        assertEquals(0, empty.changed());
        assertEquals(0, same.changed());
        assertNull(same.undo());
        assertEquals(0, saves.get());
        assertEquals(0, masks.revision());
        assertTrue(masks.blocks.isEmpty());
    }

    @Test
    void failedSaveLeavesLiveMapProfilesAndRevisionUntouchedAndAllowsRetry() {
        var masks = new TextureMasks();
        var original = profile(masks, "a");
        original.override = new BloomOverride();
        original.override.color = 3;
        var oldMap = masks.blocks;
        var style = new BloomOverride();
        style.color = 5;
        var calls = new AtomicInteger();
        assertThrows(
                IllegalStateException.class,
                () ->
                        BulkOverrides.apply(
                                masks,
                                List.of("a", "b"),
                                style,
                                BulkOverrides.Part.COLOR,
                                m -> {
                                    calls.incrementAndGet();
                                    return false;
                                }));
        assertEquals(1, calls.get());
        assertSame(oldMap, masks.blocks);
        assertSame(original, masks.profile("a"));
        assertEquals(3, original.override.color);
        assertNull(masks.profile("b"));
        assertEquals(0, masks.revision());
        assertEquals(
                2,
                BulkOverrides.apply(
                                masks,
                                List.of("a", "b"),
                                style,
                                BulkOverrides.Part.COLOR,
                                m -> true)
                        .changed());
    }

    @Test
    void undoSavesOnceAndRestoresOnlyStylesIncludingMissingProfiles() {
        var masks = new TextureMasks();
        var a = profile(masks, "a");
        a.strength = .2f;
        a.override = new BloomOverride();
        a.override.color = 7;
        var mask = new TextureMasks.Mask(2, 2);
        a.textures.put("flame", mask);
        var source = new BloomOverride();
        source.color = 5;
        var result =
                BulkOverrides.apply(
                        masks, List.of("a", "new"), source, BulkOverrides.Part.COLOR, m -> true);
        long revision = masks.revision();
        assertTrue(result.undo().available(masks));
        var calls = new AtomicInteger();
        assertEquals(
                2,
                result.undo()
                        .apply(
                                masks,
                                m -> {
                                    calls.incrementAndGet();
                                    return true;
                                }));
        assertEquals(1, calls.get());
        assertEquals(revision + 1, masks.revision());
        assertEquals(7, masks.profile("a").override.color);
        assertSame(mask, masks.mask("a", "flame"));
        assertEquals(.2f, masks.profile("a").strength);
        assertNull(masks.profile("new"));
        assertFalse(result.undo().available(masks));
    }

    @Test
    void undoRejectsLaterEditsAndOwnerReplacementAndDoesNotConsumeOnSaveFailure() {
        var masks = new TextureMasks();
        var source = new BloomOverride();
        source.color = 6;
        var undo =
                BulkOverrides.apply(
                                masks, List.of("a"), source, BulkOverrides.Part.COLOR, m -> true)
                        .undo();
        assertFalse(undo.available(new TextureMasks()));
        assertThrows(IllegalStateException.class, () -> undo.apply(masks, m -> false));
        assertTrue(undo.available(masks));
        assertEquals(1, masks.revision());
        masks.profile("a").override.color = 9;
        assertFalse(undo.available(masks));
        assertThrows(IllegalStateException.class, () -> undo.apply(masks, m -> true));
        assertEquals(9, masks.profile("a").override.color);
        var second =
                BulkOverrides.apply(
                                masks, List.of("a"), source, BulkOverrides.Part.COLOR, m -> true)
                        .undo();
        masks.changed();
        assertFalse(second.available(masks));
    }

    @Test
    void realPersistenceRetainsPaintedMasksAndCanUndo(@TempDir Path dir) {
        var masks = new TextureMasks();
        var p = profile(masks, "minecraft:campfire");
        p.strength = .6f;
        var painted = new TextureMasks.Mask(16, 16);
        painted.set(3, 4, true);
        p.textures.put("fire", painted);
        p.override = new BloomOverride();
        p.override.width = 90f;
        var style = new BloomOverride();
        style.color = 0xFFA100;
        Path path = dir.resolve("masks.json");
        var undo =
                BulkOverrides.apply(
                                masks,
                                List.of("minecraft:campfire", "minecraft:torch"),
                                style,
                                BulkOverrides.Part.COLOR,
                                m -> m.save(path))
                        .undo();
        var loaded = TextureMasks.load(path);
        assertTrue(loaded.mask("minecraft:campfire", "fire").get(3, 4));
        assertEquals(.6f, loaded.profile("minecraft:campfire").strength);
        assertEquals(90f, loaded.profile("minecraft:campfire").override.width);
        assertEquals(0xFFA100, loaded.profile("minecraft:torch").override.color);
        undo.apply(masks, m -> m.save(path));
        loaded = TextureMasks.load(path);
        assertNull(loaded.profile("minecraft:torch"));
        assertNull(loaded.profile("minecraft:campfire").override.color);
    }

    @Test
    void clearingOverrideRetainsNondefaultSourceStrength() {
        var masks = new TextureMasks();
        var p = profile(masks, "a");
        p.strength = .4f;
        p.override = new BloomOverride();
        p.override.color = 1;
        BulkOverrides.apply(
                masks, List.of("a"), new BloomOverride(), BulkOverrides.Part.STYLE, m -> true);
        assertNotNull(masks.profile("a"));
        assertEquals(.4f, masks.profile("a").strength);
        assertNull(masks.profile("a").override);
    }
}
