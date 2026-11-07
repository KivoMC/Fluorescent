package dev.stillbloom.render.source;

import dev.stillbloom.config.BloomRange;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;

// I reuse source arrays only after every reader and upload cache has released them.
final class SourceVolumePool {
    static final int CUSTOM_WORDS = 1024 * 1024;
    static final int DENSE_WORDS =
            BloomRange.VOLUME_SIZE * BloomRange.VOLUME_SIZE * BloomRange.VOLUME_SIZE;
    static final int SPARSE_CLEAR_LIMIT = 16_384;
    // I keep one custom and two color/detail volumes within the same byte limit.
    static final long MAX_RETAINED_BYTES =
            4L * (CUSTOM_WORDS + 2L * DENSE_WORDS + 2L * SPARSE_CLEAR_LIMIT);

    private record Retired(int[] words, int[] written) {}

    private final ArrayDeque<Retired> custom = new ArrayDeque<>(), dense = new ArrayDeque<>();

    int[] acquire(int length, int[]... pinned) {
        var candidates = pool(length);
        if (candidates != null) {
            for (var iterator = candidates.iterator(); iterator.hasNext(); ) {
                var retired = iterator.next();
                boolean owned = false;
                for (var live : pinned)
                    if (live == retired.words) {
                        owned = true;
                        break;
                    }
                if (owned) continue;
                iterator.remove();
                if (retired.written == null) Arrays.fill(retired.words, 0);
                else for (int index : retired.written) retired.words[index] = 0;
                return retired.words;
            }
        }
        return new int[length];
    }

    // I keep the complete old nonzero set in written, using each entry's upper 32 bits.
    void retire(int[] words, List<Long> written) {
        if (words == null) return;
        var candidates = pool(words.length);
        if (candidates == null) return;
        for (var retired : candidates) if (retired.words == words) return;
        int[] indices = null;
        if (words.length == DENSE_WORDS
                && written != null
                && written.size() <= SPARSE_CLEAR_LIMIT) {
            indices = new int[written.size()];
            for (int i = 0; i < indices.length; i++) {
                int index = (int) (written.get(i) >>> 32);
                if (index < 0 || index >= words.length) {
                    indices = null;
                    break;
                }
                indices[i] = index;
            }
        }
        retain(candidates, words, indices);
    }

    // I clear adjacent 16-bit model IDs through their shared 32-bit word.
    void retirePacked(PackedVolume.Data data) {
        if (data == null || data.words() == null) return;
        int[] words = data.words();
        if (words.length != CUSTOM_WORDS)
            throw new IllegalArgumentException("Invalid custom volume size");
        for (var retired : custom) if (retired.words == words) return;
        long[] entries = data.entries();
        int[] indices = null;
        if (entries != null && entries.length <= PackedVolume.SPARSE_LIMIT) {
            indices = new int[entries.length];
            for (int i = 0; i < entries.length; i++) {
                int voxel = (int) (entries[i] >>> 32);
                if (voxel < 0 || voxel >= CUSTOM_WORDS * 2) {
                    indices = null;
                    break;
                }
                indices[i] = voxel >>> 1;
            }
        }
        retain(custom, words, indices);
    }

    private void retain(ArrayDeque<Retired> candidates, int[] words, int[] indices) {
        int limit = words.length == CUSTOM_WORDS ? 1 : 2;
        while (candidates.size() >= limit) candidates.removeFirst();
        // I include sparse clearing data in the byte limit and fall back to a full clear when
        // needed.
        if (indices != null
                && retainedBytes() + 4L * (words.length + indices.length) > MAX_RETAINED_BYTES)
            indices = null;
        candidates.addLast(new Retired(words, indices));
    }

    private ArrayDeque<Retired> pool(int length) {
        return length == CUSTOM_WORDS ? custom : length == DENSE_WORDS ? dense : null;
    }

    long retainedBytes() {
        long bytes = 0;
        for (var list : List.of(custom, dense))
            for (var entry : list)
                bytes +=
                        4L
                                * (entry.words.length
                                        + (entry.written == null ? 0 : entry.written.length));
        return bytes;
    }

    void clear() {
        custom.clear();
        dense.clear();
    }

    static final class PackedVolume {
        static final int SPARSE_LIMIT = 8192;
        private static final long[] EMPTY = new long[0];

        record Data(int[] words, long[] entries) {}

        private final Supplier<int[]> allocate;
        private long[] entries;
        private int count;
        private int[] dense;
        private boolean present;

        PackedVolume(Supplier<int[]> allocate) {
            this.allocate = allocate;
        }

        void add(int voxel, int model) {
            if (voxel < 0
                    || voxel >= SourceVolumePool.CUSTOM_WORDS * 2
                    || model < 0
                    || model > 65535)
                throw new IllegalArgumentException("Invalid packed source entry");
            present = true;
            if (model == 0) return;
            if (dense != null) {
                put(dense, voxel, model);
                return;
            }
            if (count == SPARSE_LIMIT) {
                dense = allocate.get();
                for (int i = 0; i < count; i++)
                    put(dense, (int) (entries[i] >>> 32), (int) entries[i]);
                entries = null;
                put(dense, voxel, model);
                return;
            }
            if (entries == null) entries = new long[32];
            else if (count == entries.length)
                entries = Arrays.copyOf(entries, Math.min(SPARSE_LIMIT, count * 2));
            entries[count++] = ((long) voxel << 32) | model;
        }

        // I allow compaction to reassign model IDs only in the unpublished build.
        void reset() {
            if (dense != null) Arrays.fill(dense, 0);
            count = 0;
            present = false;
        }

        Data finish(Data previous) {
            if (!present) return new Data(null, EMPTY);
            if (dense != null) return new Data(dense, null);
            if (previous != null
                    && previous.words != null
                    && previous.entries != null
                    && previous.entries.length == count
                    && (count == 0 || Arrays.equals(entries, 0, count, previous.entries, 0, count)))
                return previous;
            int[] words = allocate.get();
            for (int i = 0; i < count; i++) put(words, (int) (entries[i] >>> 32), (int) entries[i]);
            return new Data(words, count == 0 ? EMPTY : Arrays.copyOf(entries, count));
        }

        private static void put(int[] words, int voxel, int model) {
            words[voxel >>> 1] |= model << ((voxel & 1) * 16);
        }
    }
}
