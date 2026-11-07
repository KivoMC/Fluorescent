package dev.stillbloom.preset;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import com.google.gson.JsonParser;

import dev.stillbloom.config.BloomConfig;
import dev.stillbloom.mask.ItemMasks;
import dev.stillbloom.mask.TextureMasks;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

class DefaultSetupTest {
    private static final Gson JSON = new Gson();

    @Test
    void freshInstallMatchesEveryShippedValueAndTheReusableLook(@TempDir Path dir)
            throws Exception {
        var live = DefaultSetup.load(dir);
        var authored = DefaultSetup.copy();
        assertEquals(JSON.toJsonTree(authored), JSON.toJsonTree(live));
        assertEquals(0.33201057f, live.settings().smoothBlend);
        assertEquals(2.984127f, live.settings().intensity);
        assertTrue(live.settings().heldItemBloom);
        assertEquals(66, live.masks().blocks.size());
        assertEquals(4, live.masks().groups.size());
        assertEquals(14, live.itemMasks().items.size());
        assertEquals(8, live.masks().group("minecraft:copper_lantern").members.size());
        assertSame(
                live.masks().mask("minecraft:copper_lantern", "minecraft:block/lampiao_cobre"),
                live.masks()
                        .mask(
                                "minecraft:oxidized_copper_lantern",
                                "minecraft:block/lampiao_cobre_oxidized"));
        assertTrue(live.masks().mask("minecraft:spawner", "minecraft:block/spawner").get(6, 8));
        assertTrue(live.masks().blocks.containsKey("enderscape:end_trial_spawner"));
        assertNotNull(live.itemMasks().profile("minecraft:torch"));
        for (String name :
                List.of(DefaultSetup.SETTINGS, DefaultSetup.BLOCKS, DefaultSetup.ITEMS)) {
            try (var source =
                    DefaultSetup.class.getResourceAsStream("/dev/stillbloom/defaults/" + name)) {
                assertNotNull(source);
                assertArrayEquals(source.readAllBytes(), Files.readAllBytes(dir.resolve(name)));
            }
        }
        var other = new BloomConfig();
        other.enabled = false;
        other.heldItemBloom = false;
        other.bloomRange = 16;
        var preset =
                BuiltinPresets.setup(
                        BuiltinPresets.first(), other, new TextureMasks(), new ItemMasks());
        assertFalse(preset.settings().enabled);
        preset.settings().enabled = true;
        assertEquals(JSON.toJsonTree(authored), JSON.toJsonTree(preset));
        preset.masks().blocks.clear();
        preset.itemMasks().items.clear();
        preset.settings().intensity = 0;
        assertEquals(JSON.toJsonTree(live), JSON.toJsonTree(DefaultSetup.copy()));
    }

    @Test
    void existingAndIntentionallyEmptyProfilesSurviveUpgradeAndReload(@TempDir Path dir)
            throws Exception {
        Files.writeString(
                dir.resolve(DefaultSetup.SETTINGS), "{\"heldItemBloom\":false,\"pixelSize\":8}");
        String original = "{\"blocks\":{},\"groups\":{},\"independentBlocks\":[]}";
        Files.writeString(dir.resolve(DefaultSetup.BLOCKS), original);
        var live = DefaultSetup.load(dir);
        assertEquals(4, live.settings().pixelSize);
        assertFalse(live.settings().heldItemBloom);
        assertTrue(live.masks().profiles().isEmpty());
        assertTrue(live.itemMasks().items.isEmpty());
        assertFalse(Files.exists(dir.resolve(DefaultSetup.ITEMS)));
        assertEquals(original, Files.readString(dir.resolve(DefaultSetup.BLOCKS)));
        assertEquals(
                2,
                JsonParser.parseString(Files.readString(dir.resolve(DefaultSetup.SETTINGS)))
                        .getAsJsonObject()
                        .size());
    }

    @Test
    void interruptedFirstInstallFillsOnlyMissingFiles(@TempDir Path dir) throws Exception {
        Files.createFile(dir.resolve(DefaultSetup.PENDING));
        String edited = "{\"items\":{}}";
        Files.writeString(dir.resolve(DefaultSetup.ITEMS), edited);
        var resumed = DefaultSetup.load(dir);
        assertTrue(resumed.itemMasks().items.isEmpty());
        assertEquals(edited, Files.readString(dir.resolve(DefaultSetup.ITEMS)));
        assertEquals(
                JSON.toJsonTree(DefaultSetup.copy().masks()), JSON.toJsonTree(resumed.masks()));
        assertFalse(Files.exists(dir.resolve(DefaultSetup.PENDING)));
        resumed.masks().reset("minecraft:spawner", "minecraft:block/spawner");
        assertTrue(resumed.masks().save(dir.resolve(DefaultSetup.BLOCKS)));
        assertNull(
                DefaultSetup.load(dir)
                        .masks()
                        .mask("minecraft:spawner", "minecraft:block/spawner"));
    }
}
