package dev.stillbloom.persistence;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Map;

class JsonFilesTest {
    @TempDir Path directory;

    @Test
    void unchangedSaveDoesNotRewriteOrRunMigration() throws Exception {
        Path file = directory.resolve("settings.json");
        String original = "{ \"value\" : 7 }\n";
        Files.writeString(file, original);
        var time = FileTime.fromMillis(1_600_000_000_000L);
        Files.setLastModifiedTime(file, time);
        JsonFiles.loaded(file, Map.of("value", 7));
        var document =
                new JsonFiles.Document(
                        file,
                        Map.of("value", 7),
                        path -> fail("Unchanged saves must skip migration"));
        assertTrue(JsonFiles.saveAll(false, document).success());
        assertEquals(original, Files.readString(file));
        assertEquals(time, Files.getLastModifiedTime(file));
    }

    @Test
    void failedSecondCommitRestoresFirstFileAndAllowsRetry() throws Exception {
        Path first = directory.resolve("settings.json"), second = directory.resolve("masks.json");
        Files.writeString(first, "original settings");
        Files.writeString(second, "original masks");
        var documents =
                List.of(
                        new JsonFiles.Document(first, Map.of("a", 2)),
                        new JsonFiles.Document(second, Map.of("b", 3)));
        var result =
                JsonFiles.saveAll(
                        false,
                        documents,
                        (source, target) -> {
                            if (target.equals(second))
                                throw new IOException("Simulated commit failure");
                            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
                        });
        assertFalse(result.success());
        assertEquals("original settings", Files.readString(first));
        assertEquals("original masks", Files.readString(second));
        assertNoTemporaryFiles();
        assertTrue(
                JsonFiles.saveAll(false, documents.toArray(JsonFiles.Document[]::new)).success());
        assertTrue(Files.readString(first).contains("\"a\": 2"));
        assertTrue(Files.readString(second).contains("\"b\": 3"));
        assertNoTemporaryFiles();
    }

    @Test
    void rollbackRemovesNewFileIfAnotherCommitFails() throws Exception {
        Path first = directory.resolve("new.json"), second = directory.resolve("existing.json");
        Files.writeString(second, "keep");
        var documents =
                List.of(
                        new JsonFiles.Document(first, Map.of("a", 2)),
                        new JsonFiles.Document(second, Map.of("b", 3)));
        assertFalse(
                JsonFiles.saveAll(
                                false,
                                documents,
                                (source, target) -> {
                                    if (target.equals(second))
                                        throw new IOException("Simulated commit failure");
                                    Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
                                })
                        .success());
        assertFalse(Files.exists(first));
        assertEquals("keep", Files.readString(second));
        assertNoTemporaryFiles();
    }

    @Test
    void unreadableDestinationAlsoBlocksDetachedPresetObjects() throws Exception {
        Path file = directory.resolve("masks.json");
        Files.writeString(file, "{broken");
        JsonFiles.unreadable(file, Map.of(), new IOException("Bad JSON"));
        assertTrue(
                JsonFiles.save(file, Map.of()), "Closing an unchanged fallback does not write it");
        assertFalse(JsonFiles.save(file, Map.of("paint", 1)));
        assertFalse(JsonFiles.saveAll(true, new JsonFiles.Document(file, Map.of())).success());
        assertEquals("{broken", Files.readString(file));
        assertNoTemporaryFiles();
    }

    @Test
    void applyingUnchangedPresetRecreatesMissingFilesAndRejectsInvalidDestinations()
            throws Exception {
        Path file = directory.resolve("settings.json");
        var value = Map.of("strength", 2);
        assertTrue(JsonFiles.save(file, value));
        Files.delete(file);
        assertTrue(JsonFiles.saveAll(true, new JsonFiles.Document(file, value)).success());
        assertTrue(Files.isRegularFile(file));
        Files.delete(file);
        Files.createDirectory(file);
        Files.writeString(file.resolve("keep"), "keep");
        assertFalse(JsonFiles.saveAll(true, new JsonFiles.Document(file, value)).success());
        assertEquals("keep", Files.readString(file.resolve("keep")));
        assertNoTemporaryFiles();
    }

    private void assertNoTemporaryFiles() throws Exception {
        try (var files = Files.list(directory)) {
            assertFalse(
                    files.anyMatch(
                            path -> path.getFileName().toString().startsWith(".stillbloom-")));
        }
    }
}
