package dev.stillbloom.render.source;

import static org.junit.jupiter.api.Assertions.*;

import dev.stillbloom.config.BloomConfig;
import dev.stillbloom.config.BloomStyle;

import org.junit.jupiter.api.Test;

import java.util.*;

class SourceColorTest {
    @Test
    void colorMetadataInvalidatesGroupsAndBlackRemainsAnOverride() {
        var style = BloomStyle.global(new BloomConfig());
        assertNotEquals(
                new SourceMask.RenderGroup(0, style, false, false),
                new SourceMask.RenderGroup(0, style, false, true));
        assertTrue(new SourceMask.RenderGroup(0, style).colored());
        assertTrue(new SourceMask.RenderGroup(0, style, false).colored());
        assertEquals(0xFF000000, SourceMask.packColor(0));
    }

    @Test
    void colorPackingAndHexParsingPreserveChannelsAndNaturalMarker() {
        assertEquals(0xFFEF5612, SourceMask.packColor(0x1256EF));
        assertEquals(0xFF000000, SourceMask.packColor(0));
    }
}
