package dev.stillbloom.render.source;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;

// I store integer bits losslessly in RGBA8 so both backends read the same values.
final class MaskTexture implements AutoCloseable {
    final Identifier id;
    final int size;
    final int height;
    private DynamicTexture texture;
    private boolean hasContents;
    private BitSet uploadedRows;

    MaskTexture(String name, int size) {
        this(name, size, size);
    }

    MaskTexture(String name, int width, int height) {
        id = Identifier.fromNamespaceAndPath("stillbloom", name);
        this.size = width;
        this.height = height;
    }

    private Identifier registered() {
        return id.withPath(p -> "textures/effect/" + p + ".png");
    }

    void ensure() {
        if (texture == null) {
            texture = new DynamicTexture(() -> id.toString(), new NativeImage(size, height, true));
            Minecraft.getInstance().getTextureManager().register(registered(), texture);
        }
    }

    void upload(int[] words) {
        ensure();
        hasContents = false;
        uploadedRows = null;
        texture.getPixels().getPixelBytes().order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().put(words);
        texture.upload();
        hasContents = true;
    }

    // I compare against the immutable array from the last successful upload.
    void upload(int[] words, int[] previous, BitSet occupiedRows) {
        var plan =
                RowUpdates.plan(
                        hasContents ? previous : null,
                        words,
                        size,
                        height,
                        uploadedRows,
                        occupiedRows);
        if (plan.fullUpload()) {
            upload(words);
            uploadedRows = occupiedRows;
            return;
        }
        if (plan.bands().isEmpty()) {
            uploadedRows = occupiedRows;
            return;
        }
        // I require a full repair after a partial upload fails.
        hasContents = false;
        var pixels = texture.getPixels().getPixelBytes().order(ByteOrder.LITTLE_ENDIAN);
        var integers = pixels.asIntBuffer();
        var encoder = RenderSystem.getDevice().createCommandEncoder();
        for (var band : plan.bands()) {
            int offset = band.firstRow() * size, count = band.rowCount() * size;
            integers.put(offset, words, offset, count);
            encoder.writeToTexture(
                    texture.getTexture(),
                    pixels.slice(offset * Integer.BYTES, count * Integer.BYTES),
                    0,
                    0,
                    0,
                    band.firstRow(),
                    size,
                    band.rowCount());
        }
        hasContents = true;
        uploadedRows = occupiedRows;
    }

    @Override
    public void close() {
        if (texture != null) Minecraft.getInstance().getTextureManager().release(registered());
        texture = null;
        hasContents = false;
        uploadedRows = null;
    }

    // I upload changed rows unless most of the texture changed, when one full upload is cheaper.
    static final class RowUpdates {
        static final int MAX_BANDS = 64;

        record Band(int firstRow, int rowCount) {}

        record Plan(boolean fullUpload, List<Band> bands) {}

        private static final Plan UNCHANGED = new Plan(false, List.of());

        static Plan plan(int[] previous, int[] next, int width, int height) {
            return plan(previous, next, width, height, null, null);
        }

        // I use both old and new occupancy to clear removed sources; null means the whole table is
        // occupied.
        static Plan plan(
                int[] previous, int[] next, int width, int height, BitSet oldRows, BitSet newRows) {
            long words = (long) width * height;
            if (width <= 0
                    || height <= 0
                    || words > Integer.MAX_VALUE / Integer.BYTES
                    || next == null
                    || next.length != words) {
                throw new IllegalArgumentException(
                        "Texture dimensions must match the complete word array");
            }
            if (previous == null || previous.length != next.length) return full(height);
            if (previous == next) return UNCHANGED;

            var bands = new ArrayList<Band>();
            int start = -1, dirtyRows = 0;
            for (int row = 0; row < height; row++) {
                int offset = row * width;
                boolean occupied =
                        oldRows == null || newRows == null || oldRows.get(row) || newRows.get(row);
                if (occupied
                        && Arrays.mismatch(
                                        previous,
                                        offset,
                                        offset + width,
                                        next,
                                        offset,
                                        offset + width)
                                >= 0) {
                    if (start < 0) {
                        if (bands.size() == MAX_BANDS) return full(height);
                        start = row;
                    }
                    if (++dirtyRows * 2L > height) return full(height);
                } else if (start >= 0) {
                    bands.add(new Band(start, row - start));
                    start = -1;
                }
            }
            if (start >= 0) bands.add(new Band(start, height - start));
            return bands.isEmpty() ? UNCHANGED : new Plan(false, List.copyOf(bands));
        }

        private static Plan full(int height) {
            return new Plan(true, List.of(new Band(0, height)));
        }

        static BitSet packedRows(SourceVolumePool.PackedVolume.Data data) {
            if (data == null || data.entries() == null) return null;
            var rows = new BitSet();
            // I pack two voxel IDs in each 32-bit word, with 1024 words per row.
            for (long entry : data.entries()) rows.set((int) (entry >>> 32) >>> 11);
            return rows;
        }

        static BitSet colorOrDetailRows(List<Long> entries) {
            if (entries.size() > SourceVolumePool.SPARSE_CLEAR_LIMIT) return null;
            var rows = new BitSet();
            for (long entry : entries) rows.set((int) (entry >>> 32) >>> 10);
            return rows;
        }
    }
}
