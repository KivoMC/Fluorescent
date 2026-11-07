package dev.stillbloom.render.item;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.pipeline.IndexType;

import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

// I reuse triangle indices so each corner only needs one upload.
final class HeldItemIndices implements AutoCloseable {
    private static final int[] TRIANGLES = {0, 1, 2, 0, 2, 3};
    private GpuBuffer buffer;
    private IndexType type;
    private int capacity;

    GpuBuffer buffer() {
        return buffer;
    }

    IndexType type() {
        return type;
    }

    void ensure(int vertices) {
        if (vertices <= capacity) return;
        int grown = Math.max(128, Math.multiplyExact(vertices, 2));
        IndexType next = IndexType.least(grown);
        var data =
                MemoryUtil.memAlloc(Math.multiplyExact(indexCount(grown), next.bytes))
                        .order(ByteOrder.nativeOrder());
        try {
            write(data, grown, next);
            data.flip();
            var replacement =
                    RenderSystem.getDevice()
                            .createBuffer(
                                    () -> "Still Bloom held indices", GpuBuffer.USAGE_INDEX, data);
            // I use Minecraft's normal buffer-close path when the shared index buffer grows.
            if (buffer != null) buffer.close();
            buffer = replacement;
            type = next;
            capacity = grown;
        } finally {
            MemoryUtil.memFree(data);
        }
    }

    static int indexCount(int vertices) {
        if (vertices < 0 || (vertices & 3) != 0)
            throw new IllegalArgumentException("Expected complete held quads");
        return Math.multiplyExact(vertices / 4, 6);
    }

    static void write(ByteBuffer into, int vertices, IndexType type) {
        int count = indexCount(vertices);
        if (type == IndexType.SHORT && vertices > 65536)
            throw new IllegalArgumentException("Held index exceeds unsigned short");
        if (into.remaining() < Math.multiplyExact(count, type.bytes))
            throw new IllegalArgumentException("Held index buffer is too small");
        for (int base = 0; base < vertices; base += 4)
            for (int corner : TRIANGLES) {
                if (type == IndexType.SHORT) into.putShort((short) (base + corner));
                else into.putInt(base + corner);
            }
    }

    @Override
    public void close() {
        if (buffer != null) buffer.close();
        buffer = null;
        type = null;
        capacity = 0;
    }
}
