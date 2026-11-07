package dev.stillbloom.render;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.*;

class HistoryTargetPoolTest {
    @Test
    void changingVisibleStyleSetsReusesTheSameFullPrecisionTargets() {
        var created = new ArrayList<Object>();
        var closed = new ArrayList<Object>();
        var pool =
                new BloomHistory.TargetPool<>(
                        16,
                        () -> {
                            var o = new Object();
                            created.add(o);
                            return o;
                        },
                        closed::add);
        pool.select(4, 0);
        Object first = pool.get(0);
        pool.select(6, 1);
        Object sixth = pool.get(5);
        pool.select(5, 2);
        pool.trim(3, 64);
        pool.select(6, 4);
        pool.select(4, 5);
        pool.select(6, 6);
        assertEquals(6, created.size());
        assertSame(first, pool.get(0));
        assertSame(sixth, pool.get(5));
        assertTrue(closed.isEmpty());
        pool.close();
        pool.close();
        assertEquals(6, new HashSet<>(closed).size());
        assertEquals(6, closed.size());
    }

    @Test
    void inactiveBudgetNeverEvictsVisibleHistory() {
        var closed = new ArrayList<Object>();
        var pool = new BloomHistory.TargetPool<>(16, Object::new, closed::add);
        pool.select(6, 0);
        pool.select(3, 1);
        pool.trim(2, 32);
        assertEquals(5, pool.size());
        assertEquals(32, pool.inactiveBytes());
        pool.trim(3, 0);
        assertEquals(3, pool.size());
        assertEquals(0, pool.inactiveBytes());
        assertEquals(3, closed.size());
        assertNotNull(pool.get(2));
        assertThrows(IndexOutOfBoundsException.class, () -> pool.get(3));
        pool.close();
        assertEquals(6, closed.size());
    }

    @Test
    void idleAgeStartsWhenTheTargetLeavesTheVisibleSet() {
        var closed = new ArrayList<Object>();
        var pool = new BloomHistory.TargetPool<>(16, Object::new, closed::add);
        pool.select(6, 0);
        pool.select(4, 90_000_000_000L);
        pool.trim(99_999_999_999L, 64);
        assertTrue(closed.isEmpty());
        pool.trim(100_000_000_000L, 64);
        assertEquals(2, closed.size());
        assertEquals(4, pool.size());
        pool.close();
    }

    @Test
    void reactivationRestartsTheInactiveRetentionWindow() {
        var closed = new ArrayList<Object>();
        var pool = new BloomHistory.TargetPool<>(16, Object::new, closed::add);
        pool.select(2, 0);
        pool.select(1, 1);
        pool.select(2, 9_000_000_000L);
        pool.select(1, 12_000_000_000L);
        pool.trim(20_000_000_000L, 64);
        assertTrue(closed.isEmpty());
        pool.trim(22_000_000_000L, 64);
        assertEquals(1, closed.size());
        pool.close();
    }

    @Test
    void partialAllocationFailureCanBeCleanedUpWithoutLeakingEarlierTargets() {
        var created = new ArrayList<Object>();
        var closed = new ArrayList<Object>();
        var pool =
                new BloomHistory.TargetPool<>(
                        16,
                        () -> {
                            if (created.size() == 2) throw new IllegalStateException("allocation");
                            var o = new Object();
                            created.add(o);
                            return o;
                        },
                        closed::add);
        assertThrows(IllegalStateException.class, () -> pool.select(4, 0));
        pool.close();
        assertEquals(new HashSet<>(created), new HashSet<>(closed));
        assertEquals(2, closed.size());
    }
}
