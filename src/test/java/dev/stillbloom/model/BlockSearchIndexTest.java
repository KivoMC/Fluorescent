package dev.stillbloom.model;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.*;

class BlockSearchIndexTest {
    @Test
    void textSearchRespectsDynamicNamespacesAndFriendlyNames() {
        var index =
                new BlockSearchIndex(
                        Map.of(
                                "minecraft:torch",
                                "Torch",
                                "crystalmod:torch",
                                "Crystal Torch",
                                "crystalmod:glow",
                                "Blue Crystal",
                                "different:crystal",
                                "Ruby Crystal"),
                        Map.of("crystalmod", "Crystal Worlds"));
        assertEquals(List.of("minecraft:torch"), index.search("TORCH", "minecraft", id -> true));
        assertEquals(
                List.of("crystalmod:glow", "crystalmod:torch"),
                index.search("crystal", "crystalmod", id -> true));
        assertEquals(3, index.search("crystal", "", id -> true).size());
        assertEquals("Crystal Worlds", index.sourceName("crystalmod"));
        assertEquals("different", index.sourceName("different"));
        assertEquals(List.of(), index.search("torch", "different", id -> true));
        assertEquals(2, index.search("crystal", "", id -> !id.startsWith("different:")).size());
    }

    @Test
    void largeRegistryRetainsIndexAndFindsNamesWithoutModelWork() {
        var names = new HashMap<String, String>();
        for (int i = 0; i < 12000; i++)
            names.put("mod" + (i % 80) + ":block_" + i, "Glowing Crystal " + i);
        var index = new BlockSearchIndex(names, Map.of());
        var sources = index.sources();
        for (int i = 0; i < 100; i++)
            assertEquals(150, index.search("crystal", "mod7", id -> true).size());
        assertSame(sources, index.sources());
        assertEquals(81, sources.size());
        assertFalse(index.hasSource("removedmod"));
    }
}
