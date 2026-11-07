package dev.stillbloom.render;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class BloomPassBoundsTest {
    @Test
    void extractionTargetsSelectTheirOwnGroupAndProbeFallsBackToAllSources() {
        assertEquals(0, BloomPass.sourceGroup("group_0_down_2"));
        assertEquals(12, BloomPass.sourceGroup("group_12_down_2"));
        assertEquals(Integer.MAX_VALUE, BloomPass.sourceGroup("group_2147483647_down_2"));
        for (String name :
                new String[] {
                    "down_2",
                    "activity_result",
                    "group_",
                    "group_12",
                    "group_x_down_2",
                    "group_2147483648_down_2"
                }) assertEquals(-1, BloomPass.sourceGroup(name));
    }
}
