package dev.stillbloom.gui;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;

import dev.stillbloom.StillBloomClient;
import dev.stillbloom.preset.BuiltinPresets;
import dev.stillbloom.preset.DefaultSetup;
import dev.stillbloom.preset.SetupPresets;

// I keep this menu history out of saved settings and rendering.
public final class PresetStatus {
    private static final Gson JSON = new GsonBuilder().serializeNulls().create();
    private static final Session SESSION = new Session();
    private static boolean checkedStartingLook;

    private PresetStatus() {}

    public static void changed() {
        SESSION.changed();
    }

    static void refresh(BloomSettingsScreen owner) {
        var current = live();
        if (!checkedStartingLook) {
            checkedStartingLook = true;
            if (SESSION.expected == null) {
                var initial = fingerprint(DefaultSetup.copy());
                if (initial.equals(fingerprint(current))) {
                    SESSION.label = BuiltinPresets.first().title;
                    SESSION.expected = initial;
                    return;
                }
            }
        }
        SESSION.refresh(current);
    }

    static String status(BloomSettingsScreen owner) {
        return SESSION.status();
    }

    static void applied(BloomSettingsScreen owner, String label, SetupPresets.Setup result) {
        SESSION.applied(label, result);
    }

    static Snapshot remember(BloomSettingsScreen owner) {
        refresh(owner);
        return SESSION.remember();
    }

    static void restore(BloomSettingsScreen owner, Snapshot snapshot) {
        SESSION.restore(snapshot);
    }

    static void rename(BloomSettingsScreen owner, String oldLabel, String newLabel) {
        SESSION.rename(oldLabel, newLabel);
    }

    static boolean hasUndo(BloomSettingsScreen owner) {
        return SESSION.hasUndo();
    }

    static void recordUndo(BloomSettingsScreen owner, SetupPresets.Setup before, Snapshot status) {
        SESSION.recordUndo(before, status);
    }

    static Undo undo(BloomSettingsScreen owner) {
        return SESSION.undo();
    }

    static void clearUndo(BloomSettingsScreen owner) {
        SESSION.clearUndo();
    }

    private static SetupPresets.Setup live() {
        return new SetupPresets.Setup(
                StillBloomClient.config, StillBloomClient.MASKS, StillBloomClient.ITEM_MASKS);
    }

    record Snapshot(String label, JsonElement expected, boolean modified, boolean dirty) {
        Snapshot copy() {
            return new Snapshot(
                    label, expected == null ? null : expected.deepCopy(), modified, dirty);
        }
    }

    record Undo(SetupPresets.Setup setup, Snapshot status) {}

    static final class Session {
        private String label;
        private JsonElement expected;
        private boolean modified, dirty;
        private Undo undo;

        void changed() {
            if (expected != null) {
                dirty = true;
                modified = true;
            }
        }

        void applied(String name, SetupPresets.Setup result) {
            label = name;
            expected = fingerprint(result);
            modified = false;
            dirty = false;
        }

        void refresh(SetupPresets.Setup result) {
            if (expected != null) {
                modified = !expected.equals(fingerprint(result));
                dirty = false;
            }
        }

        String status() {
            return expected == null
                    ? "Custom look"
                    : label + (modified ? " - modified" : " - applied");
        }

        Snapshot remember() {
            return new Snapshot(
                    label, expected == null ? null : expected.deepCopy(), modified, dirty);
        }

        void restore(Snapshot saved) {
            if (saved == null) {
                label = null;
                expected = null;
                modified = false;
                dirty = false;
                return;
            }
            label = saved.label;
            expected = saved.expected == null ? null : saved.expected.deepCopy();
            modified = saved.modified;
            dirty = saved.dirty;
        }

        void rename(String oldLabel, String newLabel) {
            if (oldLabel.equals(label)) label = newLabel;
        }

        boolean hasUndo() {
            return undo != null;
        }

        void recordUndo(SetupPresets.Setup before, Snapshot status) {
            undo = new Undo(SetupPresets.copy(before), status == null ? null : status.copy());
        }

        Undo undo() {
            return undo == null
                    ? null
                    : new Undo(
                            SetupPresets.copy(undo.setup),
                            undo.status == null ? null : undo.status.copy());
        }

        void clearUndo() {
            undo = null;
        }
    }

    private static JsonElement fingerprint(SetupPresets.Setup setup) {
        var tree = JSON.toJsonTree(setup).getAsJsonObject();
        // I keep the current bloom switch when applying or undoing a look.
        tree.getAsJsonObject("settings").remove("enabled");
        return tree;
    }
}
