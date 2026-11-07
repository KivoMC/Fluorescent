package dev.stillbloom.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.resource.CrossFrameResourcePool;

import dev.stillbloom.StillBloomClient;
import dev.stillbloom.render.RainFogState;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.Projection;

import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
    @Shadow @Final private Minecraft minecraft;
    @Shadow @Final private RenderTarget mainRenderTarget;
    @Shadow @Final private CrossFrameResourcePool resourcePool;

    @ModifyArg(
            method = "renderLevel",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/client/renderer/ProjectionMatrixBuffer;getBuffer(Lorg/joml/Matrix4f;)Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;"),
            index = 0)
    private Matrix4f stillbloom$captureProjection(Matrix4f projection) {
        StillBloomClient.HELD_RENDERER.captureWorldProjection(projection);
        StillBloomClient.SOURCE_MASK.captureProjection(
                projection,
                minecraft.gameRenderer.gameRenderState()
                        .levelRenderState
                        .cameraRenderState
                        .viewRotationMatrix);
        return projection;
    }

    @Inject(
            method = "renderLevel",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/client/renderer/GameRenderer;render3dHud(Lnet/minecraft/client/renderer/state/level/CameraRenderState;Lnet/minecraft/client/renderer/state/level/PlayerRenderState;Lnet/minecraft/client/renderer/state/OptionsRenderState;Z)V"))
    private void stillbloom$afterWorld(CallbackInfo ci) {
        StillBloomClient.HELD_RENDERER.captureWorld(minecraft, mainRenderTarget);
        StillBloomClient.RENDERER.render(minecraft, mainRenderTarget, resourcePool);
        StillBloomClient.HELD_RENDERER.renderWorld(minecraft, mainRenderTarget, resourcePool);
    }

    @Inject(method = "renderLevel", at = @At("HEAD"))
    private void stillbloom$beginHeld(CallbackInfo ci) {
        RainFogState.beginFrame(
                minecraft,
                minecraft.gameRenderer.gameRenderState().levelRenderState.worldPartialTicks);
        StillBloomClient.WEATHER_SCENE.beginFrame(minecraft);
        StillBloomClient.HELD_RENDERER.beginFrame();
    }

    @ModifyArg(
            method = "render3dHud",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/client/renderer/ProjectionMatrixBuffer;getBuffer(Lnet/minecraft/client/renderer/Projection;)Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;"),
            index = 0)
    private Projection stillbloom$handProjection(Projection projection) {
        StillBloomClient.HELD_RENDERER.captureHandProjection(projection);
        return projection;
    }

    @Inject(
            method = "render3dHud",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/client/renderer/GameRenderer;renderItemInHand(Lnet/minecraft/client/renderer/state/level/CameraRenderState;Lnet/minecraft/client/renderer/state/level/PlayerRenderState;Lcom/mojang/renderpearl/api/textures/GpuTextureView;)V",
                            shift = At.Shift.AFTER))
    private void stillbloom$afterHands(CallbackInfo ci) {
        StillBloomClient.HELD_RENDERER.renderHands(minecraft, mainRenderTarget, resourcePool);
    }

    @Inject(method = "close", at = @At("HEAD"))
    private void stillbloom$close(CallbackInfo ci) {
        StillBloomClient.WEATHER_SCENE.close();
        StillBloomClient.RENDERER.close();
        StillBloomClient.SOURCE_MASK.close();
        StillBloomClient.HELD_RENDERER.close();
    }
}
