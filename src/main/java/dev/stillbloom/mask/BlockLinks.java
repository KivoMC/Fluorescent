package dev.stillbloom.mask;

import dev.stillbloom.config.BloomOverride;

import java.util.*;

// I keep block IDs even when the mod that supplies them isn't installed.
public final class BlockLinks {
    public static final class Group {
        public boolean automatic;
        public Set<String> members = new TreeSet<>();
        public TextureMasks.Profile profile = new TextureMasks.Profile();

        // I link only unambiguous, equal-sized texture pairs.
        public Map<String, String> textureLinks = new TreeMap<>();
    }

    // I compare how models use textures to distinguish matching-sized states and faces.
    public record Texture(String id, int width, int height, Set<String> uses) {
        public Texture(String id, int width, int height) {
            this(id, width, height, Set.of());
        }

        public Texture {
            uses = Set.copyOf(uses);
        }
    }

    private BlockLinks() {}

    public static boolean validId(String id) {
        return id != null && id.length() <= 256 && id.matches("[a-z0-9_.-]+:[a-z0-9_./-]+");
    }

    static TextureMasks.Profile copy(TextureMasks.Profile p) {
        if (p == null) return null;
        var out = new TextureMasks.Profile();
        out.strength = p.strength;
        out.override = p.override == null ? null : BloomOverride.copyOf(p.override);
        p.textures.forEach((id, mask) -> out.textures.put(id, mask.copy()));
        return out;
    }

    public static boolean same(TextureMasks.Profile a, TextureMasks.Profile b) {
        if (a == null) a = new TextureMasks.Profile();
        if (b == null) b = new TextureMasks.Profile();
        if (a.strength != b.strength
                || !BloomOverride.sameValues(a.override, b.override)
                || !a.textures.keySet().equals(b.textures.keySet())) return false;
        for (var key : a.textures.keySet()) {
            var x = a.textures.get(key);
            var y = b.textures.get(key);
            if (x.width != y.width || x.height != y.height || !Arrays.equals(x.words, y.words))
                return false;
        }
        return true;
    }

    public static List<String> members(TextureMasks masks, String block) {
        var g = masks.group(block);
        return g == null ? List.of(block) : List.copyOf(g.members);
    }

    public static List<String> automaticCandidates(
            TextureMasks masks, String source, Collection<String> matches) {
        var group = masks.group(source);
        if (group != null && !group.automatic) return List.copyOf(group.members);
        if (masks.independentBlocks.contains(source)) return List.of(source);
        var result = new TreeSet<String>();
        result.add(source);
        for (var id : matches) {
            var g = masks.group(id);
            if (!masks.independentBlocks.contains(id) && (g == null || g.automatic)) result.add(id);
        }
        return List.copyOf(result);
    }

    // I ask for a source when settings conflict instead of overwriting paint or settings.
    public static boolean automaticUpdateNeeded(
            TextureMasks masks, String source, Collection<String> matches) {
        var g = masks.group(source);
        if (g != null && !g.automatic || masks.independentBlocks.contains(source)) return false;
        var candidates = new TreeSet<>(automaticCandidates(masks, source, matches));
        if (g != null && !candidates.containsAll(g.members)) return true;
        return candidates.size() > 1
                && (g == null || !g.members.equals(candidates))
                && candidates.stream()
                        .allMatch(id -> same(masks.profile(source), masks.profile(id)));
    }

    public static boolean acceptAutomatic(
            TextureMasks masks, String source, Collection<String> matches) {
        var current = masks.group(source);
        if (current != null && !current.automatic || masks.independentBlocks.contains(source))
            return false;
        var candidates = automaticCandidates(masks, source, matches);
        // I preserve appearance when a resource-pack change splits an automatic group.
        if (current != null && !new HashSet<>(candidates).containsAll(current.members)) {
            var removed = new ArrayList<>(current.members);
            removed.removeAll(candidates);
            detach(masks, removed, false);
        }
        if (candidates.size() < 2) return false;
        current = masks.group(source);
        if (current != null && current.members.equals(new TreeSet<>(candidates))) return false;
        var p = masks.profile(source);
        if (candidates.stream().anyMatch(id -> !same(p, masks.profile(id)))) return false;
        link(masks, candidates, source, true, Map.of());
        return true;
    }

    public static boolean conflict(TextureMasks masks, String source, Collection<String> matches) {
        var ids = automaticCandidates(masks, source, matches);
        var g = masks.group(source);
        return ids.size() > 1 && (g == null || !g.members.equals(new TreeSet<>(ids)));
    }

    // I require an explicit source and keep all existing members when merging groups.
    public static Set<String> expanded(TextureMasks masks, Collection<String> selected) {
        var result = new TreeSet<String>();
        for (var id : selected) {
            if (!validId(id)) throw new IllegalArgumentException("Choose valid block IDs.");
            result.addAll(members(masks, id));
        }
        return result;
    }

    public static void link(
            TextureMasks masks,
            Collection<String> selected,
            String source,
            boolean automatic,
            Map<String, List<Texture>> textures) {
        var ids = automatic ? new TreeSet<>(selected) : expanded(masks, selected);
        if (ids.size() < 2 || !ids.contains(source))
            throw new IllegalArgumentException(
                    "Choose at least two blocks and a source from the group.");
        for (var id : ids)
            if (!validId(id)) throw new IllegalArgumentException("Invalid block ID.");
        var oldGroup = masks.group(source);
        var oldAliases =
                oldGroup == null ? Map.<String, String>of() : new TreeMap<>(oldGroup.textureLinks);
        var profile = copy(masks.profile(source));
        if (profile == null) profile = new TextureMasks.Profile();
        detach(masks, ids, false);
        var g = new Group();
        g.automatic = automatic;
        g.members.addAll(ids);
        g.profile = profile;
        if (!automatic) g.textureLinks = textureLinks(source, ids, textures);
        if (!automatic)
            for (var old : oldAliases.entrySet()) {
                String canonical = g.textureLinks.getOrDefault(old.getValue(), old.getValue());
                if (!old.getKey().equals(canonical)
                        && !g.textureLinks.containsKey(old.getKey())
                        && !g.textureLinks.containsValue(old.getKey()))
                    g.textureLinks.put(old.getKey(), canonical);
            }
        // I store shared paint once so linked textures follow it in Automatic mode too.
        for (var alias : g.textureLinks.keySet()) g.profile.textures.remove(alias);
        ids.forEach(masks.blocks::remove);
        masks.groups.put(UUID.randomUUID().toString(), g);
        masks.relationshipsChanged();
    }

    public static Map<String, String> textureLinks(
            String source, Collection<String> ids, Map<String, List<Texture>> textures) {
        var links = new TreeMap<String, String>();
        var conflicts = new HashSet<String>();
        var originals = textures.getOrDefault(source, List.of());
        for (var id : ids)
            if (!id.equals(source)) {
                var targets = textures.getOrDefault(id, List.of());
                var known = new HashSet<String>();
                for (var a : originals)
                    for (var b : targets) if (a.id.equals(b.id)) known.add(a.id);
                var pairedSources = new HashSet<>(known);
                var pairedTargets = new HashSet<>(known);
                // I compare texture usage in the model; equal size alone can't distinguish a bulb's
                // four state textures.
                for (var target : targets) {
                    if (known.contains(target.id) || target.uses.isEmpty()) continue;
                    var match =
                            originals.stream()
                                    .filter(
                                            t ->
                                                    !known.contains(t.id)
                                                            && sameSize(t, target)
                                                            && t.uses.equals(target.uses))
                                    .toList();
                    long count =
                            targets.stream()
                                    .filter(
                                            t ->
                                                    !known.contains(t.id)
                                                            && sameSize(t, target)
                                                            && t.uses.equals(target.uses))
                                    .count();
                    if (match.size() == 1 && count == 1) {
                        var canonical = match.getFirst();
                        pair(links, conflicts, target.id, canonical.id);
                        pairedSources.add(canonical.id);
                        pairedTargets.add(target.id);
                    }
                }
                // I keep the single remaining texture match for unrelated models.
                for (var target : targets) {
                    if (pairedTargets.contains(target.id)) continue;
                    var match =
                            originals.stream()
                                    .filter(
                                            t ->
                                                    !pairedSources.contains(t.id)
                                                            && sameSize(t, target))
                                    .toList();
                    long count =
                            targets.stream()
                                    .filter(
                                            t ->
                                                    !pairedTargets.contains(t.id)
                                                            && sameSize(t, target))
                                    .count();
                    if (match.size() == 1 && count == 1)
                        pair(links, conflicts, target.id, match.getFirst().id);
                }
            }
        conflicts.forEach(links::remove);
        // I prevent alias chains and cycles when a texture already supplies another alias.
        links.entrySet().removeIf(e -> links.containsKey(e.getValue()));
        return links;
    }

    private static boolean sameSize(Texture a, Texture b) {
        return a.width == b.width && a.height == b.height;
    }

    private static void pair(
            Map<String, String> links, Set<String> conflicts, String alias, String canonical) {
        var previous = links.putIfAbsent(alias, canonical);
        if (previous != null && !previous.equals(canonical)) conflicts.add(alias);
    }

    public static void unlink(TextureMasks masks, Collection<String> ids) {
        detach(masks, ids, true);
    }

    public static void unlinkGroup(TextureMasks masks, String member) {
        unlink(masks, members(masks, member));
    }

    public static void restoreAutomatic(TextureMasks masks, String member) {
        detach(masks, List.of(member), false);
        masks.independentBlocks.remove(member);
        masks.relationshipsChanged();
    }

    private static void detach(TextureMasks masks, Collection<String> ids, boolean excluded) {
        var retained = new TreeMap<String, TextureMasks.Profile>();
        for (var id : ids) {
            var p = copy(masks.profile(id));
            if (p != null) retained.put(id, p);
            if (excluded) masks.independentBlocks.add(id);
        }
        for (var it = masks.groups.entrySet().iterator(); it.hasNext(); ) {
            var g = it.next().getValue();
            g.members.removeAll(ids);
            if (g.members.size() < 2) {
                for (var id : g.members) {
                    var p = copy(masks.profile(id));
                    if (p != null) retained.put(id, p);
                }
                it.remove();
            }
        }
        masks.blocks.putAll(retained);
        masks.relationshipsChanged();
    }

    // I stage link changes so a failed save can't leave half a relationship applied.
    public static TextureMasks copyStore(TextureMasks masks) {
        var out = new TextureMasks();
        masks.blocks.forEach((id, p) -> out.blocks.put(id, copy(p)));
        out.independentBlocks.addAll(masks.independentBlocks);
        masks.groups.forEach(
                (id, g) -> {
                    var next = new Group();
                    next.automatic = g.automatic;
                    next.members.addAll(g.members);
                    next.profile = copy(g.profile);
                    next.textureLinks.putAll(g.textureLinks);
                    out.groups.put(id, next);
                });
        return out;
    }

    public static void install(TextureMasks target, TextureMasks staged) {
        target.blocks = staged.blocks;
        target.groups = staged.groups;
        target.independentBlocks = staged.independentBlocks;
        target.relationshipsChanged();
    }

    // I keep mask objects and links when changing appearance and refresh only resolved profiles.
    static void installStyles(TextureMasks target, TextureMasks staged) {
        target.blocks = staged.blocks;
        target.groups = staged.groups;
        target.independentBlocks = staged.independentBlocks;
        target.stylesInstalled();
    }

    static void normalize(TextureMasks masks) {
        // I keep the first membership in malformed data and retain IDs for missing mods.
        var seen = new HashSet<String>();
        for (var it = masks.groups.entrySet().iterator(); it.hasNext(); ) {
            var g = it.next().getValue();
            if (g == null
                    || g.members == null
                    || g.profile == null
                    || g.profile.textures == null
                    || g.textureLinks == null) {
                it.remove();
                continue;
            }
            g.members.removeIf(id -> !validId(id) || !seen.add(id));
            g.profile.strength =
                    Float.isFinite(g.profile.strength) ? Math.clamp(g.profile.strength, 0, 8) : 1;
            if (g.profile.override != null) g.profile.override.sanitize();
            g.profile
                    .textures
                    .entrySet()
                    .removeIf(
                            e ->
                                    !validId(e.getKey())
                                            || e.getValue() == null
                                            || !e.getValue().valid());
            g.textureLinks
                    .entrySet()
                    .removeIf(
                            e ->
                                    !validId(e.getKey())
                                            || !validId(e.getValue())
                                            || e.getKey().equals(e.getValue()));
            var aliases = new HashSet<>(g.textureLinks.keySet());
            g.textureLinks.entrySet().removeIf(e -> aliases.contains(e.getValue()));
            if (g.members.size() < 2) {
                for (var id : g.members) masks.blocks.putIfAbsent(id, copy(g.profile));
                it.remove();
            }
        }
        masks.relationshipsChanged();
    }
}
