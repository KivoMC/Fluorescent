package dev.stillbloom.mixin;

import net.minecraft.client.gui.ActiveTextCollector;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.state.gui.GuiRenderState;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(GuiGraphicsExtractor.class)
public interface GuiGraphicsAccessor {
    @Accessor("guiRenderState")
    GuiRenderState stillbloom$state();

    @Invoker("createDefaultTextParameters")
    ActiveTextCollector.Parameters stillbloom$parameters(float opacity);
}
