package dev.stillbloom.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.commands.RenderPass;

import dev.stillbloom.StillBloomClient;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.client.renderer.state.level.WeatherRenderState;
import net.minecraft.client.renderer.state.level.WorldBorderRenderState;
import net.minecraft.world.phys.Vec3;

import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Optional;
import java.util.OptionalDouble;

@Mixin(LevelRenderer.class)
public abstract class LevelWeatherMixin {
    @Shadow @Final private LevelTargetBundle targets;
    @Shadow @Final private LevelRenderState levelRenderState;
    @Shadow @Final private WeatherEffectRenderer weatherEffectRenderer;
    @Shadow @Final private WorldBorderRenderer worldBorderRenderer;
    @Unique private boolean stillbloom$deferredWeather;
    @Unique private double stillbloom$borderDistance;

    @Inject(method = "render", at = @At("HEAD"))
    private void stillbloom$begin(CallbackInfo ci) {
        stillbloom$deferredWeather = false;
    }

    @WrapOperation(
            method = "executeClassicTransparency",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/client/renderer/WeatherEffectRenderer;render(Lnet/minecraft/client/renderer/state/level/WeatherRenderState;Lcom/mojang/renderpearl/api/commands/RenderPass;)V"))
    private void stillbloom$deferWeather(
            WeatherEffectRenderer renderer,
            WeatherRenderState state,
            RenderPass pass,
            Operation<Void> draw) {
        stillbloom$deferredWeather = StillBloomClient.WEATHER_SCENE.needed(Minecraft.getInstance());
        if (!stillbloom$deferredWeather) draw.call(renderer, state, pass);
    }

    @WrapOperation(
            method = "executeClassicTransparency",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/client/renderer/WorldBorderRenderer;render(Lnet/minecraft/client/renderer/state/level/WorldBorderRenderState;Lcom/mojang/renderpearl/api/commands/RenderPass;Lnet/minecraft/world/phys/Vec3;D)V"))
    private void stillbloom$deferBorder(
            WorldBorderRenderer renderer,
            WorldBorderRenderState state,
            RenderPass pass,
            Vec3 camera,
            double distance,
            Operation<Void> draw) {
        if (stillbloom$deferredWeather) stillbloom$borderDistance = distance;
        else draw.call(renderer, state, pass, camera, distance);
    }

    @Inject(method = "executeOutline", at = @At("HEAD"))
    private void stillbloom$finishWeather(CallbackInfo ci) {
        if (!stillbloom$deferredWeather) return;
        stillbloom$deferredWeather = false;
        var client = Minecraft.getInstance();
        StillBloomClient.WEATHER_SCENE.captureBeforeWeather(client);
        var main = client.gameRenderer.mainRenderTarget();
        try (var pass =
                RenderSystem.getDevice()
                        .createCommandEncoder()
                        .createRenderPass(
                                () -> "Fluorescent deferred weather",
                                main.getColorTextureView(),
                                Optional.empty(),
                                main.getDepthTextureView(),
                                OptionalDouble.empty())) {
            RenderSystem.bindDefaultUniforms(pass);
            weatherEffectRenderer.render(levelRenderState.weatherRenderState, pass);
            worldBorderRenderer.render(
                    levelRenderState.worldBorderRenderState,
                    pass,
                    levelRenderState.cameraRenderState.pos,
                    stillbloom$borderDistance);
        }
    }

    @WrapOperation(
            method = "lambda$addMainPass$0",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/client/renderer/LevelRenderer;executeOit(Lnet/minecraft/client/renderer/chunk/ChunkSectionsToRender;Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher$PreparedFrame;)V"))
    private void stillbloom$sourceOit(
            LevelRenderer renderer,
            ChunkSectionsToRender chunks,
            FeatureRenderDispatcher.PreparedFrame features,
            Operation<Void> draw) {
        var client = Minecraft.getInstance();
        if (!StillBloomClient.WEATHER_SCENE.needed(client)) {
            draw.call(renderer, chunks, features);
            return;
        }
        StillBloomClient.WEATHER_SCENE.renderOit(
                client, targets, () -> draw.call(renderer, chunks, features));
    }
}
