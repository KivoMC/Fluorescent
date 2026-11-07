package dev.stillbloom.preset;

import static org.junit.jupiter.api.Assertions.*;

import dev.stillbloom.config.BloomConfig;
import dev.stillbloom.config.BloomOverride;
import dev.stillbloom.mask.ItemMasks;
import dev.stillbloom.mask.TextureMasks;
import dev.stillbloom.persistence.JsonFiles;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;

class SetupPersistenceTest {
    @TempDir Path directory;

    @Test
    void malformedMasksSurviveClosingAndEditingAFallback() throws Exception {
        Path blocks = directory.resolve(DefaultSetup.BLOCKS),
                items = directory.resolve(DefaultSetup.ITEMS);
        Files.writeString(blocks, "{broken blocks");
        Files.writeString(items, "{broken items");
        var masks = TextureMasks.load(blocks);
        var held = ItemMasks.load(items);
        assertTrue(masks.save(blocks));
        assertTrue(held.save(items));
        var paint = new TextureMasks.Mask(16, 16);
        paint.set(2, 3, true);
        masks.put("minecraft:torch", "minecraft:block/torch", paint);
        held.put("minecraft:torch", "minecraft:block/torch", paint);
        assertFalse(masks.save(blocks));
        assertFalse(held.save(items));
        assertEquals("{broken blocks", Files.readString(blocks));
        assertEquals("{broken items", Files.readString(items));
    }

    @Test
    void failedReloadRetainsLiveSettingsAndRecoveryAllowsSaving() throws Exception {
        var live = DefaultSetup.load(directory);
        live.settings().intensity = 2.75f;
        assertTrue(SetupPersistence.save(directory, live, false).success());
        Path blocks = directory.resolve(DefaultSetup.BLOCKS);
        String original = Files.readString(blocks);
        Files.writeString(blocks, "null");
        var reload = DefaultSetup.load(directory, live);
        assertSame(live.masks(), reload.masks());
        assertEquals(2.75f, reload.settings().intensity);
        assertFalse(SetupPersistence.save(directory, DefaultSetup.copy(), true).success());
        assertEquals("null", Files.readString(blocks));
        Files.writeString(blocks, original);
        var recovered = DefaultSetup.load(directory, reload);
        assertFalse(JsonFiles.isUnreadable(blocks));
        recovered.masks().setOverride("minecraft:torch", color());
        assertTrue(SetupPersistence.save(directory, recovered, false).success());
        assertEquals(0x00FF00, TextureMasks.load(blocks).profile("minecraft:torch").override.color);
    }

    @Test
    void globalSaveFailureIsReportedAndDoesNotCommitOtherFiles() throws Exception {
        var setup = DefaultSetup.copy();
        Path settings = Files.createDirectory(directory.resolve(DefaultSetup.SETTINGS));
        Files.writeString(settings.resolve("keep.txt"), "keep");
        assertFalse(setup.settings().save(settings));
        assertFalse(SetupPersistence.save(directory, setup, true).success());
        assertFalse(Files.exists(directory.resolve(DefaultSetup.BLOCKS)));
        assertFalse(Files.exists(directory.resolve(DefaultSetup.ITEMS)));
        assertEquals("keep", Files.readString(settings.resolve("keep.txt")));
    }

    @Test
    void repeatedMenuSavesOnlyWriteTheChangedFile() throws Exception {
        var setup = DefaultSetup.load(directory);
        var time = FileTime.fromMillis(1_600_000_000_000L);
        var names = List.of(DefaultSetup.SETTINGS, DefaultSetup.BLOCKS, DefaultSetup.ITEMS);
        for (String name : names) Files.setLastModifiedTime(directory.resolve(name), time);
        assertTrue(SetupPersistence.save(directory, setup, false).success());
        for (String name : names)
            assertEquals(time, Files.getLastModifiedTime(directory.resolve(name)));
        setup.settings().intensity = 7;
        assertTrue(SetupPersistence.save(directory, setup, false).success());
        assertEquals(time, Files.getLastModifiedTime(directory.resolve(DefaultSetup.BLOCKS)));
        assertEquals(time, Files.getLastModifiedTime(directory.resolve(DefaultSetup.ITEMS)));
        assertEquals(7, BloomConfig.load(directory.resolve(DefaultSetup.SETTINGS)).intensity);
        var changedTime = Files.getLastModifiedTime(directory.resolve(DefaultSetup.SETTINGS));
        assertTrue(SetupPersistence.save(directory, setup, false).success());
        assertEquals(
                changedTime, Files.getLastModifiedTime(directory.resolve(DefaultSetup.SETTINGS)));
    }

    private static BloomOverride color() {
        var style = new BloomOverride();
        style.color = 0x00FF00;
        return style;
    }
}
