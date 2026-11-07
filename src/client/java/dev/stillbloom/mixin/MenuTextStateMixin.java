package dev.stillbloom.mixin;

import dev.stillbloom.gui.MenuPresentation;

import net.minecraft.client.renderer.state.gui.GuiTextRenderState;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(GuiTextRenderState.class)
public abstract class MenuTextStateMixin {
    @ModifyVariable(method = "<init>", at = @At("HEAD"), argsOnly = true, ordinal = 2)
    private static int stillbloom$fadeMenuText(int color) {
        return MenuPresentation.color(color);
    }

    @ModifyVariable(method = "<init>", at = @At("HEAD"), argsOnly = true, ordinal = 3)
    private static int stillbloom$fadeMenuTextBackdrop(int color) {
        return MenuPresentation.color(color);
    }
}
