package dev.stillbloom.render;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;

import dev.stillbloom.config.BloomConfig;
import dev.stillbloom.mask.TextureMasks;
import dev.stillbloom.preset.SetupPresets;
import dev.stillbloom.render.source.PaintedAtlas;

import net.minecraft.client.renderer.PostChainConfig;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.List;

class SelfLitPaintTest {

    @Test
    void paintedPathBorrowsAtlasInsteadOfSceneWithoutAddingTargetsOrPasses() {
        for (boolean wet : List.of(false, true))
            for (boolean color : List.of(false, true))
                for (boolean distance : List.of(false, true)) {
                    var c = new BloomConfig();
                    c.pixelSize = 4;
                    c.distancePixels = distance;
                    var lit = BloomGraph.createConfig(321, 183, c, true, color);
                    var idle = BloomActivity.createConfig(321, 183, c, true, color);
                    c.selfLitPaint = false;
                    var scene = BloomGraph.createConfig(321, 183, c, true, color);
                    if (wet) {
                        lit = BloomGraph.forWeather(lit);
                        scene = BloomGraph.forWeather(scene);
                        idle = BloomGraph.forWeather(idle);
                    }
                    assertEquals(lit.internalTargets(), scene.internalTargets());
                    assertEquals(lit.passes().size(), scene.passes().size());
                    assertEquals(
                            lit.passes().subList(1, lit.passes().size()),
                            scene.passes().subList(1, scene.passes().size()));
                    for (var g : List.of(lit, idle)) {
                        var first = g.passes().getFirst();
                        assertTrue(first.fragmentShaderId().getPath().contains("_paint"));
                        assertTrue(
                                ((PostChainConfig.TargetInput) first.inputs().getFirst())
                                        .useDepthBuffer());
                        assertTrue(
                                first.inputs().stream()
                                        .anyMatch(
                                                in ->
                                                        in instanceof PostChainConfig.TextureInput t
                                                                && t.location()
                                                                        .equals(PaintedAtlas.ID)));
                        assertFalse(
                                first.inputs().stream()
                                        .anyMatch(in -> in.samplerName().equals("In")));
                    }
                    assertEquals("In", scene.passes().getFirst().inputs().getFirst().samplerName());
                }
    }

    @Test
    void newAndOlderSettingsEnablePaintAndExplicitOptOutSurvivesSharing(@TempDir Path dir)
            throws Exception {
        var f = dir.resolve("config.json");
        Files.writeString(f, "{}");
        var config = BloomConfig.load(f);
        assertTrue(config.selfLitPaint);
        config.selfLitPaint = false;
        config.save(f);
        assertFalse(BloomConfig.load(f).selfLitPaint);
        var masks = new TextureMasks();
        var eyes = new TextureMasks.Mask(16, 16);
        eyes.set(6, 8, true);
        eyes.set(9, 8, true);
        masks.put("minecraft:spawner", "minecraft:block/spawner", eyes);
        var presets = SetupPresets.load(dir.resolve("presets.json"));
        var name = presets.add("Eyes", SetupPresets.capture(config, masks));
        var tree = JsonParser.parseString(presets.export(name)).getAsJsonObject();
        assertFalse(SetupPresets.parse(tree.toString()).setup().settings().selfLitPaint);
        tree.getAsJsonObject("setup").getAsJsonObject("settings").remove("selfLitPaint");
        var migrated = SetupPresets.parse(tree.toString()).setup();
        assertTrue(migrated.settings().selfLitPaint);
        assertArrayEquals(
                eyes.words,
                migrated.masks().mask("minecraft:spawner", "minecraft:block/spawner").words);
    }
}
