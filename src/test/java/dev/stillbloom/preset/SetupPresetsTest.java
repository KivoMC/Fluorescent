package dev.stillbloom.preset;

import static org.junit.jupiter.api.Assertions.*;

import dev.stillbloom.config.BloomConfig;
import dev.stillbloom.config.BloomOverride;
import dev.stillbloom.mask.ItemMasks;
import dev.stillbloom.mask.TextureMasks;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.*;

class SetupPresetsTest {
    static SetupPresets.Setup sample() {
        var c = new BloomConfig();
        c.distancePixels = true;
        c.pixelSize = 4;
        c.farPixelSize = 2;
        c.warmth = -.2f;
        var m = new TextureMasks();
        var mask = new TextureMasks.Mask(16, 32);
        mask.set(15, 31, true);
        m.put("minecraft:torch", "minecraft:block/torch", mask);
        var p = m.profile("minecraft:torch");
        p.strength = .7f;
        p.override = new BloomOverride();
        p.override.color = 0x123456;
        p.override.flare = BloomConfig.Flare.CROSS;
        return SetupPresets.capture(c, m);
    }

    @Test
    void wholeSetupRoundTripsIncludingNullableInheritanceAndMasks(@TempDir Path dir)
            throws Exception {
        Path path = dir.resolve("presets.json");
        var lib = SetupPresets.load(path);
        String n = lib.add("Ember", sample());
        var imported = SetupPresets.parse(lib.export(n)).setup();
        assertEquals(-.2f, imported.settings().warmth);
        assertTrue(imported.settings().distancePixels);
        assertEquals(4, imported.settings().pixelSize);
        var p = imported.masks().profile("minecraft:torch");
        assertEquals(.7f, p.strength);
        assertEquals(0x123456, p.override.color);
        assertNull(p.override.intensity);
        assertTrue(imported.masks().mask("minecraft:torch", "minecraft:block/torch").get(15, 31));
        assertEquals(
                BloomConfig.Flare.CROSS,
                SetupPresets.load(path).get(n).masks().profile("minecraft:torch").override.flare);
    }

    @Test
    void snapshotsAndReturnedCopiesAreDetached(@TempDir Path dir) throws Exception {
        var lib = SetupPresets.load(dir.resolve("presets.json"));
        var original = sample();
        String n = lib.add("Test", original);
        original.settings().intensity = 0;
        original.masks().blocks.clear();
        var copy = lib.get(n);
        copy.masks().blocks.clear();
        copy.settings().intensity = 0;
        assertEquals(3.2f, lib.get(n).settings().intensity);
        assertEquals(1, lib.get(n).masks().blocks.size());
    }

    @Test
    void heldItemOptionsSurviveLibrarySharingAndDetachedCopies(@TempDir Path dir) throws Exception {
        var setup = sample();
        setup.settings().heldItemBloom = true;
        setup.settings().otherPlayerItemBloom = true;
        setup.settings().heldItemRange = 56;
        Path path = dir.resolve("presets.json");
        var lib = SetupPresets.load(path);
        String name = lib.add("Held lights", setup);
        var copy = SetupPresets.copy(setup);
        copy.settings().heldItemBloom = false;
        copy.settings().heldItemRange = 8;
        assertTrue(setup.settings().heldItemBloom);
        assertEquals(56, setup.settings().heldItemRange);
        String exported = lib.export(name);
        var imported = SetupPresets.load(dir.resolve("imported.json"));
        String importedName = imported.importCode(exported);
        for (var settings :
                List.of(
                        SetupPresets.parse(exported).setup().settings(),
                        SetupPresets.load(path).get(name).settings(),
                        imported.get(importedName).settings())) {
            assertTrue(settings.heldItemBloom);
            assertTrue(settings.otherPlayerItemBloom);
            assertEquals(56, settings.heldItemRange);
        }
    }

    @Test
    void olderSetupCodesAndLibrariesDefaultHeldBloomOff(@TempDir Path dir) throws Exception {
        Path path = dir.resolve("presets.json");
        var lib = SetupPresets.load(path);
        lib.add("Legacy", sample());
        var shared = com.google.gson.JsonParser.parseString(lib.export("Legacy")).getAsJsonObject();
        var oldSettings = shared.getAsJsonObject("setup").getAsJsonObject("settings");
        for (String key : List.of("heldItemBloom", "otherPlayerItemBloom", "heldItemRange"))
            oldSettings.remove(key);
        var imported = SetupPresets.parse(shared.toString()).setup();
        assertFalse(imported.settings().heldItemBloom);
        assertFalse(imported.settings().otherPlayerItemBloom);
        assertEquals(32, imported.settings().heldItemRange);
        assertEquals(4, imported.settings().pixelSize);
        assertTrue(imported.masks().mask("minecraft:torch", "minecraft:block/torch").get(15, 31));
        var library =
                com.google.gson.JsonParser.parseString(Files.readString(path)).getAsJsonObject();
        library.getAsJsonObject("presets").getAsJsonObject("Legacy").add("settings", oldSettings);
        Files.writeString(path, library.toString());
        var loaded = SetupPresets.load(path).get("Legacy").settings();
        assertFalse(loaded.heldItemBloom);
        assertFalse(loaded.otherPlayerItemBloom);
        assertEquals(32, loaded.heldItemRange);
    }

    @Test
    void malformedHeldItemPresetValuesAreRejectedWithoutChangingLibrary(@TempDir Path dir)
            throws Exception {
        Path path = dir.resolve("presets.json");
        var lib = SetupPresets.load(path);
        lib.add("Test", sample());
        String good = lib.export("Test"), before = Files.readString(path);
        for (String bad :
                List.of(
                        good.replace("\"heldItemBloom\": false", "\"heldItemBloom\": \"true\""),
                        good.replace(
                                "\"otherPlayerItemBloom\": false",
                                "\"otherPlayerItemBloom\": null"),
                        good.replace("\"heldItemRange\": 32.0", "\"heldItemRange\": 65"),
                        good.replace("\"heldItemRange\": 32.0", "\"heldItemRange\": \"32\""))) {
            assertNotEquals(good, bad);
            assertThrows(IllegalArgumentException.class, () -> lib.importCode(bad));
        }
        assertEquals(before, Files.readString(path));
        assertEquals(List.of("Test"), lib.names());
    }

    @Test
    void importRenameDeleteNeverOverwriteExistingEntries(@TempDir Path dir) throws Exception {
        var lib = SetupPresets.load(dir.resolve("presets.json"));
        String n = lib.add("Test", sample());
        assertEquals("Test (2)", lib.importCode(lib.export(n)));
        assertThrows(IllegalArgumentException.class, () -> lib.rename(n, "Test (2)"));
        assertEquals("New", lib.rename(n, "New"));
        var applied = lib.get("New");
        lib.delete("New");
        assertEquals(1, applied.masks().blocks.size());
        assertEquals(List.of("Test (2)"), lib.names());
    }

    @Test
    void invalidImportsDoNotChangeLibrary(@TempDir Path dir) throws Exception {
        Path path = dir.resolve("presets.json");
        var lib = SetupPresets.load(path);
        lib.add("Test", sample());
        String good = lib.export("Test"), before = Files.readString(path);
        String[] bad = {
            "{}",
            "[]",
            "{".repeat(100),
            "x".repeat(1_048_577),
            good.replace("\"version\": 1", "\"version\": 2"),
            good.replace("stillbloom-setup", "stillbloom-style"),
            good.replace("\"pixelSize\": 4", "\"pixelSize\": 4.5"),
            good.replace("\"enabled\": true", "\"enabled\": \"true\""),
            good.replace("\"enabled\": true", "\"enabled\": true, \"enabled\": false"),
            good.replace("\"warmth\": -0.2", "\"warmth\": -2"),
            good.replace("\"bloomShape\": \"SOFT\"", "\"bloomShape\": \"MISSING\""),
            good.replace("\"width\": 16", "\"width\": 257"),
            good.replace("minecraft:torch", "../outside"),
            good.replace("\"color\": 1193046", "\"color\": 16777216"),
            good.replace("\"settings\": {", "\"settings\": {\"unknown\":1,")
        };
        for (String text : bad) {
            assertNotEquals(good, text);
            assertThrows(
                    IllegalArgumentException.class,
                    () -> lib.importCode(text),
                    text.substring(0, Math.min(100, text.length())));
        }
        assertEquals(before, Files.readString(path));
        assertEquals(List.of("Test"), lib.names());
    }

    @Test
    void corruptLibraryIsPreserved(@TempDir Path dir) throws Exception {
        Path path = dir.resolve("presets.json");
        Files.writeString(path, "{broken}");
        assertThrows(java.io.IOException.class, () -> SetupPresets.load(path));
        assertEquals("{broken}", Files.readString(path));
    }

    @Test
    void itemPaintAndStylesRoundTripAlongsideIndependentBlockPaint(@TempDir Path dir)
            throws Exception {
        var original = sample();
        var items = new ItemMasks();
        var painted = new TextureMasks.Mask(32, 16);
        painted.set(17, 3, true);
        items.put("minecraft:torch", "pack:item/torch", painted);
        var p = items.profile("minecraft:torch");
        p.strength = 1.75f;
        p.override = new BloomOverride();
        p.override.color = 0xAABBCC;
        p.override.shape = BloomConfig.Shape.DIAMOND;
        var captured = SetupPresets.capture(original.settings(), original.masks(), items);
        Path file = dir.resolve("setups.json");
        var lib = SetupPresets.load(file);
        String name = lib.add("Item glow", captured);
        painted.set(17, 3, false);
        p.override.color = 0;
        captured.itemMasks().items.clear();
        String code = lib.export(name);
        var imported = SetupPresets.load(dir.resolve("imported.json"));
        String copy = imported.importCode(code);
        for (var setup :
                List.of(
                        SetupPresets.parse(code).setup(),
                        SetupPresets.load(file).get(name),
                        imported.get(copy))) {
            var item = setup.itemMasks().profile("minecraft:torch");
            assertEquals(1.75f, item.strength);
            assertEquals(0xAABBCC, item.override.color);
            assertNull(item.override.intensity);
            assertTrue(setup.itemMasks().mask("minecraft:torch", "pack:item/torch").get(17, 3));
            assertTrue(setup.masks().mask("minecraft:torch", "minecraft:block/torch").get(15, 31));
            assertEquals(0x123456, setup.masks().profile("minecraft:torch").override.color);
        }
        var detached = lib.get(name);
        detached.itemMasks().mask("minecraft:torch", "pack:item/torch").set(17, 3, false);
        assertTrue(lib.get(name).itemMasks().mask("minecraft:torch", "pack:item/torch").get(17, 3));
    }

    @Test
    void oldSetupCodesAndLibrariesMigrateToEmptyItemPaint(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("setups.json");
        var lib = SetupPresets.load(file);
        lib.add("Old", sample());
        var code = com.google.gson.JsonParser.parseString(lib.export("Old")).getAsJsonObject();
        code.getAsJsonObject("setup").remove("itemMasks");
        var parsed = SetupPresets.parse(code.toString()).setup();
        assertTrue(parsed.itemMasks().items.isEmpty());
        assertEquals(4, parsed.settings().pixelSize);
        var library =
                com.google.gson.JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        library.getAsJsonObject("presets").getAsJsonObject("Old").remove("itemMasks");
        Files.writeString(file, library.toString());
        var loaded = SetupPresets.load(file).get("Old");
        assertTrue(loaded.itemMasks().items.isEmpty());
        assertTrue(loaded.masks().mask("minecraft:torch", "minecraft:block/torch").get(15, 31));
    }

    @Test
    void invalidSuppliedItemPaintIsRejectedWithoutChangingLibrary(@TempDir Path dir)
            throws Exception {
        var items = new ItemMasks();
        items.put("minecraft:stick", "pack:item/stick", new TextureMasks.Mask(16, 16));
        items.profile("minecraft:stick").override = new BloomOverride();
        items.profile("minecraft:stick").override.color = 0xABCDEF;
        var sample = sample();
        Path file = dir.resolve("setups.json");
        var lib = SetupPresets.load(file);
        lib.add("Valid", SetupPresets.capture(sample.settings(), sample.masks(), items));
        String good = lib.export("Valid"), before = Files.readString(file);
        for (String fault :
                List.of(
                        "null",
                        "missing-items",
                        "strength",
                        "dimension",
                        "word-count",
                        "id",
                        "unknown",
                        "override")) {
            var tree = com.google.gson.JsonParser.parseString(good).getAsJsonObject();
            var setup = tree.getAsJsonObject("setup");
            var itemMasks = setup.getAsJsonObject("itemMasks");
            var profiles = itemMasks.getAsJsonObject("items");
            var item = profiles.getAsJsonObject("minecraft:stick");
            var texture = item.getAsJsonObject("textures").getAsJsonObject("pack:item/stick");
            switch (fault) {
                case "null" -> setup.add("itemMasks", com.google.gson.JsonNull.INSTANCE);
                case "missing-items" -> itemMasks.remove("items");
                case "strength" -> item.addProperty("strength", 8.1);
                case "dimension" -> texture.addProperty("width", 16.5);
                case "word-count" -> texture.add("words", new com.google.gson.JsonArray());
                case "id" -> profiles.add("../invalid", profiles.remove("minecraft:stick"));
                case "unknown" -> item.addProperty("extra", true);
                case "override" -> item.getAsJsonObject("override").addProperty("color", 16777216);
            }
            assertThrows(
                    IllegalArgumentException.class, () -> lib.importCode(tree.toString()), fault);
        }
        assertEquals(before, Files.readString(file));
        assertEquals(List.of("Valid"), lib.names());
    }

    @Test
    void failedCommitDoesNotChangeMemory(@TempDir Path dir) throws Exception {
        Path path = dir.resolve("presets.json");
        var lib = SetupPresets.load(path);
        Files.createDirectory(path);
        Files.writeString(path.resolve("keep"), "keep");
        assertThrows(java.io.IOException.class, () -> lib.add("Test", sample()));
        assertTrue(lib.names().isEmpty());
        assertEquals("keep", Files.readString(path.resolve("keep")));
    }
}
