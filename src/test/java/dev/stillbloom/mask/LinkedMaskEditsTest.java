package dev.stillbloom.mask;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;

import dev.stillbloom.config.BloomConfig;
import dev.stillbloom.config.BloomOverride;
import dev.stillbloom.preset.SetupPresets;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.*;

class LinkedMaskEditsTest {
    @TempDir Path directory;
    private static final String A = "minecraft:torch",
            B = "minecraft:wall_torch",
            T = "minecraft:block/torch";

    private TextureMasks.Mask paint(int x) {
        var m = new TextureMasks.Mask(16, 16);
        m.set(x, 3, true);
        return m;
    }

    @Test
    void linkedPaintCopiesOnlyTheSelectedTextureAndKeepsIndependentAppearance() {
        var masks = new TextureMasks();
        masks.put(A, T, paint(1));
        masks.put(B, T, paint(8));
        var wall = masks.profile(B);
        wall.strength = 3;
        wall.override = new BloomOverride();
        wall.override.color = 0x123456;
        var other = paint(5);
        wall.textures.put("pack:other", other);
        LinkedMaskEdits.sync(masks, A, T, LinkedMaskEdits.targets(masks, A, List.of(A, B)));
        assertTrue(masks.mask(B, T).get(1, 3));
        assertFalse(masks.mask(B, T).get(8, 3));
        assertNotSame(masks.mask(A, T), masks.mask(B, T));
        masks.mask(A, T).set(2, 3, true);
        assertFalse(masks.mask(B, T).get(2, 3));
        assertSame(other, wall.textures.get("pack:other"));
        assertEquals(3, wall.strength);
        assertEquals(0x123456, wall.override.color);
    }

    @Test
    void undoRestoresDifferentMasksAndAutomaticModeAcrossTheWholeEdit() {
        var masks = new TextureMasks();
        masks.put(A, T, paint(1));
        masks.put(B, T, paint(8));
        String extra = "test:torch";
        var targets = List.of(A, B, extra);
        var snapshot = LinkedMaskEdits.capture(masks, T, targets);
        masks.put(A, T, paint(4));
        LinkedMaskEdits.sync(masks, A, T, targets);
        assertNotNull(masks.mask(extra, T));
        assertTrue(snapshot.restore(masks));
        assertTrue(masks.mask(A, T).get(1, 3));
        assertTrue(masks.mask(B, T).get(8, 3));
        assertNull(masks.profile(extra));
        assertFalse(snapshot.restore(new TextureMasks()));
    }

    @Test
    void emptyMaskStaysDifferentFromAutomaticAndResetKeepsStrengthAndStyle() {
        var masks = new TextureMasks();
        masks.put(A, T, new TextureMasks.Mask(16, 16));
        LinkedMaskEdits.sync(masks, A, T, List.of(A, B));
        assertNotNull(masks.mask(B, T));
        masks.profile(B).strength = 2;
        var style = new BloomOverride();
        style.color = 0xFF0000;
        masks.profile(B).override = style;
        masks.reset(A, T);
        LinkedMaskEdits.sync(masks, A, T, List.of(A, B));
        assertNull(masks.mask(B, T));
        assertEquals(2, masks.profile(B).strength);
        assertSame(style, masks.profile(B).override);
    }

    @Test
    void optingOutIsSymmetricAndDoesNotInvalidateRendering() {
        var masks = new TextureMasks();
        long revision = masks.revision();
        masks.linkMasks(B, false);
        assertEquals(List.of(A), LinkedMaskEdits.targets(masks, A, List.of(A, B)));
        assertEquals(List.of(B), LinkedMaskEdits.targets(masks, B, List.of(A, B)));
        assertEquals(revision, masks.revision());
        masks.linkMasks(B, true);
        assertEquals(List.of(A, B), LinkedMaskEdits.targets(masks, A, List.of(A, B)));
    }

    @Test
    void linkPreferenceSurvivesSaveLoadAndLegacyFilesKeepExistingPaint() throws Exception {
        var masks = new TextureMasks();
        masks.put(A, T, paint(1));
        masks.put(B, T, paint(8));
        masks.linkMasks(B, false);
        var path = directory.resolve("masks.json");
        assertTrue(masks.save(path));
        var loaded = TextureMasks.load(path);
        assertFalse(loaded.linksMasks(B));
        assertTrue(loaded.linksMasks(A));
        var tree = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
        tree.remove("independentBlocks");
        Files.writeString(path, tree.toString());
        loaded = TextureMasks.load(path);
        assertTrue(loaded.linksMasks(B));
        assertTrue(loaded.mask(A, T).get(1, 3));
        assertTrue(loaded.mask(B, T).get(8, 3));
    }

    @Test
    void presetsMigrateOldFilesAndRoundTripLinkPreferences() {
        var masks = new TextureMasks();
        masks.linkMasks(B, false);
        var setup = SetupPresets.capture(new BloomConfig(), masks);
        assertFalse(setup.masks().linksMasks(B));
        setup.masks().linkMasks(B, true);
        assertFalse(masks.linksMasks(B));
    }

    @Test
    void legacySharedPresetsMigrateWithoutRewritingMasks() throws Exception {
        var masks = new TextureMasks();
        masks.put(A, T, paint(1));
        masks.put(B, T, paint(8));
        var library = SetupPresets.load(directory.resolve("setups.json"));
        var name = library.add("Old", SetupPresets.capture(new BloomConfig(), masks));
        var json = JsonParser.parseString(library.export(name)).getAsJsonObject();
        json.getAsJsonObject("setup").getAsJsonObject("masks").remove("independentBlocks");
        var copy = SetupPresets.parse(json.toString()).setup().masks();
        assertTrue(copy.linksMasks(B));
        assertTrue(copy.mask(A, T).get(1, 3));
        assertTrue(copy.mask(B, T).get(8, 3));
    }

    @Test
    void malformedLinkPreferencesInSharedPresetsAreRejected() throws Exception {
        var library = SetupPresets.load(directory.resolve("setups.json"));
        var name = library.add("Test", SetupPresets.capture(new BloomConfig(), new TextureMasks()));
        var json = JsonParser.parseString(library.export(name)).getAsJsonObject();
        var masks = json.getAsJsonObject("setup").getAsJsonObject("masks");
        masks.add("independentBlocks", com.google.gson.JsonNull.INSTANCE);
        assertThrows(IllegalArgumentException.class, () -> SetupPresets.parse(json.toString()));
        var invalid = new com.google.gson.JsonArray();
        invalid.add("not a block id");
        masks.add("independentBlocks", invalid);
        assertThrows(IllegalArgumentException.class, () -> SetupPresets.parse(json.toString()));
    }

    @Test
    void bulkStyleEditsAndUndoKeepLinkPreferencesInTheSavedSnapshot() {
        var masks = new TextureMasks();
        masks.linkMasks(B, false);
        var style = new BloomOverride();
        style.color = 0xabcdef;
        var result =
                BulkOverrides.apply(
                        masks,
                        List.of(A, B),
                        style,
                        BulkOverrides.Part.COLOR,
                        staged -> {
                            assertFalse(staged.linksMasks(B));
                            return true;
                        });
        assertFalse(masks.linksMasks(B));
        result.undo()
                .apply(
                        masks,
                        staged -> {
                            assertFalse(staged.linksMasks(B));
                            return true;
                        });
        assertFalse(masks.linksMasks(B));
    }
}
