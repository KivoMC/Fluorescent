package dev.stillbloom.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;

import dev.stillbloom.StillBloomClient;

import net.minecraft.client.renderer.ShaderManager;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;

@Mixin(ShaderManager.class)
public abstract class ShaderManagerMixin {
    @ModifyExpressionValue(
            method = "lambda$reload$1",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/client/renderer/RenderPipelines;optionalPipelines()Ljava/util/List;"))
    private static List<RenderPipeline> stillbloom$prepareHeldPrograms(
            List<RenderPipeline> original) {
        var warmup = dev.stillbloom.render.PostPipelineCache.heldWarmup();
        if (warmup.isEmpty()) return original;
        var combined = new ArrayList<>(original);
        combined.addAll(warmup);
        return combined;
    }

    @Inject(method = "apply", at = @At("TAIL"))
    private void stillbloom$reload(CallbackInfo ci) {
        dev.stillbloom.model.BlockMaskVariants.invalidate();
        StillBloomClient.WEATHER_SCENE.invalidate();
        StillBloomClient.RENDERER.invalidate();
    }
}
