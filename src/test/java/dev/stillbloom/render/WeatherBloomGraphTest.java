package dev.stillbloom.render;

import static org.junit.jupiter.api.Assertions.*;

import dev.stillbloom.config.BloomConfig;
import dev.stillbloom.config.BloomStyle;
import dev.stillbloom.render.item.HeldItemGraph;
import dev.stillbloom.render.source.SourceMask;

import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.renderer.PostChainConfig;
import net.minecraft.client.renderer.UniformValue;
import net.minecraft.resources.Identifier;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;

class WeatherBloomGraphTest {
    @Test
    void onlyWorldExtractionChangesAcrossGroupedOptionalPaths() {
        for (boolean distance : new boolean[] {false, true})
            for (boolean custom : new boolean[] {false, true})
                for (boolean colored : new boolean[] {false, true})
                    for (boolean direct : new boolean[] {false, true})
                        for (boolean selfLit : new boolean[] {false, true}) {
                            var config = new BloomConfig();
                            config.pixelSize = 4;
                            config.distancePixels = distance;
                            config.farPixelSize = 2;
                            config.selfLitPaint = selfLit;
                            config.smoothBlend = .5f;
                            config.stabilityMillis = 200;
                            config.flareStyle = BloomConfig.Flare.CROSS;
                            var second =
                                    new BloomStyle(
                                            BloomConfig.Shape.STARBURST,
                                            3,
                                            64,
                                            BloomConfig.Flare.HORIZONTAL,
                                            .5f,
                                            64);
                            var groups =
                                    List.of(
                                            new SourceMask.RenderGroup(
                                                    0, BloomStyle.global(config), custom, colored),
                                            new SourceMask.RenderGroup(1, second, custom, colored));
                            var original =
                                    BloomGraph.createGroupedConfig(
                                            1281, 721, config, custom, colored, groups, direct,
                                            true);
                            var weather = BloomGraph.forWeather(original);
                            assertSame(original.internalTargets(), weather.internalTargets());
                            assertEquals(original.passes().size(), weather.passes().size());
                            int extractions = 0, protectedComposites = 0;
                            for (int i = 0; i < original.passes().size(); i++) {
                                var before = original.passes().get(i);
                                var after = weather.passes().get(i);
                                if (!before.fragmentShaderId()
                                        .getPath()
                                        .startsWith("post/extract")) {
                                    assertSame(
                                            before,
                                            after,
                                            "Filtering, history, and composite passes must remain untouched");
                                    if (before.fragmentShaderId()
                                            .getPath()
                                            .startsWith("post/composite")) {
                                        assertEquals(
                                                PostChain.MAIN_TARGET_ID,
                                                target(before, "Depth").targetId());
                                        assertEquals(
                                                target(before, "Scene"), target(after, "Scene"));
                                        protectedComposites++;
                                    }
                                    continue;
                                }
                                extractions++;
                                assertEquals(before.vertexShaderId(), after.vertexShaderId());
                                assertEquals(before.outputTarget(), after.outputTarget());
                                String shader = before.fragmentShaderId().getPath();
                                assertEquals(
                                        BloomGraph.id(
                                                shader.endsWith("_distance")
                                                        ? shader.substring(0, shader.length() - 9)
                                                                + "_rain_distance"
                                                        : shader + "_rain"),
                                        after.fragmentShaderId());
                                var expectedUniforms = new HashMap<>(before.uniforms());
                                expectedUniforms.put(
                                        "RainFog",
                                        java.util.Collections.nCopies(
                                                RainFogState.BUFFER_BYTES / 4,
                                                new UniformValue.FloatUniform(0)));
                                assertEquals(expectedUniforms, after.uniforms());
                                if (!custom || !selfLit)
                                    assertEquals(
                                            BloomGraph.WEATHER_SCENE,
                                            target(after, "In").targetId());
                                assertEquals(
                                        BloomGraph.WEATHER_SCENE,
                                        target(after, "Depth").targetId());
                                for (int j = 0; j < before.inputs().size(); j++) {
                                    var input = before.inputs().get(j);
                                    var result = after.inputs().get(j);
                                    if (input instanceof PostChainConfig.TargetInput t
                                            && t.targetId().equals(PostChain.MAIN_TARGET_ID))
                                        assertEquals(
                                                new PostChainConfig.TargetInput(
                                                        t.samplerName(),
                                                        BloomGraph.WEATHER_SCENE,
                                                        t.useDepthBuffer(),
                                                        t.bilinear()),
                                                result);
                                    else
                                        assertSame(
                                                input,
                                                result,
                                                "Painted masks and source tables keep their original bindings");
                                }
                                assertEquals(
                                        PostChain.MAIN_TARGET_ID,
                                        target(before, "Depth").targetId(),
                                        "Input graph is not mutated");
                            }
                            assertEquals(2, extractions);
                            assertEquals(2, protectedComposites);
                            assertEquals(
                                    PostChain.MAIN_TARGET_ID,
                                    weather.passes().getLast().outputTarget());
                        }
    }

    @Test
    void activityUsesTheSameCleanSceneWithoutChangingReductionOrActiveMask() {
        var config = new BloomConfig();
        config.selfLitPaint = false;
        config.pixelSize = 4;
        config.distancePixels = true;
        config.farPixelSize = 2;
        var original = BloomActivity.createConfig(1919, 721, config, true, true);
        var weather = BloomGraph.forWeather(original);
        var before = original.passes().getFirst();
        var after = weather.passes().getFirst();
        assertEquals(
                BloomGraph.id("post/extract_color_rain"),
                after.fragmentShaderId(),
                "Idle detection keeps its original cheap non-distance extraction");
        assertEquals(BloomGraph.WEATHER_SCENE, target(after, "In").targetId());
        assertEquals(BloomGraph.WEATHER_SCENE, target(after, "Depth").targetId());
        var mask =
                after.inputs().stream()
                        .filter(input -> input.samplerName().equals("Sources"))
                        .findFirst()
                        .orElseThrow();
        assertEquals(
                new PostChainConfig.TextureInput(
                        "Sources",
                        SourceMask.ACTIVE_TEXTURE_ID,
                        SourceMask.TEXTURE_SIZE,
                        SourceMask.TEXTURE_SIZE,
                        false),
                mask);
        assertEquals(before.outputTarget(), after.outputTarget());
        for (int i = 1; i < original.passes().size(); i++)
            assertSame(original.passes().get(i), weather.passes().get(i));
        assertEquals(BloomActivity.RESULT, weather.passes().getLast().outputTarget());
        assertEquals(original.internalTargets(), weather.internalTargets());
    }

    @Test
    void heldAndForeignShadersDoNotGainWorldWeatherBindings() {
        var config = new BloomConfig();
        config.pixelSize = 3;
        config.smoothBlend = .5f;
        var held = HeldItemGraph.create(576, 384, config);
        var unchanged = BloomGraph.forWeather(held);
        for (int i = 0; i < held.passes().size(); i++)
            assertSame(held.passes().get(i), unchanged.passes().get(i));
        var extraction = BloomGraph.createConfig(1280, 720, config).passes().getFirst();
        var foreign =
                new PostChainConfig.Pass(
                        extraction.vertexShaderId(),
                        Identifier.fromNamespaceAndPath("another_mod", "post/extract"),
                        extraction.inputs(),
                        extraction.outputTarget(),
                        extraction.uniforms());
        assertSame(
                foreign,
                BloomGraph.forWeather(new PostChainConfig(java.util.Map.of(), List.of(foreign)))
                        .passes()
                        .getFirst());
    }

    private static PostChainConfig.TargetInput target(PostChainConfig.Pass pass, String sampler) {
        return (PostChainConfig.TargetInput)
                pass.inputs().stream()
                        .filter(input -> input.samplerName().equals(sampler))
                        .findFirst()
                        .orElseThrow();
    }
}
