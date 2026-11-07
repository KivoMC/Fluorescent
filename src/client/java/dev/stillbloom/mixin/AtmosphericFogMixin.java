package dev.stillbloom.mixin;

import dev.stillbloom.render.RainFogState;

import net.minecraft.client.renderer.fog.environment.AtmosphericFogEnvironment;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AtmosphericFogEnvironment.class)
public abstract class AtmosphericFogMixin {
    @Shadow private float rainFogMultiplier;

    @Inject(method = "setupFog", at = @At("TAIL"))
    private void stillbloom$rainFog(CallbackInfo ci) {
        RainFogState.captureRainFog(rainFogMultiplier);
    }
}
