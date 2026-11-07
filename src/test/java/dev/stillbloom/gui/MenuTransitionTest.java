package dev.stillbloom.gui;

import static org.junit.jupiter.api.Assertions.*;

import dev.stillbloom.gui.layout.MenuTransition;

import org.junit.jupiter.api.Test;

class MenuTransitionTest {
    @Test
    void openIsTimeBasedBoundedAndMonotonic() {
        var transition = new MenuTransition(1_000);
        float previous = 0;
        for (long elapsed = 0; elapsed <= 500_000_000L; elapsed += 3_700_000L) {
            float a = transition.alpha(1_000 + elapsed);
            assertTrue(a >= previous);
            assertTrue(a >= 0 && a <= 1);
            previous = a;
        }
        assertEquals(0, transition.alpha(1_000));
        assertEquals(1, transition.alpha(1_000 + MenuTransition.OPEN_NANOS));
        assertFalse(transition.closing());
    }

    @Test
    void closeDuringOpeningDoesNotJumpAndRetainsItsDeadline() {
        var transition = new MenuTransition(0);
        long now = 75_000_000L;
        float original = transition.alpha(now);
        transition.close(now);
        assertEquals(original, transition.alpha(now));
        assertTrue(transition.closing());
        transition.close(now + 30_000_000L);
        assertFalse(transition.finished(now + MenuTransition.CLOSE_NANOS - 1));
        assertTrue(transition.finished(now + MenuTransition.CLOSE_NANOS));
        assertEquals(0, transition.alpha(now + MenuTransition.CLOSE_NANOS));
    }

    @Test
    void rapidReopenReversesFromCurrentOpacity() {
        var transition = new MenuTransition(0);
        transition.close(MenuTransition.OPEN_NANOS);
        long halfway = MenuTransition.OPEN_NANOS + 80_000_000L;
        float alpha = transition.alpha(halfway);
        transition.reopen(halfway);
        assertFalse(transition.closing());
        assertEquals(alpha, transition.alpha(halfway));
        assertEquals(1, transition.alpha(halfway + MenuTransition.OPEN_NANOS));
    }

    @Test
    void viewChangesAreBriefAndCannotCancelClose() {
        var transition = new MenuTransition(0);
        transition.view(MenuTransition.OPEN_NANOS);
        assertEquals(.68f, transition.alpha(MenuTransition.OPEN_NANOS));
        assertEquals(1, transition.alpha(MenuTransition.OPEN_NANOS + MenuTransition.VIEW_NANOS));
        transition.close(500_000_000L);
        transition.view(510_000_000L);
        assertTrue(transition.closing());
        assertEquals(0, transition.alpha(675_000_000L));
    }

    @Test
    void opacityKeepsRgbAndOriginalTransparency() {
        assertEquals(0x407DE5E1, MenuPresentation.color(0x807DE5E1, .5f));
        assertEquals(0x007DE5E1, MenuPresentation.color(0xFF7DE5E1, 0));
        assertEquals(0x007DE5E1, MenuPresentation.color(0x007DE5E1, .5f));
        assertEquals(0xFF7DE5E1, MenuPresentation.color(0xFF7DE5E1, 1));
    }
}
