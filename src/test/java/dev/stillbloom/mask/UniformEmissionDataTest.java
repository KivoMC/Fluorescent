package dev.stillbloom.mask;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonNull;
import com.google.gson.JsonParser;

import dev.stillbloom.config.BloomConfig;
import dev.stillbloom.preset.BuiltinPresets;
import dev.stillbloom.preset.PresetFixtures;
import dev.stillbloom.preset.SetupPresets;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

class UniformEmissionDataTest {
    private static SetupPresets.Setup sample(boolean uniform) {
        var config = new BloomConfig();
        config.uniformEmission = uniform;
        config.preserveDetail = .37f;
        var blocks = new TextureMasks();
        var painted = new TextureMasks.Mask(16, 16);
        painted.set(2, 3, true);
        blocks.put("minecraft:torch", "minecraft:block/torch", painted);
        var items = new ItemMasks();
        var itemPaint = new TextureMasks.Mask(16, 16);
        itemPaint.set(4, 5, true);
        items.put("minecraft:torch", "pack:item/torch", itemPaint);
        return SetupPresets.capture(config, blocks, items);
    }

    @Test
    void completePresetsRoundTripEmissionWithoutChangingDetailOrPaint(@TempDir Path dir)
            throws Exception {
        var file = dir.resolve("setups.json");
        var library = SetupPresets.load(file);
        for (boolean enabled : new boolean[] {false, true}) {
            var original = sample(enabled);
            String name = library.add("Emission " + enabled, original);
            original.settings().uniformEmission = !enabled;
            String code = library.export(name);
            var imported = SetupPresets.load(dir.resolve("imported-" + enabled + ".json"));
            String importedName = imported.importCode(code);
            for (var setup :
                    List.of(
                            SetupPresets.parse(code).setup(),
                            SetupPresets.load(file).get(name),
                            imported.get(importedName))) {
                assertEquals(enabled, setup.settings().uniformEmission);
                assertEquals(.37f, setup.settings().preserveDetail);
                assertTrue(
                        setup.masks().mask("minecraft:torch", "minecraft:block/torch").get(2, 3));
                assertTrue(setup.itemMasks().mask("minecraft:torch", "pack:item/torch").get(4, 5));
            }
            var copy = library.get(name);
            copy.settings().uniformEmission = !enabled;
            assertEquals(enabled, library.get(name).settings().uniformEmission);
        }
    }

    @Test
    void oldCodesAndLibrariesDefaultMissingEmissionOffWithoutRewriting(@TempDir Path dir)
            throws Exception {
        var file = dir.resolve("setups.json");
        var library = SetupPresets.load(file);
        library.add("Old", sample(true));
        var code = JsonParser.parseString(library.export("Old")).getAsJsonObject();
        code.getAsJsonObject("setup").getAsJsonObject("settings").remove("uniformEmission");
        var parsed = SetupPresets.parse(code.toString()).setup();
        assertFalse(parsed.settings().uniformEmission);
        assertEquals(.37f, parsed.settings().preserveDetail);
        assertTrue(parsed.masks().mask("minecraft:torch", "minecraft:block/torch").get(2, 3));
        var tree = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        tree.getAsJsonObject("presets")
                .getAsJsonObject("Old")
                .getAsJsonObject("settings")
                .remove("uniformEmission");
        String legacy = tree.toString();
        Files.writeString(file, legacy);
        var loaded = SetupPresets.load(file).get("Old");
        assertFalse(loaded.settings().uniformEmission);
        assertTrue(loaded.itemMasks().mask("minecraft:torch", "pack:item/torch").get(4, 5));
        assertEquals(
                legacy,
                Files.readString(file),
                "Read-time migration must retain the original library file");
    }

    @Test
    void suppliedEmissionValuesStayStrictAndCannotModifyLibrary(@TempDir Path dir)
            throws Exception {
        var file = dir.resolve("setups.json");
        var library = SetupPresets.load(file);
        library.add("Valid", sample(true));
        String code = library.export("Valid"), before = Files.readString(file);
        for (String fault : List.of("string", "number", "null")) {
            var tree = JsonParser.parseString(code).getAsJsonObject();
            var settings = tree.getAsJsonObject("setup").getAsJsonObject("settings");
            switch (fault) {
                case "string" -> settings.addProperty("uniformEmission", "true");
                case "number" -> settings.addProperty("uniformEmission", 1);
                case "null" -> settings.add("uniformEmission", JsonNull.INSTANCE);
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
    void futureAuthoredLooksKeepTheUsersEmissionPreference() {
        var current = new BloomConfig();
        current.uniformEmission = true;
        var look = PresetFixtures.authored();
        assertFalse(BuiltinPresets.settings(look).uniformEmission);
        assertTrue(BuiltinPresets.forCurrent(look, current).uniformEmission);
        assertTrue(current.uniformEmission);
    }
}
