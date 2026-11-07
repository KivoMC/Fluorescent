package dev.stillbloom.mask;

import java.util.*;

// I stage editor changes while rendering continues to read the ordinary masks.
public final class LinkedMaskEdits {
    private LinkedMaskEdits() {}

    static List<String> targets(TextureMasks masks, String source, Collection<String> matches) {
        if (masks.group(source) != null) return BlockLinks.members(masks, source);
        var ids = new TreeSet<String>();
        ids.add(source);
        if (masks.linksMasks(source)) for (var id : matches) if (masks.linksMasks(id)) ids.add(id);
        return List.copyOf(ids);
    }

    public static Snapshot capture(TextureMasks masks, String texture, Collection<String> targets) {
        var before = new LinkedHashMap<String, TextureMasks.Mask>();
        var groups = new HashMap<String, BlockLinks.Group>();
        for (var id : targets) {
            var mask = masks.mask(id, texture);
            before.put(id, mask == null ? null : mask.copy());
            groups.put(id, masks.group(id));
        }
        return new Snapshot(masks, texture, before, groups);
    }

    public static boolean sync(
            TextureMasks masks, String source, String texture, Collection<String> targets) {
        var mask = masks.mask(source, texture);
        boolean changed = false;
        for (var id : targets)
            if (!id.equals(source)) {
                var other = masks.mask(id, texture);
                if (other == mask
                        || other != null
                                && mask != null
                                && other.width == mask.width
                                && other.height == mask.height
                                && Arrays.equals(other.words, mask.words)) continue;
                write(masks, id, texture, mask);
                changed = true;
            }
        if (changed) masks.changed();
        return changed;
    }

    private static void write(
            TextureMasks masks, String id, String texture, TextureMasks.Mask mask) {
        if (mask != null) {
            masks.ensureProfile(id).textures.put(masks.textureKey(id, texture), mask.copy());
            return;
        }
        masks.reset(id, texture);
    }

    public record Snapshot(
            TextureMasks owner,
            String texture,
            Map<String, TextureMasks.Mask> before,
            Map<String, BlockLinks.Group> groups) {
        public boolean restore(TextureMasks masks) {
            if (masks != owner
                    || groups.entrySet().stream()
                            .anyMatch(e -> masks.group(e.getKey()) != e.getValue())) return false;
            before.forEach((id, mask) -> write(masks, id, texture, mask));
            masks.changed();
            return true;
        }
    }
}
