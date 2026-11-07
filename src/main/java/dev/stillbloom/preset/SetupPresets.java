package dev.stillbloom.preset;

import com.google.gson.*;

import dev.stillbloom.config.BloomConfig;
import dev.stillbloom.mask.ItemMasks;
import dev.stillbloom.mask.TextureMasks;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

// I load these detached setups in the menu, not during rendering.
public final class SetupPresets {
    private static final Gson JSON =
            new GsonBuilder().serializeNulls().setPrettyPrinting().create();
    private static final int CODE_LIMIT = 1_048_576, FILE_LIMIT = 8_388_608, LIMIT = 64;
    private final Path path;
    private SortedMap<String, Setup> entries = new TreeMap<>();

    public record Setup(BloomConfig settings, TextureMasks masks, ItemMasks itemMasks) {
        public Setup(BloomConfig settings, TextureMasks masks) {
            this(settings, masks, new ItemMasks());
        }
    }

    public record Shared(String name, Setup setup) {}

    private SetupPresets(Path path) {
        this.path = path;
    }

    public static Setup capture(BloomConfig c, TextureMasks m) {
        return copy(new Setup(c, m));
    }

    public static Setup capture(BloomConfig c, TextureMasks m, ItemMasks items) {
        return copy(new Setup(c, m, items));
    }

    public static Setup copy(Setup setup) {
        return readSetup(JSON.toJsonTree(setup));
    }

    public List<String> names() {
        return List.copyOf(entries.keySet());
    }

    public Setup get(String name) {
        var s = entries.get(name);
        if (s == null) throw new IllegalArgumentException("Select a saved preset.");
        return copy(s);
    }

    public String add(String requested, Setup setup) throws IOException {
        if (entries.size() >= LIMIT)
            throw new IllegalArgumentException("Library full (64 presets). Delete one first.");
        String base = StylePresets.name(requested), unique = base;
        int i = 2;
        while (entries.containsKey(unique)) {
            String suffix = " (" + i++ + ")";
            unique = base.substring(0, Math.min(base.length(), 48 - suffix.length())) + suffix;
        }
        var detached = copy(setup);
        if (sharedCode(unique, detached).length() > CODE_LIMIT)
            throw new IllegalArgumentException("This setup is too large to share (1 MB limit).");
        var next = new TreeMap<>(entries);
        next.put(unique, detached);
        commit(next);
        return unique;
    }

    public String rename(String old, String requested) throws IOException {
        get(old);
        String n = StylePresets.name(requested);
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

    private static String sharedCode(String name, Setup setup) {
        var root = envelope("stillbloom-setup");
        root.addProperty("name", name);
        root.add("setup", JSON.toJsonTree(setup));
        return JSON.toJson(root);
    }

    public String export(String name) {
        return sharedCode(name, get(name));
    }

    public String importCode(String code) throws IOException {
        var s = parse(code);
        return add(s.name, s.setup);
    }

    public static Shared parse(String code) {
        if (code == null || code.length() > CODE_LIMIT)
            throw new IllegalArgumentException("Preset code is missing or too large (1 MB limit).");
        try {
            var root = parseJson(code);
            checkEnvelope(root, "stillbloom-setup");
            if (!root.keySet().equals(Set.of("format", "version", "name", "setup")))
                throw new IllegalArgumentException();
            if (!root.getAsJsonPrimitive("name").isString()) throw new IllegalArgumentException();
            return new Shared(
                    StylePresets.name(root.get("name").getAsString()),
                    readSetup(root.get("setup")));
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(
                    "Invalid setup preset. Use a Fluorescent setup code (version 1).");
        }
    }

    public static SetupPresets load(Path path) throws IOException {
        var library = new SetupPresets(path);
        if (!Files.exists(path)) return library;
        if (Files.size(path) > FILE_LIMIT)
            throw new IOException("Setup library too large; original file kept.");
        try {
            var root = parseJson(Files.readString(path));
            checkEnvelope(root, "stillbloom-setup-library");
            if (!root.keySet().equals(Set.of("format", "version", "presets")))
                throw new IllegalArgumentException();
            var presets = root.getAsJsonObject("presets");
            if (presets == null || presets.size() > LIMIT) throw new IllegalArgumentException();
            for (var e : presets.entrySet())
                if (library.entries.put(StylePresets.name(e.getKey()), readSetup(e.getValue()))
                        != null) throw new IllegalArgumentException();
            return library;
        } catch (RuntimeException e) {
            throw new IOException("Cannot read setup library; original file kept.", e);
        }
    }

    private void commit(SortedMap<String, Setup> next) throws IOException {
        var root = envelope("stillbloom-setup-library");
        root.add("presets", JSON.toJsonTree(next));
        String data = JSON.toJson(root) + "\n";
        if (data.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > FILE_LIMIT)
            throw new IllegalArgumentException("Setup library is full (8 MB limit).");
        Path dir = path.toAbsolutePath().getParent();
        Files.createDirectories(dir);
        Path temp = Files.createTempFile(dir, "stillbloom-setups-", ".tmp");
        try {
            Files.writeString(temp, data);
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

    // I limit JSON nesting before parsing and reject duplicate keys and coerced values.
    private static JsonObject parseJson(String code) {
        try (var reader = new com.google.gson.stream.JsonReader(new java.io.StringReader(code))) {
            reader.setStrictness(Strictness.STRICT);
            JsonElement tree = readJson(reader, 0);
            if (reader.peek() != com.google.gson.stream.JsonToken.END_DOCUMENT)
                throw new IllegalArgumentException();
            return tree.getAsJsonObject();
        } catch (IOException e) {
            throw new IllegalArgumentException(e);
        }
    }

    private static JsonElement readJson(com.google.gson.stream.JsonReader r, int depth)
            throws IOException {
        if (depth > 16) throw new IllegalArgumentException();
        return switch (r.peek()) {
            case BEGIN_OBJECT -> {
                var o = new JsonObject();
                r.beginObject();
                while (r.hasNext()) {
                    String k = r.nextName();
                    if (o.has(k)) throw new IllegalArgumentException();
                    o.add(k, readJson(r, depth + 1));
                }
                r.endObject();
                yield o;
            }
            case BEGIN_ARRAY -> {
                var a = new JsonArray();
                r.beginArray();
                while (r.hasNext()) a.add(readJson(r, depth + 1));
                r.endArray();
                yield a;
            }
            case STRING -> new JsonPrimitive(r.nextString());
            case NUMBER -> new JsonPrimitive(new java.math.BigDecimal(r.nextString()));
            case BOOLEAN -> new JsonPrimitive(r.nextBoolean());
            case NULL -> {
                r.nextNull();
                yield JsonNull.INSTANCE;
            }
            default -> throw new IllegalArgumentException();
        };
    }

    private static Setup readSetup(JsonElement tree) {
        try {
            tree = JsonParser.parseString(JSON.toJson(tree));
            // I add empty item paint only when an older setup omits it; supplied data still needs
            // to be valid.
            if (!tree.getAsJsonObject().has("itemMasks")) {
                var items = new JsonObject();
                items.add("items", new JsonObject());
                tree.getAsJsonObject().add("itemMasks", items);
            }
            // I fill in missing held-item fields from pre-0.19 setups, but still reject invalid
            // supplied values.
            var settings = tree.getAsJsonObject().getAsJsonObject("settings");
            // I migrate only the pixel-size fields; every other preset value stays strict.
            for (String key : List.of("pixelSize", "farPixelSize")) {
                var size = settings.getAsJsonPrimitive(key);
                if (size == null || !size.isNumber()) throw new IllegalArgumentException();
                settings.addProperty(
                        key, BloomConfig.validPixelSize(size.getAsBigDecimal().intValueExact()));
            }
            if (!settings.has("heldItemBloom")) settings.addProperty("heldItemBloom", false);
            if (!settings.has("otherPlayerItemBloom"))
                settings.addProperty("otherPlayerItemBloom", false);
            if (!settings.has("heldItemRange")) settings.addProperty("heldItemRange", 32);
            // I add smooth-glow defaults only when older setups omit them.
            if (!settings.has("smoothBlend")) settings.addProperty("smoothBlend", 0);
            if (!settings.has("uniformEmission")) settings.addProperty("uniformEmission", false);
            if (!settings.has("selfLitPaint")) settings.addProperty("selfLitPaint", true);
            var blockMasks = tree.getAsJsonObject().getAsJsonObject("masks");
            if (!blockMasks.has("independentBlocks"))
                blockMasks.add("independentBlocks", new JsonArray());
            if (!blockMasks.has("groups")) blockMasks.add("groups", new JsonObject());
            // I add the inherited block-detail default for old setups without relaxing other
            // validation.
            for (var profile :
                    tree.getAsJsonObject()
                            .getAsJsonObject("masks")
                            .getAsJsonObject("blocks")
                            .entrySet()) {
                var override = profile.getValue().getAsJsonObject().get("override");
                if (override != null
                        && !override.isJsonNull()
                        && !override.getAsJsonObject().has("preserveDetail"))
                    override.getAsJsonObject().add("preserveDetail", JsonNull.INSTANCE);
            }
            var s = JSON.fromJson(tree, Setup.class);
            if (s == null
                    || s.settings == null
                    || s.masks == null
                    || s.masks.blocks == null
                    || s.itemMasks == null
                    || s.itemMasks.items == null) throw new IllegalArgumentException();
            if (s.masks.independentBlocks == null || s.masks.independentBlocks.size() > 1024)
                throw new IllegalArgumentException();
            s.masks.independentBlocks.forEach(SetupPresets::id);
            if (s.masks.groups == null || s.masks.groups.size() > 1024)
                throw new IllegalArgumentException();
            var members = new HashSet<String>();
            var groupProfiles = new TreeMap<String, TextureMasks.Profile>();
            for (var e : s.masks.groups.entrySet()) {
                var g = e.getValue();
                if (e.getKey().isBlank()
                        || e.getKey().length() > 128
                        || g == null
                        || g.members == null
                        || g.members.size() < 2
                        || g.profile == null
                        || g.textureLinks == null) throw new IllegalArgumentException();
                for (var member : g.members) {
                    id(member);
                    if (!members.add(member) || s.masks.blocks.containsKey(member))
                        throw new IllegalArgumentException();
                }
                if (members.size() > 4096 || g.textureLinks.size() > 4096)
                    throw new IllegalArgumentException();
                for (var alias : g.textureLinks.entrySet()) {
                    id(alias.getKey());
                    id(alias.getValue());
                    if (g.textureLinks.containsKey(alias.getValue()))
                        throw new IllegalArgumentException();
                }
                groupProfiles.put(g.members.iterator().next(), g.profile);
            }
            // I require the normalized tree to match exactly, rejecting missing, unknown or coerced
            // values.
            s.settings.sanitize();
            if (s.masks.blocks.size() + s.itemMasks.items.size() > 1024)
                throw new IllegalArgumentException();
            int masks = 0;
            for (var profiles : List.of(s.masks.blocks, s.itemMasks.items, groupProfiles))
                for (var e : profiles.entrySet()) {
                    id(e.getKey());
                    var p = e.getValue();
                    if (p == null
                            || p.textures == null
                            || !Float.isFinite(p.strength)
                            || p.strength < 0
                            || p.strength > 8) throw new IllegalArgumentException();
                    if (p.override != null) p.override.sanitize();
                    for (var t : p.textures.entrySet()) {
                        id(t.getKey());
                        if (++masks > 4096 || t.getValue() == null || !t.getValue().valid())
                            throw new IllegalArgumentException();
                    }
                }
            if (!tree.equals(JsonParser.parseString(JSON.toJson(s))))
                throw new IllegalArgumentException();
            return s;
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Invalid or unsupported setup values.", e);
        }
    }

    private static void id(String id) {
        if (id.length() > 256 || !id.matches("[a-z0-9_.-]+:[a-z0-9_./-]+"))
            throw new IllegalArgumentException();
    }

    private static JsonObject envelope(String format) {
        var o = new JsonObject();
        o.addProperty("format", format);
        o.addProperty("version", 1);
        return o;
    }

    private static void checkEnvelope(JsonObject o, String format) {
        if (!new JsonPrimitive(format).equals(o.get("format"))
                || !new JsonPrimitive(1).equals(o.get("version")))
            throw new IllegalArgumentException();
    }
}
