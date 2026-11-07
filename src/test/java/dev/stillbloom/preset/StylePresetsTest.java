package dev.stillbloom.preset;

import static org.junit.jupiter.api.Assertions.*;

import dev.stillbloom.config.BloomConfig;
import dev.stillbloom.config.BloomOverride;
import dev.stillbloom.mask.TextureMasks;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;

class StylePresetsTest {
    @Test
    void snapshotsFreezeInheritedValuesAndKeepInactiveFlareSettings() {
        var g = new BloomConfig();
        g.flareStyle = BloomConfig.Flare.OFF;
        g.flareLength = 77;
        g.flareStrength = .8f;
        var o = new BloomOverride();
        o.color = 0x123456;
        var s = StylePresets.capture(o, g);
        float old = g.intensity;
        g.intensity = 8;
        o.color = 0;
        assertEquals(old, s.intensity);
        assertEquals(0x123456, s.color);
        assertEquals(77, s.flareLength);
        assertEquals(.8f, s.flareStrength);
        assertEquals(BloomConfig.Flare.OFF, s.flare);
        assertEquals(old, s.resolve(g).intensity());
    }

    @Test
    void persistsSharesAndReturnsDetachedCopies(@TempDir Path dir) throws Exception {
        Path path = dir.resolve("styles.json");
        var lib = StylePresets.load(path);
        var s = StylePresets.capture(null, new BloomConfig());
        s.color = 0;
        s.shape = BloomConfig.Shape.DIAMOND;
        String name = lib.add("  Ember  ", s);
        s.color = 1;
        assertEquals("Ember", name);
        lib.get(name).color = 2;
        lib = StylePresets.load(path);
        assertEquals(0, lib.get(name).color);
        var shared = StylePresets.parse(lib.export(name));
        assertEquals(name, shared.name());
        assertEquals(0, shared.style().color);
        assertEquals(BloomConfig.Shape.DIAMOND, shared.style().shape);
        var natural = StylePresets.capture(null, new BloomConfig());
        String n = lib.add("Natural", natural);
        assertNull(StylePresets.parse(lib.export(n)).style().color);
    }

    @Test
    void importsNeverOverwriteAndRenamingDeletionLeaveAppliedStylesIndependent(@TempDir Path dir)
            throws Exception {
        var lib = StylePresets.load(dir.resolve("styles.json"));
        var s = StylePresets.capture(null, new BloomConfig());
        String first = lib.add("Ember", s);
        String second = lib.importCode(lib.export(first));
        assertEquals("Ember (2)", second);
        assertThrows(IllegalArgumentException.class, () -> lib.rename(second, first));
        var masks = new TextureMasks();
        var mask = new TextureMasks.Mask(16, 16);
        mask.set(1, 1, true);
        masks.put("torch", "texture", mask);
        masks.profile("torch").override = lib.get(first);
        var applied = masks.profile("torch").override;
        assertEquals("Flame", lib.rename(first, "Flame"));
        lib.delete("Flame");
        assertSame(applied, masks.profile("torch").override);
        assertTrue(masks.mask("torch", "texture").get(1, 1));
        assertEquals(1, lib.names().size());
    }

    @Test
    void invalidSharingCannotMutateLibrary(@TempDir Path dir) throws Exception {
        var lib = StylePresets.load(dir.resolve("styles.json"));
        String n = lib.add("Test", StylePresets.capture(null, new BloomConfig()));
        String valid = lib.export(n);
        String[] invalid = {
            "",
            "[]",
            "{}",
            valid.replace("\"version\": 1", "\"version\": 2"),
            valid.replace("stillbloom-style", "other"),
            valid.replace("\"color\": null", "\"color\": 1.5"),
            valid.replace("\"color\": null", "\"color\": 16777216"),
            valid.replaceAll("\"intensity\": [0-9.]+", "\"intensity\": -1"),
            valid.replace("\"flare\": \"OFF\"", "\"flare\": \"UNKNOWN\""),
            "x".repeat(4097)
        };
        for (String text : invalid) {
            assertNotEquals(valid, text, "Fixture must change the code");
            assertThrows(IllegalArgumentException.class, () -> lib.importCode(text));
        }
        assertEquals(1, lib.names().size());
        assertEquals(1, StylePresets.load(dir.resolve("styles.json")).names().size());
    }

    @Test
    void malformedAndFutureLibrariesRemainUntouched(@TempDir Path dir) throws Exception {
        Path p = dir.resolve("styles.json");
        String bad = "{\"format\":\"stillbloom-style-library\",\"version\":2,\"presets\":{}}";
        Files.writeString(p, bad);
        assertThrows(java.io.IOException.class, () -> StylePresets.load(p));
        assertEquals(bad, Files.readString(p));
        Files.writeString(p, "{");
        assertThrows(java.io.IOException.class, () -> StylePresets.load(p));
        assertEquals("{", Files.readString(p));
    }

    @Test
    void failedWritesDoNotChangeInMemoryLibrary(@TempDir Path dir) throws Exception {
        Path p = dir.resolve("styles.json");
        var lib = StylePresets.load(p);
        Files.createDirectory(p);
        Files.writeString(p.resolve("keep"), "unchanged");
        assertThrows(
                java.io.IOException.class,
                () -> lib.add("Test", StylePresets.capture(null, new BloomConfig())));
        assertTrue(lib.names().isEmpty());
        assertEquals("unchanged", Files.readString(p.resolve("keep")));
    }

    @Test
    void namesAndDuplicateSuffixesAreBounded(@TempDir Path dir) throws Exception {
        var lib = StylePresets.load(dir.resolve("styles.json"));
        var style = StylePresets.capture(null, new BloomConfig());
        String max = "A".repeat(48);
        assertEquals(max, lib.add(max, style));
        String duplicate = lib.add(max, style);
        assertEquals(48, duplicate.length());
        assertTrue(duplicate.endsWith(" (2)"));
        for (String bad : new String[] {" ", "a\nb", "a".repeat(49)})
            assertThrows(IllegalArgumentException.class, () -> lib.add(bad, style));
    }
}
