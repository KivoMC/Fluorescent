package dev.stillbloom.render;

import com.mojang.blaze3d.framegraph.FrameGraphBuilder;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import com.mojang.blaze3d.resource.ResourceDescriptor;

import java.util.Arrays;
import java.util.function.LongSupplier;
import java.util.function.ToLongFunction;

// I release frame resources even when drawing fails.
public final class ScopedAllocator implements GraphicsResourceAllocator, AutoCloseable {
    private GraphicsResourceAllocator delegate;
    private ResourceDescriptor<?>[] descriptors = new ResourceDescriptor<?>[16];
    private Object[] resources = new Object[16];
    private int size;
    private final TargetPool pool;
    private final LongSupplier clock;
    private final ToLongFunction<ResourceDescriptor<?>> retainedSize;
    private long now;

    public ScopedAllocator() {
        this(0, 0);
    }

    public ScopedAllocator(long maxBytes, long keepNanos) {
        this(
                maxBytes,
                keepNanos,
                System::nanoTime,
                descriptor ->
                        descriptor instanceof BloomTargetDescriptor target ? target.byteSize() : 0);
    }

    ScopedAllocator(
            long maxBytes,
            long keepNanos,
            LongSupplier clock,
            ToLongFunction<ResourceDescriptor<?>> retainedSize) {
        this.pool = new TargetPool(maxBytes, keepNanos);
        this.clock = clock;
        this.retainedSize = retainedSize;
    }

    public void trim() {
        pool.trim(clock.getAsLong());
    }

    public void execute(FrameGraphBuilder frame, GraphicsResourceAllocator allocator) {
        if (delegate != null) throw new IllegalStateException("Nested bloom frame execution");
        now = clock.getAsLong();
        pool.trim(now);
        delegate = allocator;
        Throwable failure = null;
        try {
            frame.execute(this);
        } catch (RuntimeException | Error e) {
            failure = e;
            throw e;
        } finally {
            try {
                cleanup(failure);
            } finally {
                delegate = null;
                if (failure != null) {
                    try {
                        pool.close();
                    } catch (RuntimeException | Error e) {
                        if (e != failure) failure.addSuppressed(e);
                    }
                }
            }
        }
    }

    @Override
    public <T> T acquire(ResourceDescriptor<T> descriptor) {
        if (delegate == null) throw new IllegalStateException("Bloom allocator outside execution");
        if (size == resources.length) {
            int capacity = size * 2;
            descriptors = Arrays.copyOf(descriptors, capacity);
            resources = Arrays.copyOf(resources, capacity);
        }
        long bytes = retainedSize.applyAsLong(descriptor);
        boolean owned = pool.accepts(bytes);
        T resource = owned ? pool.take(descriptor) : null;
        boolean reused = resource != null;
        if (!reused) resource = delegate.acquire(descriptor);
        descriptors[size] = descriptor;
        resources[size++] = resource;
        // I track the target before preparing it so a failed clear still releases it.
        if (reused) descriptor.prepare(resource);
        return resource;
    }

    @Override
    public <T> void release(ResourceDescriptor<T> descriptor, T resource) {
        for (int i = size - 1; i >= 0; i--)
            if (resources[i] == resource) {
                long bytes = retainedSize.applyAsLong(descriptor);
                if (!pool.accepts(bytes)) delegate.release(descriptor, resource);
                int last = --size;
                descriptors[i] = descriptors[last];
                resources[i] = resources[last];
                descriptors[last] = null;
                resources[last] = null;
                if (pool.accepts(bytes)) pool.put(descriptor, resource, bytes, now, delegate);
                return;
            }
        throw new IllegalStateException("Unowned bloom frame resource");
    }

    @Override
    public void close() {
        if (delegate != null) throw new IllegalStateException("Closing an active bloom allocator");
        pool.close();
    }

    long retainedBytes() {
        return pool.bytes;
    }

    // I keep only my own temporary targets warm; other effects still use Minecraft's pool.
    private static final class TargetPool {
        private final long maxBytes, keepNanos;
        private final ResourceDescriptor<?>[] descriptors = new ResourceDescriptor<?>[64];
        private final Object[] resources = new Object[64];
        private final long[] sizes = new long[64], used = new long[64];
        private int count;
        private long bytes;

        TargetPool(long maxBytes, long keepNanos) {
            if (maxBytes < 0 || keepNanos < 0)
                throw new IllegalArgumentException("Invalid target budget");
            this.maxBytes = maxBytes;
            this.keepNanos = keepNanos;
        }

        boolean accepts(long size) {
            return keepNanos > 0 && size > 0 && size <= maxBytes;
        }

        @SuppressWarnings("unchecked")
        <T> T take(ResourceDescriptor<T> descriptor) {
            for (int i = count - 1; i >= 0; i--)
                if (descriptor.canUsePhysicalResource(descriptors[i])) {
                    T resource = (T) resources[i];
                    remove(i);
                    return resource;
                }
            return null;
        }

        void put(
                ResourceDescriptor<?> descriptor,
                Object resource,
                long size,
                long now,
                GraphicsResourceAllocator fallback) {
            // I return overflow to Minecraft's pool so large effects don't reallocate every frame.
            try {
                while (count == resources.length || bytes > maxBytes - size) {
                    int oldest = 0;
                    for (int i = 1; i < count; i++) if (used[i] < used[oldest]) oldest = i;
                    release(fallback, descriptors[oldest], resources[oldest]);
                    remove(oldest);
                }
            } catch (RuntimeException | Error e) {
                try {
                    free(descriptor, resource);
                } catch (RuntimeException | Error cleanup) {
                    if (cleanup != e) e.addSuppressed(cleanup);
                }
                throw e;
            }
            descriptors[count] = descriptor;
            resources[count] = resource;
            sizes[count] = size;
            used[count++] = now;
            bytes += size;
        }

        @SuppressWarnings("unchecked")
        private static <T> void release(
                GraphicsResourceAllocator fallback,
                ResourceDescriptor<T> descriptor,
                Object resource) {
            fallback.release(descriptor, (T) resource);
        }

        void trim(long now) {
            for (int i = count - 1; i >= 0; i--) if (now - used[i] >= keepNanos) freeAt(i);
        }

        private void remove(int index) {
            bytes -= sizes[index];
            int last = --count;
            descriptors[index] = descriptors[last];
            resources[index] = resources[last];
            sizes[index] = sizes[last];
            used[index] = used[last];
            descriptors[last] = null;
            resources[last] = null;
            sizes[last] = used[last] = 0;
        }

        private void freeAt(int index) {
            var descriptor = descriptors[index];
            Object resource = resources[index];
            remove(index);
            free(descriptor, resource);
        }

        void close() {
            Throwable failure = null;
            while (count > 0) {
                try {
                    freeAt(count - 1);
                } catch (RuntimeException | Error e) {
                    if (failure == null) failure = e;
                    else if (e != failure) failure.addSuppressed(e);
                }
            }
            if (failure instanceof RuntimeException e) throw e;
            if (failure instanceof Error e) throw e;
        }
    }

    private void cleanup(Throwable failure) {
        Throwable cleanupFailure = null;
        while (size > 0) {
            int index = --size;
            var descriptor = descriptors[index];
            Object resource = resources[index];
            descriptors[index] = null;
            resources[index] = null;
            try {
                free(descriptor, resource);
            } catch (RuntimeException | Error e) {
                if (failure != null) {
                    if (e != failure) failure.addSuppressed(e);
                } else if (cleanupFailure == null) cleanupFailure = e;
                else if (e != cleanupFailure) cleanupFailure.addSuppressed(e);
            }
        }
        if (cleanupFailure instanceof RuntimeException e) throw e;
        if (cleanupFailure instanceof Error e) throw e;
    }

    @SuppressWarnings("unchecked")
    private static <T> void free(ResourceDescriptor<T> descriptor, Object resource) {
        descriptor.free((T) resource);
    }
}
