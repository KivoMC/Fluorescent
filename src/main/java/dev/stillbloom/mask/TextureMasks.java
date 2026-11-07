package dev.stillbloom.mask;

import com.google.gson.GsonBuilder;

import dev.stillbloom.config.BloomConfig;
import dev.stillbloom.config.BloomOverride;
import dev.stillbloom.config.BloomStyle;
import dev.stillbloom.persistence.JsonFiles;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

// I require painted pixels for placed-block bloom and key each mask by block and texture.
public final class TextureMasks {
    public static final class Mask {
        public int width, height;
        public int[] words;

        public Mask(int width, int height) {
            this.width = width;
            this.height = height;
            words = new int[(width * height + 31) / 32];
        }

        public boolean get(int x, int y) {
            int i = x + y * width;
            return (words[i >>> 5] & (1 << (i & 31))) != 0;
        }

        public void set(int x, int y, boolean value) {
            if (x < 0 || y < 0 || x >= width || y >= height) return;
            int i = x + y * width;
            if (value) words[i >>> 5] |= 1 << (i & 31);
            else words[i >>> 5] &= ~(1 << (i & 31));
        }

        public Mask copy() {
            Mask m = new Mask(width, height);
            m.words = words.clone();
            return m;
        }

        public boolean valid() {
            return width > 0
                    && height > 0
                    && width <= 256
                    && height <= 256
                    && words != null
                    && words.length == (width * height + 31) / 32;
        }

        public boolean hasPaint() {
            if (!valid()) return false;
            int pixels = width * height, full = pixels >>> 5;
            for (int i = 0; i < full; i++) if (words[i] != 0) return true;
            int remainder = pixels & 31;
            return remainder != 0 && (words[full] & (-1 >>> (32 - remainder))) != 0;
        }
    }

    public static final class Profile {
        public float strength = 1;
        public Map<String, Mask> textures = new TreeMap<>();
        public BloomOverride override;

        // I evaluate this after settings change, not while sampling rendered pixels.
        public boolean hasEmission() {
            return strength > 0
                    && Float.isFinite(strength)
                    && textures.values().stream().anyMatch(Mask::hasPaint);
        }
    }

    public Map<String, Profile> blocks = new TreeMap<>();

    // I save one profile per group and rebuild resolved profiles only after edits.
    public Map<String, BlockLinks.Group> groups = new TreeMap<>();

    public Set<String> independentBlocks = new TreeSet<>();

    public boolean linksMasks(String block) {
        return !independentBlocks.contains(block);
    }

    public void linkMasks(String block, boolean linked) {
        if (linked) independentBlocks.remove(block);
        else {
            if (group(block) != null) BlockLinks.unlink(this, List.of(block));
            independentBlocks.add(block);
        }
    }

    private transient long revision, geometryRevision;
    private transient Map<String, BlockLinks.Group> memberships;
    private transient Map<String, Profile> resolved;

    public long revision() {
        return revision;
    }

    public long geometryRevision() {
        return geometryRevision;
    }

    // I rebuild geometry for paint, strength or link edits, but not for style-only changes.
    public void changed() {
        geometryRevision++;
        stylesChanged();
    }

    void stylesChanged() {
        revision++;
        resolved = null;
    }

    void stylesInstalled() {
        memberships = null;
        stylesChanged();
    }

    void relationshipsChanged() {
        memberships = null;
        changed();
    }

    public BlockLinks.Group group(String block) {
        if (memberships == null) {
            memberships = new HashMap<>();
            for (var g : groups.values()) for (var id : g.members) memberships.put(id, g);
        }
        return memberships.get(block);
    }

    // I use a flat cached lookup so rendering doesn't walk groups or copy profiles.
    public Map<String, Profile> profiles() {
        if (groups.isEmpty()) return blocks;
        if (resolved == null) {
            resolved = new HashMap<>(blocks);
            for (var group : groups.values()) {
                var p = group.profile;
                if (!group.textureLinks.isEmpty()) {
                    p = new Profile();
                    p.strength = group.profile.strength;
                    p.override = group.profile.override;
                    p.textures = new TreeMap<>(group.profile.textures);
                    for (var link : group.textureLinks.entrySet()) {
                        var mask = group.profile.textures.get(link.getValue());
                        if (mask != null) p.textures.put(link.getKey(), mask);
                        else p.textures.remove(link.getKey());
                    }
                }
                for (var id : group.members) resolved.put(id, p);
            }
        }
        return resolved;
    }

    public Profile profile(String block) {
        return profiles().get(block);
    }

    Profile storedProfile(String block) {
        var g = group(block);
        return g == null ? blocks.get(block) : g.profile;
    }

    public Profile ensureProfile(String block) {
        var g = group(block);
        if (g != null) return g.profile;
        var p = blocks.get(block);
        if (p == null) {
            p = new Profile();
            blocks.put(block, p);
            resolved = null;
        }
        return p;
    }

    String textureKey(String block, String texture) {
        var g = group(block);
        return g == null ? texture : g.textureLinks.getOrDefault(texture, texture);
    }

    public Mask mask(String block, String texture) {
        var p = profile(block);
        return p == null ? null : p.textures.get(texture);
    }

    public void put(String block, String texture, Mask mask) {
        ensureProfile(block).textures.put(textureKey(block, texture), mask);
        changed();
    }

    public void reset(String block, String texture) {
        var p = storedProfile(block);
        if (p != null && p.textures.remove(textureKey(block, texture)) != null) {
            prune(block);
            changed();
        }
    }

    public void removeOverride(String block) {
        var p = storedProfile(block);
        if (p != null && p.override != null) {
            p.override = null;
            prune(block);
            stylesChanged();
        }
    }

    public void setOverride(String block, BloomOverride style) {
        if (style == null || style.empty()) removeOverride(block);
        else {
            ensureProfile(block).override = style;
            stylesChanged();
        }
    }

    private void prune(String block) {
        var p = storedProfile(block);
        if (group(block) == null
                && p != null
                && p.textures.isEmpty()
                && p.override == null
                && p.strength == 1) blocks.remove(block);
    }

    public BloomStyle style(String block, BloomConfig global) {
        var p = profile(block);
        return p == null || p.override == null
                ? BloomStyle.global(global)
                : p.override.resolve(global);
    }

    public boolean save(Path path) {
        return JsonFiles.saveAll(false, document(path)).success();
    }

    public JsonFiles.Document document(Path path) {
        return new JsonFiles.Document(path, this, this::backupLegacy);
    }

    private void backupLegacy(Path path) throws IOException {
        // I keep a pre-migration backup because older builds can't read group records.
        Path backup = path.resolveSibling(path.getFileName() + ".pre-groups.bak");
        if (!groups.isEmpty() && Files.exists(path) && !Files.exists(backup)) {
            var previous =
                    com.google.gson.JsonParser.parseString(Files.readString(path))
                            .getAsJsonObject();
            var oldGroups = previous.get("groups");
            if (oldGroups == null
                    || oldGroups.isJsonNull()
                    || oldGroups.isJsonObject() && oldGroups.getAsJsonObject().isEmpty())
                Files.copy(path, backup);
        }
    }

    public static TextureMasks load(Path path) {
        return load(path, null);
    }

    public static TextureMasks load(Path path, TextureMasks previous) {
        if (Files.notExists(path)) {
            JsonFiles.missing(path);
            return new TextureMasks();
        }
        try (var r = Files.newBufferedReader(path)) {
            TextureMasks m = new GsonBuilder().create().fromJson(r, TextureMasks.class);
            if (m == null || m.blocks == null)
                throw new IllegalArgumentException("Missing block profiles");
            if (m.groups == null) m.groups = new TreeMap<>();
            if (m.independentBlocks == null) m.independentBlocks = new TreeSet<>();
            m.independentBlocks.removeIf(
                    id ->
                            id == null
                                    || id.length() > 256
                                    || !id.matches("[a-z0-9_.-]+:[a-z0-9_./-]+"));
            m.blocks
                    .entrySet()
                    .removeIf(e -> e.getValue() == null || e.getValue().textures == null);
            for (var p : m.blocks.values()) {
                p.strength = Float.isFinite(p.strength) ? Math.clamp(p.strength, 0, 8) : 1;
                if (p.override != null) p.override.sanitize();
                p.textures.entrySet().removeIf(e -> e.getValue() == null || !e.getValue().valid());
            }
            BlockLinks.normalize(m);
            JsonFiles.loaded(path, m);
            return m;
        } catch (Exception e) {
            var fallback = previous == null ? new TextureMasks() : previous;
            JsonFiles.unreadable(path, fallback, e);
            return fallback;
        }
    }
}
