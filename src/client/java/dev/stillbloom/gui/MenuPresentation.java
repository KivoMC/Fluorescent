package dev.stillbloom.gui;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;

import dev.stillbloom.mixin.GuiGraphicsAccessor;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;

import org.joml.Matrix3x2fc;

// I apply this opacity only to menu drawing, never to the world's bloom.
public final class MenuPresentation {
    private static float opacity = 1;

    private MenuPresentation() {}

    public static void extract(
            Screen screen,
            GuiGraphicsExtractor graphics,
            int x,
            int y,
            float tick,
            Operation<Void> original) {
        boolean managed = screen instanceof BloomMenuScreen || screen instanceof BloomColorScreen;
        if (!managed) {
            original.call(screen, graphics, x, y, tick);
            return;
        }
        if (!BloomMenuScreen.advancePresentation(screen)) return;
        float previous = opacity;
        opacity = BloomMenuScreen.currentAlpha();
        try {
            original.call(screen, graphics, x, y, tick);
        } finally {
            opacity = previous;
        }
    }

    // I cancel a pending exit when a new screen opens so it starts a fresh fade.
    public static void screenChanging(Screen next) {
        if (!(next instanceof BloomMenuScreen) && !(next instanceof BloomColorScreen))
            BloomMenuScreen.clearPresentation();
    }

    public static int color(int argb) {
        return opacity >= 1 ? argb : color(argb, opacity);
    }

    static int color(int argb, float alpha) {
        return (argb & 0xFFFFFF) | (Math.clamp(Math.round((argb >>> 24) * alpha), 0, 255) << 24);
    }

    public static GuiElementRenderState fade(GuiElementRenderState element) {
        return opacity >= 1 ? element : new FadedElement(element, opacity);
    }

    private record FadedElement(GuiElementRenderState source, float opacity)
            implements GuiElementRenderState {
        public RenderPipeline pipeline() {
            return source.pipeline();
        }

        public TextureSetup textureSetup() {
            return source.textureSetup();
        }

        public ScreenRectangle scissorArea() {
            return source.scissorArea();
        }

        public ScreenRectangle bounds() {
            return source.bounds();
        }

        public void buildVertices(VertexConsumer out) {
            source.buildVertices(new FadedVertices(out, opacity));
        }
    }

    private record FadedVertices(VertexConsumer out, float opacity) implements VertexConsumer {
        public VertexConsumer addVertex(float x, float y, float z) {
            out.addVertex(x, y, z);
            return this;
        }

        public VertexConsumer setColor(int r, int g, int b, int a) {
            out.setColor(r, g, b, Math.clamp(Math.round(a * opacity), 0, 255));
            return this;
        }

        public VertexConsumer setColor(int color) {
            out.setColor(MenuPresentation.color(color, opacity));
            return this;
        }

        public VertexConsumer setUv(float u, float v) {
            out.setUv(u, v);
            return this;
        }

        public VertexConsumer setUv1(int u, int v) {
            out.setUv1(u, v);
            return this;
        }

        public VertexConsumer setUv2(int u, int v) {
            out.setUv2(u, v);
            return this;
        }

        public VertexConsumer setUv3(float u, float v) {
            out.setUv3(u, v);
            return this;
        }

        public VertexConsumer setNormal(float x, float y, float z) {
            out.setNormal(x, y, z);
            return this;
        }

        public VertexConsumer setLineWidth(float width) {
            out.setLineWidth(width);
            return this;
        }
    }

    // I draw the checkerboard in one element to avoid thousands of overlap checks.
    static record Checkerboard(
            Matrix3x2fc pose,
            int left,
            int top,
            int right,
            int bottom,
            ScreenRectangle scissorArea,
            ScreenRectangle bounds)
            implements GuiElementRenderState {
        static void draw(GuiGraphicsExtractor graphics, int left, int top, int right, int bottom) {
            if (right <= left || bottom <= top) return;
            var accessor = (GuiGraphicsAccessor) graphics;
            var params = accessor.stillbloom$parameters(1);
            var bounds =
                    new ScreenRectangle(left, top, right - left, bottom - top)
                            .transformMaxBounds(params.pose());
            if (params.scissor() != null) bounds = bounds.intersection(params.scissor());
            if (bounds != null)
                accessor.stillbloom$state()
                        .addGuiElement(
                                new Checkerboard(
                                        params.pose(),
                                        left,
                                        top,
                                        right,
                                        bottom,
                                        params.scissor(),
                                        bounds));
        }

        public RenderPipeline pipeline() {
            return RenderPipelines.GUI;
        }

        public TextureSetup textureSetup() {
            return TextureSetup.noTexture();
        }

        public void buildVertices(VertexConsumer out) {
            for (int y = top; y < bottom; y += 8)
                for (int x = left; x < right; x += 8) {
                    int color =
                            (((x - left) / 8 + (y - top) / 8) & 1) == 0 ? 0xFF171C26 : 0xFF202734;
                    int x1 = Math.min(x + 8, right), y1 = Math.min(y + 8, bottom);
                    out.addVertexWith2DPose(pose, x, y).setColor(color);
                    out.addVertexWith2DPose(pose, x, y1).setColor(color);
                    out.addVertexWith2DPose(pose, x1, y1).setColor(color);
                    out.addVertexWith2DPose(pose, x1, y).setColor(color);
                }
        }
    }
}
