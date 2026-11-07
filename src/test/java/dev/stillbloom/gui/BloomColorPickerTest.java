package dev.stillbloom.gui;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.List;

class BloomColorPickerTest {
    @Test
    void partialOrInvalidTextDoesNotBecomeAColor() {
        assertEquals(0x12ABEF, BloomColors.parse(" #12abef "));
        for (String invalid :
                new String[] {"", "#", "FFF", "#12345", "1234567", "-12345", "GG0000", "0xFFFF"})
            assertNull(BloomColors.parse(invalid));
        assertNull(BloomColors.parse(null));
        assertEquals("00000F", BloomColors.hex(15));
        assertEquals(0, BloomColors.channel("0"));
        assertEquals(255, BloomColors.channel("255"));
        assertEquals(7, BloomColors.channel("007"));
        for (String invalid : new String[] {"", "-1", "256", "999", "1.0", "1e2", "+10", "1234"})
            assertNull(BloomColors.channel(invalid));
        assertNull(BloomColors.channel(null));
    }

    @Test
    void rgbHsvRoundTripsPreserveSelectedColor() {
        for (int r = 0; r <= 255; r += 5)
            for (int g = 0; g <= 255; g += 5)
                for (int b = 0; b <= 255; b += 5) {
                    int rgb = r << 16 | g << 8 | b;
                    float[] hsv = BloomColors.hsv(rgb, .37f);
                    assertEquals(rgb, BloomColors.rgb(hsv[0], hsv[1], hsv[2]));
                }
        assertEquals(0xFF0000, BloomColors.rgb(0, 1, 1));
        assertEquals(0x00FF00, BloomColors.rgb(1f / 3, 1, 1));
        assertEquals(0x0000FF, BloomColors.rgb(2f / 3, 1, 1));
        assertEquals(0xFF0000, BloomColors.rgb(1, 1, 1));
    }

    @Test
    void grayscaleRetainsTheLastHueAndBlackStaysBlack() {
        for (int rgb : new int[] {0, 0x808080, 0xFFFFFF}) {
            float[] hsv = BloomColors.hsv(rgb, .61f);
            assertEquals(.61f, hsv[0]);
            assertEquals(0, hsv[1]);
            assertEquals(rgb, BloomColors.rgb(hsv[0], hsv[1], hsv[2]));
        }
        assertEquals(0, BloomColors.rgb(.61f, .9f, 0));
        assertEquals(0xFFFFFF, BloomColors.rgb(.61f, 0, 1));
        assertNotEquals(0, BloomColors.rgb(.61f, .9f, 1));
    }

    @Test
    void hexParsingPreservesChannelsAndNaturalMarker() {
        assertEquals(0x1256EF, BloomColors.parse(" #1256ef "));
        assertEquals(0, BloomColors.parse("000000"));
        for (var text : List.of("#12", "blue", "GG0000", "#00000000", "-12345", ""))
            assertNull(BloomColors.parse(text));
    }
}
