package dev.stillbloom.preset;

import dev.stillbloom.persistence.JsonFiles;

import java.nio.file.Path;

// I save all three settings files together so a failed setup can roll back completely.
public final class SetupPersistence {
    private SetupPersistence() {}

    public static JsonFiles.Result save(
            Path directory, SetupPresets.Setup setup, boolean applyingPreset) {
        return JsonFiles.saveAll(
                applyingPreset,
                new JsonFiles.Document(directory.resolve(DefaultSetup.SETTINGS), setup.settings()),
                setup.masks().document(directory.resolve(DefaultSetup.BLOCKS)),
                new JsonFiles.Document(directory.resolve(DefaultSetup.ITEMS), setup.itemMasks()));
    }

    public static String recoveryMessage(Path directory) {
        for (var name :
                new String[] {DefaultSetup.SETTINGS, DefaultSetup.BLOCKS, DefaultSetup.ITEMS}) {
            if (JsonFiles.isUnreadable(directory.resolve(name)))
                return "Restore " + name + " and press F10 to reload.";
        }
        return "";
    }
}
