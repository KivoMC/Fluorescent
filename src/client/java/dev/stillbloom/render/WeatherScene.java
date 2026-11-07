package dev.stillbloom.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;

import dev.stillbloom.StillBloomClient;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelTargetBundle;
import net.minecraft.resources.Identifier;

import org.joml.Vector4f;

// I extract glow from the scene before rain and snow while keeping weather in the final image.
public final class WeatherScene implements AutoCloseable {
    static final Identifier SNAPSHOT = BloomGraph.id("weather_scene");
    private static final long IDLE_NANOS = 2_000_000_000L;
    private static final Vector4f ZERO = new Vector4f();
    private static final Vector4f DEPTH_BOUNDS_CLEAR = new Vector4f(-Float.MAX_VALUE, 0, 0, 0);
    private TextureTarget snapshot, displayed;
    private Object level;
    private long lastUsed;
    private boolean valid, failed, suppressPrecipitation;

    public void beginFrame(Minecraft client) {
        valid = false;
        suppressPrecipitation = false;
        if (level != client.level) {
            release();
            level = client.level;
            failed = false;
        }
        if (client.level == null
                || StillBloomClient.config == null
                || !StillBloomClient.config.enabled
                || !RainFogState.needsScene(client)) release();
        else if (snapshot != null && System.nanoTime() - lastUsed > IDLE_NANOS) release();
    }

    public boolean needed(Minecraft client) {
        return !failed
                && client.level != null
                && StillBloomClient.config != null
                && StillBloomClient.config.enabled
                && RainFogState.needsScene(client)
                && StillBloomClient.SOURCE_MASK.hasSources()
                && StillBloomClient.SOURCE_MASK.hasVisibleSources(client);
    }

    public boolean suppressPrecipitation() {
        return suppressPrecipitation;
    }

    // I copy after the world pass closes, before deferred weather and the border draw.
    public void captureBeforeWeather(Minecraft client) {
        if (!needed(client)) return;
        try {
            capture(client.gameRenderer.mainRenderTarget());
        } catch (Exception e) {
            fail(e);
        }
    }

    // I replay transparency without precipitation because a color copy can't remove 26.3's mixed-in
    // rain.
    // I restore both display color and depth afterward; dry frames use the normal pass once.
    public void renderOit(Minecraft client, LevelTargetBundle targets, Runnable draw) {
        if (!needed(client)) {
            draw.run();
            return;
        }
        RenderTarget main = client.gameRenderer.mainRenderTarget();
        try {
            ensure(main);
            if (displayed == null)
                displayed =
                        new TextureTarget(
                                "Fluorescent native weather scene",
                                main.width,
                                main.height,
                                main.getColorTexture().getFormat(),
                                main.getDepthTexture().getFormat());
            copy(main, snapshot);
        } catch (Exception e) {
            fail(e);
            draw.run();
            return;
        }
        draw.run();
        try {
            copy(main, displayed);
        } catch (Exception e) {
            fail(e);
            return;
        }
        try {
            copy(snapshot, main);
            resetOit(targets);
            suppressPrecipitation = true;
            draw.run();
            capture(main);
        } catch (Exception e) {
            failCapture(e);
        } finally {
            suppressPrecipitation = false;
            copy(displayed, main);
        }
    }

    private static void resetOit(LevelTargetBundle targets) {
        // I use the native target's commands and clear values to keep the replay consistent.
        var encoder = RenderSystem.getDevice().createCommandEncoder();
        encoder.clearColorTexture(targets.depthBounds.get().getColorTexture(), DEPTH_BOUNDS_CLEAR);
        encoder.clearColorTexture(targets.accumulate.get().getColorTexture(), ZERO);
        for (var target : targets.transmittance)
            encoder.clearColorTexture(target.get().getColorTexture(), ZERO);
    }

    private void capture(RenderTarget main) {
        ensure(main);
        copy(main, snapshot);
        lastUsed = System.nanoTime();
        valid = true;
    }

    private static void copy(RenderTarget from, RenderTarget to) {
        to.copyDepthFrom(from);
        RenderSystem.getDevice()
                .createCommandEncoder()
                .copyTextureToTexture(
                        from.getColorTexture(),
                        to.getColorTexture(),
                        0,
                        0,
                        0,
                        0,
                        0,
                        from.width,
                        from.height);
    }

    private void ensure(RenderTarget main) {
        if (snapshot != null
                && snapshot.width == main.width
                && snapshot.height == main.height
                && snapshot.getColorTexture().getFormat() == main.getColorTexture().getFormat()
                && snapshot.getDepthTexture().getFormat() == main.getDepthTexture().getFormat())
            return;
        release();
        snapshot =
                new TextureTarget(
                        "Fluorescent weather source scene",
                        main.width,
                        main.height,
                        main.getColorTexture().getFormat(),
                        main.getDepthTexture().getFormat());
    }

    public RenderTarget input(RenderTarget main) {
        return valid
                        && snapshot != null
                        && snapshot.width == main.width
                        && snapshot.height == main.height
                ? snapshot
                : main;
    }

    public void invalidate() {
        close();
    }

    public void retry() {
        failed = false;
    }

    private void failCapture(Exception e) {
        valid = false;
        failed = true;
        StillBloomClient.LOGGER.warn(
                "Weather bloom stabilization unavailable; continuing normal bloom. Reload settings to retry.",
                e);
    }

    private void fail(Exception e) {
        failCapture(e);
        release();
    }

    private void release() {
        valid = false;
        if (snapshot != null) snapshot.destroyBuffers();
        snapshot = null;
        if (displayed != null) displayed.destroyBuffers();
        displayed = null;
        lastUsed = 0;
    }

    @Override
    public void close() {
        release();
        level = null;
        failed = false;
        suppressPrecipitation = false;
    }
}
