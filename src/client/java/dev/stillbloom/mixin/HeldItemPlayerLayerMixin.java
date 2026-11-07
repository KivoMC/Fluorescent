package dev.stillbloom.mixin;

import com.mojang.blaze3d.vertex.PoseStack;

import dev.stillbloom.render.item.HeldItemCapture;

import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.layers.ItemInHandLayer;
import net.minecraft.client.renderer.entity.state.ArmedEntityRenderState;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemStack;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ItemInHandLayer.class)
public abstract class HeldItemPlayerLayerMixin {
    @Inject(method = "submitArmWithItem", at = @At("HEAD"))
    private void stillbloom$beginItem(
            ArmedEntityRenderState owner,
            ItemStackRenderState itemState,
            ItemStack stack,
            HumanoidArm arm,
            PoseStack poses,
            SubmitNodeCollector collector,
            int light,
            CallbackInfo ci) {
        HeldItemCapture.beginPlayer(owner, stack);
    }

    @Inject(method = "submitArmWithItem", at = @At("RETURN"))
    private void stillbloom$endItem(
            ArmedEntityRenderState owner,
            ItemStackRenderState itemState,
            ItemStack stack,
            HumanoidArm arm,
            PoseStack poses,
            SubmitNodeCollector collector,
            int light,
            CallbackInfo ci) {
        HeldItemCapture.endItem();
    }
}
