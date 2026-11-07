package dev.stillbloom.render.source;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.nio.file.*;
import java.util.List;

class TextureGeometryAtlasTest {
    @Test
    void paddedNonSquareSpritesStartAtImageContentAtEveryAtlasSize() {
        for (int atlas : List.of(256, 2048, 16384))
            for (int width : List.of(8, 16, 48))
                for (int height : List.of(8, 32)) {
                    int x = atlas - width - 4, y = atlas - height - 16;
                    assertEquals(
                            x,
                            TextureGeometry.contentOrigin(
                                    x / (float) atlas, (x + width) / (float) atlas, width));
                    assertEquals(
                            y,
                            TextureGeometry.contentOrigin(
                                    y / (float) atlas, (y + height) / (float) atlas, height));
                }
    }
}
