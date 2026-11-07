package dev.stillbloom.render;

import dev.stillbloom.config.BloomConfig;
import dev.stillbloom.render.source.GeometryTexture;
import dev.stillbloom.render.source.PaintedAtlas;
import dev.stillbloom.render.source.SourceMask;
import dev.stillbloom.render.source.TextureGeometry;

import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.renderer.PostChainConfig;
import net.minecraft.client.renderer.UniformValue;
import net.minecraft.resources.Identifier;

import java.util.*;

// I keep graph descriptions separate from live GPU resources.
public final class BloomGraph {
    private BloomGraph() {}

    public static Identifier id(String name) {
        return Identifier.fromNamespaceAndPath("stillbloom", name);
    }

    public static int reducedSize(int size, int divisor) {
        return Math.max(1, (size + divisor - 1) / divisor);
    }

    public static PostChainConfig createConfig(int width, int height, BloomConfig config) {
        return createConfig(width, height, config, false);
    }

    // I extract every style from the original scene so earlier glow doesn't feed into later glow.
    public static PostChainConfig createGroupedConfig(
            int width,
            int height,
            BloomConfig config,
            boolean custom,
            List<SourceMask.RenderGroup> groups) {
        return createGroupedConfig(width, height, config, custom, false, groups);
    }

    public static PostChainConfig createGroupedConfig(
            int width,
            int height,
            BloomConfig config,
            boolean custom,
            boolean colored,
            List<SourceMask.RenderGroup> groups) {
        return createGroupedConfig(width, height, config, custom, colored, groups, false);
    }

    public static PostChainConfig createGroupedConfig(
            int width,
            int height,
            BloomConfig config,
            boolean custom,
            boolean colored,
            List<SourceMask.RenderGroup> groups,
            boolean directComposite) {
        return createGroupedConfig(
                width, height, config, custom, colored, groups, directComposite, false);
    }

    public static PostChainConfig createGroupedConfig(
            int width,
            int height,
            BloomConfig config,
            boolean custom,
            boolean colored,
            List<SourceMask.RenderGroup> groups,
            boolean directComposite,
            boolean details) {
        if (groups.isEmpty()) throw new IllegalArgumentException("No active bloom styles");
        Map<Identifier, PostChainConfig.InternalTarget> targets = new LinkedHashMap<>();
        List<PostChainConfig.Pass> passes = new ArrayList<>(), composites = new ArrayList<>();
        Identifier scene = PostChain.MAIN_TARGET_ID;
        for (var group : groups) {
            var single =
                    createConfig(
                            width,
                            height,
                            group.style().apply(config),
                            custom && group.custom(),
                            colored && group.colored(),
                            details);
            Identifier grid = id("down_" + config.renderPixelSize());
            java.util.function.Function<Identifier, Identifier> rename =
                    id ->
                            id.equals(PostChain.MAIN_TARGET_ID)
                                    ? id
                                    : BloomGraph.id("group_" + group.index() + "_" + id.getPath());
            boolean separateStability = config.stabilityMillis > 0 && config.renderPixelSize() <= 3;
            Identifier extracted = id("extracted_" + config.renderPixelSize());
            single.internalTargets()
                    .forEach(
                            (key, value) -> {
                                if (separateStability && key.equals(grid))
                                    targets.put(rename.apply(extracted), value);
                                else if (config.stabilityMillis <= 0 || !key.equals(grid))
                                    targets.put(rename.apply(key), value);
                            });
            for (var p : single.passes()) {
                if (p.outputTarget().equals(PostChain.MAIN_TARGET_ID)) continue;
                boolean composite = p.outputTarget().equals(id("composite"));
                boolean extraction = p == single.passes().getFirst();
                List<PostChainConfig.Input> inputs = new ArrayList<>();
                for (var in : p.inputs()) {
                    if (in instanceof PostChainConfig.TargetInput target) {
                        Identifier source =
                                composite && target.samplerName().equals("Scene")
                                        ? scene
                                        : rename.apply(target.targetId());
                        inputs.add(
                                new PostChainConfig.TargetInput(
                                        target.samplerName(),
                                        source,
                                        target.useDepthBuffer(),
                                        target.bilinear()));
                    } else if (extraction
                            && in instanceof PostChainConfig.TextureInput texture
                            && texture.samplerName().equals("Sources")) {
                        inputs.add(
                                new PostChainConfig.TextureInput(
                                        "Sources",
                                        SourceMask.groupTexture(group.index()),
                                        SourceMask.TEXTURE_SIZE,
                                        SourceMask.TEXTURE_SIZE,
                                        false));
                    } else inputs.add(in);
                }
                boolean stable = config.stabilityMillis > 0 && p.outputTarget().equals(grid);
                var uniforms = p.uniforms();
                var shader = p.fragmentShaderId();
                if (stable && separateStability) {
                    // I keep extraction and masks at 2/3px, where the grid is already final.
                    // I leave the combined reduction/history path in place at 4px.
                    passes.add(
                            new PostChainConfig.Pass(
                                    p.vertexShaderId(),
                                    shader,
                                    inputs,
                                    rename.apply(extracted),
                                    uniforms));
                    passes.add(
                            pass(
                                    "stabilize" + (config.usesDistancePixels() ? "_distance" : ""),
                                    rename.apply(grid),
                                    List.of(
                                            input("In", rename.apply(extracted)),
                                            input("History", rename.apply(id("history")))),
                                    Map.of(
                                            "Stability",
                                            Collections.nCopies(
                                                    4, new UniformValue.FloatUniform(0)))));
                    continue;
                }
                if (stable) {
                    inputs.add(input("History", rename.apply(id("history"))));
                    uniforms =
                            Map.of(
                                    "Stability",
                                    Collections.nCopies(4, new UniformValue.FloatUniform(0)));
                    shader =
                            id(
                                    "post/downsample_stable"
                                            + (config.usesDistancePixels() ? "_distance" : ""));
                }
                var remapped =
                        new PostChainConfig.Pass(
                                p.vertexShaderId(),
                                shader,
                                inputs,
                                rename.apply(p.outputTarget()),
                                uniforms);
                if (composite) {
                    composites.add(remapped);
                    scene = remapped.outputTarget();
                } else passes.add(remapped);
            }
        }
        if (directComposite && composites.size() > 1) {
            // I read the previous temporary image for the final composite, so I never read and
            // write MAIN color together.
            var last = composites.removeLast();
            targets.remove(last.outputTarget());
            composites.add(
                    new PostChainConfig.Pass(
                            last.vertexShaderId(),
                            last.fragmentShaderId(),
                            last.inputs(),
                            PostChain.MAIN_TARGET_ID,
                            last.uniforms()));
            passes.addAll(composites);
        } else {
            passes.addAll(composites);
            passes.add(
                    pass("copy", PostChain.MAIN_TARGET_ID, List.of(input("In", scene)), Map.of()));
        }
        return new PostChainConfig(targets, passes);
    }

    public static PostChainConfig createConfig(
            int width, int height, BloomConfig config, boolean custom) {
        return createConfig(width, height, config, custom, false);
    }

    public static PostChainConfig createConfig(
            int width, int height, BloomConfig config, boolean custom, boolean colored) {
        return createConfig(width, height, config, custom, colored, false);
    }

    public static PostChainConfig createConfig(
            int width,
            int height,
            BloomConfig config,
            boolean custom,
            boolean colored,
            boolean details) {
        if (config.pixelSize != BloomConfig.validPixelSize(config.pixelSize)
                || config.farPixelSize != BloomConfig.validPixelSize(config.farPixelSize))
            throw new IllegalArgumentException(
                    "Validate pixel sizes before constructing bloom targets");
        if (config.usesDistancePixels())
            return createDistanceConfig(width, height, config, custom, colored, details);
        Map<Identifier, PostChainConfig.InternalTarget> targets = new LinkedHashMap<>();
        List<PostChainConfig.Pass> passes = new ArrayList<>();
        Identifier previous = PostChain.MAIN_TARGET_ID;
        int extractionSize = config.pixelSize == 3 ? 3 : 2;
        for (int factor = extractionSize; factor <= config.pixelSize; factor *= 2) {
            Identifier target = id("down_" + factor);
            targets.put(target, target(reducedSize(width, factor), reducedSize(height, factor)));
            Map<String, List<UniformValue>> parameters = new HashMap<>();
            List<PostChainConfig.Input> inputs = new ArrayList<>();
            boolean selfLit = factor == extractionSize && custom && config.selfLitPaint;
            // I keep input zero screen-sized because extraction bounds use its dimensions.
            if (selfLit)
                inputs.add(
                        new PostChainConfig.TargetInput(
                                "Depth", PostChain.MAIN_TARGET_ID, true, false));
            else inputs.add(input("In", previous));
            if (factor == extractionSize) {
                parameters.putAll(
                        uniforms(
                                config.threshold,
                                config.softKnee,
                                config.bloomRange,
                                config.bloomRange * (1 - config.fadeFraction)));
                parameters.put(
                        "SourceMask",
                        Collections.nCopies(
                                SourceMask.BUFFER_BYTES / 4, new UniformValue.FloatUniform(0)));
                if (selfLit)
                    inputs.add(
                            new PostChainConfig.TextureInput(
                                    "Atlas", PaintedAtlas.ID, 1, 1, false));
                else
                    inputs.add(
                            new PostChainConfig.TargetInput(
                                    "Depth", PostChain.MAIN_TARGET_ID, true, false));
                inputs.add(
                        new PostChainConfig.TextureInput(
                                "Sources",
                                SourceMask.TEXTURE_ID,
                                SourceMask.TEXTURE_SIZE,
                                SourceMask.TEXTURE_SIZE,
                                false));

                if (custom) {
                    inputs.add(
                            new PostChainConfig.TextureInput(
                                    "Custom",
                                    id("custom_volume"),
                                    TextureGeometry.SIZE,
                                    TextureGeometry.SIZE,
                                    false));
                    inputs.add(
                            new PostChainConfig.TextureInput(
                                    "Geometry",
                                    id("custom_geometry"),
                                    GeometryTexture.SIZE,
                                    GeometryTexture.SIZE,
                                    false));
                }
                if (colored)
                    inputs.add(
                            new PostChainConfig.TextureInput(
                                    "Colors", SourceMask.COLORS_ID, 1024, 2048, false));
            }
            String extract =
                    "extract"
                            + (custom
                                    ? (config.uniformEmission ? "_uniform" : "")
                                            + (selfLit ? "_paint" : "")
                                    : "_auto")
                            + (colored ? "_color" : "");
            passes.add(
                    pass(
                            factor == extractionSize ? extract : "downsample",
                            target,
                            inputs,
                            parameters));
            previous = target;
        }
        // I bind a same-sized input when the wide halo is off, avoiding an unused blur and extra
        // target.
        boolean wide = config.bloomShape != BloomConfig.Shape.STARBURST && config.wideHalo != 0;
        for (String name :
                (config.bloomShape == BloomConfig.Shape.STARBURST
                        ? List.of("near")
                        : wide
                                ? List.of("near_x", "near", "wide_x", "wide")
                                : List.of("near_x", "near"))) {
            targets.put(
                    id(name),
                    target(
                            reducedSize(width, config.pixelSize),
                            reducedSize(height, config.pixelSize)));
        }
        float extent = Math.max(1, config.glowWidth / (2 * config.pixelSize));
        if (config.bloomShape == BloomConfig.Shape.STARBURST) {
            passes.add(
                    pass(
                            "starburst",
                            id("near"),
                            List.of(linearInput("In", previous)),
                            uniforms(
                                    extent,
                                    config.nearRadius / 6,
                                    config.wideRadius / 6,
                                    config.wideHalo)));
        } else {
            boolean diamond = config.bloomShape == BloomConfig.Shape.DIAMOND;
            float diagonal = diamond ? 0.70710678f : 0;
            float mode = config.bloomShape == BloomConfig.Shape.SOFT ? 0 : 1;
            // I interpolate only diagonal samples; straight kernels already land on pixel centers.
            passes.add(
                    pass(
                            "blur",
                            id("near_x"),
                            List.of(diamond ? linearInput("In", previous) : input("In", previous)),
                            uniforms(
                                    diamond ? diagonal : 1,
                                    diagonal,
                                    config.nearRadius / 6,
                                    extent,
                                    mode,
                                    0,
                                    0,
                                    0)));
            passes.add(
                    pass(
                            "blur",
                            id("near"),
                            List.of(
                                    diamond
                                            ? linearInput("In", id("near_x"))
                                            : input("In", id("near_x"))),
                            uniforms(
                                    diagonal,
                                    diamond ? -diagonal : 1,
                                    config.nearRadius / 6,
                                    extent,
                                    mode,
                                    0,
                                    0,
                                    0)));
            if (wide) {
                passes.add(
                        pass(
                                "blur",
                                id("wide_x"),
                                List.of(
                                        diamond
                                                ? linearInput("In", id("near"))
                                                : input("In", id("near"))),
                                uniforms(
                                        diamond ? diagonal : 1,
                                        diagonal,
                                        config.wideRadius / 6,
                                        extent,
                                        mode,
                                        0,
                                        0,
                                        0)));
                passes.add(
                        pass(
                                "blur",
                                id("wide"),
                                List.of(
                                        diamond
                                                ? linearInput("In", id("wide_x"))
                                                : input("In", id("wide_x"))),
                                uniforms(
                                        diagonal,
                                        diamond ? -diagonal : 1,
                                        config.wideRadius / 6,
                                        extent,
                                        mode,
                                        0,
                                        0,
                                        0)));
            }
        }
        boolean flare = config.flareStyle != BloomConfig.Flare.OFF && config.flareStrength > 0;
        if (flare) {
            targets.put(
                    id("flare"),
                    target(
                            reducedSize(width, config.pixelSize),
                            reducedSize(height, config.pixelSize)));
            passes.add(
                    pass(
                            "flare",
                            id("flare"),
                            List.of(input("In", previous)),
                            uniforms(
                                    config.flareLength / config.pixelSize,
                                    config.flareStyle == BloomConfig.Flare.CROSS ? 1 : 0,
                                    0,
                                    0)));
        }
        targets.put(id("composite"), target(width, height));
        boolean smooth = config.smoothBlend > 0;
        List<PostChainConfig.Input> compositeInputs =
                new ArrayList<>(
                        List.of(
                                input("Scene", PostChain.MAIN_TARGET_ID),
                                glowInput("Near", id("near"), smooth),
                                glowInput("Wide", id(wide ? "wide" : "near"), smooth),
                                glowInput("Core", previous, smooth),
                                glowInput("Flare", flare ? id("flare") : previous, smooth)));
        Map<String, List<UniformValue>> compositeUniforms =
                new HashMap<>(
                        uniforms(
                                config.pixelSize,
                                config.intensity,
                                config.wideHalo,
                                config.coreStrength,
                                config.saturation,
                                config.warmth,
                                config.additiveBlend,
                                config.preserveDetail,
                                flare ? config.flareStrength : 0,
                                0,
                                config.smoothBlend,
                                0));
        if (config.preserveDetail > 0 || details) {
            compositeInputs.add(
                    new PostChainConfig.TargetInput(
                            "Depth", PostChain.MAIN_TARGET_ID, true, false));
            compositeInputs.add(
                    new PostChainConfig.TextureInput(
                            "Sources",
                            SourceMask.TEXTURE_ID,
                            SourceMask.TEXTURE_SIZE,
                            SourceMask.TEXTURE_SIZE,
                            false));
            compositeUniforms.put(
                    "SourceMask",
                    Collections.nCopies(
                            SourceMask.BUFFER_BYTES / 4, new UniformValue.FloatUniform(0)));
        }
        if (details)
            compositeInputs.add(
                    new PostChainConfig.TextureInput(
                            "Details", SourceMask.DETAILS_ID, 1024, 2048, false));
        String compositeShader =
                (details
                                ? "composite_detail"
                                : config.preserveDetail > 0 ? "composite_preserve" : "composite")
                        + (smooth ? "_smooth" : "");
        passes.add(pass(compositeShader, id("composite"), compositeInputs, compositeUniforms));
        passes.add(
                pass(
                        "copy",
                        PostChain.MAIN_TARGET_ID,
                        List.of(input("In", id("composite"))),
                        Map.of()));
        return new PostChainConfig(targets, passes);
    }

    // I carry distance with the glow source, not the background behind it.
    private static PostChainConfig createDistanceConfig(
            int width,
            int height,
            BloomConfig config,
            boolean custom,
            boolean colored,
            boolean details) {
        var fine = config.copy();
        fine.distancePixels = false;
        fine.pixelSize = config.finePixelSize();
        var base = createConfig(width, height, fine, custom, colored, details);
        List<PostChainConfig.Pass> passes = new ArrayList<>();
        for (var p : base.passes()) {
            if (p.fragmentShaderId().getPath().equals("post/copy")) {
                passes.add(p);
                continue;
            }
            Map<String, List<UniformValue>> parameters = new HashMap<>(p.uniforms());
            List<PostChainConfig.Input> inputs = p.inputs();
            if (p == base.passes().getFirst()) {
                var values = new ArrayList<>(parameters.get("BloomConfig"));
                for (float value :
                        new float[] {
                            config.pixelDistanceStart,
                            config.pixelDistanceStart + config.pixelDistanceSpan,
                            0,
                            0
                        }) values.add(new UniformValue.FloatUniform(value));
                parameters.put("BloomConfig", values);
            }
            if (p.outputTarget().equals(id("composite"))) {
                var values = new ArrayList<>(parameters.get("BloomConfig"));
                values.set(0, new UniformValue.FloatUniform(config.pixelSize));
                values.set(9, new UniformValue.FloatUniform(fine.pixelSize));
                parameters.put("BloomConfig", values);
                inputs =
                        p.inputs().stream()
                                .map(
                                        in ->
                                                in instanceof PostChainConfig.TargetInput target
                                                                && !target.samplerName()
                                                                        .equals("Scene")
                                                                && !target.samplerName()
                                                                        .equals("Depth")
                                                        ? (PostChainConfig.Input)
                                                                new PostChainConfig.TargetInput(
                                                                        target.samplerName(),
                                                                        target.targetId(),
                                                                        target.useDepthBuffer(),
                                                                        true)
                                                        : in)
                                .toList();
            }
            passes.add(
                    new PostChainConfig.Pass(
                            p.vertexShaderId(),
                            id(p.fragmentShaderId().getPath() + "_distance"),
                            inputs,
                            p.outputTarget(),
                            parameters));
        }
        return new PostChainConfig(base.internalTargets(), passes);
    }

    private static PostChainConfig.InternalTarget target(int w, int h) {
        return new PostChainConfig.InternalTarget(Optional.of(w), Optional.of(h), false, 0);
    }

    private static PostChainConfig.TargetInput input(String name, Identifier target) {
        return new PostChainConfig.TargetInput(name, target, false, false);
    }

    private static PostChainConfig.TargetInput linearInput(String name, Identifier target) {
        return new PostChainConfig.TargetInput(name, target, false, true);
    }

    private static PostChainConfig.TargetInput glowInput(
            String name, Identifier target, boolean smooth) {
        return smooth ? linearInput(name, target) : input(name, target);
    }

    private static Map<String, List<UniformValue>> uniforms(float... values) {
        List<UniformValue> uniforms = new ArrayList<>();
        for (float value : values) uniforms.add(new UniformValue.FloatUniform(value));
        return Map.of("BloomConfig", uniforms);
    }

    private static PostChainConfig.Pass pass(
            String shader,
            Identifier output,
            List<PostChainConfig.Input> inputs,
            Map<String, List<UniformValue>> uniforms) {
        return new PostChainConfig.Pass(
                Identifier.withDefaultNamespace("core/screenquad"),
                id("post/" + shader),
                inputs,
                output,
                uniforms);
    }

    static final Identifier WEATHER_SCENE = BloomGraph.id("rain_scene");

    // I extract from the weather-free scene and keep rain in the final image.
    static PostChainConfig forWeather(PostChainConfig original) {
        var passes = new ArrayList<PostChainConfig.Pass>();
        for (var pass : original.passes()) {
            String shader = pass.fragmentShaderId().getPath();
            if (!pass.fragmentShaderId().getNamespace().equals("stillbloom")
                    || !shader.startsWith("post/extract")) {
                passes.add(pass);
                continue;
            }
            String wetShader =
                    shader.endsWith("_distance")
                            ? shader.substring(0, shader.length() - 9) + "_rain_distance"
                            : shader + "_rain";
            var uniforms = new HashMap<>(pass.uniforms());
            uniforms.put(
                    "RainFog",
                    Collections.nCopies(
                            RainFogState.BUFFER_BYTES / 4, new UniformValue.FloatUniform(0)));
            List<PostChainConfig.Input> inputs =
                    pass.inputs().stream()
                            .map(
                                    input ->
                                            input instanceof PostChainConfig.TargetInput target
                                                            && target.targetId()
                                                                    .equals(
                                                                            PostChain
                                                                                    .MAIN_TARGET_ID)
                                                    ? (PostChainConfig.Input)
                                                            new PostChainConfig.TargetInput(
                                                                    target.samplerName(),
                                                                    WEATHER_SCENE,
                                                                    target.useDepthBuffer(),
                                                                    target.bilinear())
                                                    : input)
                            .toList();
            passes.add(
                    new PostChainConfig.Pass(
                            pass.vertexShaderId(),
                            BloomGraph.id(wetShader),
                            inputs,
                            pass.outputTarget(),
                            uniforms));
        }
        return new PostChainConfig(original.internalTargets(), passes);
    }
}
