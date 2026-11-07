package dev.stillbloom.preset;

import com.google.gson.Gson;

import dev.stillbloom.config.BloomConfig;
import dev.stillbloom.mask.ItemMasks;
import dev.stillbloom.mask.TextureMasks;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;

// I use the same bundled files for first install and the Default look.
public final class DefaultSetup {
    public static final String SETTINGS = "stillbloom.json";
    public static final String BLOCKS = "stillbloom-textures.json";
    public static final String ITEMS = "stillbloom-items.json";
    static final String PENDING = ".stillbloom-install-default.pending";
    private static final List<String> FILES = List.of(BLOCKS, ITEMS, SETTINGS);
    private static final String RESOURCE_DIR = "/dev/stillbloom/defaults/";
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("StillBloom");

    private DefaultSetup() {}

    private static InputStream resource(String name) throws IOException {
        InputStream input = DefaultSetup.class.getResourceAsStream(RESOURCE_DIR + name);
        if (input == null) throw new IOException("Missing bundled preset file " + name);
        return input;
    }

    private static <T> T read(String name, Class<T> type) {
        try (var reader = new InputStreamReader(resource(name), StandardCharsets.UTF_8)) {
            return new Gson().fromJson(reader, type);
        } catch (IOException failure) {
            throw new IllegalStateException("Unable to read bundled preset " + name, failure);
        }
    }

    private static final class Template {
        static final SetupPresets.Setup VALUE =
                SetupPresets.capture(
                        read(SETTINGS, BloomConfig.class),
                        read(BLOCKS, TextureMasks.class),
                        read(ITEMS, ItemMasks.class));
    }

    static BloomConfig settings() {
        return Template.VALUE.settings().copy();
    }

    public static SetupPresets.Setup copy() {
        return SetupPresets.copy(Template.VALUE);
    }

    public static SetupPresets.Setup load(Path directory) {
        return load(directory, null);
    }

    public static SetupPresets.Setup load(Path directory, SetupPresets.Setup previous) {
        Path settings = directory.resolve(SETTINGS);
        Path blocks = directory.resolve(BLOCKS);
        Path items = directory.resolve(ITEMS);
        Path pending = directory.resolve(PENDING);
        boolean fresh =
                FILES.stream()
                        .allMatch(
                                name ->
                                        Files.notExists(
                                                directory.resolve(name),
                                                LinkOption.NOFOLLOW_LINKS));
        if (fresh || Files.exists(pending)) {
            // I record install progress so the next launch can finish an interrupted setup without
            // replacing edited files.
            try {
                Files.createDirectories(directory);
                try {
                    Files.createFile(pending);
                } catch (FileAlreadyExistsException anotherInstaller) {

                }
                for (String name : FILES) installMissing(directory, name);
                Files.deleteIfExists(pending);
            } catch (IOException failure) {
                LOGGER.warn(
                        "Could not finish installing the default preset. A later load will retry.",
                        failure);
                var fallback = copy();
                return new SetupPresets.Setup(
                        Files.notExists(settings)
                                ? fallback.settings()
                                : BloomConfig.load(
                                        settings, previous == null ? null : previous.settings()),
                        Files.notExists(blocks)
                                ? fallback.masks()
                                : TextureMasks.load(
                                        blocks, previous == null ? null : previous.masks()),
                        Files.notExists(items)
                                ? fallback.itemMasks()
                                : ItemMasks.load(
                                        items, previous == null ? null : previous.itemMasks()));
            }
        }
        return new SetupPresets.Setup(
                BloomConfig.load(settings, previous == null ? null : previous.settings()),
                TextureMasks.load(blocks, previous == null ? null : previous.masks()),
                ItemMasks.load(items, previous == null ? null : previous.itemMasks()));
    }

    private static void installMissing(Path directory, String name) throws IOException {
        Path destination = directory.resolve(name);
        if (!Files.notExists(destination, LinkOption.NOFOLLOW_LINKS)) return;
        Path staged = Files.createTempFile(directory, ".stillbloom-default-", ".tmp");
        try {
            try (var input = resource(name);
                    var output = Files.newOutputStream(staged)) {
                input.transferTo(output);
            }
            try {
                Files.move(staged, destination);
            } catch (FileAlreadyExistsException anotherWriter) {
                // I leave the destination alone if it appeared while I was staging the new file.
            }
        } finally {
            Files.deleteIfExists(staged);
        }
    }
}
