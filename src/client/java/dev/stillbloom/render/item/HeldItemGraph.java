package dev.stillbloom.render.item;

import dev.stillbloom.config.BloomConfig;
import dev.stillbloom.render.BloomGraph;

import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.renderer.PostChainConfig;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class HeldItemGraph {
    static final Identifier EMISSION = BloomGraph.id("held_emission");
    static final Identifier COVERAGE = BloomGraph.id("held_coverage");

    private HeldItemGraph() {}

    public static PostChainConfig create(int width, int height, BloomConfig config) {
        BloomConfig settings = config.copy();
        // I avoid stationary history for moving items and take detail from their actual surfaces.
        settings.stabilityMillis = 0;
        settings.preserveDetail = 0;
        PostChainConfig base =
                BloomGraph.createConfig(width, height, settings, false, false, false);
        Map<Identifier, PostChainConfig.InternalTarget> targets = new LinkedHashMap<>();
        base.internalTargets().forEach((id, spec) -> targets.put(rename(id), spec));
        List<PostChainConfig.Pass> passes = new ArrayList<>();
        for (PostChainConfig.Pass pass : base.passes()) {
            boolean first = pass == base.passes().getFirst();
            boolean composite = pass.outputTarget().equals(BloomGraph.id("composite"));
            Identifier shader = pass.fragmentShaderId();
            List<PostChainConfig.Input> inputs = new ArrayList<>();
            var uniforms = pass.uniforms();
            if (first) {
                shader =
                        BloomGraph.id(
                                "post/held_downsample"
                                        + (settings.usesDistancePixels() ? "_distance" : ""));
                inputs.add(new PostChainConfig.TargetInput("In", EMISSION, false, false));
                uniforms = Map.of();
            } else {
                for (PostChainConfig.Input input : pass.inputs()) {
                    if (input instanceof PostChainConfig.TargetInput target) {
                        inputs.add(
                                new PostChainConfig.TargetInput(
                                        target.samplerName(),
                                        rename(target.targetId()),
                                        target.useDepthBuffer(),
                                        target.bilinear()));
                    } else {
                        throw new IllegalStateException(
                                "Held bloom inherited a world texture input");
                    }
                }
                if (composite) {
                    shader =
                            BloomGraph.id(
                                    "post/composite_held"
                                            + (settings.smoothBlend > 0 ? "_smooth" : "")
                                            + (settings.usesDistancePixels() ? "_distance" : ""));
                    inputs.add(new PostChainConfig.TargetInput("Coverage", COVERAGE, false, false));
                }
            }
            passes.add(
                    new PostChainConfig.Pass(
                            pass.vertexShaderId(),
                            shader,
                            inputs,
                            rename(pass.outputTarget()),
                            uniforms));
        }
        return new PostChainConfig(targets, passes);
    }

    private static Identifier rename(Identifier id) {
        return id.equals(PostChain.MAIN_TARGET_ID) ? id : BloomGraph.id("held_" + id.getPath());
    }
}
