package dev.stillbloom.render;

import static org.junit.jupiter.api.Assertions.*;

import com.mojang.blaze3d.framegraph.FrameGraphBuilder;
import com.mojang.blaze3d.resource.CrossFrameResourcePool;
import com.mojang.blaze3d.resource.ResourceDescriptor;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

class ScopedAllocatorTest {
    private static final class Descriptor implements ResourceDescriptor<Object> {
        int allocated, freed, prepared;
        boolean failAllocation, failFree, failPrepare;
        long bytes = 10;

        public Object allocate() {
            if (failAllocation) throw new IllegalStateException("allocation");
            allocated++;
            return new Object();
        }

        public void free(Object resource) {
            freed++;
            if (failFree) throw new IllegalArgumentException("free");
        }

        public void prepare(Object resource) {
            prepared++;
            if (failPrepare) throw new IllegalStateException("prepare");
        }
    }

    private static ScopedAllocator cached(AtomicLong clock, long budget) {
        return new ScopedAllocator(
                budget,
                4_000_000_000L,
                clock::get,
                d -> d instanceof Descriptor target ? target.bytes : 0);
    }

    @Test
    void returningAfterThreeInactiveFramesReusesTargetsUntilTheIdleDeadline() {
        var clock = new AtomicLong();
        var scope = cached(clock, 100);
        var descriptor = new Descriptor();
        var pool = new CrossFrameResourcePool(3);
        scope.execute(frame(descriptor, () -> {}), pool);
        for (int i = 0; i < 200; i++) pool.endFrame();
        clock.set(3_000_000_000L);
        scope.trim();
        scope.execute(frame(descriptor, () -> {}), pool);
        assertEquals(1, descriptor.allocated);
        assertEquals(2, descriptor.prepared);
        assertEquals(10, scope.retainedBytes());
        clock.set(6_999_999_999L);
        scope.trim();
        assertEquals(0, descriptor.freed);
        clock.incrementAndGet();
        scope.trim();
        assertEquals(1, descriptor.freed);
        assertEquals(0, scope.retainedBytes());
        scope.execute(frame(descriptor, () -> {}), pool);
        assertEquals(2, descriptor.allocated);
        scope.close();
        scope.close();
        pool.close();
        assertEquals(2, descriptor.freed);
    }

    @Test
    void cacheEvictsLeastRecentlyUsedTargetsAndOversizedTargetsStillUseMinecraftsPool() {
        var clock = new AtomicLong();
        var scope = cached(clock, 20);
        var a = new Descriptor();
        var b = new Descriptor();
        var c = new Descriptor();
        var large = new Descriptor();
        large.bytes = 30;
        var pool = new CrossFrameResourcePool(3);
        for (var d : new Descriptor[] {a, b, a, c, large, large}) {
            clock.incrementAndGet();
            scope.execute(frame(d, () -> {}), pool);
            assertTrue(scope.retainedBytes() <= 20);
        }
        assertEquals(0, a.freed);
        assertEquals(0, b.freed);
        assertEquals(0, c.freed);
        assertEquals(1, large.allocated);
        scope.close();
        assertEquals(1, a.freed);
        assertEquals(1, c.freed);
        assertEquals(0, large.freed);
        pool.close();
        assertEquals(1, b.freed);
        assertEquals(1, large.freed);
    }

    @Test
    void simultaneousTargetsCannotAliasAndSmallTargetsCannotGrowTheCacheWithoutBound() {
        var clock = new AtomicLong();
        var scope = cached(clock, 1000);
        var descriptor = new Descriptor();
        var pool = new CrossFrameResourcePool(3);
        var graph = new FrameGraphBuilder();
        var a = graph.createInternal("a", descriptor);
        var b = graph.createInternal("b", descriptor);
        var pass = graph.addPass("both");
        pass.readsAndWrites(a);
        pass.readsAndWrites(b);
        pass.disableCulling();
        pass.executes(() -> assertNotSame(a.get(), b.get()));
        scope.execute(graph, pool);
        assertEquals(2, descriptor.allocated);
        scope.close();
        var all = new Descriptor[80];
        for (int i = 0; i < all.length; i++) {
            all[i] = new Descriptor();
            scope.execute(frame(all[i], () -> {}), pool);
        }
        assertEquals(640, scope.retainedBytes());
        scope.close();
        pool.close();
        for (var d : all) assertEquals(1, d.freed);
    }

    @Test
    void preparationAndDrawFailuresFreeActiveAndCachedTargetsExactlyOnce() {
        var clock = new AtomicLong();
        var scope = cached(clock, 100);
        var pool = new CrossFrameResourcePool(3);
        var cached = new Descriptor();
        var bad = new Descriptor();
        scope.execute(frame(cached, () -> {}), pool);
        scope.execute(frame(bad, () -> {}), pool);
        bad.failPrepare = true;
        assertEquals(
                "prepare",
                assertThrows(
                                IllegalStateException.class,
                                () -> scope.execute(frame(bad, () -> fail("No draw")), pool))
                        .getMessage());
        assertEquals(1, bad.freed);
        assertEquals(1, cached.freed);
        bad.failPrepare = false;
        bad.failFree = true;
        var failure = new IllegalStateException("draw");
        assertSame(
                failure,
                assertThrows(
                        IllegalStateException.class,
                        () ->
                                scope.execute(
                                        frame(
                                                bad,
                                                () -> {
                                                    throw failure;
                                                }),
                                        pool)));
        assertEquals(2, bad.freed);
        assertEquals(1, failure.getSuppressed().length);
        assertEquals(0, scope.retainedBytes());
        scope.close();
        pool.close();
    }

    @Test
    void aWorkingSetLargerThanTheWarmBudgetStillReusesOverflowEveryFrame() {
        var clock = new AtomicLong();
        var scope = cached(clock, 10);
        var pool = new CrossFrameResourcePool(3);
        var targets =
                new Descriptor[] {
                    new Descriptor(), new Descriptor(), new Descriptor(), new Descriptor()
                };
        for (int frame = 0; frame < 100; frame++) {
            clock.addAndGet(16_666_667L);
            for (var target : targets) scope.execute(frame(target, () -> {}), pool);
            pool.endFrame();
            assertTrue(scope.retainedBytes() <= 10);
        }
        for (var target : targets) {
            assertEquals(1, target.allocated);
            assertEquals(0, target.freed);
        }
        scope.close();
        pool.close();
        for (var target : targets) assertEquals(1, target.freed);
    }

    private static FrameGraphBuilder frame(Descriptor descriptor, Runnable task) {
        var frame = new FrameGraphBuilder();
        var resource = frame.createInternal("target", descriptor);
        var pass = frame.addPass("write");
        pass.readsAndWrites(resource);
        pass.disableCulling();
        pass.executes(task);
        return frame;
    }

    @Test
    void successfulFramesReturnResourcesToTheNativePoolAndReuseThem() {
        var scope = new ScopedAllocator();
        var descriptor = new Descriptor();
        var pool = new CrossFrameResourcePool(3);
        for (int i = 0; i < 10; i++) {
            scope.execute(frame(descriptor, () -> {}), pool);
            pool.endFrame();
        }
        assertEquals(1, descriptor.allocated);
        assertEquals(0, descriptor.freed);
        pool.close();
        assertEquals(1, descriptor.freed);
    }

    @Test
    void failedPassDestroysEveryOutstandingResourceAndTheScopeCanBeUsedAgain() {
        var scope = new ScopedAllocator();
        var descriptor = new Descriptor();
        var pool = new CrossFrameResourcePool(3);
        var frame = new FrameGraphBuilder();
        var first = frame.createInternal("first", descriptor);
        var second = frame.createInternal("second", descriptor);
        var pass = frame.addPass("failure");
        pass.readsAndWrites(first);
        pass.readsAndWrites(second);
        pass.disableCulling();
        var failure = new IllegalStateException("draw");
        pass.executes(
                () -> {
                    throw failure;
                });
        assertSame(
                failure,
                assertThrows(IllegalStateException.class, () -> scope.execute(frame, pool)));
        assertEquals(2, descriptor.allocated);
        assertEquals(2, descriptor.freed);
        scope.execute(frame(descriptor, () -> {}), pool);
        assertEquals(3, descriptor.allocated);
        pool.close();
        assertEquals(3, descriptor.freed);
    }

    @Test
    void laterAllocationFailureReleasesEarlierAcquisitions() {
        var scope = new ScopedAllocator();
        var good = new Descriptor();
        var bad = new Descriptor();
        bad.failAllocation = true;
        var pool = new CrossFrameResourcePool(3);
        var frame = new FrameGraphBuilder();
        var first = frame.createInternal("first", good);
        var second = frame.createInternal("second", bad);
        var pass = frame.addPass("allocation failure");
        pass.readsAndWrites(first);
        pass.readsAndWrites(second);
        pass.disableCulling();
        pass.executes(() -> fail("No draw"));
        assertEquals(
                "allocation",
                assertThrows(IllegalStateException.class, () -> scope.execute(frame, pool))
                        .getMessage());
        assertEquals(1, good.allocated);
        assertEquals(1, good.freed);
        pool.close();
        assertEquals(1, good.freed);
    }

    @Test
    void cleanupFailureDoesNotHideTheRenderFailureOrStopOtherCleanup() {
        var scope = new ScopedAllocator();
        var good = new Descriptor();
        var bad = new Descriptor();
        bad.failFree = true;
        var pool = new CrossFrameResourcePool(3);
        var frame = new FrameGraphBuilder();
        var first = frame.createInternal("first", good);
        var second = frame.createInternal("second", bad);
        var pass = frame.addPass("failure");
        pass.readsAndWrites(first);
        pass.readsAndWrites(second);
        pass.disableCulling();
        var failure = new AssertionError("draw");
        pass.executes(
                () -> {
                    throw failure;
                });
        assertSame(failure, assertThrows(AssertionError.class, () -> scope.execute(frame, pool)));
        assertEquals(1, good.freed);
        assertEquals(1, bad.freed);
        assertEquals(1, failure.getSuppressed().length);
        pool.close();
        assertEquals(1, good.freed);
        assertEquals(1, bad.freed);
    }
}
