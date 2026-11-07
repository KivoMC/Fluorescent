package dev.stillbloom.config;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

class BloomConfigPersistenceTest {
    @TempDir Path directory;

    @Test
    void completeSettingsReplaceExistingFileWithoutLeavingTemporaryFiles() throws Exception {
        Path file = directory.resolve("nested/settings.json");
        var original = new BloomConfig();
        original.intensity = 1.25f;
        original.save(file);
        var replacement = new BloomConfig();
        replacement.intensity = 5.5f;
        replacement.pixelSize = 2;
        replacement.uniformEmission = true;
        replacement.heldItemBloom = true;
        replacement.save(file);

        var loaded = BloomConfig.load(file);
        assertEquals(5.5f, loaded.intensity);
        assertEquals(2, loaded.pixelSize);
        assertTrue(loaded.uniformEmission);
        assertTrue(loaded.heldItemBloom);
        assertOnlyEntry(file.getParent(), file);
    }

    @Test
    void relativeFilenameWithoutParentCanBeSaved() throws Exception {
        Path file = Path.of("bloom-config-test-" + UUID.randomUUID() + ".json");
        assertNull(file.getParent());
        try {
            var config = new BloomConfig();
            config.intensity = 2.75f;
            config.save(file);
            assertTrue(Files.isRegularFile(file));
            assertEquals(2.75f, BloomConfig.load(file).intensity);
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void failedReplacementPreservesExistingDestinationAndCleansTemporaryFile() throws Exception {
        Path destination = Files.createDirectory(directory.resolve("settings.json"));
        Path existing = destination.resolve("keep.txt");
        Files.writeString(existing, "existing data");

        // I use a nonempty directory to make replacing the settings file fail.
        assertDoesNotThrow(() -> new BloomConfig().save(destination));
        assertTrue(Files.isDirectory(destination));
        assertEquals("existing data", Files.readString(existing));
        assertOnlyEntry(directory, destination);
        assertOnlyEntry(destination, existing);
    }

    @Test
    void missingConfigurationCreatesDefaultSettings() throws Exception {
        Path file = directory.resolve("nested/settings.json");
        var loaded = BloomConfig.load(file);
        assertEquals(new BloomConfig().intensity, loaded.intensity);
        assertEquals(new BloomConfig().pixelSize, loaded.pixelSize);
        assertTrue(Files.isRegularFile(file));
        assertEquals(loaded.intensity, BloomConfig.load(file).intensity);
        assertOnlyEntry(file.getParent(), file);
    }

    @Test
    void malformedEmptyAndNullConfigurationsArePreservedWhileDefaultsAreReturned()
            throws Exception {
        Path file = directory.resolve("settings.json");
        for (String original : List.of("{broken}", "", "null")) {
            Files.writeString(file, original);
            var loaded = BloomConfig.load(file);
            assertEquals(new BloomConfig().intensity, loaded.intensity);
            assertEquals(new BloomConfig().pixelSize, loaded.pixelSize);
            assertEquals(original, Files.readString(file));
            assertOnlyEntry(directory, file);
        }
    }

    private static void assertOnlyEntry(Path directory, Path expected) throws Exception {
        try (var entries = Files.list(directory)) {
            assertEquals(List.of(expected), entries.toList());
        }
    }
}
