package dev.stillbloom.render;

import static org.junit.jupiter.api.Assertions.*;

import dev.stillbloom.config.BloomConfig;
import dev.stillbloom.config.BloomOverride;
import dev.stillbloom.config.BloomStyle;
import dev.stillbloom.mask.TextureMasks;
import dev.stillbloom.preset.SetupPresets;
import dev.stillbloom.render.item.HeldItemGraph;
import dev.stillbloom.render.source.GeometryTexture;
import dev.stillbloom.render.source.PaintedAtlas;
import dev.stillbloom.render.source.SourceMask;
import dev.stillbloom.render.source.TextureGeometry;

import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.renderer.PostChainConfig;
import net.minecraft.client.renderer.UniformValue;
import net.minecraft.resources.Identifier;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.*;

// I require a real half-resolution grid at 2px without losing extraction or history.
class PixelTwoTest {
    private static final boolean[] FLAGS = {false, true};
    private static final int WIDTH = 1921, HEIGHT = 1081, HALF_WIDTH = 961, HALF_HEIGHT = 541;

    @Test
    void supportedSizesPersistThroughConfigAndSharedSetups(@TempDir Path dir) throws Exception {
        var defaults = new BloomConfig();
        assertEquals(3, defaults.pixelSize);
        assertEquals(4, defaults.farPixelSize);
        var legacy = dir.resolve("legacy.json");
        Files.writeString(legacy, "{}");
        assertEquals(3, BloomConfig.load(legacy).pixelSize);
        assertEquals(4, BloomConfig.load(legacy).farPixelSize);
        for (int near : new int[] {2, 3, 4})
            for (int far : new int[] {2, 3, 4}) {
                var config = new BloomConfig();
                config.pixelSize = near;
                config.farPixelSize = far;
                config.distancePixels = true;
                config.uniformEmission = true;
                config.preserveDetail = .63f;
                config.stabilityMillis = 40;
                config.sanitize();
                assertEquals(near, config.pixelSize);
                assertEquals(far, config.farPixelSize);
                assertEquals(Math.min(near, far), config.renderPixelSize());
                assertEquals(far < near, config.usesDistancePixels());
                Path file = dir.resolve("config-" + near + "-" + far + ".json");
                config.save(file);
                var loaded = BloomConfig.load(file);
                assertEquals(near, loaded.pixelSize);
                assertEquals(far, loaded.farPixelSize);
                var masks = new TextureMasks();
                var paint = new TextureMasks.Mask(16, 16);
                paint.set(15, 0, true);
                masks.put("minecraft:torch", "minecraft:block/torch", paint);
                var profile = masks.profile("minecraft:torch");
                profile.strength = 1.4f;
                profile.override = new BloomOverride();
                profile.override.color = 0x12ABEF;
                profile.override.preserveDetail = .45f;
                Path libraryFile = dir.resolve("presets-" + near + "-" + far + ".json");
                var library = SetupPresets.load(libraryFile);
                String name = library.add("Two-pixel test", SetupPresets.capture(config, masks));
                String code = library.export(name);
                var imported =
                        SetupPresets.load(dir.resolve("imported-" + near + "-" + far + ".json"));
                String importedName = imported.importCode(code);
                for (var setup :
                        List.of(
                                SetupPresets.parse(code).setup(),
                                SetupPresets.load(libraryFile).get(name),
                                imported.get(importedName))) {
                    assertEquals(near, setup.settings().pixelSize);
                    assertEquals(far, setup.settings().farPixelSize);
                    assertTrue(setup.settings().uniformEmission);
                    assertEquals(.63f, setup.settings().preserveDetail);
                    assertEquals(40, setup.settings().stabilityMillis);
                    assertTrue(setup.settings().distancePixels);
                    assertTrue(
                            setup.masks()
                                    .mask("minecraft:torch", "minecraft:block/torch")
                                    .get(15, 0));
                    assertEquals(0x12ABEF, setup.masks().profile("minecraft:torch").override.color);
                    assertEquals(
                            .45f, setup.masks().profile("minecraft:torch").override.preserveDetail);
                }
            }
        for (int expected : new int[] {4, 2, 3, 4}) {
            defaults.cyclePixelSize();
            defaults.sanitize();
            assertEquals(expected, defaults.pixelSize);
        }
        for (int invalid : new int[] {-10, 0, 1}) {
            var c = new BloomConfig();
            c.pixelSize = invalid;
            c.farPixelSize = invalid;
            c.sanitize();
            assertEquals(4, c.pixelSize);
            assertEquals(4, c.farPixelSize, "Unsupported grids fall back to 4");
        }
    }

    @Test
    void everyShapeRetainsActualMaskColorDepthAndDetailBindingsWithOptionalOneToOneStability() {
        for (var shape : BloomConfig.Shape.values())
            for (boolean distance : FLAGS)
                for (boolean custom : FLAGS)
                    for (boolean colored : FLAGS)
                        for (boolean uniform : FLAGS)
                            for (boolean details : FLAGS) {
                                var c = settings(shape, distance);
                                c.uniformEmission = uniform;
                                var groups =
                                        List.of(
                                                new SourceMask.RenderGroup(
                                                        7, BloomStyle.global(c), custom, colored));
                                var plain =
                                        BloomGraph.createGroupedConfig(
                                                WIDTH, HEIGHT, c, true, true, groups, false,
                                                details);
                                var extract = plain.passes().getFirst();
                                assertExtraction(extract, c, 7, custom, colored);
                                assertEquals(group(7, "down_2"), extract.outputTarget());
                                assertGridSizes(plain);
                                assertGraphOrder(plain, Set.of());
                                var composite =
                                        plain.passes().stream()
                                                .filter(
                                                        p ->
                                                                p.outputTarget()
                                                                        .equals(
                                                                                group(
                                                                                        7,
                                                                                        "composite")))
                                                .findFirst()
                                                .orElseThrow();
                                assertEquals(
                                        target("Core", group(7, "down_2"), false, true),
                                        input(composite, "Core"));
                                assertEquals(
                                        target("Depth", PostChain.MAIN_TARGET_ID, true, false),
                                        input(composite, "Depth"));
                                assertEquals(
                                        texture(
                                                "Sources",
                                                SourceMask.TEXTURE_ID,
                                                SourceMask.TEXTURE_SIZE,
                                                SourceMask.TEXTURE_SIZE),
                                        input(composite, "Sources"));
                                assertEquals(
                                        details,
                                        composite.inputs().stream()
                                                .anyMatch(i -> i.samplerName().equals("Details")));
                                if (details)
                                    assertEquals(
                                            texture("Details", SourceMask.DETAILS_ID, 1024, 2048),
                                            input(composite, "Details"));
                                assertEquals(
                                        new UniformValue.FloatUniform(distance ? 4 : 2),
                                        composite.uniforms().get("BloomConfig").get(0));
                                if (distance)
                                    assertEquals(
                                            new UniformValue.FloatUniform(2),
                                            composite.uniforms().get("BloomConfig").get(9));
                                assertFalse(
                                        plain.passes().stream()
                                                .anyMatch(
                                                        p ->
                                                                p.uniforms()
                                                                        .containsKey("Stability")));

                                c.stabilityMillis = 40;
                                var stable =
                                        BloomGraph.createGroupedConfig(
                                                WIDTH, HEIGHT, c, true, true, groups, false,
                                                details);
                                assertEquals(
                                        plain.passes().size() + 1,
                                        stable.passes().size(),
                                        "Only the new 2px history pass is added");
                                var stableExtract = stable.passes().getFirst();
                                assertExtraction(stableExtract, c, 7, custom, colored);
                                assertEquals(extract.inputs(), stableExtract.inputs());
                                assertEquals(extract.uniforms(), stableExtract.uniforms());
                                assertEquals(
                                        extract.fragmentShaderId(),
                                        stableExtract.fragmentShaderId());
                                assertEquals(group(7, "extracted_2"), stableExtract.outputTarget());
                                assertStabilizer(stable.passes().get(1), 7, distance);
                                assertEquals(
                                        plain.passes().subList(1, plain.passes().size()),
                                        stable.passes().subList(2, stable.passes().size()),
                                        "All filters, flare, Core, scene/detail protection and composite settings must be identical");
                                var expectedTargets = new LinkedHashMap<>(plain.internalTargets());
                                var grid = expectedTargets.remove(group(7, "down_2"));
                                expectedTargets.put(group(7, "extracted_2"), grid);
                                assertEquals(expectedTargets, stable.internalTargets());
                                assertFalse(
                                        stable.internalTargets().containsKey(group(7, "down_2")),
                                        "Current history is the external half-size grid");
                                assertGridSizes(stable);
                                assertGraphOrder(stable, Set.of(group(7, "history")));
                                var off = c.copy();
                                off.stabilityMillis = 0;
                                assertEquals(
                                        16384L,
                                        BloomRenderer.estimateRetainedBytes(
                                                        WIDTH, HEIGHT, c, groups, stable)
                                                - BloomRenderer.estimateRetainedBytes(
                                                        WIDTH, HEIGHT, off, groups, plain),
                                        "Shared RGBA32 history is accounted separately; only the extra pass belongs to the inactive chain");
                            }
    }

    @Test
    void mixedGroupsKeepTheirOwnHistoryBeforeFilteringAndNeverExtractPreviousBloom() {
        for (var shape : BloomConfig.Shape.values())
            for (boolean distance : FLAGS)
                for (boolean direct : FLAGS) {
                    var c = settings(shape, distance);
                    c.stabilityMillis = 40;
                    c.uniformEmission = true;
                    var other =
                            new BloomStyle(
                                    BloomConfig.Shape.DIAMOND,
                                    4,
                                    64,
                                    BloomConfig.Flare.HORIZONTAL,
                                    .8f,
                                    80);
                    var groups =
                            List.of(
                                    new SourceMask.RenderGroup(
                                            0, BloomStyle.global(c), false, false),
                                    new SourceMask.RenderGroup(3, other, true, true),
                                    new SourceMask.RenderGroup(
                                            9, BloomStyle.global(c), true, false));
                    var graph =
                            BloomGraph.createGroupedConfig(
                                    WIDTH, HEIGHT, c, true, true, groups, direct, true);
                    var histories = new HashSet<Identifier>();
                    int lastExtraction = -1, firstComposite = Integer.MAX_VALUE;
                    for (var group : groups) {
                        var extract =
                                graph.passes().stream()
                                        .filter(
                                                p ->
                                                        p.outputTarget()
                                                                .equals(
                                                                        group(
                                                                                group.index(),
                                                                                "extracted_2")))
                                        .findFirst()
                                        .orElseThrow();
                        int index = graph.passes().indexOf(extract);
                        lastExtraction = Math.max(lastExtraction, index);
                        assertExtraction(
                                extract, c, group.index(), group.custom(), group.colored());
                        assertStabilizer(graph.passes().get(index + 1), group.index(), distance);
                        histories.add(group(group.index(), "history"));
                    }
                    for (int i = 0; i < graph.passes().size(); i++)
                        if (graph.passes()
                                .get(i)
                                .fragmentShaderId()
                                .getPath()
                                .startsWith("post/composite"))
                            firstComposite = Math.min(firstComposite, i);
                    assertTrue(
                            lastExtraction < firstComposite,
                            "Every group extracts the untouched scene before any glow is composited");
                    assertEquals(
                            3,
                            graph.passes().stream()
                                    .filter(p -> p.uniforms().containsKey("Stability"))
                                    .count());
                    assertGridSizes(graph);
                    assertGraphOrder(graph, histories);
                    assertEquals(PostChain.MAIN_TARGET_ID, graph.passes().getLast().outputTarget());
                }
    }

    @Test
    void previouslySupportedSizesKeepFrozenGraphExceptStraightBlurSamplingAndFusedHistory() {
        for (int pixels : new int[] {4})
            for (var shape : BloomConfig.Shape.values())
                for (boolean distance : FLAGS)
                    for (boolean stable : FLAGS)
                        for (boolean direct : FLAGS) {
                            var c = settings(shape, distance);
                            c.pixelSize = pixels;
                            c.farPixelSize = 4;
                            c.smoothBlend = 0;
                            c.uniformEmission = false;
                            c.selfLitPaint = false;
                            c.stabilityMillis = stable ? 40 : 0;
                            var groups =
                                    List.of(
                                            new SourceMask.RenderGroup(
                                                    2, BloomStyle.global(c), true, true),
                                            new SourceMask.RenderGroup(
                                                    8, BloomStyle.global(c), false, false));
                            var actual =
                                    BloomGraph.createGroupedConfig(
                                            WIDTH, HEIGHT, c, true, true, groups, direct, true);
                            assertEquals(
                                    BlurSamplingTest.withNearestStraightBlurInputs(
                                            Reference22Graph.createGroupedConfig(
                                                    WIDTH, HEIGHT, c, true, true, groups, direct,
                                                    true)),
                                    actual);
                            assertFalse(
                                    actual.passes().stream()
                                            .anyMatch(
                                                    p ->
                                                            p.fragmentShaderId()
                                                                    .getPath()
                                                                    .startsWith("post/stabilize")));
                            assertEquals(
                                    stable ? 2 : 0,
                                    actual.passes().stream()
                                            .filter(p -> p.uniforms().containsKey("Stability"))
                                            .count());
                        }
    }

    @Test
    void heldTwoUsesTheRealHalfSizeGridOnOddWindowsWithoutWorldHistoryOrMasks() {
        for (var shape : BloomConfig.Shape.values())
            for (boolean distance : FLAGS)
                for (var flare : BloomConfig.Flare.values())
                    for (int[] size :
                            List.of(new int[] {1, 1}, new int[] {73, 55}, new int[] {1921, 1081})) {
                        var c = settings(shape, distance);
                        c.stabilityMillis = 40;
                        c.uniformEmission = true;
                        c.flareStyle = flare;
                        var graph = HeldItemGraph.create(size[0], size[1], c);
                        var extract = graph.passes().getFirst();
                        assertEquals(BloomGraph.id("held_down_2"), extract.outputTarget());
                        assertEquals(
                                List.of(target("In", BloomGraph.id("held_emission"), false, false)),
                                extract.inputs());
                        assertTrue(extract.uniforms().isEmpty());
                        assertEquals(
                                BloomGraph.id(
                                        "post/held_downsample" + (distance ? "_distance" : "")),
                                extract.fragmentShaderId());
                        graph.internalTargets()
                                .forEach(
                                        (id, spec) -> {
                                            boolean composite = id.getPath().endsWith("composite");
                                            assertEquals(
                                                    composite ? size[0] : (size[0] + 1) / 2,
                                                    spec.width().orElseThrow());
                                            assertEquals(
                                                    composite ? size[1] : (size[1] + 1) / 2,
                                                    spec.height().orElseThrow());
                                        });
                        for (var pass : graph.passes()) {
                            assertFalse(pass.uniforms().containsKey("Stability"));
                            assertFalse(pass.uniforms().containsKey("SourceMask"));
                            for (var in : pass.inputs())
                                assertFalse(
                                        assertInstanceOf(PostChainConfig.TargetInput.class, in)
                                                .useDepthBuffer());
                        }
                        var composite =
                                graph.passes().stream()
                                        .filter(
                                                p ->
                                                        p.outputTarget()
                                                                .equals(
                                                                        BloomGraph.id(
                                                                                "held_composite")))
                                        .findFirst()
                                        .orElseThrow();
                        assertEquals(
                                target("Core", BloomGraph.id("held_down_2"), false, true),
                                input(composite, "Core"));
                        assertEquals(
                                target("Coverage", BloomGraph.id("held_coverage"), false, false),
                                input(composite, "Coverage"));
                        assertGraphOrder(
                                graph,
                                Set.of(
                                        BloomGraph.id("held_emission"),
                                        BloomGraph.id("held_coverage")));
                        var off = c.copy();
                        off.stabilityMillis = 0;
                        assertEquals(HeldItemGraph.create(size[0], size[1], off), graph);
                        assertEquals(40, c.stabilityMillis);
                        assertEquals(.63f, c.preserveDetail);
                    }
    }

    private static BloomConfig settings(BloomConfig.Shape shape, boolean distance) {
        var c = new BloomConfig();
        c.pixelSize = distance ? 4 : 2;
        c.farPixelSize = 2;
        c.distancePixels = distance;
        c.bloomShape = shape;
        c.stabilityMillis = 0;
        c.threshold = .41f;
        c.softKnee = .13f;
        c.bloomRange = 64;
        c.fadeFraction = .25f;
        c.pixelDistanceStart = 12;
        c.pixelDistanceSpan = 28;
        c.preserveDetail = .63f;
        c.smoothBlend = .5f;
        c.flareStyle = BloomConfig.Flare.CROSS;
        c.flareStrength = .7f;
        c.flareLength = 72;
        return c;
    }

    private static Identifier group(int group, String target) {
        return BloomGraph.id("group_" + group + "_" + target);
    }

    private static PostChainConfig.Input input(PostChainConfig.Pass pass, String sampler) {
        return pass.inputs().stream()
                .filter(i -> i.samplerName().equals(sampler))
                .findFirst()
                .orElseThrow();
    }

    private static PostChainConfig.TargetInput target(
            String name, Identifier id, boolean depth, boolean linear) {
        return new PostChainConfig.TargetInput(name, id, depth, linear);
    }

    private static PostChainConfig.TextureInput texture(
            String name, Identifier id, int width, int height) {
        return new PostChainConfig.TextureInput(name, id, width, height, false);
    }

    private static void assertExtraction(
            PostChainConfig.Pass pass, BloomConfig c, int group, boolean custom, boolean colored) {
        String shader =
                custom
                        ? (c.uniformEmission ? "extract_uniform" : "extract")
                        : (colored ? "extract_auto_color" : "extract_auto");
        if (custom && c.selfLitPaint) shader += "_paint";
        if (custom && colored) shader += "_color";
        if (c.usesDistancePixels()) shader += "_distance";
        assertEquals(BloomGraph.id("post/" + shader), pass.fragmentShaderId());
        var inputs = new ArrayList<PostChainConfig.Input>();
        if (!custom || !c.selfLitPaint)
            inputs.add(target("In", PostChain.MAIN_TARGET_ID, false, false));
        inputs.add(target("Depth", PostChain.MAIN_TARGET_ID, true, false));
        if (custom && c.selfLitPaint) inputs.add(texture("Atlas", PaintedAtlas.ID, 1, 1));
        inputs.add(
                texture(
                        "Sources",
                        SourceMask.groupTexture(group),
                        SourceMask.TEXTURE_SIZE,
                        SourceMask.TEXTURE_SIZE));
        if (custom) {
            inputs.add(
                    texture(
                            "Custom",
                            BloomGraph.id("custom_volume"),
                            TextureGeometry.SIZE,
                            TextureGeometry.SIZE));
            inputs.add(
                    texture(
                            "Geometry",
                            BloomGraph.id("custom_geometry"),
                            GeometryTexture.SIZE,
                            GeometryTexture.SIZE));
        }
        if (colored) inputs.add(texture("Colors", SourceMask.COLORS_ID, 1024, 2048));
        assertEquals(inputs, pass.inputs());
        var uniforms = new ArrayList<UniformValue>();
        for (float value :
                new float[] {
                    c.threshold, c.softKnee, c.bloomRange, c.bloomRange * (1 - c.fadeFraction)
                }) uniforms.add(new UniformValue.FloatUniform(value));
        if (c.usesDistancePixels())
            for (float value :
                    new float[] {
                        c.pixelDistanceStart, c.pixelDistanceStart + c.pixelDistanceSpan, 0, 0
                    }) uniforms.add(new UniformValue.FloatUniform(value));
        assertEquals(
                Map.of(
                        "BloomConfig",
                        uniforms,
                        "SourceMask",
                        Collections.nCopies(
                                SourceMask.BUFFER_BYTES / 4, new UniformValue.FloatUniform(0))),
                pass.uniforms());
    }

    private static void assertStabilizer(PostChainConfig.Pass pass, int group, boolean distance) {
        assertEquals(
                BloomGraph.id("post/stabilize" + (distance ? "_distance" : "")),
                pass.fragmentShaderId());
        assertEquals(group(group, "down_2"), pass.outputTarget());
        assertEquals(
                List.of(
                        target("In", group(group, "extracted_2"), false, false),
                        target("History", group(group, "history"), false, false)),
                pass.inputs());
        assertEquals(
                Map.of("Stability", Collections.nCopies(4, new UniformValue.FloatUniform(0))),
                pass.uniforms());
    }

    private static void assertGridSizes(PostChainConfig graph) {
        graph.internalTargets()
                .forEach(
                        (id, spec) -> {
                            boolean composite = id.getPath().endsWith("composite");
                            assertEquals(
                                    composite ? WIDTH : HALF_WIDTH,
                                    spec.width().orElseThrow(),
                                    id.toString());
                            assertEquals(
                                    composite ? HEIGHT : HALF_HEIGHT,
                                    spec.height().orElseThrow(),
                                    id.toString());
                        });
    }

    private static void assertGraphOrder(PostChainConfig graph, Set<Identifier> externalInputs) {
        var available = new HashSet<>(externalInputs);
        available.add(PostChain.MAIN_TARGET_ID);
        for (var pass : graph.passes()) {
            for (var in : pass.inputs())
                if (in instanceof PostChainConfig.TargetInput target)
                    assertTrue(
                            available.contains(target.targetId()),
                            "Read before write or missing binding: "
                                    + target
                                    + " in "
                                    + pass.fragmentShaderId());
            available.add(pass.outputTarget());
        }
    }
}
