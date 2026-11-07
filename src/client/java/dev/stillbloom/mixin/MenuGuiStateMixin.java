package dev.stillbloom.mixin;

import dev.stillbloom.gui.MenuPresentation;

import net.minecraft.client.renderer.state.gui.GuiElementRenderState;
import net.minecraft.client.renderer.state.gui.GuiRenderState;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(GuiRenderState.class)
public abstract class MenuGuiStateMixin {
    @ModifyVariable(method = "addGuiElement", at = @At("HEAD"), argsOnly = true)
    private GuiElementRenderState stillbloom$fadeMenuElement(GuiElementRenderState state) {
        return MenuPresentation.fade(state);
    }
}
