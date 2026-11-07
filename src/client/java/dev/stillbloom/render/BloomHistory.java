package dev.stillbloom.render;

import com.mojang.blaze3d.framegraph.FrameGraphBuilder;
import com.mojang.blaze3d.pipeline.*;
import com.mojang.blaze3d.resource.ResourceHandle;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;

import dev.stillbloom.StillBloomClient;
import dev.stillbloom.render.source.SourceMask;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

import org.joml.Matrix4f;

import java.nio.*;
import java.util.*;
import java.util.ArrayList;
import java.util.function.Consumer;
import java.util.function.Supplier;

// I swap two history targets to avoid copying the glow every frame.
final class BloomHistory implements AutoCloseable {
    private record Pair(
            Identifier previous, Identifier current, TextureTarget a, TextureTarget b) {}

    private record Buffers(TextureTarget a, TextureTarget b) {
        void close() {
            a.destroyBuffers();
            b.destroyBuffers();
        }
    }

    private final List<Pair> pairs = new ArrayList<>();
    private TargetPool<Buffers> pool;
    private int targetWidth, targetHeight;
    private final ByteBuffer data = ByteBuffer.allocateDirect(16).order(ByteOrder.nativeOrder());
    private final Matrix4f previousView = new Matrix4f(), view = new Matrix4f();
    private long last;
    private boolean valid, swap;
    private double x, y, z;
    private Object revision;

    Set<Identifier> initialize(
            int width, int height, int pixels, List<SourceMask.RenderGroup> groups) {
        int w = BloomGraph.reducedSize(width, pixels), h = BloomGraph.reducedSize(height, pixels);
        if (pool == null || targetWidth != w || targetHeight != h) {
            close();
            targetWidth = w;
            targetHeight = h;
            pool =
                    new TargetPool<>(
                            32L * w * h,
                            () -> {
                                // I use 32-bit history so tiny brightness steps aren't rounded away
                                // at high FPS.
                                var a =
                                        new TextureTarget(
                                                "Still Bloom history A",
                                                w,
                                                h,
                                                GpuFormat.RGBA32_FLOAT,
                                                null);
                                try {
                                    return new Buffers(
                                            a,
                                            new TextureTarget(
                                                    "Still Bloom history B",
                                                    w,
                                                    h,
                                                    GpuFormat.RGBA32_FLOAT,
                                                    null));
                                } catch (RuntimeException e) {
                                    a.destroyBuffers();
                                    throw e;
                                }
                            },
                            Buffers::close);
        }
        pool.select(groups.size(), System.nanoTime());
        pairs.clear();
        invalidate();
        swap = false;
        Set<Identifier> ids = new HashSet<>();
        ids.add(net.minecraft.client.renderer.PostChain.MAIN_TARGET_ID);
        for (int i = 0; i < groups.size(); i++) {
            var group = groups.get(i);
            var previous = BloomGraph.id("group_" + group.index() + "_history");
            var current = BloomGraph.id("group_" + group.index() + "_down_" + pixels);
            var buffers = pool.get(i);
            pairs.add(new Pair(previous, current, buffers.a, buffers.b));
            ids.add(previous);
            ids.add(current);
        }
        return ids;
    }

    void trimInactive(long now, long budget) {
        if (pool != null) pool.trim(now, budget);
    }

    void invalidate() {
        valid = false;
        last = 0;
    }

    void prepare(Minecraft client, List<GpuBuffer> buffers) {
        if (pairs.isEmpty()) return;
        var p = client.gameRenderer.gameRenderState().levelRenderState.cameraRenderState.pos;
        var sources = StillBloomClient.SOURCE_MASK;
        sources.copyView(view);
        long now = System.nanoTime();
        double dx = p.x - x, dy = p.y - y, dz = p.z - z;
        boolean reset =
                !valid
                        || revision != sources.historyRevision()
                        || dx * dx + dy * dy + dz * dz > .25
                        || !view.equals(previousView, .15f);
        float weight =
                BloomHistory.historyWeight(
                        (now - last) * 1e-9, StillBloomClient.config.stabilityMillis, reset);
        data.clear();
        data.putFloat(weight).putFloat(0).putFloat(0).putFloat(0).flip();
        var encoder = RenderSystem.getDevice().createCommandEncoder();
        for (var b : buffers) encoder.writeToBuffer(b.slice(), data.duplicate());
        last = now;
        x = p.x;
        y = p.y;
        z = p.z;
        previousView.set(view);
        revision = sources.historyRevision();
    }

    void targets(FrameGraphBuilder frame, Map<Identifier, ResourceHandle<RenderTarget>> targets) {
        for (var p : pairs) {
            targets.put(p.previous, frame.importExternal(p.previous.toString(), swap ? p.b : p.a));
            targets.put(p.current, frame.importExternal(p.current.toString(), swap ? p.a : p.b));
        }
    }

    void advance() {
        valid = true;
        swap = !swap;
    }

    @Override
    public void close() {
        if (pool != null) {
            pool.close();
            pool = null;
        }
        pairs.clear();
        invalidate();
        swap = false;
        revision = null;
    }

    static float historyWeight(double seconds, float millis, boolean reset) {
        if (reset || millis <= 0 || seconds <= 0 || seconds > .25) return 0;
        return (float) Math.exp(-seconds / (millis * .001));
    }

    // I reuse matching history targets and limit how many unused pairs stay cached.
    static final class TargetPool<T> implements AutoCloseable {
        private static final long IDLE_NANOS = 10_000_000_000L;

        private static final class Slot<T> {
            final T value;
            long retired;

            Slot(T value) {
                this.value = value;
            }
        }

        private final ArrayList<Slot<T>> slots = new ArrayList<>();
        private final long bytesPerPair;
        private final Supplier<T> factory;
        private final Consumer<T> disposer;
        private int active;

        TargetPool(long bytesPerPair, Supplier<T> factory, Consumer<T> disposer) {
            if (bytesPerPair <= 0) throw new IllegalArgumentException("Invalid history size");
            this.bytesPerPair = bytesPerPair;
            this.factory = factory;
            this.disposer = disposer;
        }

        void select(int count, long now) {
            if (count < 0) throw new IllegalArgumentException("Negative history count");
            while (slots.size() < count) slots.add(new Slot<>(factory.get()));
            for (int i = count; i < active; i++) slots.get(i).retired = now;
            active = count;
        }

        T get(int index) {
            if (index < 0 || index >= active) throw new IndexOutOfBoundsException(index);
            return slots.get(index).value;
        }

        void trim(long now, long inactiveBudget) {
            while (slots.size() > active) {
                var last = slots.getLast();
                if (inactiveBytes() <= Math.max(0, inactiveBudget)
                        && now - last.retired < IDLE_NANOS) break;
                disposer.accept(slots.removeLast().value);
            }
        }

        long inactiveBytes() {
            return (slots.size() - active) * bytesPerPair;
        }

        int size() {
            return slots.size();
        }

        @Override
        public void close() {
            while (!slots.isEmpty()) disposer.accept(slots.removeLast().value);
            active = 0;
        }
    }
}
