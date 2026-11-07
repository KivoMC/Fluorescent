package dev.stillbloom.render.source;

import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;

import dev.stillbloom.render.BloomGraph;

import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.Identifier;

// I expose the live block atlas where PostChain expects it, without copying or owning it.
public final class PaintedAtlas extends AbstractTexture {
    public static final Identifier ID = BloomGraph.id("paint_atlas");
    private final TextureManager manager;

    private PaintedAtlas(TextureManager manager) {
        this.manager = manager;
    }

    public static void register(TextureManager manager) {
        manager.register(
                ID.withPath(p -> "textures/effect/" + p + ".png"), new PaintedAtlas(manager));
    }

    @Override
    public GpuTexture getTexture() {
        return manager.getTexture(TextureAtlas.LOCATION_BLOCKS).getTexture();
    }

    @Override
    public GpuTextureView getTextureView() {
        return manager.getTexture(TextureAtlas.LOCATION_BLOCKS).getTextureView();
    }

    @Override
    public void close() {} // I leave the real atlas for Minecraft's texture manager to close.
}
