package dev.stillbloom.gui.layout;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class SmoothScrollTest {
    @Test
    void wheelAccumulationKeepsVisiblePositionContinuousAndClampsItsTarget() {
        var scroll = new SmoothScroll();
        scroll.reset(0, 100, 0);
        scroll.wheel(40, 100, 0);
        assertEquals(40, scroll.target());
        assertEquals(0, scroll.value(0));
        long next = 30_000_000L;
        double before = scroll.value(next);
        scroll.wheel(40, 100, next);
        assertEquals(before, scroll.value(next));
        assertEquals(80, scroll.target());
        scroll.wheel(40, 100, next);
        assertEquals(100, scroll.target());
        assertEquals(100, scroll.value(next + SmoothScroll.DURATION_NANOS));
        assertFalse(scroll.moving(next + SmoothScroll.DURATION_NANOS));
    }

    @Test
    void positionsDependOnElapsedTimeRatherThanNumberOfFrames() {
        var sparse = new SmoothScroll();
        var dense = new SmoothScroll();
        sparse.reset(0, 400, 0);
        dense.reset(0, 400, 0);
        sparse.wheel(180, 400, 0);
        dense.wheel(180, 400, 0);
        for (long t = 0; t < 65_000_000L; t += 1_000_000L) dense.value(t);
        assertEquals(sparse.value(65_000_000L), dense.value(65_000_000L));
        double previous = 0;
        for (long t = 0; t <= SmoothScroll.DURATION_NANOS; t += 1_000_000L) {
            double value = dense.value(t);
            assertTrue(value >= previous && value <= 180);
            previous = value;
        }
        assertEquals(180, dense.value(SmoothScroll.DURATION_NANOS));
    }

    @Test
    void reversalIsContinuousAndDirectInputCancelsMotion() {
        var scroll = new SmoothScroll();
        scroll.reset(30, 200, 0);
        scroll.wheel(90, 200, 0);
        long now = 40_000_000L;
        double shown = scroll.value(now);
        scroll.wheel(-140, 200, now);
        assertEquals(shown, scroll.value(now));
        assertEquals(0, scroll.target());
        assertTrue(scroll.value(now + 10_000_000L) < shown);
        scroll.reset(81, 200, now + 10_000_000L);
        assertEquals(81, scroll.value(now + 20_000_000L));
        assertFalse(scroll.moving(now + 20_000_000L));
    }

    @Test
    void shrinkingBoundsNeverOverscrollsAndFractionalWheelInputAccumulates() {
        var scroll = new SmoothScroll();
        scroll.reset(90, 200, 0);
        scroll.wheel(90, 200, 0);
        scroll.bounds(60, 50_000_000L);
        assertEquals(60, scroll.value(50_000_000L));
        assertEquals(60, scroll.target());
        scroll.reset(0, 100, 0);
        for (int i = 0; i < 12; i++) scroll.wheel(.125, 100, i * 1_000_000L);
        assertEquals(1.5, scroll.target(), 1e-10);
        assertEquals(2, scroll.pixels(200_000_000L));
    }
}
