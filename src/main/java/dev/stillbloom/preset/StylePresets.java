package dev.stillbloom.preset;

import com.google.gson.*;

import dev.stillbloom.config.BloomConfig;
import dev.stillbloom.config.BloomOverride;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

// I keep style presets as detached menu snapshots rather than live renderer inputs.
public final class StylePresets {
    private static final Gson JSON =
            new GsonBuilder().serializeNulls().setPrettyPrinting().create();
    private static final String SHARE = "stillbloom-style", LIBRARY = "stillbloom-style-library";
    private static final int LIMIT = 128;
    private final Path path;
    private SortedMap<String, BloomOverride> entries = new TreeMap<>();

    private StylePresets(Path path) {
        this.path = path;
    }

    public record Shared(String name, BloomOverride style) {}

    public static BloomOverride capture(BloomOverride source, BloomConfig global) {
        var s = source == null ? new BloomOverride() : source;
        var o = new BloomOverride();
        o.shape = s.shape == null ? global.bloomShape : s.shape;
        o.intensity = s.intensity == null ? global.intensity : s.intensity;
        o.width = s.width == null ? global.glowWidth : s.width;
        o.flare = s.flare == null ? global.flareStyle : s.flare;
        // I keep disabled flare settings so enabling the flare later restores its reach and
        // strength.
        o.flareStrength = s.flareStrength == null ? global.flareStrength : s.flareStrength;
        o.flareLength = s.flareLength == null ? global.flareLength : s.flareLength;
        o.color = s.color;
        o.preserveDetail = s.preserveDetail == null ? global.preserveDetail : s.preserveDetail;
        return readStyle(JSON.toJsonTree(o).getAsJsonObject());
    }

    private static BloomOverride copy(BloomOverride style) {
        return readStyle(JSON.toJsonTree(style).getAsJsonObject());
    }

    public List<String> names() {
        return List.copyOf(entries.keySet());
    }

    public BloomOverride get(String name) {
        var s = entries.get(name);
        if (s == null) throw new IllegalArgumentException("Select a saved preset.");
        return copy(s);
    }

    public static String name(String name) {
        if (name == null) throw new IllegalArgumentException("Enter a preset name.");
        String n = name.strip();
        if (n.isEmpty() || n.length() > 48 || n.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Use a name of 1 to 48 printable characters.");
        return n;
    }

    public String add(String requested, BloomOverride style) throws IOException {
        if (entries.size() >= LIMIT)
            throw new IllegalArgumentException("Library full (128 presets). Delete one first.");
        String base = name(requested), unique = base;
        int i = 2;
        while (entries.containsKey(unique)) {
            String suffix = " (" + i++ + ")";
            unique = base.substring(0, Math.min(base.length(), 48 - suffix.length())) + suffix;
        }
        var next = new TreeMap<>(entries);
        next.put(unique, copy(style));
        commit(next);
        return unique;
    }

    public String rename(String old, String requested) throws IOException {
        get(old);
        String n = name(requested);
        if (n.equals(old)) return n;
        if (entries.containsKey(n)) throw new IllegalArgumentException("That name already exists.");
        var next = new TreeMap<>(entries);
        next.put(n, next.remove(old));
        commit(next);
        return n;
    }

    public void delete(String name) throws IOException {
        get(name);
        var next = new TreeMap<>(entries);
        next.remove(name);
        commit(next);
    }

    public String export(String name) {
        var root = envelope(SHARE);
        root.addProperty("name", name(name));
        root.add("style", JSON.toJsonTree(get(name)));
        return JSON.toJson(root);
    }

    public String importCode(String code) throws IOException {
        var shared = parse(code);
        return add(shared.name, shared.style);
    }

    public static Shared parse(String code) {
        if (code == null || code.length() > 4096)
            throw new IllegalArgumentException("Preset code is missing or too large.");
        try {
            var root = JsonParser.parseString(code).getAsJsonObject();
            checkEnvelope(root, SHARE);
            return new Shared(name(string(root, "name")), readStyle(root.getAsJsonObject("style")));
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(
                    "Invalid preset code. Copy a Fluorescent block-style preset (version 1).");
        }
    }

    public static StylePresets load(Path path) throws IOException {
        var library = new StylePresets(path);
        if (!Files.exists(path)) return library;
        if (Files.size(path) > 1_048_576)
            throw new IOException("Preset library is too large; original file kept.");
        try {
            var root = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
            checkEnvelope(root, LIBRARY);
            var presets = root.getAsJsonObject("presets");
            if (presets == null || presets.size() > LIMIT) throw new IllegalArgumentException();
            for (var e : presets.entrySet()) {
                String n = name(e.getKey());
                if (library.entries.put(n, readStyle(e.getValue().getAsJsonObject())) != null)
                    throw new IllegalArgumentException();
            }
            return library;
        } catch (RuntimeException e) {
            throw new IOException("Cannot read preset library; original file kept.", e);
        }
    }

    private void commit(SortedMap<String, BloomOverride> next) throws IOException {
        var root = envelope(LIBRARY);
        root.add("presets", JSON.toJsonTree(next));
        Files.createDirectories(path.toAbsolutePath().getParent());
        Path temp =
                Files.createTempFile(
                        path.toAbsolutePath().getParent(), "stillbloom-presets-", ".tmp");
        try {
            Files.writeString(temp, JSON.toJson(root) + "\n");
            try {
                Files.move(
                        temp,
                        path,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
            }
            entries = next;
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static JsonObject envelope(String format) {
        var root = new JsonObject();
        root.addProperty("format", format);
        root.addProperty("version", 1);
        return root;
    }

    private static void checkEnvelope(JsonObject root, String format) {
        if (!string(root, "format").equals(format) || number(root, "version", 1, 1) != 1)
            throw new IllegalArgumentException();
    }

    private static String string(JsonObject o, String key) {
        var v = o.getAsJsonPrimitive(key);
        if (v == null || !v.isString()) throw new IllegalArgumentException();
        return v.getAsString();
    }

    private static float number(JsonObject o, String key, float min, float max) {
        var v = o.getAsJsonPrimitive(key);
        if (v == null || !v.isNumber()) throw new IllegalArgumentException();
        double n = v.getAsDouble();
        if (!Double.isFinite(n) || n < min || n > max) throw new IllegalArgumentException();
        return (float) n;
    }

    private static BloomOverride readStyle(JsonObject s) {
        var required =
                Set.of(
                        "shape",
                        "intensity",
                        "width",
                        "flare",
                        "flareStrength",
                        "flareLength",
                        "color");
        if (s == null
                || !s.keySet().containsAll(required)
                || s.keySet().stream()
                        .anyMatch(k -> !required.contains(k) && !k.equals("preserveDetail")))
            throw new IllegalArgumentException("Incomplete style.");
        var o = new BloomOverride();
        o.shape = BloomConfig.Shape.valueOf(string(s, "shape"));
        o.flare = BloomConfig.Flare.valueOf(string(s, "flare"));
        o.intensity = number(s, "intensity", 0, 8);
        o.width = number(s, "width", 8, 128);
        o.flareStrength = number(s, "flareStrength", 0, 2);
        o.flareLength = number(s, "flareLength", 8, 128);
        // I keep inheritance when an older style code omits this setting.
        if (s.has("preserveDetail") && !s.get("preserveDetail").isJsonNull())
            o.preserveDetail = number(s, "preserveDetail", 0, 1);
        if (!s.get("color").isJsonNull()) {
            number(s, "color", 0, 0xFFFFFF);
            try {
                o.color = s.get("color").getAsBigDecimal().intValueExact();
            } catch (ArithmeticException e) {
                throw new IllegalArgumentException("Invalid color.");
            }
        }
        return o;
    }
}
