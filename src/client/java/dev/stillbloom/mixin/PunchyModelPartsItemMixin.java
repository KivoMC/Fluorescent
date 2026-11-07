package dev.stillbloom.mixin;

import com.mojang.blaze3d.vertex.PoseStack;

import dev.stillbloom.compat.CustomHeldItemCapture;
import dev.stillbloom.render.item.HeldItemCapture;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "punchy.client.modelparts.ModelPartsSystem", remap = false)
public abstract class PunchyModelPartsItemMixin {
    @Inject(method = "tryRenderItem", at = @At("HEAD"), require = 0)
    private void stillbloom$beginCustom(
            ItemStack stack,
            LocalPlayer player,
            HumanoidArm arm,
            PoseStack poses,
            SubmitNodeCollector collector,
            int light,
            float tick,
            boolean display,
            CallbackInfoReturnable<Boolean> ci) {
        CustomHeldItemCapture.beginCustom(stack);
        HeldItemCapture.pushCustom(
                player,
                stack,
                arm == HumanoidArm.LEFT
                        ? ItemDisplayContext.FIRST_PERSON_LEFT_HAND
                        : ItemDisplayContext.FIRST_PERSON_RIGHT_HAND);
    }

    @Inject(method = "tryRenderItem", at = @At("RETURN"), require = 0)
    private void stillbloom$endCustom(
            ItemStack stack,
            LocalPlayer player,
            HumanoidArm arm,
            PoseStack poses,
            SubmitNodeCollector collector,
            int light,
            float tick,
            boolean display,
            CallbackInfoReturnable<Boolean> ci) {
        HeldItemCapture.endItem();
        CustomHeldItemCapture.endCustom();
    }
}
