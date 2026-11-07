package dev.stillbloom.gui;

import static org.junit.jupiter.api.Assertions.*;

import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.state.gui.ColoredRectangleRenderState;

import org.joml.Matrix3x2f;
import org.junit.jupiter.api.Test;

import java.util.*;

class CheckerboardElementTest {
    @Test
    void batchedGeometryExactlyMatchesNativeSquaresIncludingPartialEdgesAndPose() {
        for (int w : new int[] {1, 8, 17, 799})
            for (int h : new int[] {1, 8, 19, 351}) {
                int left = -3, top = 7;
                var pose = new Matrix3x2f().translate(2, 5).scale(1.25f);
                var actual = new Vertices();
                new MenuPresentation.Checkerboard(pose, left, top, left + w, top + h, null, null)
                        .buildVertices(actual);
                var expected = new Vertices();
                for (int y = top; y < top + h; y += 8)
                    for (int x = left; x < left + w; x += 8) {
                        int color =
                                (((x - left) / 8 + (y - top) / 8) & 1) == 0
                                        ? 0xFF171C26
                                        : 0xFF202734;
                        new ColoredRectangleRenderState(
                                        RenderPipelines.GUI,
                                        TextureSetup.noTexture(),
                                        pose,
                                        x,
                                        y,
                                        Math.min(x + 8, left + w),
                                        Math.min(y + 8, top + h),
                                        color,
                                        color,
                                        null)
                                .buildVertices(expected);
                    }
                assertEquals(expected.values, actual.values, w + "x" + h);
            }
    }

    private static final class Vertices implements VertexConsumer {
        private final List<Object> values = new ArrayList<>();

        public VertexConsumer addVertex(float x, float y, float z) {
            values.add(List.of(x, y, z));
            return this;
        }

        public VertexConsumer setColor(int r, int g, int b, int a) {
            values.add(List.of(r, g, b, a));
            return this;
        }

        public VertexConsumer setColor(int color) {
            return setColor(
                    (color >>> 16) & 255, (color >>> 8) & 255, color & 255, (color >>> 24) & 255);
        }

        public VertexConsumer setUv(float u, float v) {
            return this;
        }

        public VertexConsumer setUv1(int u, int v) {
            return this;
        }

        public VertexConsumer setUv2(int u, int v) {
            return this;
        }

        public VertexConsumer setUv3(float u, float v) {
            return this;
        }

        public VertexConsumer setNormal(float x, float y, float z) {
            return this;
        }

        public VertexConsumer setLineWidth(float width) {
            return this;
        }
    }
}
