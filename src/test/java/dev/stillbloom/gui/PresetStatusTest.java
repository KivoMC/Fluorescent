package dev.stillbloom.gui;

import static org.junit.jupiter.api.Assertions.*;

import dev.stillbloom.config.BloomConfig;
import dev.stillbloom.config.BloomOverride;
import dev.stillbloom.mask.ItemMasks;
import dev.stillbloom.mask.TextureMasks;
import dev.stillbloom.preset.BuiltinPresets;
import dev.stillbloom.preset.PresetFixtures;
import dev.stillbloom.preset.SetupPresets;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

class PresetStatusTest {
    private static SetupPresets.Setup sample() {
        var config = new BloomConfig();
        var blocks = new TextureMasks();
        var mask = new TextureMasks.Mask(16, 16);
        mask.set(2, 3, true);
        blocks.put("minecraft:torch", "minecraft:block/torch", mask);
        var items = new ItemMasks();
        var item = new TextureMasks.Mask(16, 16);
        item.set(4, 5, true);
        items.put("minecraft:torch", "minecraft:item/torch", item);
        return new SetupPresets.Setup(config, blocks, items);
    }

    @Test
    void unknownSessionDoesNotInferThatAnyPresetWasApplied() {
        var session = new PresetStatus.Session();
        assertEquals("Custom look", session.status());
        session.changed();
        session.refresh(sample());
        assertEquals("Custom look", session.status());
        assertFalse(session.hasUndo());
    }

    @Test
    void wholeResultCheckpointIsDetachedAndMasterSwitchDoesNotModifyIt() {
        var setup = sample();
        var session = new PresetStatus.Session();
        session.applied("Ember", setup);
        setup.settings().enabled = false;
        session.changed();
        session.refresh(setup);
        assertEquals("Ember - applied", session.status());
        setup.settings().smoothBlend = .5f;
        session.changed();
        session.refresh(setup);
        assertEquals("Ember - modified", session.status());
        setup.settings().smoothBlend = 0;
        session.refresh(setup);
        assertEquals("Ember - applied", session.status());
        setup.settings().heldItemRange = 48;
        session.refresh(setup);
        assertEquals("Ember - modified", session.status());
    }

    @Test
    void blockStylePaintAndItemPaintEachCountAsLaterCustomization() {
        for (int kind = 0; kind < 3; kind++) {
            var setup = sample();
            var session = new PresetStatus.Session();
            session.applied("Saved", setup);
            if (kind == 0) {
                var profile = setup.masks().profile("minecraft:torch");
                profile.override = new BloomOverride();
                profile.override.color = 0x00FF00;
            }
            if (kind == 1)
                setup.masks().mask("minecraft:torch", "minecraft:block/torch").set(7, 8, true);
            if (kind == 2)
                setup.itemMasks().mask("minecraft:torch", "minecraft:item/torch").set(7, 8, true);
            session.changed();
            session.refresh(setup);
            assertEquals("Saved - modified", session.status());
        }
    }

    @Test
    void transientMaskRevisionsAndRepeatedStatusReadsDoNotChangeAppliedStatus() {
        var setup = sample();
        var session = new PresetStatus.Session();
        session.applied("Natural", setup);
        setup.masks().changed();
        setup.itemMasks().changed();
        session.changed();
        session.refresh(setup);
        assertEquals("Natural - applied", session.status());
        setup.settings().intensity = 7;
        session.changed();
        // I update the cheap status label immediately and compare full setups only on explicit menu
        // actions.
        for (int i = 0; i < 20; i++) assertEquals("Natural - modified", session.status());
        session.refresh(setup);
        assertEquals("Natural - modified", session.status());
        setup.settings().intensity = 3.2f;
        session.changed();
        assertEquals("Natural - modified", session.status());
        session.refresh(setup);
        assertEquals("Natural - applied", session.status());
    }

    @Test
    void previewCancellationRestoresThePreviousAppliedAndModifiedCheckpoint() {
        var setup = sample();
        var session = new PresetStatus.Session();
        session.applied("Original", setup);
        setup.settings().intensity = 5;
        session.changed();
        session.refresh(setup);
        var checkpoint = session.remember();
        var candidate = sample();
        candidate.settings().intensity = 7;
        session.applied("Trial", candidate);
        session.restore(checkpoint);
        assertEquals("Original - modified", session.status());
        session.refresh(setup);
        assertEquals("Original - modified", session.status());
        setup.settings().intensity = 3.2f;
        session.refresh(setup);
        assertEquals("Original - applied", session.status());
    }

    @Test
    void undoDataAndStatusSurviveNavigationAndAreReturnedAsDetachedCopies() {
        var setup = sample();
        var session = new PresetStatus.Session();
        session.applied("Original", setup);
        var status = session.remember();
        session.recordUndo(setup, status);
        setup.settings().intensity = 7;
        setup.masks().blocks.clear();
        session.applied("Second", setup);
        assertTrue(session.hasUndo());
        var first = session.undo();
        assertEquals(3.2f, first.setup().settings().intensity);
        assertEquals(1, first.setup().masks().blocks.size());
        first.setup().masks().blocks.clear();
        first.setup().settings().intensity = 0;
        var fresh = session.undo();
        assertEquals(1, fresh.setup().masks().blocks.size());
        assertEquals(3.2f, fresh.setup().settings().intensity);
        session.restore(fresh.status());
        session.refresh(fresh.setup());
        assertEquals("Original - applied", session.status());
        session.clearUndo();
        assertFalse(session.hasUndo());
    }

    @Test
    void renamingTheAppliedSavedEntryPreservesItsCheckpoint() {
        var setup = sample();
        var session = new PresetStatus.Session();
        session.applied("My presets / Old", setup);
        session.rename("My presets / Other", "My presets / New");
        assertEquals("My presets / Old - applied", session.status());
        session.rename("My presets / Old", "My presets / New");
        session.refresh(setup);
        assertEquals("My presets / New - applied", session.status());
    }

    @Test
    void builtInCatalogRemainsSeparateFromCreateRenameImportDeleteAndUndo(@TempDir Path dir)
            throws Exception {
        var library = SetupPresets.load(dir.resolve("looks.json"));
        assertTrue(library.names().isEmpty());
        var before = PresetFixtures.savedSetup(0);
        var chosen = PresetFixtures.savedSetup(3);
        String name = library.add("Natural", chosen);
        String renamed = library.rename(name, "My glow");
        assertEquals(List.of("My glow"), library.names());
        var imported = SetupPresets.load(dir.resolve("friend.json"));
        assertEquals("My glow", imported.importCode(library.export(renamed)));
        PresetFixtures.assertSameSettings(chosen.settings(), imported.get("My glow").settings());
        var session = new PresetStatus.Session();
        session.applied("Before", before);
        var status = session.remember();
        session.recordUndo(before, status);
        session.applied("My presets / My glow", library.get(renamed));
        library.delete(renamed);
        assertTrue(SetupPresets.load(dir.resolve("looks.json")).names().isEmpty());
        assertTrue(session.hasUndo());
        var undo = session.undo();
        PresetFixtures.assertSameSettings(before.settings(), undo.setup().settings());
        assertTrue(
                undo.setup()
                        .masks()
                        .mask("minecraft:lantern", "minecraft:block/lantern")
                        .get(1, 2));
        session.restore(undo.status());
        assertEquals("Before - applied", session.status());
        assertEquals(List.of("My glow"), imported.names());
        assertEquals("Default", BuiltinPresets.first().title);
    }
}
