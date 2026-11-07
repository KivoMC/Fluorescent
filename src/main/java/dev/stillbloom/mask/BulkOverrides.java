package dev.stillbloom.mask;

import dev.stillbloom.config.BloomOverride;

import java.util.*;
import java.util.function.Predicate;

// I save the whole batch before exposing it to rendering.
public final class BulkOverrides {
    public enum Part {
        COLOR,
        SHAPE,
        STYLE
    }

    public record Result(int changed, Undo undo) {}

    public static Result apply(
            TextureMasks masks,
            Collection<String> blocks,
            BloomOverride source,
            Part part,
            Predicate<TextureMasks> save) {
        Objects.requireNonNull(masks);
        Objects.requireNonNull(part);
        Objects.requireNonNull(save);
        var style = BloomOverride.copyOf(source);
        style.sanitize();
        var before = new LinkedHashMap<String, BloomOverride>();
        var staged = stage(masks);
        var targets = new LinkedHashSet<String>();
        for (var id : blocks) targets.addAll(BlockLinks.members(masks, id));
        for (String id : targets) {
            if (id == null || id.isBlank())
                throw new IllegalArgumentException("Choose valid block IDs.");
            var current = masks.profile(id);
            var previous = current == null ? null : current.override;
            var next =
                    part == Part.STYLE
                            ? BloomOverride.copyOf(style)
                            : BloomOverride.copyOf(previous);
            if (part == Part.COLOR) next.color = style.color;
            if (part == Part.SHAPE) next.shape = style.shape;
            if (BloomOverride.sameValues(previous, next)) continue;
            before.put(id, BloomOverride.copyOf(previous));
            put(staged, id, current, next);
        }
        if (before.isEmpty()) return new Result(0, null);
        publish(masks, staged, save);
        return new Result(before.size(), new Undo(masks, before));
    }

    private static void put(
            TextureMasks staged, String id, TextureMasks.Profile original, BloomOverride override) {
        var next = new TextureMasks.Profile();
        // I copy only appearance here, keeping each block's masks and source strength.
        if (original != null) {
            next.strength = original.strength;
            next.textures = original.textures;
        }
        next.override =
                override == null || override.empty() ? null : BloomOverride.copyOf(override);
        var group = staged.group(id);
        if (group != null) {
            next.textures = group.profile.textures;
            group.profile = next;
        } else if (next.override == null && next.textures.isEmpty() && next.strength == 1)
            staged.blocks.remove(id);
        else staged.blocks.put(id, next);
        staged.stylesChanged();
    }

    private static TextureMasks stage(TextureMasks masks) {
        var staged = new TextureMasks();
        staged.blocks = new TreeMap<>(masks.blocks);
        staged.independentBlocks = new TreeSet<>(masks.independentBlocks);
        masks.groups.forEach(
                (id, g) -> {
                    var copy = new BlockLinks.Group();
                    copy.automatic = g.automatic;
                    copy.members.addAll(g.members);
                    copy.profile = g.profile;
                    copy.textureLinks.putAll(g.textureLinks);
                    staged.groups.put(id, copy);
                });
        return staged;
    }

    private static void publish(
            TextureMasks masks, TextureMasks staged, Predicate<TextureMasks> save) {
        if (!save.test(staged))
            throw new IllegalStateException("Could not save batch. No blocks changed; try again.");
        BlockLinks.installStyles(masks, staged);
    }

    public static final class Undo {
        private final TextureMasks owner;
        private final long revision;
        private final Map<String, BloomOverride> before, after = new LinkedHashMap<>();
        private final Map<String, TextureMasks.Profile> profiles = new LinkedHashMap<>();
        private boolean used;

        private Undo(TextureMasks owner, Map<String, BloomOverride> before) {
            this.owner = owner;
            revision = owner.revision();
            this.before = before;
            for (String id : before.keySet()) {
                var p = owner.profile(id);
                profiles.put(id, p);
                after.put(id, BloomOverride.copyOf(p == null ? null : p.override));
            }
        }

        public boolean available(TextureMasks masks) {
            if (used || masks != owner || masks.revision() != revision) return false;
            for (String id : before.keySet()) {
                var p = masks.profile(id);
                if (p != profiles.get(id)
                        || !BloomOverride.sameValues(p == null ? null : p.override, after.get(id)))
                    return false;
            }
            return true;
        }

        public int apply(TextureMasks masks, Predicate<TextureMasks> save) {
            if (!available(masks))
                throw new IllegalStateException(
                        "Blocks changed since this batch. Undo is no longer available.");
            var staged = stage(masks);
            for (var e : before.entrySet())
                put(staged, e.getKey(), masks.profile(e.getKey()), e.getValue());
            publish(masks, staged, save);
            used = true;
            return before.size();
        }
    }
}
