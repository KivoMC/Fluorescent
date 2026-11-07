package dev.stillbloom.render.source;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuTexture;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.Identifier;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.BitSet;

// I store floats directly in each texel to avoid packing them into separate bytes.
public final class GeometryTexture implements AutoCloseable {
    public static final int SIZE = 512;
    final Identifier id = Identifier.fromNamespaceAndPath("stillbloom", "custom_geometry");
    private Data texture;
    private ByteBuffer transfer;
    private GpuBuffer staging;
    private boolean initialized;

    private Identifier registered() {
        return id.withPath(p -> "textures/effect/" + p + ".png");
    }

    void ensure() {
        if (texture == null || !texture.live()) {
            texture = new Data();
            initialized = false;
            Minecraft.getInstance().getTextureManager().register(registered(), texture);
        }
    }

    boolean needsUpload() {
        return !initialized;
    }

    void upload(int[] words, DirtyRows rows) {
        if (words.length != DirtyRows.WORDS)
            throw new IllegalArgumentException("Invalid geometry table size");
        ensure();
        if (!initialized) rows.markAll();
        if (!rows.isDirty()) return;
        if (transfer == null)
            transfer = ByteBuffer.allocateDirect(SIZE * SIZE * 16).order(ByteOrder.nativeOrder());
        transfer.clear();
        if (staging == null || staging.isClosed())
            staging =
                    RenderSystem.getDevice()
                            .createBuffer(
                                    () -> "Still Bloom geometry upload",
                                    GpuBuffer.USAGE_COPY_SRC | GpuBuffer.USAGE_COPY_DST,
                                    transfer.capacity());
        var encoder = RenderSystem.getDevice().createCommandEncoder();
        rows.upload(
                (firstRow, rowCount) -> {
                    int firstWord = firstRow * DirtyRows.WORDS_PER_ROW,
                            wordCount = rowCount * DirtyRows.WORDS_PER_ROW;
                    int firstByte = firstWord * Integer.BYTES,
                            byteCount = wordCount * Integer.BYTES;
                    // I keep upload strips separate so queued copies don't read bytes that a later
                    // strip overwrote.
                    var part = transfer.slice(firstByte, byteCount).order(ByteOrder.nativeOrder());
                    part.asIntBuffer().put(words, firstWord, wordCount);
                    var slice = staging.slice(firstByte, byteCount);
                    encoder.writeToBuffer(slice, part);
                    // I use buffer copies to preserve 32-bit components and map each packed strip
                    // to its original rows.
                    encoder.copyBufferToTexture(
                            slice,
                            0,
                            0,
                            SIZE,
                            rowCount,
                            texture.getTexture(),
                            0,
                            firstRow,
                            SIZE,
                            rowCount,
                            0,
                            0);
                });
        initialized = true;
    }

    @Override
    public void close() {
        if (texture != null) Minecraft.getInstance().getTextureManager().release(registered());
        if (staging != null) staging.close();
        staging = null;
        texture = null;
        transfer = null;
        initialized = false;
    }

    private static final class Data extends AbstractTexture {
        boolean live() {
            return texture != null && !texture.isClosed();
        }

        Data() {
            var device = RenderSystem.getDevice();
            texture =
                    device.createTexture(
                            "Still Bloom geometry",
                            GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST,
                            GpuFormat.RGBA32_UINT,
                            SIZE,
                            SIZE,
                            1,
                            1);
            textureView = device.createTextureView(texture);
            sampler = RenderSystem.getSamplerCache().getRepeat(FilterMode.NEAREST);
        }
    }

    static final class DirtyRows {
        static final int ROWS = 512, WORDS_PER_ROW = 512 * 4, WORDS = ROWS * WORDS_PER_ROW;
        private final BitSet rows = new BitSet(ROWS);

        @FunctionalInterface
        interface Upload {
            void accept(int firstRow, int rowCount);
        }

        void markWords(int from, int to) {
            if (from < 0 || to < from || to > WORDS)
                throw new IllegalArgumentException("Invalid geometry word range");
            if (from != to) rows.set(from / WORDS_PER_ROW, (to - 1) / WORDS_PER_ROW + 1);
        }

        void markAll() {
            rows.set(0, ROWS);
        }

        boolean isDirty() {
            return !rows.isEmpty();
        }

        // I keep all pending rows after a failed upload so the retry can repair the whole change.
        void upload(Upload upload) {
            for (int start = rows.nextSetBit(0); start >= 0; ) {
                int end = rows.nextClearBit(start);
                upload.accept(start, end - start);
                start = rows.nextSetBit(end);
            }
            rows.clear();
        }
    }
}
