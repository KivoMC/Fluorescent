package dev.stillbloom.mixin;

import com.mojang.blaze3d.vertex.PoseStack;

import dev.stillbloom.render.item.HeldItemCapture;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.FirstPersonHandsAndItemsRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.FirstPersonHandsAndItemsRenderState;
import net.minecraft.client.renderer.state.level.PlayerRenderState;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(FirstPersonHandsAndItemsRenderer.class)
public abstract class HeldItemFirstPersonMixin {
    @Inject(method = "submitArmWithItem", at = @At("HEAD"))
    private void stillbloom$beginItem(
            PlayerRenderState player,
            FirstPersonHandsAndItemsRenderState state,
            float partialTick,
            float pitch,
            InteractionHand hand,
            float attack,
            ItemStack stack,
            float height,
            PoseStack poses,
            SubmitNodeCollector collector,
            int light,
            CallbackInfo ci) {
        var mainArm =
                player.avatarRenderState == null
                        ? HumanoidArm.RIGHT
                        : player.avatarRenderState.mainArm;
        var arm = hand == InteractionHand.MAIN_HAND ? mainArm : mainArm.getOpposite();
        HeldItemCapture.beginFirstPerson(
                Minecraft.getInstance().player,
                stack,
                arm == HumanoidArm.RIGHT
                        ? ItemDisplayContext.FIRST_PERSON_RIGHT_HAND
                        : ItemDisplayContext.FIRST_PERSON_LEFT_HAND);
    }

    @Inject(method = "submitArmWithItem", at = @At("RETURN"))
    private void stillbloom$endItem(CallbackInfo ci) {
        HeldItemCapture.endItem();
    }
}
