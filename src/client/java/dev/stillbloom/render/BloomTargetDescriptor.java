package dev.stillbloom.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.resource.RenderTargetDescriptor;
import com.mojang.blaze3d.resource.ResourceDescriptor;
import com.mojang.renderpearl.api.GpuFormat;

// I skip the extra clear because each bloom pass writes its output or clears before a partial draw.
public record BloomTargetDescriptor(int width, int height, GpuFormat format)
        implements ResourceDescriptor<RenderTarget> {
    public BloomTargetDescriptor {
        if (width < 1 || height < 1 || format == null)
            throw new IllegalArgumentException("Invalid bloom target");
    }

    @Override
    public RenderTarget allocate() {
        return new TextureTarget("Still Bloom intermediate", width, height, format, null);
    }

    long byteSize() {
        long pixels = (long) width * height;
        int pixelBytes = format.blockSize();
        return pixels > Long.MAX_VALUE / pixelBytes ? Long.MAX_VALUE : pixels * pixelBytes;
    }

    @Override
    public void free(RenderTarget target) {
        target.destroyBuffers();
    }

    @Override
    public boolean canUsePhysicalResource(ResourceDescriptor<?> other) {
        return equals(other);
    }

    // I also require the caller to check that every shader belongs to Fluorescent.
    public static ResourceDescriptor<RenderTarget> transientTarget(
            String name, ResourceDescriptor<RenderTarget> original, boolean ownedChain) {
        if (ownedChain
                && name.startsWith("stillbloom:")
                && original instanceof RenderTargetDescriptor target)
            return new BloomTargetDescriptor(
                    target.width(), target.height(), target.color().format());
        return original;
    }

    // I keep Minecraft's first clear for persistent activity targets, but omit unused depth
    // storage.
    public static RenderTargetDescriptor persistentTarget(
            RenderTargetDescriptor original, boolean ownedChain) {
        return ownedChain
                ? new RenderTargetDescriptor(
                        original.width(), original.height(), original.color(), null)
                : original;
    }
}
