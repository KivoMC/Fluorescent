package dev.stillbloom.mask;

import com.google.gson.GsonBuilder;

import dev.stillbloom.config.BloomConfig;
import dev.stillbloom.config.BloomStyle;
import dev.stillbloom.persistence.JsonFiles;

import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;

// I inherit the matching block's masks and settings when an item has no entry.
public final class ItemMasks {
    private static final Pattern ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");
    public Map<String, TextureMasks.Profile> items = new TreeMap<>();
    private transient long revision, geometryRevision;

    public long revision() {
        return revision;
    }

    public long geometryRevision() {
        return geometryRevision;
    }

    public void changed() {
        geometryRevision++;
        stylesChanged();
    }

    public void stylesChanged() {
        revision++;
    }

    public TextureMasks.Profile profile(String item) {
        return items.get(item);
    }

    public TextureMasks.Mask mask(String item, String texture) {
        var p = profile(item);
        return p == null ? null : p.textures.get(texture);
    }

    public void put(String item, String texture, TextureMasks.Mask mask) {
        if (mask == null || !mask.valid())
            throw new IllegalArgumentException("Invalid item texture mask.");
        items.computeIfAbsent(item, k -> new TextureMasks.Profile()).textures.put(texture, mask);
        changed();
    }

    public void reset(String item, String texture) {
        var p = profile(item);
        if (p != null && p.textures.remove(texture) != null) {
            prune(item);
            changed();
        }
    }

    public void removeOverride(String item) {
        var p = profile(item);
        if (p != null && p.override != null) {
            p.override = null;
            prune(item);
            stylesChanged();
        }
    }

    private void prune(String item) {
        var p = profile(item);
        if (p != null && p.textures.isEmpty() && p.override == null && p.strength == 1)
            items.remove(item);
    }

    public BloomStyle style(String item, BloomConfig global) {
        var p = profile(item);
        return p == null || p.override == null
                ? BloomStyle.global(global)
                : p.override.resolve(global);
    }

    // I keep paint on its original pixel grid so changing pack resolution doesn't stretch it.
    public static TextureMasks.Mask compatibleMask(TextureMasks.Mask mask, int width, int height) {
        return mask != null && mask.valid() && mask.width == width && mask.height == height
                ? mask
                : null;
    }

    public boolean save(Path path) {
        return JsonFiles.save(path, this);
    }

    public static ItemMasks load(Path path) {
        return load(path, null);
    }

    public static ItemMasks load(Path path, ItemMasks previous) {
        if (Files.notExists(path)) {
            JsonFiles.missing(path);
            return new ItemMasks();
        }
        try {
            if (Files.size(path) > 8_388_608)
                throw new IllegalArgumentException("Item masks file is too large.");
            ItemMasks masks;
            try (var reader = Files.newBufferedReader(path)) {
                masks = new GsonBuilder().create().fromJson(reader, ItemMasks.class);
            }
            if (masks == null || masks.items == null)
                throw new IllegalArgumentException("Missing item profiles.");
            masks.items
                    .entrySet()
                    .removeIf(
                            e ->
                                    !validId(e.getKey())
                                            || e.getValue() == null
                                            || e.getValue().textures == null);
            for (var profile : masks.items.values()) {
                profile.strength =
                        Float.isFinite(profile.strength) ? Math.clamp(profile.strength, 0, 8) : 1;
                if (profile.override != null) profile.override.sanitize();
                profile.textures
                        .entrySet()
                        .removeIf(
                                e ->
                                        !validId(e.getKey())
                                                || e.getValue() == null
                                                || !e.getValue().valid());
            }
            JsonFiles.loaded(path, masks);
            return masks;
        } catch (Exception e) {
            var fallback = previous == null ? new ItemMasks() : previous;
            JsonFiles.unreadable(path, fallback, e);
            return fallback;
        }
    }

    private static boolean validId(String id) {
        return id != null && id.length() <= 256 && ID.matcher(id).matches();
    }
}
