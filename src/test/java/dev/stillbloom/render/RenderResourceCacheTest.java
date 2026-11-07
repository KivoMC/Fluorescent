package dev.stillbloom.render;

import static org.junit.jupiter.api.Assertions.*;

import dev.stillbloom.config.BloomConfig;
import dev.stillbloom.config.BloomStyle;
import dev.stillbloom.render.source.SourceMask;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

class RenderResourceCacheTest {
    @Test
    void takingTransfersOwnershipAndReinsertingRefreshesItsIdleAge() {
        var closed = new ArrayList<String>();
        var cache = new BloomRenderer.ChainCache<String, String>(2, 100, 10, closed::add);
        cache.put("a", "first", 40, 0);
        cache.put("b", "second", 30, 1);
        assertEquals("first", cache.take(new String("a"), 3));
        assertEquals(30, cache.bytes());
        cache.put("a", "first", 40, 5);
        cache.expire(11);
        assertEquals(List.of("second"), closed);
        assertEquals("first", cache.take("a", 12));
        cache.close();
        assertEquals(List.of("second"), closed);
        assertEquals(0, cache.bytes());
    }

    @Test
    void countAndByteLimitsEvictOldestInactiveResource() {
        var closed = new ArrayList<String>();
        var cache = new BloomRenderer.ChainCache<String, String>(2, 100, 100, closed::add);
        cache.put("a", "a", 40, 0);
        cache.put("b", "b", 40, 1);
        cache.put("c", "c", 40, 2);
        assertEquals(List.of("a"), closed);
        assertEquals(2, cache.size());
        assertNull(cache.take("a", 3));
        cache.put("d", "d", 80, 4);
        assertEquals(List.of("a", "b", "c"), closed);
        assertEquals(80, cache.bytes());
        assertEquals(1, cache.size());
        cache.close();
        cache.close();
        assertEquals(List.of("a", "b", "c", "d"), closed);
    }

    @Test
    void oversizedOrDisabledEntriesAreClosedImmediately() {
        for (int mode = 0; mode < 3; mode++) {
            var closed = new ArrayList<String>();
            var cache =
                    new BloomRenderer.ChainCache<String, String>(
                            mode == 0 ? 0 : 2, 100, mode == 1 ? 0 : 10, closed::add);
            cache.put("a", "a", mode == 2 ? Long.MAX_VALUE : 40, 0);
            assertEquals(List.of("a"), closed);
            assertEquals(0, cache.size());
            assertEquals(0, cache.bytes());
        }
    }

    @Test
    void replacementExpiryAndCloseDisposeEachResourceExactlyOnce() {
        var closed = new ArrayList<String>();
        var cache = new BloomRenderer.ChainCache<String, String>(2, 100, 10, closed::add);
        cache.put("a", "old", 20, 0);
        cache.put("a", "new", 30, 1);
        assertEquals(List.of("old"), closed);
        assertNull(cache.take("a", 11));
        assertEquals(List.of("old", "new"), closed);
        cache.put("b", "b", 40, 12);
        cache.close();
        assertEquals(List.of("old", "new", "b"), closed);
        assertNull(cache.take("b", 13));
    }

    @Test
    void requestedEntryCanBeTakenBeforeOutgoingEntryUsesCapacity() {
        var closed = new ArrayList<String>();
        var cache = new BloomRenderer.ChainCache<String, String>(1, 100, 10, closed::add);
        cache.put("old-set", "requested", 100, 0);
        var reused = cache.take("old-set", 1);
        cache.put("new-set", "outgoing", 100, 1);
        assertEquals("requested", reused);
        assertTrue(closed.isEmpty());
        cache.close();
        assertEquals(List.of("outgoing"), closed);
    }

    @Test
    void idleAgeWorksAcrossNanoTimeWraparound() {
        var closed = new ArrayList<String>();
        var cache = new BloomRenderer.ChainCache<String, String>(1, 100, 10, closed::add);
        cache.put("a", "a", 10, Long.MAX_VALUE - 5);
        assertEquals("a", cache.take("a", Long.MIN_VALUE + 2));
        cache.put("a", "a", 10, Long.MAX_VALUE - 5);
        assertNull(cache.take("a", Long.MIN_VALUE + 4));
        assertEquals(List.of("a"), closed);
    }

    @Test
    void sharedHistoryAndTransientDistanceTargetsDoNotInflateInactiveChainWeight() {
        var config = new BloomConfig();
        var groups = List.of(new SourceMask.RenderGroup(0, BloomStyle.global(config)));
        var graph =
                BloomGraph.createGroupedConfig(1920, 1080, config, true, true, groups, false, true);
        long bytes = BloomRenderer.estimateRetainedBytes(1920, 1080, config, groups, graph);
        assertEquals(graph.passes().size() * 16384L, bytes);
        config.pixelSize = 4;
        config.distancePixels = true;
        config.farPixelSize = 2;
        graph = BloomGraph.createGroupedConfig(1920, 1080, config, true, true, groups, false, true);
        long distanceBytes =
                graph.internalTargets().entrySet().stream()
                        .filter(e -> DistanceTargets.carriesDistance(e.getKey()))
                        .mapToLong(
                                e ->
                                        8L
                                                * e.getValue().width().orElseThrow()
                                                * e.getValue().height().orElseThrow())
                        .sum();
        assertEquals(
                graph.passes().size() * 16384L,
                BloomRenderer.estimateRetainedBytes(1920, 1080, config, groups, graph));
        assertTrue(distanceBytes > 0);
    }
}
