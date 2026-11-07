package dev.stillbloom.gui;

import static org.junit.jupiter.api.Assertions.*;

import dev.stillbloom.config.BloomConfig;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.util.*;
import java.util.stream.Collectors;

class SettingCatalogTest {
    @Test
    void everyExistingGlobalSettingHasExactlyOneCanonicalSearchDestination() {
        Set<String> fields =
                Arrays.stream(BloomConfig.class.getFields())
                        .filter(f -> !Modifier.isStatic(f.getModifiers()))
                        .map(f -> f.getName())
                        .collect(Collectors.toSet());
        Set<String> ids =
                SettingCatalog.ALL.stream()
                        .map(SettingCatalog.Entry::id)
                        .collect(Collectors.toSet());
        assertEquals(SettingCatalog.ALL.size(), ids.size(), "No ambiguous duplicate controls");
        assertTrue(
                ids.containsAll(fields),
                () ->
                        "Missing settings: "
                                + fields.stream().filter(f -> !ids.contains(f)).toList());
        for (var entry : SettingCatalog.ALL) {
            assertFalse(entry.title().isBlank());
            assertFalse(entry.description().isBlank());
            assertTrue(entry.page() >= 0 && entry.page() <= 4);
        }
        assertEquals(3, SettingCatalog.get("bloomRange").page());
        assertEquals(3, SettingCatalog.get("fadeFraction").page());
        for (String id : List.of("pixelSize", "distancePixels", "flareStyle"))
            assertEquals(
                    1,
                    SettingCatalog.get(id).page(),
                    "Cost links must point to the original appearance setting");
    }

    @Test
    void heldSettingsHaveOneDedicatedDestination() {
        assertFalse(SettingCatalog.GROUPS.contains("items"));
        for (String id :
                List.of("heldItemBloom", "otherPlayerItemBloom", "heldItemRange", "editItems")) {
            var entry = SettingCatalog.get(id);
            assertEquals(4, entry.page());
            assertEquals("Held Items", entry.location());
            assertTrue(SettingCatalog.search("held").contains(entry));
        }
    }

    @Test
    void searchFindsPlayerTermsAndTechnicalNamesWithoutChangingData() {
        var config = new BloomConfig();
        float strength = config.intensity;
        assertTrue(ids("glow smooth").contains("smoothBlend"));
        assertTrue(ids("render distance").contains("bloomRange"));
        assertTrue(ids("knee").contains("softKnee"));
        assertTrue(ids("other players").contains("otherPlayerItemBloom"));
        assertTrue(ids("mask").containsAll(Set.of("editItems", "blockBrowser")));
        assertTrue(ids("nothing-matches-this-token").isEmpty());
        assertEquals(SettingCatalog.ALL, SettingCatalog.search("   "));
        assertEquals(strength, config.intensity);
        assertTrue(config.enabled);
    }

    @Test
    void uniformEmissionHasOneGlobalDestinationSeparateFromTextureDetail() {
        var emission = SettingCatalog.get("uniformEmission");
        assertEquals(1, emission.page());
        assertEquals("response", emission.group());
        assertEquals("Uniform emission", emission.title());
        assertEquals(Set.of("uniformEmission"), ids("directional shading"));
        assertEquals(Set.of("uniformEmission"), ids("uniform emission"));
        var detail = SettingCatalog.get("preserveDetail");
        assertEquals("Keep block detail", detail.title());
        assertEquals("look", detail.group());
    }

    private static Set<String> ids(String query) {
        return SettingCatalog.search(query).stream()
                .map(SettingCatalog.Entry::id)
                .collect(Collectors.toSet());
    }
}
