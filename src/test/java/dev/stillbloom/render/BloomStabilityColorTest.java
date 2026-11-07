package dev.stillbloom.render;

import static org.junit.jupiter.api.Assertions.*;

import dev.stillbloom.config.BloomConfig;
import dev.stillbloom.config.BloomOverride;
import dev.stillbloom.config.BloomStyle;
import dev.stillbloom.mask.TextureMasks;
import dev.stillbloom.render.source.SourceMask;

import net.minecraft.client.renderer.PostChainConfig;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.*;

class BloomStabilityColorTest {
    @Test
    void cameraCacheTracksProjectionPositionOriginAndNewBufferLifetime() {
        var cache = new CameraUniforms();
        var data = java.nio.ByteBuffer.allocateDirect(SourceMask.BUFFER_BYTES);
        new org.joml.Matrix4f().get(0, data);
        data.putFloat(64, 1).putFloat(68, 2).putFloat(72, 3).putFloat(76, 0);
        assertTrue(cache.changed(data));
        assertFalse(cache.changed(data));
        assertEquals(0, data.position());
        for (int field : new int[] {0, 16, 32, 48, 64, 68, 72, 76}) {
            data.putFloat(field, data.getFloat(field) + .25f);
            assertTrue(cache.changed(data));
            assertFalse(cache.changed(data));
        }
        cache.reset();
        assertTrue(cache.changed(data));
    }

    @Test
    void responseTracksElapsedTimeAtCappedAndUncappedRates() {
        for (int fps : new int[] {30, 60, 120, 240, 1000, 2000}) {
            float previous = .7f, weight = BloomHistory.historyWeight(1.0 / fps, 40, false);
            for (int i = 0; i < fps / 10; i++)
                previous = BrightnessHistory.brightness(.8f, previous, weight);
            assertEquals(.8 - .1 * Math.exp(-2.5), previous, 2e-6, "Response at " + fps + " fps");
        }
    }

    @Test
    void emptyNewAndResetFramesCannotLeaveGhostBrightness() {
        assertEquals(0, BrightnessHistory.brightness(0, 1, .99f));
        assertEquals(.4f, BrightnessHistory.brightness(.4f, 0, .99f));
        assertEquals(0, BloomHistory.historyWeight(.001, 40, true));
        assertEquals(0, BloomHistory.historyWeight(.3, 40, false));
        assertEquals(0, BloomHistory.historyWeight(.01, 0, false));
        assertEquals(.2f, BrightnessHistory.brightness(.2f, 1, 0));
        assertTrue(BrightnessHistory.brightness(.2f, 1, .99f) <= .25f);
    }

    @Test
    void alternatingBrightnessIsAttenuatedWithoutChangingItsMean() {
        float previous = .5f, w = BloomHistory.historyWeight(1.0 / 120, 40, false);
        double sum = 0, variance = 0;
        for (int i = 0; i < 240; i++) {
            float current = i % 2 == 0 ? .48f : .52f;
            previous = BrightnessHistory.brightness(current, previous, w);
            if (i >= 120) {
                sum += previous;
                variance += (previous - .5) * (previous - .5);
            }
        }
        assertEquals(.5, sum / 120, 1e-5);
        assertTrue(variance / 120 < .0004 * .1);
    }

    @Test
    void smoothingUsesOneToOneOrFusedGridSizedExternalHistory() {
        var c = new BloomConfig();
        for (int pixel : new int[] {3, 4})
            for (var shape : BloomConfig.Shape.values()) {
                c.pixelSize = pixel;
                c.bloomShape = shape;
                c.stabilityMillis = 0;
                var groups = List.of(new SourceMask.RenderGroup(0, BloomStyle.global(c)));
                var old = BloomGraph.createGroupedConfig(1921, 1081, c, false, groups);
                c.stabilityMillis = 40;
                var stable = BloomGraph.createGroupedConfig(1921, 1081, c, false, groups);
                assertEquals(old.passes().size() + (pixel < 4 ? 1 : 0), stable.passes().size());
                assertEquals(
                        1,
                        stable.passes().stream()
                                .filter(p -> p.uniforms().containsKey("Stability"))
                                .count());
                var p =
                        stable.passes().stream()
                                .filter(v -> v.uniforms().containsKey("Stability"))
                                .findFirst()
                                .orElseThrow();
                assertEquals(BloomGraph.id("group_0_down_" + pixel), p.outputTarget());
                assertFalse(stable.internalTargets().containsKey(p.outputTarget()));
                assertEquals(
                        BloomGraph.id("group_0_history"),
                        ((PostChainConfig.TargetInput) p.inputs().getLast()).targetId());
            }
    }

    @Test
    void colorsPersistAndResetWithoutSplittingStyleGroups(@TempDir Path dir) {
        var masks = new TextureMasks();
        var p = new TextureMasks.Profile();
        p.override = new BloomOverride();
        p.override.color = 0x1256EF;
        masks.blocks.put("minecraft:torch", p);
        var g = new BloomConfig();
        assertFalse(p.override.empty());
        assertEquals(BloomStyle.global(g), p.override.resolve(g));
        var file = dir.resolve("colors.json");
        assertTrue(masks.save(file));
        assertEquals(0x1256EF, TextureMasks.load(file).profile("minecraft:torch").override.color);
        masks.put("minecraft:torch", "texture", new TextureMasks.Mask(16, 16));
        masks.reset("minecraft:torch", "texture");
        assertEquals(0x1256EF, p.override.color);
        p.override.intensity = 4f;
        p.override.color = null;
        assertEquals(4, p.override.intensity);
        assertFalse(p.override.empty());
        p.override.color = -1;
        p.override.sanitize();
        assertNull(p.override.color);
        p.override.color = 0x1000000;
        p.override.sanitize();
        assertNull(p.override.color);
        p.override.color = 0;
        p.override.sanitize();
        assertEquals(0, p.override.color);
    }

    @Test
    void naturalShadersAvoidColorResourcesAndIdleProbeHonorsColors() {
        var c = new BloomConfig();
        for (boolean custom : new boolean[] {false, true}) {
            var natural = BloomGraph.createConfig(1920, 1080, c, custom, false).passes().getFirst();
            var colored = BloomGraph.createConfig(1920, 1080, c, custom, true).passes().getFirst();
            assertEquals(natural.inputs().size() + 1, colored.inputs().size());
            assertEquals("Colors", colored.inputs().getLast().samplerName());
            assertEquals(
                    colored.fragmentShaderId(),
                    BloomActivity.createConfig(1920, 1080, c, custom, true)
                            .passes()
                            .getFirst()
                            .fragmentShaderId());
        }
    }

    @Test
    void oldConfigsReceiveSmoothingAndInvalidSettingsStayBounded(@TempDir Path dir)
            throws Exception {
        var path = dir.resolve("config.json");
        Files.writeString(path, "{\"intensity\":4.25}");
        var c = BloomConfig.load(path);
        assertEquals(40, c.stabilityMillis);
        assertEquals(4.25f, c.intensity);
        c.stabilityMillis = Float.NaN;
        c.sanitize();
        assertEquals(40, c.stabilityMillis);
        c.stabilityMillis = -1;
        c.sanitize();
        assertEquals(0, c.stabilityMillis);
        c.stabilityMillis = 200;
        c.sanitize();
        assertEquals(120, c.stabilityMillis);
    }
}
