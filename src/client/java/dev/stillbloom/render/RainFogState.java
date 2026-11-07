package dev.stillbloom.render;

import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;

import dev.stillbloom.StillBloomClient;
import dev.stillbloom.mixin.FogRendererAccessor;

import net.minecraft.client.Minecraft;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.material.FogType;

import org.lwjgl.system.MemoryStack;

import java.util.List;

// I share one weather description between extraction and the visibility probe each frame.
public final class RainFogState {
    public static final int BUFFER_BYTES = 48;
    private static final float[] VALUES = new float[12];
    private static boolean needsScene;
    private static float rainFogMultiplier;

    private RainFogState() {}

    public static void captureRainFog(float multiplier) {
        rainFogMultiplier = multiplier;
    }

    public static void beginFrame(Minecraft client, float partialTick) {
        java.util.Arrays.fill(VALUES, 0);
        needsScene = false;
        if (client.level == null
                || StillBloomClient.config == null
                || !StillBloomClient.config.enabled) return;
        needsScene = client.level.getRainLevel(partialTick) > 0;
        var camera = client.gameRenderer.mainCamera();
        var fog = client.gameRenderer.gameRenderState().levelRenderState.cameraRenderState.fogData;
        if (fog == null) return;
        var fluid = camera.getFluidInCamera();
        if ((fluid != FogType.NONE && fluid != FogType.ATMOSPHERIC)
                || !FogRendererAccessor.stillbloom$fogEnabled()) return;
        if (camera.entity() instanceof LivingEntity living
                && (living.hasEffect(MobEffects.BLINDNESS)
                        || living.hasEffect(MobEffects.DARKNESS))) return;
        if (client.gui.hud.getBossOverlay().shouldCreateWorldFog()) return;
        if (rainFogMultiplier < 0.0001f) return;
        needsScene = true;
        float baseStart =
                camera.attributeProbe()
                        .getValue(EnvironmentAttributes.FOG_START_DISTANCE, partialTick);
        float baseEnd =
                camera.attributeProbe()
                        .getValue(EnvironmentAttributes.FOG_END_DISTANCE, partialTick);
        float[] values = {
            fog.color.x,
            fog.color.y,
            fog.color.z,
            fog.color.w,
            fog.environmentalStart,
            fog.environmentalEnd,
            fog.renderDistanceStart,
            fog.renderDistanceEnd,
            baseStart,
            baseEnd,
            0,
            0
        };
        for (float value : values) if (!Float.isFinite(value)) return;
        if (baseEnd <= baseStart
                || fog.environmentalEnd <= fog.environmentalStart
                || fog.renderDistanceEnd <= fog.renderDistanceStart) return;
        // I keep the rain path until Minecraft's fog has finished fading out.
        boolean rainFog = fog.environmentalStart < baseStart || fog.environmentalEnd < baseEnd;
        if (!rainFog) return;
        needsScene = true;
        values[10] = 1;
        System.arraycopy(values, 0, VALUES, 0, VALUES.length);
    }

    public static boolean needsScene(Minecraft client) {
        return needsScene && client.level != null;
    }

    static void upload(List<GpuBuffer> buffers) {
        if (buffers.isEmpty()) return;
        try (var stack = MemoryStack.stackPush()) {
            var builder = Std140Builder.onStack(stack, BUFFER_BYTES);
            for (float value : VALUES) builder.putFloat(value);
            var data = builder.get();
            var encoder = RenderSystem.getDevice().createCommandEncoder();
            for (var buffer : buffers) {
                data.rewind();
                encoder.writeToBuffer(buffer.slice(), data);
            }
        }
    }
}
