package dev.stillbloom.mask;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;

import dev.stillbloom.config.BloomConfig;
import dev.stillbloom.config.BloomOverride;
import dev.stillbloom.config.BloomStyle;
import dev.stillbloom.preset.SetupPresets;
import dev.stillbloom.preset.StylePresets;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;

class BlockDetailDataTest {
    @Test
    void detailOnlyOverridePersistsAndDoesNotSplitStyleBatches(@TempDir Path dir) throws Exception {
        var m = new TextureMasks();
        var p = new TextureMasks.Profile();
        p.override = new BloomOverride();
        p.override.preserveDetail = 0f;
        m.blocks.put("minecraft:campfire", p);
        assertFalse(p.override.empty());
        var g = new BloomConfig();
        assertEquals(BloomStyle.global(g), p.override.resolve(g));
        Path path = dir.resolve("masks.json");
        assertTrue(m.save(path));
        assertEquals(
                0f, TextureMasks.load(path).profile("minecraft:campfire").override.preserveDetail);
        p.override.preserveDetail = null;
        assertTrue(p.override.empty());
    }

    @Test
    void oldMaskFilesInheritAndInvalidDetailCannotReachRendering(@TempDir Path dir)
            throws Exception {
        Path path = dir.resolve("masks.json");
        Files.writeString(path, "{\"blocks\":{\"minecraft:torch\":{\"override\":{\"color\":0}}}}");
        var o = TextureMasks.load(path).profile("minecraft:torch").override;
        assertNull(o.preserveDetail);
        assertEquals(0, o.color);
        o.preserveDetail = Float.NaN;
        o.sanitize();
        assertNull(o.preserveDetail);
        o.preserveDetail = Float.POSITIVE_INFINITY;
        o.sanitize();
        assertNull(o.preserveDetail);
        o.preserveDetail = -.5f;
        o.sanitize();
        assertEquals(0f, o.preserveDetail);
        o.preserveDetail = 2f;
        o.sanitize();
        assertEquals(1f, o.preserveDetail);
    }

    @Test
    void styleSharingCapturesDetailAndReadsOldStyles(@TempDir Path dir) throws Exception {
        var g = new BloomConfig();
        g.preserveDetail = .37f;
        var lib = StylePresets.load(dir.resolve("styles.json"));
        String n = lib.add("Test", StylePresets.capture(null, g));
        var shared = StylePresets.parse(lib.export(n));
        assertEquals(.37f, shared.style().preserveDetail);
        g.preserveDetail = 1;
        assertEquals(.37f, shared.style().preserveDetail);
        var old = JsonParser.parseString(lib.export(n)).getAsJsonObject();
        old.getAsJsonObject("style").remove("preserveDetail");
        assertNull(StylePresets.parse(old.toString()).style().preserveDetail);
        var file =
                JsonParser.parseString(Files.readString(dir.resolve("styles.json")))
                        .getAsJsonObject();
        file.getAsJsonObject("presets").getAsJsonObject(n).remove("preserveDetail");
        Files.writeString(dir.resolve("styles.json"), file.toString());
        assertNull(StylePresets.load(dir.resolve("styles.json")).get(n).preserveDetail);
        for (JsonElement invalid :
                new JsonElement[] {
                    new JsonPrimitive(-1),
                    new JsonPrimitive(1.1),
                    new JsonPrimitive("0.5"),
                    new JsonObject()
                }) {
            old.getAsJsonObject("style").add("preserveDetail", invalid);
            assertThrows(IllegalArgumentException.class, () -> StylePresets.parse(old.toString()));
        }
    }

    @Test
    void wholeSetupSharingMigratesOnlyMissingDetail(@TempDir Path dir) throws Exception {
        var m = new TextureMasks();
        var p = new TextureMasks.Profile();
        p.override = new BloomOverride();
        p.override.preserveDetail = .61f;
        m.blocks.put("minecraft:campfire", p);
        var lib = SetupPresets.load(dir.resolve("setups.json"));
        String n = lib.add("Test", SetupPresets.capture(new BloomConfig(), m));
        assertEquals(
                .61f,
                SetupPresets.parse(lib.export(n))
                        .setup()
                        .masks()
                        .profile("minecraft:campfire")
                        .override
                        .preserveDetail);
        var old = JsonParser.parseString(lib.export(n)).getAsJsonObject();
        var o =
                old.getAsJsonObject("setup")
                        .getAsJsonObject("masks")
                        .getAsJsonObject("blocks")
                        .getAsJsonObject("minecraft:campfire")
                        .getAsJsonObject("override");
        o.remove("preserveDetail");
        assertNull(
                SetupPresets.parse(old.toString())
                        .setup()
                        .masks()
                        .profile("minecraft:campfire")
                        .override
                        .preserveDetail);
        for (JsonElement invalid :
                new JsonElement[] {
                    new JsonPrimitive(-1),
                    new JsonPrimitive(1.1),
                    new JsonPrimitive("0.5"),
                    new JsonObject()
                }) {
            o.add("preserveDetail", invalid);
            assertThrows(IllegalArgumentException.class, () -> SetupPresets.parse(old.toString()));
        }
        o.remove("preserveDetail");
        o.addProperty("unknownDetail", 1);
        assertThrows(IllegalArgumentException.class, () -> SetupPresets.parse(old.toString()));
    }
}
