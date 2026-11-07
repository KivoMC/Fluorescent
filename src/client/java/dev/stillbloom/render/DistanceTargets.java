package dev.stillbloom.render;

import com.mojang.blaze3d.framegraph.FrameGraphBuilder;
import com.mojang.blaze3d.pipeline.*;
import com.mojang.blaze3d.resource.ResourceHandle;
import com.mojang.renderpearl.api.GpuFormat;

import net.minecraft.client.renderer.PostChainConfig;
import net.minecraft.resources.Identifier;

import java.util.*;

// I store source distances as floats and let the frame allocator own their textures.
public final class DistanceTargets implements AutoCloseable {
    private final Map<Identifier, BloomTargetDescriptor> targets = new LinkedHashMap<>();

    static boolean carriesDistance(Identifier id) {
        return !id.getPath().endsWith("composite");
    }

    public PostChainConfig initialize(PostChainConfig graph, Set<Identifier> external) {
        Map<Identifier, PostChainConfig.InternalTarget> internal = new LinkedHashMap<>();
        for (var e : graph.internalTargets().entrySet()) {
            if (!carriesDistance(e.getKey())) {
                internal.put(e.getKey(), e.getValue());
                continue;
            }
            var spec = e.getValue();
            var target =
                    new BloomTargetDescriptor(
                            spec.width().orElseThrow(),
                            spec.height().orElseThrow(),
                            GpuFormat.RGBA16_FLOAT);
            targets.put(e.getKey(), target);
            external.add(e.getKey());
        }
        return new PostChainConfig(internal, graph.passes());
    }

    public boolean active() {
        return !targets.isEmpty();
    }

    public void importInto(
            FrameGraphBuilder frame, Map<Identifier, ResourceHandle<RenderTarget>> handles) {
        targets.forEach(
                (id, target) -> handles.put(id, frame.createInternal(id.toString(), target)));
    }

    @Override
    public void close() {
        targets.clear();
    }
}
