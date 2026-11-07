package dev.stillbloom.config;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonNull;
import com.google.gson.JsonParser;

import dev.stillbloom.mask.ItemMasks;
import dev.stillbloom.mask.TextureMasks;
import dev.stillbloom.preset.BuiltinPresets;
import dev.stillbloom.preset.PresetFixtures;
import dev.stillbloom.preset.SetupPresets;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

class SmoothBlendConfigTest {
    private static SetupPresets.Setup sample(float smooth) {
        var config = new BloomConfig();
        config.smoothBlend = smooth;
        config.pixelSize = 4;
        var blocks = new TextureMasks();
        var blockMask = new TextureMasks.Mask(16, 16);
        blockMask.set(2, 3, true);
        blocks.put("minecraft:torch", "minecraft:block/torch", blockMask);
        blocks.profile("minecraft:torch").override = new BloomOverride();
        blocks.profile("minecraft:torch").override.color = 0x123456;
        var items = new ItemMasks();
        var itemMask = new TextureMasks.Mask(16, 16);
        itemMask.set(4, 5, true);
        items.put("minecraft:torch", "pack:item/torch", itemMask);
        return SetupPresets.capture(config, blocks, items);
    }

    @Test
    void defaultsAndLegacyConfigurationsKeepPixelatedRendering(@TempDir Path dir) throws Exception {
        assertEquals(0, new BloomConfig().smoothBlend);
        for (int i = 0; i < 3; i++) assertEquals(0, BloomConfig.preset(i).smoothBlend);
        var file = dir.resolve("old.json");
        Files.writeString(file, "{\"pixelSize\":8,\"intensity\":2.75}");
        var legacy = BloomConfig.load(file);
        assertEquals(0, legacy.smoothBlend);
        assertEquals(4, legacy.pixelSize);
        assertEquals(2.75f, legacy.intensity);
        assertEquals(
                "{\"pixelSize\":8,\"intensity\":2.75}",
                Files.readString(file),
                "Loading an existing file does not rewrite it");
    }

    @Test
    void valueIsFiniteBoundedCopiedAndSaved(@TempDir Path dir) {
        for (float invalid :
                new float[] {Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY}) {
            var c = new BloomConfig();
            c.smoothBlend = invalid;
            c.sanitize();
            assertEquals(0, c.smoothBlend);
        }
        var c = new BloomConfig();
        c.smoothBlend = -1;
        c.sanitize();
        assertEquals(0, c.smoothBlend);
        c.smoothBlend = 2;
        c.sanitize();
        assertEquals(1, c.smoothBlend);
        var file = dir.resolve("settings.json");
        for (float value : new float[] {0, .375f, 1}) {
            c.smoothBlend = value;
            var copy = c.copy();
            assertEquals(value, copy.smoothBlend);
            copy.smoothBlend = .75f;
            assertEquals(value, c.smoothBlend);
            c.save(file);
            assertEquals(value, BloomConfig.load(file).smoothBlend);
        }
    }

    @Test
    void completeSetupsShareAndPersistTheBlendIndependently(@TempDir Path dir) throws Exception {
        var file = dir.resolve("setups.json");
        var library = SetupPresets.load(file);
        for (float value : new float[] {0, .375f, 1}) {
            var original = sample(value);
            String name = library.add("Smooth " + value, original);
            original.settings().smoothBlend = .75f;
            String code = library.export(name);
            var imported = SetupPresets.parse(code).setup();
            assertEquals(value, imported.settings().smoothBlend);
            assertEquals(value, SetupPresets.load(file).get(name).settings().smoothBlend);
            assertTrue(imported.masks().mask("minecraft:torch", "minecraft:block/torch").get(2, 3));
            assertEquals(0x123456, imported.masks().profile("minecraft:torch").override.color);
            assertTrue(imported.itemMasks().mask("minecraft:torch", "pack:item/torch").get(4, 5));
            imported.settings().smoothBlend = .75f;
            assertEquals(value, library.get(name).settings().smoothBlend);
        }
    }

    @Test
    void oldSetupCodesAndLibrariesMigrateOnlyTheAbsentBlend(@TempDir Path dir) throws Exception {
        var file = dir.resolve("setups.json");
        var library = SetupPresets.load(file);
        library.add("Old", sample(.375f));
        var code = JsonParser.parseString(library.export("Old")).getAsJsonObject();
        code.getAsJsonObject("setup").getAsJsonObject("settings").remove("smoothBlend");
        var old = SetupPresets.parse(code.toString()).setup();
        assertEquals(0, old.settings().smoothBlend);
        assertEquals(4, old.settings().pixelSize);
        assertTrue(old.masks().mask("minecraft:torch", "minecraft:block/torch").get(2, 3));
        assertTrue(old.itemMasks().mask("minecraft:torch", "pack:item/torch").get(4, 5));
        var tree = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        tree.getAsJsonObject("presets")
                .getAsJsonObject("Old")
                .getAsJsonObject("settings")
                .remove("smoothBlend");
        String legacy = tree.toString();
        Files.writeString(file, legacy);
        assertEquals(0, SetupPresets.load(file).get("Old").settings().smoothBlend);
        assertEquals(
                legacy,
                Files.readString(file),
                "Migration on read must preserve the original library file");
    }

    @Test
    void malformedSuppliedBlendCannotModifyTheSavedLibrary(@TempDir Path dir) throws Exception {
        var file = dir.resolve("setups.json");
        var library = SetupPresets.load(file);
        library.add("Valid", sample(.375f));
        String code = library.export("Valid"), before = Files.readString(file);
        for (String fault : List.of("negative", "large", "string", "null", "boolean")) {
            var tree = JsonParser.parseString(code).getAsJsonObject();
            var settings = tree.getAsJsonObject("setup").getAsJsonObject("settings");
            switch (fault) {
                case "negative" -> settings.addProperty("smoothBlend", -.001);
                case "large" -> settings.addProperty("smoothBlend", 1.001);
                case "string" -> settings.addProperty("smoothBlend", "0.375");
                case "null" -> settings.add("smoothBlend", JsonNull.INSTANCE);
                case "boolean" -> settings.addProperty("smoothBlend", true);
            }
            assertThrows(
                    IllegalArgumentException.class,
                    () -> library.importCode(tree.toString()),
                    fault);
        }
        assertEquals(before, Files.readString(file));
        assertEquals(List.of("Valid"), library.names());
    }

    @Test
    void futureAuthoredLooksApplyTheirOwnSmoothnessAndKeepSourceChoices() {
        for (float value : new float[] {0, .375f, 1}) {
            var look = PresetFixtures.authored();
            var current = new BloomConfig();
            current.smoothBlend = value;
            current.enabled = false;
            current.bloomRange = 24;
            current.heldItemBloom = true;
            var preset = BuiltinPresets.forCurrent(look, current);
            assertEquals(BuiltinPresets.settings(look).smoothBlend, preset.smoothBlend);
            assertFalse(preset.enabled);
            assertEquals(24, preset.bloomRange);
            assertTrue(preset.heldItemBloom);
            preset.smoothBlend = .75f;
            assertEquals(value, current.smoothBlend);
        }
    }
}
