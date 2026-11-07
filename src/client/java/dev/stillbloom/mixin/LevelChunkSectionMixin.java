package dev.stillbloom.mixin;

import dev.stillbloom.render.source.SectionRevision;

import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LevelChunkSection.class)
public class LevelChunkSectionMixin implements SectionRevision {
    @Unique private volatile long stillbloom$version;

    @Override
    public long stillbloom$revision() {
        return stillbloom$version;
    }

    @Inject(
            method =
                    "setBlockState(IIILnet/minecraft/world/level/block/state/BlockState;Z)Lnet/minecraft/world/level/block/state/BlockState;",
            at = @At("RETURN"))
    private void stillbloom$changed(
            int x,
            int y,
            int z,
            BlockState state,
            boolean acquire,
            CallbackInfoReturnable<BlockState> ci) {
        if (ci.getReturnValue() != state) stillbloom$version++;
    }

    @Inject(
            method = {"read", "recalcBlockCounts"},
            at = @At("TAIL"))
    private void stillbloom$reloaded(CallbackInfo ci) {
        stillbloom$version++;
    }
}
