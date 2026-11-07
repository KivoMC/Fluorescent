package dev.stillbloom;

import dev.stillbloom.gui.BloomGuideScreen;
import dev.stillbloom.gui.BloomOverrideScreen;

import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

// I reuse the crosshair hit so picking a block needs no extra search.
final class BlockPicker {
    private BlockPicker() {}

    static void open(Minecraft client) {
        if (client.level == null || client.player == null || client.gui.screen() != null) return;
        if (client.hitResult instanceof BlockHitResult hit
                && hit.getType() == HitResult.Type.BLOCK
                && client.level.hasChunkAt(hit.getBlockPos())) {
            var state = client.level.getBlockState(hit.getBlockPos());
            if (!state.isAir()) {
                String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
                client.gui.setScreen(
                        BloomGuideScreen.firstOpen(
                                new BloomOverrideScreen(null, id).withPreview()));
                client.player.sendOverlayMessage(
                        Component.literal(
                                "Editing all "
                                        + state.getBlock().getName().getString()
                                        + " blocks"));
                return;
            }
        }
        client.player.sendOverlayMessage(
                Component.literal("Aim at a placed block to edit its bloom"));
    }
}
