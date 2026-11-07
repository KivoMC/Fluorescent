package dev.stillbloom.mixin;

import com.mojang.blaze3d.vertex.PoseStack;

import dev.stillbloom.compat.CustomHeldItemCapture;

import net.minecraft.client.renderer.SubmitNodeCollection;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(SubmitNodeCollection.class)
public abstract class CustomHeldItemSubmitMixin {
    @ModifyVariable(method = "submitCustomGeometry", at = @At("HEAD"), argsOnly = true)
    private SubmitNodeCollector.CustomGeometryRenderer stillbloom$captureCustom(
            SubmitNodeCollector.CustomGeometryRenderer renderer,
            PoseStack poses,
            RenderType renderType,
            SubmitNodeCollector.CustomGeometryRenderer original) {
        return CustomHeldItemCapture.wrap(renderType, renderer);
    }
}
