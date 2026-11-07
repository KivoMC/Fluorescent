package dev.stillbloom.render.source;

import dev.stillbloom.config.BloomRange;

import org.joml.FrustumIntersection;

import java.util.ArrayList;
import java.util.List;

// I subtract the camera before converting large world coordinates to floats.
public record SourceBounds(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
    boolean visible(FrustumIntersection frustum, double x, double y, double z, float range) {
        double dx = BloomRange.distanceToInterval(x, minX, maxX),
                dy = BloomRange.distanceToInterval(y, minY, maxY),
                dz = BloomRange.distanceToInterval(z, minZ, maxZ);
        if (dx * dx + dy * dy + dz * dz >= range * range) return false;
        // I keep padding so sources crossing screen or near-plane edges aren't lost.
        return frustum.testAab(
                (float) (minX - x - 1),
                (float) (minY - y - 1),
                (float) (minZ - z - 1),
                (float) (maxX - x + 1),
                (float) (maxY - y + 1),
                (float) (maxZ - z + 1));
    }

    // I use smaller bounds for drawing while keeping full-section visibility checks.
    static final class Clusters {
        private static final int MAX_CLUSTERS = 8;
        private final int sectionX, sectionY, sectionZ;
        private int minX = Integer.MAX_VALUE, minY = minX, minZ = minX;
        private int maxX = Integer.MIN_VALUE, maxY = maxX, maxZ = maxX;
        private int count;
        private boolean coarseOnly;
        // I reuse bounded scratch storage to avoid allocating a map for every source.
        private final int[] keys = new int[MAX_CLUSTERS], boxes = new int[MAX_CLUSTERS * 6];

        Clusters(int sectionX, int sectionY, int sectionZ) {
            this.sectionX = sectionX;
            this.sectionY = sectionY;
            this.sectionZ = sectionZ;
        }

        void add(int x, int y, int z) {
            int localX = x - sectionX, localY = y - sectionY, localZ = z - sectionZ;
            if (localX < 0
                    || localX >= 16
                    || localY < 0
                    || localY >= 16
                    || localZ < 0
                    || localZ >= 16)
                throw new IllegalArgumentException("Source must belong to this section");
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            minZ = Math.min(minZ, z);
            maxX = Math.max(maxX, x + 1);
            maxY = Math.max(maxY, y + 1);
            maxZ = Math.max(maxZ, z + 1);
            if (coarseOnly) return;
            int key = (localX >> 2) | ((localY >> 2) << 2) | ((localZ >> 2) << 4), slot = 0;
            while (slot < count && keys[slot] != key) slot++;
            if (slot == count) {
                // I keep one box for dense sections so extra clusters don't make projection more
                // expensive.
                if (count == MAX_CLUSTERS) {
                    coarseOnly = true;
                    return;
                }
                keys[count++] = key;
                int at = slot * 6;
                boxes[at] = x;
                boxes[at + 1] = y;
                boxes[at + 2] = z;
                boxes[at + 3] = x + 1;
                boxes[at + 4] = y + 1;
                boxes[at + 5] = z + 1;
            } else {
                int at = slot * 6;
                boxes[at] = Math.min(boxes[at], x);
                boxes[at + 1] = Math.min(boxes[at + 1], y);
                boxes[at + 2] = Math.min(boxes[at + 2], z);
                boxes[at + 3] = Math.max(boxes[at + 3], x + 1);
                boxes[at + 4] = Math.max(boxes[at + 4], y + 1);
                boxes[at + 5] = Math.max(boxes[at + 5], z + 1);
            }
        }

        SourceBounds coarse() {
            if (count == 0) throw new IllegalStateException("No sources in section");
            return new SourceBounds(minX, minY, minZ, maxX, maxY, maxZ);
        }

        List<SourceBounds> extraction() {
            if (count == 0) return List.of();
            if (coarseOnly || count == 1) return List.of(coarse());
            var result = new ArrayList<SourceBounds>(count);
            for (int i = 0; i < count; i++) {
                int at = i * 6;
                result.add(
                        new SourceBounds(
                                boxes[at],
                                boxes[at + 1],
                                boxes[at + 2],
                                boxes[at + 3],
                                boxes[at + 4],
                                boxes[at + 5]));
            }
            return List.copyOf(result);
        }
    }
}
