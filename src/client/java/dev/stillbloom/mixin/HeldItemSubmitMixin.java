package dev.stillbloom.mixin;

import com.mojang.blaze3d.vertex.PoseStack;

import dev.stillbloom.render.item.HeldItemCapture;

import net.minecraft.client.renderer.SubmitNodeCollection;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.world.item.ItemDisplayContext;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SubmitNodeCollection.class)
public abstract class HeldItemSubmitMixin {
    @Inject(method = "submitItem", at = @At("HEAD"))
    private void stillbloom$captureItem(
            PoseStack poses,
            ItemDisplayContext display,
            int light,
            int overlay,
            int outline,
            int[] tintLayers,
            net.minecraft.client.resources.model.geometry.ItemQuads quads,
            ItemStackRenderState.FoilType foil,
            CallbackInfo ci) {
        HeldItemCapture.capture(poses, display, tintLayers, quads.all());
    }
}
