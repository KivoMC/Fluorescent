package dev.stillbloom.mask;

import static org.junit.jupiter.api.Assertions.*;

import dev.stillbloom.config.BloomOverride;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.*;

class BlockTextureLinkTest {
    static final String SOURCE = "minecraft:copper_bulb";
    static final String[] PREFIXES = {"", "exposed_", "weathered_", "oxidized_"};
    static final String[] SUFFIXES = {"", "_powered", "_lit", "_lit_powered"};
    @TempDir Path dir;

    static String texture(String prefix, String suffix) {
        return "minecraft:block/" + prefix + "copper_bulb" + suffix;
    }

    static Set<String> uses(boolean lit, boolean powered) {
        var result = new TreeSet<String>();
        for (var face : List.of("up", "down", "north", "south", "east", "west"))
            result.add("lit=" + lit + ",powered=" + powered + "|" + face);
        return result;
    }

    static Map<String, List<BlockLinks.Texture>> bulbs() {
        var result = new TreeMap<String, List<BlockLinks.Texture>>();
        for (var prefix : PREFIXES) {
            var slots = new ArrayList<BlockLinks.Texture>();
            for (int i = 0; i < SUFFIXES.length; i++)
                slots.add(
                        new BlockLinks.Texture(
                                texture(prefix, SUFFIXES[i]), 16, 16, uses(i >= 2, (i & 1) == 1)));
            // I change model order to check that it can't decide which texture receives the paint.
            Collections.reverse(slots);
            result.put("minecraft:" + prefix + "copper_bulb", slots);
            result.put("minecraft:waxed_" + prefix + "copper_bulb", slots);
        }
        return result;
    }

    static TextureMasks.Mask paint(int x) {
        var m = new TextureMasks.Mask(16, 16);
        m.set(x, 3, true);
        return m;
    }

    @Test
    void allOxidationAndWaxVariantsPairCorrespondingLitAndPoweredStates() {
        var textures = bulbs();
        var links = BlockLinks.textureLinks(SOURCE, textures.keySet(), textures);
        assertEquals(12, links.size());
        for (var prefix : List.of("exposed_", "weathered_", "oxidized_"))
            for (var suffix : SUFFIXES)
                assertEquals(texture("", suffix), links.get(texture(prefix, suffix)));
        assertFalse(links.containsKey(texture("", "_lit")));
    }

    @Test
    void rebuildingAnRc10GroupSharesBothPaintedStatesWithoutPaintingUnlitStates() {
        var textures = bulbs();
        var m = new TextureMasks();
        String lit = texture("", "_lit"), powered = texture("", "_lit_powered");
        m.put(SOURCE, lit, paint(2));
        m.put(SOURCE, powered, paint(11));
        var style = new BloomOverride();
        style.color = 0x00AAFF;
        m.setOverride(SOURCE, style);
        BlockLinks.link(m, textures.keySet(), SOURCE, false, Map.of());
        assertTrue(m.group(SOURCE).textureLinks.isEmpty());
        var path = dir.resolve("rc10.json");
        assertTrue(m.save(path));
        m = TextureMasks.load(path);
        BlockLinks.link(m, textures.keySet(), SOURCE, false, textures);
        for (var prefix : PREFIXES)
            for (var wax : List.of("", "waxed_")) {
                String block = "minecraft:" + wax + prefix + "copper_bulb";
                assertSame(m.mask(SOURCE, lit), m.mask(block, texture(prefix, "_lit")));
                assertSame(m.mask(SOURCE, powered), m.mask(block, texture(prefix, "_lit_powered")));
                assertNull(m.mask(block, texture(prefix, "")));
                assertNull(m.mask(block, texture(prefix, "_powered")));
                assertEquals(0x00AAFF, m.profile(block).override.color);
            }
        assertEquals(2, m.group(SOURCE).profile.textures.size());
        assertTrue(m.mask(SOURCE, lit).get(2, 3));
        assertTrue(m.mask(SOURCE, powered).get(11, 3));
        String oxide = "minecraft:oxidized_copper_bulb", oxideLit = texture("oxidized_", "_lit");
        m.put(oxide, oxideLit, paint(5));
        assertTrue(m.mask(SOURCE, lit).get(5, 3));
        assertFalse(m.mask(SOURCE, powered).get(5, 3));
        assertTrue(m.save(path));
        var loaded = TextureMasks.load(path);
        assertSame(loaded.mask(SOURCE, lit), loaded.mask(oxide, oxideLit));
        BlockLinks.unlink(loaded, List.of(oxide));
        assertTrue(loaded.mask(oxide, oxideLit).get(5, 3));
        loaded.reset(SOURCE, lit);
        assertNull(loaded.mask("minecraft:weathered_copper_bulb", texture("weathered_", "_lit")));
        assertTrue(loaded.mask(oxide, oxideLit).get(5, 3));
    }

    @Test
    void multiTextureFacesMatchByUsageInsteadOfAlphabeticalNames() {
        var source =
                List.of(
                        new BlockLinks.Texture("a:top", 16, 16, Set.of("|up")),
                        new BlockLinks.Texture("a:side", 16, 16, Set.of("|north", "|east")));
        var target =
                List.of(
                        new BlockLinks.Texture("b:z", 16, 16, Set.of("|up")),
                        new BlockLinks.Texture("b:a", 16, 16, Set.of("|east", "|north")));
        var links =
                BlockLinks.textureLinks(
                        "a:block",
                        List.of("a:block", "b:block"),
                        Map.of("a:block", source, "b:block", target));
        assertEquals(Map.of("b:z", "a:top", "b:a", "a:side"), links);
    }

    @Test
    void ambiguousUsageAndDifferentResolutionsRemainUnpaired() {
        var source =
                List.of(
                        new BlockLinks.Texture("a:one", 16, 16, Set.of("|north")),
                        new BlockLinks.Texture("a:two", 16, 16, Set.of("|north")));
        var target =
                List.of(
                        new BlockLinks.Texture("b:one", 16, 16, Set.of("|north")),
                        new BlockLinks.Texture("b:two", 16, 16, Set.of("|north")));
        assertTrue(
                BlockLinks.textureLinks(
                                "a:block",
                                List.of("a:block", "b:block"),
                                Map.of("a:block", source, "b:block", target))
                        .isEmpty());
        assertTrue(
                BlockLinks.textureLinks(
                                "a:block",
                                List.of("a:block", "b:block"),
                                Map.of(
                                        "a:block",
                                        source,
                                        "b:block",
                                        List.of(
                                                new BlockLinks.Texture(
                                                        "b:large", 32, 32, Set.of("|north")))))
                        .isEmpty());
    }

    @Test
    void aTextureUsedForConflictingRolesAcrossMembersIsNotOverwrittenByIterationOrder() {
        var source =
                List.of(
                        new BlockLinks.Texture("a:top", 16, 16, Set.of("|up")),
                        new BlockLinks.Texture("a:side", 16, 16, Set.of("|north")));
        var b =
                List.of(
                        new BlockLinks.Texture("shared:part", 16, 16, Set.of("|up")),
                        new BlockLinks.Texture("b:side", 16, 16, Set.of("|north")));
        var c =
                List.of(
                        new BlockLinks.Texture("c:top", 16, 16, Set.of("|up")),
                        new BlockLinks.Texture("shared:part", 16, 16, Set.of("|north")));
        var descriptions = Map.of("a:block", source, "b:block", b, "c:block", c);
        var links =
                BlockLinks.textureLinks(
                        "a:block", List.of("a:block", "b:block", "c:block"), descriptions);
        assertFalse(links.containsKey("shared:part"));
        assertEquals(
                links,
                BlockLinks.textureLinks(
                        "a:block", List.of("c:block", "b:block", "a:block"), descriptions));
    }
}
