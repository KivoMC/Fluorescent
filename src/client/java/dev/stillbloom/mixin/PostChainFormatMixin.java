package dev.stillbloom.mixin;

import com.mojang.blaze3d.framegraph.FrameGraphBuilder;
import com.mojang.blaze3d.pipeline.*;
import com.mojang.blaze3d.resource.*;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;

import dev.stillbloom.render.BloomTargetDescriptor;

import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.Identifier;

import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.*;

// I keep 32-bit targets for history and the 2px stability pass to avoid rounding away small
// changes.
@Mixin(PostChain.class)
public abstract class PostChainFormatMixin {
    @Shadow @Final private List<PostPass> passes;
    @Shadow @Final private Map<Identifier, PostChainConfig.InternalTarget> internalTargets;
    @Unique private boolean stillbloom$ownedTargets;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void stillbloom$identifyTargets(CallbackInfo ci) {
        stillbloom$ownedTargets =
                !passes.isEmpty()
                        && passes.stream()
                                .allMatch(
                                        pass ->
                                                ((PostPassAccessor) pass)
                                                        .stillbloom$getPipeline()
                                                        .getShaders()
                                                        .get(
                                                                com.mojang.renderpearl.api.pipeline
                                                                        .ShaderType.FRAGMENT)
                                                        .getNamespace()
                                                        .equals("stillbloom"))
                        && internalTargets.keySet().stream()
                                .allMatch(id -> id.getNamespace().equals("stillbloom"));
    }

    @Redirect(
            method = "addToFrame",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lcom/mojang/blaze3d/framegraph/FrameGraphBuilder;createInternal(Ljava/lang/String;Lcom/mojang/blaze3d/resource/ResourceDescriptor;)Lcom/mojang/blaze3d/resource/ResourceHandle;"))
    private ResourceHandle<RenderTarget> stillbloom$transientTarget(
            FrameGraphBuilder frame, String name, ResourceDescriptor<RenderTarget> descriptor) {
        return frame.createInternal(
                name,
                BloomTargetDescriptor.transientTarget(name, descriptor, stillbloom$ownedTargets));
    }

    @ModifyArg(
            method = "addToFrame",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/client/renderer/PostChain;getOrCreatePersistentTarget(Lnet/minecraft/resources/Identifier;Lcom/mojang/blaze3d/resource/RenderTargetDescriptor;)Lcom/mojang/blaze3d/pipeline/RenderTarget;"),
            index = 1)
    private RenderTargetDescriptor stillbloom$persistentTarget(RenderTargetDescriptor descriptor) {
        return BloomTargetDescriptor.persistentTarget(descriptor, stillbloom$ownedTargets);
    }

    @Redirect(
            method = "createPass",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lcom/mojang/renderpearl/api/pipeline/RenderPipeline$Builder;build()Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;"))
    private static RenderPipeline stillbloom$historyFormat(
            RenderPipeline.Builder builder,
            TextureManager textures,
            PostChainConfig.Pass config,
            Identifier id) {
        return dev.stillbloom.render.PostPipelineCache.finish(
                builder, config.fragmentShaderId(), config.outputTarget());
    }
}
