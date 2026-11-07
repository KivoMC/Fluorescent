package dev.stillbloom.preset;

import static org.junit.jupiter.api.Assertions.*;

import dev.stillbloom.config.BloomConfig;
import dev.stillbloom.config.BloomOverride;
import dev.stillbloom.mask.ItemMasks;
import dev.stillbloom.mask.TextureMasks;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

class BuiltinPresetsTest {
    private static final List<String> FORMER_NAMES =
            List.of("Natural", "Ember", "Pixel Classic", "Cinematic", "Starburst");

    @Test
    void shippedCatalogContainsOneReadOnlyCompleteDefault() {
        assertEquals(
                List.of("Default"),
                BuiltinPresets.looks().stream().map(look -> look.title).toList());
        assertTrue(BuiltinPresets.includesSourceProfiles(BuiltinPresets.first()));
        assertFalse(BuiltinPresets.contains(null));
        assertFalse(BuiltinPresets.contains(PresetFixtures.authored()));
        assertThrows(
                UnsupportedOperationException.class,
                () -> BuiltinPresets.looks().add(PresetFixtures.authored()));
        assertThrows(IllegalArgumentException.class, () -> BuiltinPresets.settings(null));
    }

    @Test
    void futureAuthoredLooksApplyAppearanceWhileSourcePreferencesRemain() throws Exception {
        Set<String> preferences =
                Set.of(
                        "enabled",
                        "bloomRange",
                        "fadeFraction",
                        "heldItemBloom",
                        "otherPlayerItemBloom",
                        "heldItemRange",
                        "uniformEmission",
                        "distancePixels",
                        "farPixelSize",
                        "pixelDistanceStart",
                        "pixelDistanceSpan");
        var current = new BloomConfig();
        current.smoothBlend = .333f;
        current.uniformEmission = true;
        current.enabled = false;
        current.distancePixels = true;
        current.farPixelSize = 2;
        current.pixelDistanceStart = 12;
        current.pixelDistanceSpan = 24;
        current.intensity = .2f;
        current.bloomShape = BloomConfig.Shape.DIAMOND;
        current.flareStrength = 0;
        var before = current.copy();
        var look = PresetFixtures.authored();
        var result = BuiltinPresets.forCurrent(look, current);
        var config = BuiltinPresets.settings(look);
        for (var f : BloomConfig.class.getDeclaredFields())
            if (!Modifier.isStatic(f.getModifiers()))
                assertEquals(
                        f.get(preferences.contains(f.getName()) ? current : config),
                        f.get(result),
                        f.getName());
        PresetFixtures.assertSameSettings(before, current);
        assertEquals(.85f, new BloomConfig().preserveDetail);
    }

    @Test
    void authoredResultsAreDetachedEvenWhenFactoryReusesAConfig() throws Exception {
        var shared = new BloomConfig();
        shared.intensity = 5.7f;
        var before = shared.copy();
        var look = new BuiltinPresets.Look("Test only", "", "", "", () -> shared);
        var changed = BuiltinPresets.settings(look);
        assertNotSame(shared, changed);
        changed.intensity = 0;
        changed.pixelSize = 32;
        changed.bloomShape = null;
        PresetFixtures.assertSameSettings(before, shared);
        PresetFixtures.assertSameSettings(before, BuiltinPresets.settings(look));
    }

    @Test
    void futureLookKeepsDetachedBlockAndItemOverrides() throws Exception {
        var c = new BloomConfig();
        c.enabled = false;
        c.bloomRange = 24;
        c.fadeFraction = .6f;
        c.heldItemRange = 16;
        var blocks = new TextureMasks();
        var blockMask = new TextureMasks.Mask(16, 16);
        blockMask.set(2, 3, true);
        blocks.put("minecraft:torch", "minecraft:block/torch", blockMask);
        var block = blocks.profile("minecraft:torch");
        block.strength = .7f;
        block.override = new BloomOverride();
        block.override.color = 0x123456;
        block.override.shape = BloomConfig.Shape.DIAMOND;
        var items = new ItemMasks();
        var itemMask = new TextureMasks.Mask(32, 16);
        itemMask.set(17, 3, true);
        items.put("minecraft:torch", "pack:item/torch", itemMask);
        var item = items.profile("minecraft:torch");
        item.strength = 1.4f;
        item.override = new BloomOverride();
        item.override.color = 0xABCDEF;
        item.override.flare = BloomConfig.Flare.CROSS;
        for (int switches = 0; switches < 4; switches++) {
            c.heldItemBloom = (switches & 1) != 0;
            c.otherPlayerItemBloom = (switches & 2) != 0;
            var before = c.copy();
            var setup = BuiltinPresets.setup(PresetFixtures.authored(), c, blocks, items);
            var result = setup.settings();
            assertFalse(result.enabled);
            assertEquals(24, result.bloomRange);
            assertEquals(.6f, result.fadeFraction);
            assertEquals(16, result.heldItemRange);
            assertEquals(c.heldItemBloom, result.heldItemBloom);
            assertEquals(c.otherPlayerItemBloom, result.otherPlayerItemBloom);
            PresetFixtures.assertSameSettings(before, c);
            var savedBlock = setup.masks().profile("minecraft:torch");
            var savedItem = setup.itemMasks().profile("minecraft:torch");
            assertEquals(.7f, savedBlock.strength);
            assertEquals(0x123456, savedBlock.override.color);
            assertEquals(BloomConfig.Shape.DIAMOND, savedBlock.override.shape);
            assertEquals(1.4f, savedItem.strength);
            assertEquals(0xABCDEF, savedItem.override.color);
            assertEquals(BloomConfig.Flare.CROSS, savedItem.override.flare);
            assertTrue(setup.masks().mask("minecraft:torch", "minecraft:block/torch").get(2, 3));
            assertTrue(setup.itemMasks().mask("minecraft:torch", "pack:item/torch").get(17, 3));
            savedBlock.override.color = 0;
            savedItem.override.color = 0;
            setup.masks().blocks.clear();
            setup.itemMasks().items.clear();
            assertEquals(0x123456, block.override.color);
            assertEquals(0xABCDEF, item.override.color);
            assertTrue(blockMask.get(2, 3));
            assertTrue(itemMask.get(17, 3));
        }
    }

    @Test
    void sameNamedUserPresetsSurviveReadOnlyReloadAndSharingExactly(@TempDir Path dir)
            throws Exception {
        var path = dir.resolve("looks.json");
        var original = SetupPresets.load(path);
        for (int i = 0; i < FORMER_NAMES.size(); i++)
            assertEquals(
                    FORMER_NAMES.get(i),
                    original.add(FORMER_NAMES.get(i), PresetFixtures.savedSetup(i)));
        byte[] before = Files.readAllBytes(path);
        var loaded = SetupPresets.load(path);
        assertEquals(Set.copyOf(FORMER_NAMES), Set.copyOf(loaded.names()));
        for (int i = 0; i < FORMER_NAMES.size(); i++) {
            String name = FORMER_NAMES.get(i);
            assertEquals(original.export(name), loaded.export(name));
            var setup = SetupPresets.parse(loaded.export(name)).setup();
            PresetFixtures.assertSameSettings(
                    PresetFixtures.savedSetup(i).settings(), setup.settings());
            assertTrue(
                    setup.masks()
                            .mask("minecraft:lantern", "minecraft:block/lantern")
                            .get(i + 1, 2));
            assertTrue(setup.itemMasks().mask("minecraft:torch", "pack:item/torch").get(i + 1, 3));
        }
        assertArrayEquals(
                before,
                Files.readAllBytes(path),
                "Catalog inspection and loading must not rewrite user data");
    }
}
