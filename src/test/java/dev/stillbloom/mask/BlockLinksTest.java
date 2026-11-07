package dev.stillbloom.mask;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;

import dev.stillbloom.config.BloomConfig;
import dev.stillbloom.config.BloomOverride;
import dev.stillbloom.preset.SetupPresets;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.*;

class BlockLinksTest {
    static final String A = "minecraft:torch",
            B = "minecraft:wall_torch",
            C = "crystals:blue",
            D = "othermod:crystal",
            T = "minecraft:block/torch",
            U = "crystals:block/glow";
    @TempDir Path dir;

    TextureMasks.Mask paint(int x) {
        var mask = new TextureMasks.Mask(16, 16);
        mask.set(x, 3, true);
        return mask;
    }

    BloomOverride style() {
        var s = new BloomOverride();
        s.color = 0x123456;
        s.shape = BloomConfig.Shape.STARBURST;
        s.intensity = 3.5f;
        s.width = 91f;
        s.preserveDetail = .6f;
        s.flare = BloomConfig.Flare.CROSS;
        s.flareStrength = .4f;
        s.flareLength = 74f;
        return s;
    }

    @Test
    void automaticGroupSharesEverySupportedPropertyAndOnlyOneStoredConfiguration() {
        var m = new TextureMasks();
        assertTrue(BlockLinks.acceptAutomatic(m, A, List.of(A, B)));
        assertTrue(m.blocks.isEmpty());
        m.setOverride(A, style());
        m.ensureProfile(B).strength = 2.3f;
        m.changed();
        m.put(B, T, paint(4));
        assertSame(m.profile(A), m.profile(B));
        assertEquals(1, m.groups.size());
        assertEquals(2.3f, m.profile(A).strength);
        assertTrue(BloomOverride.sameValues(style(), m.profile(B).override));
        assertTrue(m.mask(A, T).get(4, 3));
        m.removeOverride(B);
        assertNull(m.profile(A).override);
        assertEquals(2.3f, m.profile(A).strength);
        assertNotNull(m.mask(A, T));
    }

    @Test
    void discoveryPreservesDifferentLegacyConfigurationsUntilSourceIsChosen() {
        var m = new TextureMasks();
        m.put(A, T, paint(1));
        m.put(B, T, paint(6));
        m.setOverride(B, style());
        long rev = m.revision();
        assertFalse(BlockLinks.acceptAutomatic(m, A, List.of(A, B)));
        assertEquals(rev, m.revision());
        assertTrue(BlockLinks.conflict(m, A, List.of(A, B)));
        assertTrue(m.mask(A, T).get(1, 3));
        assertTrue(m.mask(B, T).get(6, 3));
        BlockLinks.link(m, List.of(A, B), B, false, Map.of());
        assertTrue(m.mask(A, T).get(6, 3));
        assertTrue(BloomOverride.sameValues(style(), m.profile(A).override));
    }

    @Test
    void manualCrossModGroupSharesTexturePatternInBothDirectionsAndSurvivesRestart() {
        var m = new TextureMasks();
        m.put(A, T, paint(1));
        m.setOverride(A, style());
        BlockLinks.link(
                m,
                List.of(A, C),
                A,
                false,
                Map.of(
                        A,
                        List.of(new BlockLinks.Texture(T, 16, 16)),
                        C,
                        List.of(new BlockLinks.Texture(U, 16, 16))));
        assertSame(m.mask(A, T), m.mask(C, U));
        m.put(C, U, paint(7));
        assertTrue(m.mask(A, T).get(7, 3));
        var path = dir.resolve("links.json");
        assertTrue(m.save(path));
        var loaded = TextureMasks.load(path);
        assertEquals(List.of(C, A), BlockLinks.members(loaded, A));
        assertSame(loaded.profile(A), loaded.profile(C));
        assertSame(loaded.mask(A, T), loaded.mask(C, U));
        loaded.reset(C, U);
        assertNull(loaded.mask(A, T));
        assertNull(loaded.mask(C, U));
        assertTrue(BloomOverride.sameValues(style(), loaded.profile(C).override));
    }

    @Test
    void ambiguousOrDifferentSizeTexturesAreNeverGuessed() {
        var source = List.of(new BlockLinks.Texture(T, 16, 16));
        assertTrue(
                BlockLinks.textureLinks(
                                A,
                                List.of(A, C),
                                Map.of(A, source, C, List.of(new BlockLinks.Texture(U, 32, 32))))
                        .isEmpty());
        assertTrue(
                BlockLinks.textureLinks(
                                A,
                                List.of(A, C),
                                Map.of(
                                        A,
                                        source,
                                        C,
                                        List.of(
                                                new BlockLinks.Texture(U, 16, 16),
                                                new BlockLinks.Texture(
                                                        "crystals:block/frame", 16, 16))))
                        .isEmpty());
    }

    @Test
    void unlinkKeepsVisualsAndOptOutProtectsBothDirectionsUntilExplicitRestore() {
        var m = new TextureMasks();
        BlockLinks.acceptAutomatic(m, A, List.of(A, B));
        m.setOverride(A, style());
        m.put(B, T, paint(2));
        BlockLinks.unlink(m, List.of(B));
        assertNull(m.group(B));
        assertFalse(m.linksMasks(B));
        assertTrue(BlockLinks.same(m.profile(A), m.profile(B)));
        assertNotSame(m.profile(A), m.profile(B));
        assertFalse(BlockLinks.acceptAutomatic(m, A, List.of(A, B)));
        m.profile(A).override.color = 0;
        assertEquals(0x123456, m.profile(B).override.color);
        BlockLinks.restoreAutomatic(m, B);
        assertTrue(m.linksMasks(B));
        assertTrue(BlockLinks.conflict(m, B, List.of(A, B)));
    }

    @Test
    void mergingExpandsExistingGroupsAndWholeUnlinkKeepsAllMembers() {
        var m = new TextureMasks();
        BlockLinks.link(m, List.of(A, B), A, false, Map.of());
        m.setOverride(B, style());
        BlockLinks.link(m, List.of(C, D), C, false, Map.of());
        BlockLinks.link(m, List.of(B, D), B, false, Map.of());
        assertEquals(4, BlockLinks.members(m, A).size());
        assertEquals(1, m.groups.size());
        assertFalse(BlockLinks.acceptAutomatic(m, A, List.of(A, "minecraft:soul_torch")));
        assertEquals(4, BlockLinks.members(m, A).size());
        BlockLinks.unlinkGroup(m, C);
        assertTrue(m.groups.isEmpty());
        assertEquals(4, m.independentBlocks.size());
        for (var id : List.of(A, B, C, D))
            assertTrue(BloomOverride.sameValues(style(), m.profile(id).override));
    }

    @Test
    void bulkEditingAnyMemberIncludesGroupAndUndoRestoresStyleWithoutTouchingPaint() {
        var m = new TextureMasks();
        BlockLinks.link(m, List.of(A, B, C), A, false, Map.of());
        m.put(A, T, paint(3));
        var original = m.mask(A, T);
        var result =
                BulkOverrides.apply(m, List.of(B), style(), BulkOverrides.Part.STYLE, s -> true);
        assertEquals(3, result.changed());
        assertSame(m.profile(A), m.profile(C));
        assertSame(original, m.mask(B, T));
        assertEquals(3, result.undo().apply(m, s -> true));
        assertNull(m.profile(A).override);
        assertSame(original, m.mask(C, T));
        assertEquals(1, m.groups.size());
    }

    @Test
    void failedBulkSaveDoesNotChangeSharedConfiguration() {
        var m = new TextureMasks();
        BlockLinks.link(m, List.of(A, C), A, false, Map.of());
        var original = m.profile(A);
        long rev = m.revision();
        assertThrows(
                IllegalStateException.class,
                () ->
                        BulkOverrides.apply(
                                m, List.of(C), style(), BulkOverrides.Part.COLOR, s -> false));
        assertSame(original, m.profile(A));
        assertEquals(rev, m.revision());
        assertNull(m.profile(C).override);
    }

    @Test
    void syncOnlyTouchesMaskAndNoopDoesNotInvalidateRendering() {
        var m = new TextureMasks();
        BlockLinks.link(m, List.of(A, B), A, false, Map.of());
        m.put(A, T, paint(5));
        m.setOverride(B, style());
        long revision = m.revision();
        assertFalse(LinkedMaskEdits.sync(m, A, T, BlockLinks.members(m, A)));
        assertEquals(revision, m.revision());
        assertTrue(BloomOverride.sameValues(style(), m.profile(B).override));
    }

    @Test
    void maskUndoRestoresGroupAndRejectsChangedMembership() {
        var m = new TextureMasks();
        BlockLinks.link(m, List.of(A, B), A, false, Map.of());
        m.put(A, T, paint(1));
        var snapshot = LinkedMaskEdits.capture(m, T, BlockLinks.members(m, A));
        m.put(B, T, paint(2));
        assertTrue(snapshot.restore(m));
        assertTrue(m.mask(B, T).get(1, 3));
        BlockLinks.unlink(m, List.of(B));
        assertFalse(snapshot.restore(m));
    }

    @Test
    void automaticResourcePackSplitRetainsAppearanceAndManualMembership() {
        var m = new TextureMasks();
        BlockLinks.acceptAutomatic(m, A, List.of(A, B));
        m.put(A, T, paint(2));
        m.setOverride(A, style());
        BlockLinks.acceptAutomatic(m, A, List.of(A));
        assertNull(m.group(A));
        assertNull(m.group(B));
        assertTrue(BlockLinks.same(m.profile(A), m.profile(B)));
        assertTrue(m.linksMasks(B));
    }

    @Test
    void presetsKeepGroupsAbsentModIdsAliasesAndOptOuts() throws Exception {
        var m = new TextureMasks();
        m.put(A, T, paint(2));
        BlockLinks.link(
                m,
                List.of(A, C),
                A,
                false,
                Map.of(
                        A,
                        List.of(new BlockLinks.Texture(T, 16, 16)),
                        C,
                        List.of(new BlockLinks.Texture(U, 16, 16))));
        m.linkMasks(D, false);
        var library = SetupPresets.load(dir.resolve("presets.json"));
        var name = library.add("Linked", SetupPresets.capture(new BloomConfig(), m));
        var loaded = SetupPresets.parse(library.export(name)).setup().masks();
        assertEquals(1, loaded.groups.size());
        assertFalse(loaded.linksMasks(D));
        assertTrue(loaded.mask(C, U).get(2, 3));
        loaded.put(C, U, paint(8));
        assertTrue(loaded.mask(A, T).get(8, 3));
        assertTrue(m.mask(A, T).get(2, 3));
    }

    @Test
    void duplicateMembershipAndAliasCyclesInSharedPresetsAreRejected() throws Exception {
        var m = new TextureMasks();
        BlockLinks.link(m, List.of(A, B), A, false, Map.of());
        var library = SetupPresets.load(dir.resolve("presets.json"));
        var n = library.add("Test", SetupPresets.capture(new BloomConfig(), m));
        var tree = JsonParser.parseString(library.export(n)).getAsJsonObject();
        var groups =
                tree.getAsJsonObject("setup").getAsJsonObject("masks").getAsJsonObject("groups");
        groups.add("duplicate", groups.entrySet().iterator().next().getValue().deepCopy());
        assertThrows(IllegalArgumentException.class, () -> SetupPresets.parse(tree.toString()));
        groups.remove("duplicate");
        var aliases =
                groups.entrySet()
                        .iterator()
                        .next()
                        .getValue()
                        .getAsJsonObject()
                        .getAsJsonObject("textureLinks");
        aliases.addProperty(T, U);
        aliases.addProperty(U, T);
        assertThrows(IllegalArgumentException.class, () -> SetupPresets.parse(tree.toString()));
    }

    @Test
    void loadingLegacyFormatDoesNotMergeOrDropCustomMasks() throws Exception {
        var m = new TextureMasks();
        m.put(A, T, paint(1));
        m.put(B, T, paint(8));
        var path = dir.resolve("legacy.json");
        assertTrue(m.save(path));
        var tree = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
        tree.remove("groups");
        tree.remove("independentBlocks");
        Files.writeString(path, tree.toString());
        var loaded = TextureMasks.load(path);
        assertTrue(loaded.groups.isEmpty());
        assertTrue(loaded.mask(A, T).get(1, 3));
        assertTrue(loaded.mask(B, T).get(8, 3));
    }

    @Test
    void firstGroupSaveKeepsOriginalFileForDowngradesAndNeverOverwritesBackup() throws Exception {
        var m = new TextureMasks();
        m.put(A, T, paint(1));
        m.put(B, T, paint(8));
        var path = dir.resolve("masks.json");
        assertTrue(m.save(path));
        String original = Files.readString(path);
        BlockLinks.link(m, List.of(A, B), A, false, Map.of());
        assertTrue(m.save(path));
        var backup = dir.resolve("masks.json.pre-groups.bak");
        assertEquals(original, Files.readString(backup));
        m.put(B, T, paint(5));
        assertTrue(m.save(path));
        assertEquals(original, Files.readString(backup));
        assertTrue(TextureMasks.load(backup).mask(B, T).get(8, 3));
    }
}
