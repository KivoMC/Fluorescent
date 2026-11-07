package dev.stillbloom.mixin;

import net.minecraft.client.renderer.texture.atlas.SpriteSource;
import net.minecraft.client.renderer.texture.atlas.SpriteSourceList;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

// I read the source order that built the active atlas so the editor shows the same texture.
@Mixin(SpriteSourceList.class)
public interface ItemSpriteSourceListAccessor {
    @Accessor("sources")
    List<SpriteSource> stillbloom$sources();
}
