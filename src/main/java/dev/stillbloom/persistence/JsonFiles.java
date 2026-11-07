package dev.stillbloom.persistence;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

// I publish settings changes only after the save succeeds.
public final class JsonFiles {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("StillBloom");
    private static final Map<Path, State> STATES = new HashMap<>();

    private record State(String contents, boolean unreadable) {}

    public record Result(boolean success, String message) {
        private static final Result OK = new Result(true, "");
    }

    @FunctionalInterface
    public interface Preparation {
        void run(Path path) throws IOException;
    }

    public record Document(Path path, Object value, Preparation preparation) {
        public Document(Path path, Object value) {
            this(path, value, ignored -> {});
        }
    }

    @FunctionalInterface
    interface Replacement {
        void move(Path source, Path target) throws IOException;
    }

    private static final class Pending {
        final Path path;
        final String contents;
        Path staged, backup;
        boolean existed, committed, retainBackup;

        Pending(Path path, String contents) {
            this.path = path;
            this.contents = contents;
        }
    }

    private JsonFiles() {}

    private static Path key(Path path) {
        return path.toAbsolutePath().normalize();
    }

    private static String encode(Object value) {
        return GSON.toJson(value) + "\n";
    }

    // I compare the cleaned-up in-memory data so menu navigation doesn't rewrite unchanged files.
    public static synchronized void loaded(Path path, Object value) {
        STATES.put(key(path), new State(encode(value), false));
    }

    public static synchronized void missing(Path path) {
        STATES.remove(key(path));
    }

    // I protect the destination path even when saving copied presets or staged links.
    public static synchronized void unreadable(Path path, Object fallback, Exception failure) {
        STATES.put(key(path), new State(encode(fallback), true));
        LOGGER.error(
                "Cannot read {}; preserving the file until it is restored and reloaded",
                path,
                failure);
    }

    public static synchronized boolean isUnreadable(Path path) {
        var state = STATES.get(key(path));
        return state != null && state.unreadable;
    }

    public static boolean save(Path path, Object value) {
        return saveAll(false, new Document(path, value)).success();
    }

    public static Result saveAll(boolean requireReadable, Document... documents) {
        return saveAll(requireReadable, List.of(documents), JsonFiles::replace);
    }

    // I stage all files first and roll back if saving the complete setup fails partway through.
    static synchronized Result saveAll(
            boolean requireReadable, List<Document> documents, Replacement replacement) {
        var pending = new ArrayList<Pending>();
        try {
            var paths = new HashSet<Path>();
            for (var document : documents) {
                Path path = key(document.path());
                if (!paths.add(path))
                    throw new IOException("Duplicate settings destination: " + path);
                String contents = encode(document.value());
                var previous = STATES.get(path);
                // I leave an unchanged fallback alone on menu close, but validate every destination
                // when applying a setup.
                boolean unchanged = previous != null && previous.contents.equals(contents);
                if (previous != null && previous.unreadable && (requireReadable || !unchanged)) {
                    throw new IOException(
                            "Restore "
                                    + path.getFileName()
                                    + " and press F10 before saving changes.");
                }
                if (unchanged && (!requireReadable || Files.isRegularFile(path))) continue;
                if (Files.exists(path, LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(path)) {
                    throw new IOException(
                            "Settings destination is not a file: " + path.getFileName());
                }
                var change = new Pending(path, contents);
                pending.add(change);
                Files.createDirectories(path.getParent());
                document.preparation().run(path);
                change.staged = Files.createTempFile(path.getParent(), ".stillbloom-save-", ".tmp");
                Files.writeString(change.staged, contents);
                change.existed = Files.exists(path);
                if (change.existed) {
                    change.backup =
                            Files.createTempFile(path.getParent(), ".stillbloom-rollback-", ".tmp");
                    Files.copy(path, change.backup, StandardCopyOption.REPLACE_EXISTING);
                }
            }
            for (var change : pending) {
                replacement.move(change.staged, change.path);
                change.committed = true;
            }
            for (var change : pending) STATES.put(change.path, new State(change.contents, false));
            return Result.OK;
        } catch (Exception failure) {
            for (int i = pending.size() - 1; i >= 0; i--) {
                var change = pending.get(i);
                if (!change.committed) continue;
                try {
                    if (change.existed) replace(change.backup, change.path);
                    else Files.deleteIfExists(change.path);
                } catch (IOException rollbackFailure) {
                    change.retainBackup = true;
                    // I don't let a later save overwrite the only remaining recovery copy.
                    STATES.put(change.path, new State(change.contents, true));
                    failure.addSuppressed(rollbackFailure);
                    LOGGER.error(
                            "Restore {} from recovery copy {}",
                            change.path,
                            change.backup,
                            rollbackFailure);
                }
            }
            LOGGER.error(
                    "Could not save bloom settings; previous files retained where possible",
                    failure);
            return new Result(
                    false,
                    failure.getMessage() == null
                            ? "Could not save settings. See the log for details."
                            : failure.getMessage());
        } finally {
            for (var change : pending) {
                cleanup(change.staged);
                if (!change.retainBackup) cleanup(change.backup);
            }
        }
    }

    private static void replace(Path source, Path target) throws IOException {
        try {
            Files.move(
                    source,
                    target,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void cleanup(Path path) {
        if (path == null) return;
        try {
            Files.deleteIfExists(path);
        } catch (IOException failure) {
            LOGGER.warn("Could not remove temporary settings file {}", path, failure);
        }
    }
}
