package dev.stillbloom.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import dev.stillbloom.gui.MenuPresentation;

import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Gui.class)
public abstract class MenuGuiMixin {
    @Inject(method = "setScreen", at = @At("HEAD"))
    private void stillbloom$menuScreenChanged(Screen next, CallbackInfo ci) {
        MenuPresentation.screenChanging(next);
    }

    @WrapOperation(
            method = "extractRenderState",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/client/gui/screens/Screen;extractRenderStateWithTooltipAndSubtitles(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V"))
    private void stillbloom$menuPresentation(
            Screen screen,
            GuiGraphicsExtractor graphics,
            int x,
            int y,
            float tick,
            Operation<Void> original) {
        MenuPresentation.extract(screen, graphics, x, y, tick, original);
    }
}
