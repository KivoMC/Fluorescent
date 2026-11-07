package dev.stillbloom.preset;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;

import dev.stillbloom.config.BloomConfig;
import dev.stillbloom.render.BloomGraph;
import dev.stillbloom.render.item.HeldItemGraph;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.List;

class PixelSizeMigrationTest {
    private static final Gson JSON = new GsonBuilder().serializeNulls().create();
    private static final int[] UNSUPPORTED = {
        Integer.MIN_VALUE, -1, 0, 1, 5, 6, 8, 16, 32, 999, Integer.MAX_VALUE
    };

    @Test
    void settingsFallbackChangesOnlyPixelSizesAndDoesNotRewriteOnLoad(@TempDir Path dir)
            throws Exception {
        var original = DefaultSetup.copy().settings();
        original.intensity = 2.75f;
        original.pixelSize = 3;
        original.farPixelSize = 2;
        for (String key : List.of("pixelSize", "farPixelSize"))
            for (int value : UNSUPPORTED) {
                var tree = JSON.toJsonTree(original).getAsJsonObject();
                tree.addProperty(key, value);
                Path file = dir.resolve(key + "-" + value + ".json");
                String before = tree.toString();
                Files.writeString(file, before);
                var loaded = BloomConfig.load(file);
                tree.addProperty(key, 4);
                assertEquals(tree, JSON.toJsonTree(loaded));
                assertEquals(before, Files.readString(file));
                assertTrue(loaded.save(file));
                assertEquals(tree, JSON.toJsonTree(BloomConfig.load(file)));
            }
    }

    @Test
    void sharedAndSavedSetupsMigrateGridFieldsWithoutChangingAnyPaintOrLinks(@TempDir Path dir)
            throws Exception {
        Path path = dir.resolve("presets.json");
        var library = SetupPresets.load(path);
        library.add("Painted", DefaultSetup.copy());
        String shared = library.export("Painted");
        var originalLibrary = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
        for (String key : List.of("pixelSize", "farPixelSize"))
            for (int value : UNSUPPORTED) {
                var code = JsonParser.parseString(shared).getAsJsonObject();
                var setup = code.getAsJsonObject("setup");
                setup.getAsJsonObject("settings").addProperty(key, value);
                var migrated = SetupPresets.parse(code.toString()).setup();
                var saved = originalLibrary.deepCopy();
                saved.getAsJsonObject("presets").add("Painted", setup.deepCopy());
                String before = saved.toString();
                Files.writeString(path, before);
                var reloaded = SetupPresets.load(path);
                assertEquals(
                        before, Files.readString(path), "Opening a library must not rewrite it");
                setup.getAsJsonObject("settings").addProperty(key, 4);
                assertEquals(setup, JsonParser.parseString(JSON.toJson(migrated)));
                assertEquals(setup, JsonParser.parseString(JSON.toJson(reloaded.get("Painted"))));
                assertEquals(
                        setup,
                        JsonParser.parseString(
                                JSON.toJson(
                                        SetupPresets.parse(reloaded.export("Painted")).setup())));
            }
    }

    @Test
    void pixelMigrationStillRejectsMalformedTypesAndKeepsTheLibrary(@TempDir Path dir)
            throws Exception {
        Path path = dir.resolve("presets.json");
        var library = SetupPresets.load(path);
        library.add("Painted", DefaultSetup.copy());
        String good = library.export("Painted"), before = Files.readString(path);
        for (String key : List.of("pixelSize", "farPixelSize"))
            for (String invalid :
                    List.of("null", "true", "\"3\"", "3.5", "2147483648", "missing")) {
                var code = JsonParser.parseString(good).getAsJsonObject();
                var settings = code.getAsJsonObject("setup").getAsJsonObject("settings");
                if (invalid.equals("missing")) settings.remove(key);
                else settings.add(key, JsonParser.parseString(invalid));
                assertThrows(
                        IllegalArgumentException.class, () -> library.importCode(code.toString()));
                assertEquals(before, Files.readString(path));
                assertEquals(List.of("Painted"), library.names());
            }
    }

    @Test
    void rawUnsupportedGridsCannotConstructWorldOrHeldRendering() {
        for (String key : List.of("pixelSize", "farPixelSize"))
            for (int value : UNSUPPORTED) {
                var config = new BloomConfig();
                if (key.equals("pixelSize")) config.pixelSize = value;
                else config.farPixelSize = value;
                assertThrows(
                        IllegalArgumentException.class,
                        () -> BloomGraph.createConfig(1281, 721, config));
                assertThrows(
                        IllegalArgumentException.class,
                        () -> HeldItemGraph.create(1281, 721, config));
                config.sanitize();
                assertDoesNotThrow(() -> BloomGraph.createConfig(1281, 721, config));
                assertDoesNotThrow(() -> HeldItemGraph.create(1281, 721, config));
            }
    }
}
