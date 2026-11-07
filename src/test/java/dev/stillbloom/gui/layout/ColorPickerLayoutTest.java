package dev.stillbloom.gui.layout;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

class ColorPickerLayoutTest {
    private record Rect(String name, int x, int y, int w, int h) {
        boolean intersects(Rect other) {
            return x < other.x + other.w
                    && x + w > other.x
                    && y < other.y + other.h
                    && y + h > other.y;
        }
    }

    @Test
    void allControlsRemainVisibleAndSeparateAtSupportedGuiSizes() {
        for (int[] size :
                List.of(
                        new int[] {320, 180},
                        new int[] {320, 240},
                        new int[] {355, 200},
                        new int[] {427, 240},
                        new int[] {480, 270},
                        new int[] {640, 360},
                        new int[] {854, 480},
                        new int[] {1280, 720})) {
            var layout = ColorPickerLayout.fit(size[0], size[1]);
            var controls = controls(layout);
            assertEquals(
                    23,
                    controls.size(),
                    "HSV + hue + preview + hex + RGB + copy/paste +12swatches +2footer controls");
            for (var control : controls) {
                assertTrue(control.w > 0 && control.h > 0, control.name);
                assertTrue(
                        control.x >= layout.panelX() + 1 && control.y >= layout.panelY() + 1,
                        control.name);
                assertTrue(
                        control.x + control.w <= layout.panelX() + layout.panelW() - 1,
                        control.name);
                assertTrue(
                        control.y + control.h <= layout.panelY() + layout.panelH() - 1,
                        control.name);
                for (var other : controls)
                    if (control != other)
                        assertFalse(
                                control.intersects(other),
                                () ->
                                        size[0]
                                                + "x"
                                                + size[1]
                                                + ": "
                                                + control.name
                                                + " overlaps "
                                                + other.name);
            }
        }
    }

    @Test
    void compactModeKeepsReadableInputAndPointerTargets() {
        var small = ColorPickerLayout.fit(320, 180);
        assertTrue(small.compact());
        assertTrue(small.size() >= 72);
        assertTrue(
                small.channelW() - 8 >= 40, "Three-digit RGB values fit with normal font padding");
        assertTrue(small.swatchH() >= 16, "Swatches remain useful pointer targets");
        assertTrue(small.copyY() + small.fieldH() <= small.swatchY() - 2);
        assertTrue(small.swatchY() + small.swatchH() <= small.footerY() - 2);
        assertFalse(ColorPickerLayout.fit(854, 480).compact());
    }

    private static List<Rect> controls(ColorPickerLayout l) {
        var result = new ArrayList<Rect>();
        result.add(new Rect("HSV", l.panelX() + 12, l.bodyY(), l.size(), l.size()));
        result.add(new Rect("Hue", l.panelX() + l.size() + 19, l.bodyY(), 14, l.size()));
        result.add(
                new Rect(
                        "Color preview", l.rightX(), l.bodyY(), l.rightW(), l.compact() ? 16 : 23));
        result.add(new Rect("Hex", l.rightX() + 25, l.hexY(), l.rightW() - 25, l.fieldH()));
        int channel = l.channelW();
        for (int i = 0; i < 3; i++)
            result.add(
                    new Rect(
                            "RGB" + i,
                            l.rightX() + i * (channel + 4) + (l.compact() ? 8 : 0),
                            l.rgbY(),
                            (i == 2 ? l.rightW() - 2 * (channel + 4) : channel)
                                    - (l.compact() ? 8 : 0),
                            l.fieldH()));
        int half = (l.rightW() - 4) / 2;
        result.add(new Rect("Copy", l.rightX(), l.copyY(), half, l.fieldH()));
        result.add(
                new Rect(
                        "Paste",
                        l.rightX() + half + 4,
                        l.copyY(),
                        l.rightW() - half - 4,
                        l.fieldH()));
        int swatch = (l.panelW() - 24 - 3 * 11) / 12;
        for (int i = 0; i < 12; i++)
            result.add(
                    new Rect(
                            "Swatch" + i,
                            l.panelX() + 12 + i * (swatch + 3),
                            l.swatchY(),
                            swatch,
                            l.swatchH()));
        result.add(new Rect("Natural / Inherit", l.panelX() + 12, l.footerY(), 92, 20));
        result.add(new Rect("Done", l.panelX() + l.panelW() - 84, l.footerY(), 72, 20));
        return result;
    }
}
