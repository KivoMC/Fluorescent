package dev.stillbloom.model;

import dev.stillbloom.config.BloomConfig;
import dev.stillbloom.config.BloomStyle;
import dev.stillbloom.mask.TextureMasks;

// I inherit each missing item setting from its block, then from the current global setting.
public final class ItemAppearance {
    public record Resolved(
            BloomStyle style,
            Integer color,
            float detail,
            TextureMasks.Profile item,
            TextureMasks.Profile block) {}

    private ItemAppearance() {}

    public static Resolved resolve(
            BloomConfig global, TextureMasks.Profile block, TextureMasks.Profile item) {
        var base = block == null ? null : block.override;
        var own = item == null ? null : item.override;
        var style =
                new BloomStyle(
                        value(
                                own == null ? null : own.shape,
                                base == null ? null : base.shape,
                                global.bloomShape),
                        value(
                                own == null ? null : own.intensity,
                                base == null ? null : base.intensity,
                                global.intensity),
                        value(
                                own == null ? null : own.width,
                                base == null ? null : base.width,
                                global.glowWidth),
                        value(
                                own == null ? null : own.flare,
                                base == null ? null : base.flare,
                                global.flareStyle),
                        value(
                                own == null ? null : own.flareStrength,
                                base == null ? null : base.flareStrength,
                                global.flareStrength),
                        value(
                                own == null ? null : own.flareLength,
                                base == null ? null : base.flareLength,
                                global.flareLength));
        return new Resolved(
                style,
                value(own == null ? null : own.color, base == null ? null : base.color, null),
                value(
                        own == null ? null : own.preserveDetail,
                        base == null ? null : base.preserveDetail,
                        global.preserveDetail),
                item,
                block);
    }

    private static <T> T value(T own, T block, T fallback) {
        return own != null ? own : block != null ? block : fallback;
    }

    public static TextureMasks.Profile blockProfile(String itemId, TextureMasks blocks) {
        var item =
                net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(
                        net.minecraft.resources.Identifier.parse(itemId));
        return item instanceof net.minecraft.world.item.BlockItem block
                ? blocks.profile(
                        net.minecraft.core.registries.BuiltInRegistries.BLOCK
                                .getKey(block.getBlock())
                                .toString())
                : null;
    }
}
