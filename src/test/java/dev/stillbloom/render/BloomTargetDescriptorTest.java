package dev.stillbloom.render;

import static org.junit.jupiter.api.Assertions.*;

import com.mojang.blaze3d.resource.RenderTargetDescriptor;
import com.mojang.renderpearl.api.GpuFormat;

import org.joml.Vector4f;
import org.junit.jupiter.api.Test;

class BloomTargetDescriptorTest {
    @Test
    void byteBudgetAccountsForTargetFormatAndCannotOverflow() {
        assertEquals(
                640L * 360 * 8,
                new BloomTargetDescriptor(640, 360, GpuFormat.RGBA16_FLOAT).byteSize());
        assertEquals(
                640L * 360 * 4,
                new BloomTargetDescriptor(640, 360, GpuFormat.RGBA8_UNORM).byteSize());
        assertEquals(
                Long.MAX_VALUE,
                new BloomTargetDescriptor(
                                Integer.MAX_VALUE, Integer.MAX_VALUE, GpuFormat.RGBA32_FLOAT)
                        .byteSize());
    }

    @Test
    void reuseRequiresExactDimensionsAndFormatAndNeverBorrowsVanillaTargets() {
        var target = new BloomTargetDescriptor(640, 360, GpuFormat.RGBA16_FLOAT);
        assertTrue(
                target.canUsePhysicalResource(
                        new BloomTargetDescriptor(640, 360, GpuFormat.RGBA16_FLOAT)));
        assertFalse(
                target.canUsePhysicalResource(
                        new BloomTargetDescriptor(641, 360, GpuFormat.RGBA16_FLOAT)));
        assertFalse(
                target.canUsePhysicalResource(
                        new BloomTargetDescriptor(640, 361, GpuFormat.RGBA16_FLOAT)));
        for (var format : new GpuFormat[] {GpuFormat.RGBA8_UNORM, GpuFormat.RGBA32_FLOAT})
            assertFalse(target.canUsePhysicalResource(new BloomTargetDescriptor(640, 360, format)));
        var vanilla =
                new RenderTargetDescriptor(
                        640,
                        360,
                        new RenderTargetDescriptor.TextureProperties(
                                new Vector4f(), GpuFormat.RGBA16_FLOAT),
                        null);
        assertFalse(target.canUsePhysicalResource(vanilla));
        assertFalse(vanilla.canUsePhysicalResource(target));
        // I leave initialization to the producing pass so prepare doesn't add a redundant GPU
        // clear.
        assertDoesNotThrow(() -> target.prepare(null));
    }

    @Test
    void conversionLeavesUnrelatedNamesAndMixedShaderChainsUntouched() {
        var vanilla =
                new RenderTargetDescriptor(
                        321,
                        181,
                        new RenderTargetDescriptor.TextureProperties(
                                new Vector4f(1, 0, 0, 1), GpuFormat.RGBA8_UNORM),
                        RenderTargetDescriptor.TextureProperties.DEFAULT_DEPTH);
        assertSame(vanilla, BloomTargetDescriptor.transientTarget("minecraft:main", vanilla, true));
        assertSame(
                vanilla,
                BloomTargetDescriptor.transientTarget("stillbloom:down_2", vanilla, false));
        assertEquals(
                new BloomTargetDescriptor(321, 181, GpuFormat.RGBA8_UNORM),
                BloomTargetDescriptor.transientTarget("stillbloom:down_2", vanilla, true));
        assertSame(vanilla, BloomTargetDescriptor.persistentTarget(vanilla, false));
        var persistent = BloomTargetDescriptor.persistentTarget(vanilla, true);
        assertEquals(vanilla.width(), persistent.width());
        assertEquals(vanilla.height(), persistent.height());
        assertSame(vanilla.color(), persistent.color());
        assertNull(persistent.depth());
    }
}
