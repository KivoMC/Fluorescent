package dev.stillbloom.mixin;

import net.minecraft.client.renderer.item.ItemStackRenderState;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ItemStackRenderState.class)
public interface ItemRenderStateAccessor {
    @Accessor("activeLayerCount")
    int stillbloom$activeLayers();

    @Accessor("layers")
    ItemStackRenderState.LayerRenderState[] stillbloom$layers();
}
