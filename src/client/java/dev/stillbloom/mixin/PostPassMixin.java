package dev.stillbloom.mixin;

import com.mojang.blaze3d.buffers.*;
import com.mojang.blaze3d.pipeline.*;
import com.mojang.blaze3d.resource.ResourceHandle;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;

import dev.stillbloom.render.BloomPass;

import net.minecraft.client.renderer.PostPass;
import net.minecraft.resources.Identifier;

import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.*;

// I replace only Fluorescent's passes; Minecraft and other mods keep their own drawing.
@Mixin(PostPass.class)
public abstract class PostPassMixin {
    @Shadow @Final private RenderPipeline pipeline;
    @Shadow @Final private Identifier outputTargetId;
    @Shadow @Final private List<PostPass.Input> inputs;
    @Shadow @Final private Map<String, GpuBuffer> customUniforms;
    @Unique private BloomPass stillbloom$cached;

    @Inject(method = "lambda$addToFrame$1", at = @At("HEAD"), cancellable = true)
    private void stillbloom$render(
            ResourceHandle<RenderTarget> output,
            GpuBufferSlice projection,
            Map<Identifier, ResourceHandle<RenderTarget>> targets,
            CallbackInfo ci) {
        if (!pipeline.getShaders()
                .get(com.mojang.renderpearl.api.pipeline.ShaderType.FRAGMENT)
                .getNamespace()
                .equals("stillbloom")) return;
        if (stillbloom$cached == null)
            stillbloom$cached = new BloomPass(pipeline, inputs, customUniforms, outputTargetId);
        stillbloom$cached.render(output.get(), projection, targets);
        ci.cancel();
    }

    @Inject(method = "close", at = @At("HEAD"))
    private void stillbloom$close(CallbackInfo ci) {
        if (stillbloom$cached != null) {
            stillbloom$cached.close();
            stillbloom$cached = null;
        }
    }
}
