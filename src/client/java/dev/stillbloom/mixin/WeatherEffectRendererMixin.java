package dev.stillbloom.mixin;

import dev.stillbloom.StillBloomClient;

import net.minecraft.client.renderer.WeatherEffectRenderer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(WeatherEffectRenderer.class)
public abstract class WeatherEffectRendererMixin {
    @Inject(method = "renderOit", at = @At("HEAD"), cancellable = true)
    private void stillbloom$beforeWeather(CallbackInfo ci) {
        if (StillBloomClient.WEATHER_SCENE.suppressPrecipitation()) ci.cancel();
    }
}
