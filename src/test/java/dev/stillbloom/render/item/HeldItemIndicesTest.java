package dev.stillbloom.render.item;

import static org.junit.jupiter.api.Assertions.*;

import com.mojang.renderpearl.api.pipeline.IndexType;

import org.junit.jupiter.api.Test;

import java.lang.reflect.*;
import java.nio.*;
import java.util.Random;

class HeldItemIndicesTest {
    private static final int STRIDE = 80;
    private static final int[] ORIGINAL_TRIANGLES = {0, 1, 2, 0, 2, 3};

    private static Field field(Class<?> owner, String name) throws Exception {
        var value = owner.getDeclaredField(name);
        value.setAccessible(true);
        return value;
    }

    @Test
    void indexedBatchExpandsToEveryOriginalAttributeBitAfterGrowthAndReset() throws Exception {
        var batchType = Class.forName("dev.stillbloom.render.item.HeldItemRenderer$Batch");
        var constructor = batchType.getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        Object batch = constructor.newInstance(new Object[] {null});
        var append =
                batchType.getDeclaredMethod(
                        "quad",
                        float[].class,
                        int.class,
                        float[].class,
                        int.class,
                        Integer.class,
                        float.class,
                        float.class,
                        float.class,
                        float.class);
        append.setAccessible(true);
        var reset = batchType.getDeclaredMethod("reset");
        reset.setAccessible(true);
        var random = new Random(0x20_05);
        for (int quads : new int[] {150, 1, 33}) {
            reset.invoke(batch);
            var expected = ByteBuffer.allocate(quads * 6 * STRIDE).order(ByteOrder.nativeOrder());
            for (int q = 0; q < quads; q++) {
                float[] clip = new float[32], uv = new float[16];
                for (int i = 0; i < clip.length; i++) clip[i] = random.nextFloat(-50, 50);
                for (int i = 0; i < uv.length; i++) uv[i] = random.nextFloat(-2, 2);
                int tint = random.nextInt();
                Integer color = q % 3 == 0 ? null : random.nextInt() & 0xffffff;
                float strength = random.nextFloat(0, 8),
                        detail = random.nextFloat(),
                        fade = random.nextFloat(),
                        near = random.nextFloat();
                append.invoke(batch, clip, 16, uv, tint, color, strength, detail, fade, near);
                // I compare with the frozen 0.20.4 vertex layout, including packed color rounding.
                for (int corner : ORIGINAL_TRIANGLES) {
                    for (int i = 0; i < 4; i++) expected.putFloat(clip[16 + corner * 4 + i]);
                    for (int i = 0; i < 4; i++) expected.putFloat(uv[corner * 4 + i]);
                    putColor(expected, tint, true);
                    putColor(expected, color == null ? 0 : color, color != null);
                    expected.putFloat(strength).putFloat(detail).putFloat(fade).putFloat(near);
                }
            }
            int vertices = field(batchType, "vertices").getInt(batch);
            var unique =
                    ((ByteBuffer) field(batchType, "data").get(batch))
                            .duplicate()
                            .order(ByteOrder.nativeOrder());
            assertEquals(quads * 4, vertices);
            assertEquals(quads * 4 * STRIDE, unique.position());
            var indices =
                    ByteBuffer.allocate(HeldItemIndices.indexCount(vertices) * 2)
                            .order(ByteOrder.nativeOrder());
            HeldItemIndices.write(indices, vertices, IndexType.SHORT);
            indices.flip();
            var expanded = ByteBuffer.allocate(expected.position());
            while (indices.hasRemaining()) {
                int offset = Short.toUnsignedInt(indices.getShort()) * STRIDE;
                for (int i = 0; i < STRIDE; i++) expanded.put(unique.get(offset + i));
            }
            assertArrayEquals(
                    expected.array(),
                    expanded.array(),
                    "No triangle winding, UV, color, depth, or mask attribute may change");
            assertEquals(
                    expected.position() * 2L,
                    unique.position() * 3L,
                    "Per-frame vertex upload is exactly one third smaller");
        }
    }

    private static void putColor(ByteBuffer out, int rgb, boolean active) {
        out.putFloat((rgb >> 16 & 255) / 255f)
                .putFloat((rgb >> 8 & 255) / 255f)
                .putFloat((rgb & 255) / 255f)
                .putFloat(active ? 1 : 0);
    }

    @Test
    void unsignedShortBoundaryAndIntIndicesRetainTheSamePrefix() {
        for (int vertices : new int[] {4, 128, 65532, 65536, 65540, 131072}) {
            var ints =
                    ByteBuffer.allocate(HeldItemIndices.indexCount(vertices) * 4)
                            .order(ByteOrder.nativeOrder());
            HeldItemIndices.write(ints, vertices, IndexType.INT);
            ints.flip();
            ByteBuffer shorts = null;
            if (vertices <= 65536) {
                shorts =
                        ByteBuffer.allocate(HeldItemIndices.indexCount(vertices) * 2)
                                .order(ByteOrder.nativeOrder());
                HeldItemIndices.write(shorts, vertices, IndexType.SHORT);
                shorts.flip();
            }
            for (int quad = 0; quad < vertices / 4; quad++)
                for (int corner : ORIGINAL_TRIANGLES) {
                    int index = ints.getInt();
                    assertEquals(quad * 4 + corner, index);
                    if (shorts != null) assertEquals(index, Short.toUnsignedInt(shorts.getShort()));
                }
            assertFalse(ints.hasRemaining());
            if (shorts != null) assertFalse(shorts.hasRemaining());
        }
    }

    @Test
    void malformedOrUndersizedBuffersFailBeforeWriting() {
        var empty = ByteBuffer.allocate(0);
        assertThrows(IllegalArgumentException.class, () -> HeldItemIndices.indexCount(-4));
        assertThrows(IllegalArgumentException.class, () -> HeldItemIndices.indexCount(5));
        assertThrows(
                IllegalArgumentException.class,
                () -> HeldItemIndices.write(empty, 4, IndexType.SHORT));
        assertThrows(
                IllegalArgumentException.class,
                () -> HeldItemIndices.write(empty, 65540, IndexType.SHORT));
        assertEquals(0, empty.position());
    }
}
