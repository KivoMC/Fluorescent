package dev.stillbloom.render;

import static org.junit.jupiter.api.Assertions.*;

import dev.stillbloom.config.BloomConfig;

import org.junit.jupiter.api.Test;

class BloomIdleTest {
    @Test
    void staleReadbacksCannotHideANewView() {
        var state = new BloomActivity.IdleState();
        state.observe(1);
        long ticket = state.begin(0);
        state.observe(2);
        state.complete(ticket, true, 1);
        assertFalse(state.idle());
        assertFalse(state.shouldProbe(1));
        ticket = state.begin(250_000_000L);
        state.complete(ticket, true, 250_000_001L);
        assertTrue(state.idle());
        state.observe(3);
        assertFalse(state.idle());
    }

    @Test
    void idleChecksAreThrottledAndWakeOnRealContribution() {
        var state = new BloomActivity.IdleState();
        state.observe(1);
        long ticket = state.begin(0);
        assertFalse(state.shouldProbe(1_000_000_000L));
        state.complete(ticket, true, 1);
        assertTrue(state.idle());
        assertFalse(state.shouldProbe(90_000_000L));
        assertTrue(state.shouldProbe(110_000_000L));
        ticket = state.begin(110_000_000L);
        state.complete(ticket, false, 110_000_001L);
        assertFalse(state.idle());
        state.reset();
        assertTrue(state.shouldProbe(0));
    }

    @Test
    void activityReductionCoversOddSizesAndEndsInOnePixel() {
        for (int width : new int[] {1, 7, 1281, 1919}) {
            var config = BloomActivity.createConfig(width, 721, new BloomConfig(), false);
            assertEquals(
                    BloomGraph.id("post/extract_auto"),
                    config.passes().getFirst().fragmentShaderId());
            assertEquals(BloomActivity.RESULT, config.passes().getLast().outputTarget());
            assertFalse(config.internalTargets().containsKey(BloomActivity.RESULT));
            for (var pass : config.passes().subList(1, config.passes().size()))
                assertEquals(BloomGraph.id("post/activity_max"), pass.fragmentShaderId());
            // I check every edge pixel so rounding can't turn the visibility probe into sparse
            // sampling.
            int n = BloomGraph.reducedSize(width, 2);
            while (n > 1) {
                int reduced = BloomGraph.reducedSize(n, 4);
                assertEquals(reduced - 1, (n - 1) / 4);
                n = reduced;
            }
        }
    }
}
