package dev.stillbloom.render.source;

import static org.junit.jupiter.api.Assertions.*;

import dev.stillbloom.mask.TextureMasks;

import net.minecraft.core.BlockPos;

import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Map;

class GeometryCacheTest {
    private static Field field(Class<?> owner, String name) throws Exception {
        var field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    @Test
    void movingVolumePrunesOnlyOutsidePlacementsAndKeepsGeometry() throws Exception {
        var geometry = new TextureGeometry();
        @SuppressWarnings("unchecked")
        var placed = (Map<Long, Object>) field(TextureGeometry.class, "placed").get(geometry);
        long retained = BlockPos.asLong(8, 64, -16),
                departed = BlockPos.asLong(7, 64, -16),
                far = BlockPos.asLong(136, 64, -16);
        placed.put(retained, null);
        placed.put(departed, null);
        placed.put(far, null);
        @SuppressWarnings("unchecked")
        var models = (Map<Object, Integer>) field(TextureGeometry.class, "cache").get(geometry);
        models.put("supported", 1);
        models.put("rejected", 65535);
        int[] table = (int[]) field(TextureGeometry.class, "table").get(geometry);
        table[TextureGeometry.HEADER_WORDS] = 0x12345678;
        field(TextureGeometry.class, "dirty").setBoolean(geometry, false);
        geometry.retainVolume(8, 0, -64);
        assertEquals(java.util.Set.of(retained), placed.keySet());
        assertEquals(
                Map.of("supported", 1),
                models,
                "Rejected variants must not accumulate across travelled volumes");
        assertFalse(geometry.compactVolumeIfNeeded());
        assertEquals(0x12345678, table[TextureGeometry.HEADER_WORDS]);
        assertFalse(
                field(TextureGeometry.class, "dirty").getBoolean(geometry),
                "Moving without new models must not upload geometry again");
    }

    @Test
    void exhaustedCarriedCacheResetsForFreshCurrentVolume() throws Exception {
        var geometry = new TextureGeometry();
        int[] table = (int[]) field(TextureGeometry.class, "table").get(geometry);
        table[0] = 71;
        table[TextureGeometry.HEADER_WORDS] = 29;
        for (String name : new String[] {"cache", "masks", "placed", "complexIndices"}) {
            @SuppressWarnings("unchecked")
            var map = (Map<Object, Object>) field(TextureGeometry.class, name).get(geometry);
            map.put(name.equals("placed") ? 1L : "old", 1);
        }
        field(TextureGeometry.class, "cursor").setInt(geometry, table.length - 1);
        field(TextureGeometry.class, "nextId").setInt(geometry, 65535);
        field(TextureGeometry.class, "indicesDisabled").setBoolean(geometry, true);
        field(TextureGeometry.class, "requiredIndices").setBoolean(geometry, true);
        field(TextureGeometry.class, "budgetExceeded").setBoolean(geometry, true);
        field(TextureGeometry.class, "dirty").setBoolean(geometry, false);
        assertTrue(geometry.compactVolumeIfNeeded());
        assertEquals(
                TextureGeometry.HEADER_WORDS,
                field(TextureGeometry.class, "cursor").getInt(geometry));
        assertEquals(1, field(TextureGeometry.class, "nextId").getInt(geometry));
        assertEquals(
                TextureGeometry.HEADER_WORDS - 4,
                field(TextureGeometry.class, "indexCursor").getInt(geometry));
        assertFalse(field(TextureGeometry.class, "indicesDisabled").getBoolean(geometry));
        assertFalse(field(TextureGeometry.class, "requiredIndices").getBoolean(geometry));
        assertTrue(field(TextureGeometry.class, "dirty").getBoolean(geometry));
        var resetRows =
                (GeometryTexture.DirtyRows) field(TextureGeometry.class, "dirtyRows").get(geometry);
        var resetStrips = new java.util.ArrayList<String>();
        resetRows.upload((first, count) -> resetStrips.add(first + ":" + count));
        assertEquals(
                java.util.List.of("0:512"),
                resetStrips,
                "Compaction must clear stale GPU words across the entire table");
        assertEquals(0, java.util.Arrays.stream(table).filter(word -> word != 0).count());
        for (String name : new String[] {"cache", "masks", "placed", "complexIndices"})
            assertTrue(((Map<?, ?>) field(TextureGeometry.class, name).get(geometry)).isEmpty());
        assertFalse(geometry.compactVolumeIfNeeded(), "Only one fresh fallback per carried volume");
        field(TextureGeometry.class, "indicesDisabled").setBoolean(geometry, true);
        assertTrue(
                geometry.compactVolumeIfNeeded(),
                "Old inactive models must not permanently consume the acceleration index budget");
    }

    @Test
    void placementBoundsAreHalfOpenAtNegativeAndLargeWorldCoordinates() {
        for (int origin : new int[] {-30_000_000, -128, -1, 0, 30_000_000}) {
            assertTrue(
                    TextureGeometry.insideVolume(
                            BlockPos.asLong(origin, -64, origin), origin, -64, origin));
            assertTrue(
                    TextureGeometry.insideVolume(
                            BlockPos.asLong(origin + 127, 63, origin + 127), origin, -64, origin));
            assertFalse(
                    TextureGeometry.insideVolume(
                            BlockPos.asLong(origin - 1, -64, origin), origin, -64, origin));
            assertFalse(
                    TextureGeometry.insideVolume(
                            BlockPos.asLong(origin + 128, -64, origin), origin, -64, origin));
            assertFalse(
                    TextureGeometry.insideVolume(
                            BlockPos.asLong(origin, 64, origin), origin, -64, origin));
            assertFalse(
                    TextureGeometry.insideVolume(
                            BlockPos.asLong(origin, -64, origin + 128), origin, -64, origin));
        }
    }

    @Test
    void headerPressureCannotRemoveAnIndexRequiredByAComplexModel() throws Exception {
        var geometry = new TextureGeometry();
        int[] table = (int[]) field(TextureGeometry.class, "table").get(geometry);
        table[6] = 16;
        field(TextureGeometry.class, "nextId").setInt(geometry, 4);
        field(TextureGeometry.class, "indexCursor").setInt(geometry, 16);
        field(TextureGeometry.class, "requiredIndices").setBoolean(geometry, true);
        assertFalse(geometry.reserveHeader(false));
        assertEquals(16, table[6]);
        assertFalse(field(TextureGeometry.class, "indicesDisabled").getBoolean(geometry));
        assertTrue(geometry.compactVolumeIfNeeded());
        assertTrue(geometry.reserveHeader(true));
    }

    @Test
    void simpleModelsRetainExhaustiveFallbackButComplexOnesCannotUseIt() throws Exception {
        var geometry = new TextureGeometry();
        int[] table = (int[]) field(TextureGeometry.class, "table").get(geometry);
        table[6] = 16;
        field(TextureGeometry.class, "nextId").setInt(geometry, 4);
        field(TextureGeometry.class, "indexCursor").setInt(geometry, 16);
        assertFalse(geometry.reserveHeader(true));
        assertEquals(16, table[6]);
        assertTrue(geometry.reserveHeader(false));
        assertEquals(0, table[6]);
        assertTrue(field(TextureGeometry.class, "indicesDisabled").getBoolean(geometry));
        assertFalse(geometry.reserveHeader(true));
    }

    @Test
    void failedComplexAllocationReclaimsNewMasksWithoutLosingSharedPaint() throws Exception {
        var geometry = new TextureGeometry();
        var shared = new TextureMasks.Mask(16, 16);
        var added = new TextureMasks.Mask(16, 16);
        @SuppressWarnings("unchecked")
        var masks =
                (Map<TextureMasks.Mask, Integer>)
                        field(TextureGeometry.class, "masks").get(geometry);
        masks.put(shared, TextureGeometry.HEADER_WORDS);
        masks.put(added, TextureGeometry.HEADER_WORDS + 8);
        int[] table = (int[]) field(TextureGeometry.class, "table").get(geometry);
        table[8] = TextureGeometry.HEADER_WORDS + 16;
        table[9] = 204;
        geometry.rollbackModel(2, TextureGeometry.HEADER_WORDS + 8, java.util.Set.of(added));
        assertEquals(Map.of(shared, TextureGeometry.HEADER_WORDS), masks);
        assertEquals(2, field(TextureGeometry.class, "nextId").getInt(geometry));
        assertEquals(
                TextureGeometry.HEADER_WORDS + 8,
                field(TextureGeometry.class, "cursor").getInt(geometry));
        assertArrayEquals(new int[4], java.util.Arrays.copyOfRange(table, 8, 12));
    }

    @Test
    void projectionInverseMatchesFreshInversionThroughStableAndMovingViews() throws Exception {
        var sources = new SourceMask();
        var rotation = new Matrix4f();
        var projection = new Matrix4f();
        sources.captureProjection(projection, rotation);
        assertEquals(new Matrix4f(), field(SourceMask.class, "inverse").get(sources));
        for (int i = 0; i < 12; i++) {
            projection.identity().perspective((float) Math.toRadians(60 + i), 16f / 9f, .05f, 256);
            rotation.identity().rotateY(i * .13f).rotateX(i * .04f);
            var expected = new Matrix4f(projection).mul(rotation).invert();
            sources.captureProjection(projection, rotation);
            assertEquals(expected, field(SourceMask.class, "inverse").get(sources));
            long revision = sources.visibilityRevision();
            sources.captureProjection(projection, rotation);
            assertEquals(revision, sources.visibilityRevision());
            assertEquals(expected, field(SourceMask.class, "inverse").get(sources));
        }
    }
}
