package dev.stillbloom.model;

import dev.stillbloom.mask.BlockLinks;

import java.util.*;
import java.util.function.Predicate;

// I cache registry names so searching needs no model inspection or GPU work.
public final class BlockSearchIndex {
    record Entry(String id, String name, String namespace, String search) {
        Entry(String id, String name) {
            this(
                    id,
                    name,
                    id.substring(0, id.indexOf(':')),
                    (id + "\n" + name).toLowerCase(Locale.ROOT));
        }
    }

    public record Source(String id, String name, int count) {}

    private final List<Entry> entries;
    private final Map<String, Entry> byId = new HashMap<>();
    private final List<Source> sources;

    BlockSearchIndex(Map<String, String> names, Map<String, String> modNames) {
        entries =
                names.entrySet().stream()
                        .filter(e -> BlockLinks.validId(e.getKey()))
                        .sorted(Map.Entry.comparingByKey())
                        .map(e -> new Entry(e.getKey(), e.getValue()))
                        .toList();
        var counts = new TreeMap<String, Integer>();
        for (var e : entries) {
            byId.put(e.id, e);
            counts.merge(e.namespace, 1, Integer::sum);
        }
        var list = new ArrayList<Source>();
        list.add(new Source("", "All Blocks", entries.size()));
        if (counts.containsKey("minecraft"))
            list.add(new Source("minecraft", "Vanilla", counts.remove("minecraft")));
        int firstMod = list.size();
        counts.forEach(
                (id, count) -> list.add(new Source(id, modNames.getOrDefault(id, id), count)));
        list.subList(firstMod, list.size())
                .sort(Comparator.comparing(Source::name, String.CASE_INSENSITIVE_ORDER));
        sources = List.copyOf(list);
    }

    public List<String> search(String query, String source, Predicate<String> include) {
        String q = query.strip().toLowerCase(Locale.ROOT);
        return entries.stream()
                .filter(
                        e ->
                                (source.isEmpty() || source.equals(e.namespace))
                                        && (q.isEmpty() || e.search.contains(q))
                                        && include.test(e.id))
                .map(Entry::id)
                .toList();
    }

    public List<Source> sources() {
        return sources;
    }

    public String name(String id) {
        var e = byId.get(id);
        return e == null ? id : e.name;
    }

    public String sourceName(String id) {
        return sources.stream()
                .filter(s -> s.id.equals(id))
                .map(Source::name)
                .findFirst()
                .orElse(id);
    }

    public boolean hasSource(String id) {
        return id.isEmpty() || sources.stream().anyMatch(s -> s.id.equals(id));
    }
}
