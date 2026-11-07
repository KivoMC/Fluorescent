package dev.stillbloom.preset;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.stillbloom.config.BloomConfig;
import dev.stillbloom.mask.ItemMasks;
import dev.stillbloom.mask.TextureMasks;

import java.lang.reflect.Modifier;

public final class PresetFixtures {
    private PresetFixtures() {}

    public static BuiltinPresets.Look authored() {
        return new BuiltinPresets.Look(
                "Test only",
                "Authoring contract",
                "Test",
                "Not shipped",
                () -> {
                    var c = new BloomConfig();
                    c.intensity = 6.3f;
                    c.pixelSize = 2;
                    c.smoothBlend = .375f;
                    c.preserveDetail = .42f;
                    c.flareStyle = BloomConfig.Flare.CROSS;
                    c.flareStrength = .7f;
                    return c;
                });
    }

    public static void assertSameSettings(BloomConfig expected, BloomConfig actual)
            throws Exception {
        for (var f : BloomConfig.class.getDeclaredFields())
            if (!Modifier.isStatic(f.getModifiers()))
                assertEquals(f.get(expected), f.get(actual), f.getName());
    }

    public static SetupPresets.Setup savedSetup(int index) {
        var c = new BloomConfig();
        c.pixelSize = 2;
        c.intensity = .9f + index;
        c.preserveDetail = .1f * index;
        c.smoothBlend = .125f * index;
        var blocks = new TextureMasks();
        var b = new TextureMasks.Mask(16, 16);
        b.set(index + 1, 2, true);
        blocks.put("minecraft:lantern", "minecraft:block/lantern", b);
        var items = new ItemMasks();
        var m = new TextureMasks.Mask(16, 16);
        m.set(index + 1, 3, true);
        items.put("minecraft:torch", "pack:item/torch", m);
        return SetupPresets.capture(c, blocks, items);
    }
}
