package dev.stillbloom.gui;

import dev.stillbloom.StillBloomClient;
import dev.stillbloom.config.BloomOverride;
import dev.stillbloom.preset.StylePresets;

import net.minecraft.client.gui.components.*;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.util.Locale;

public final class StylePresetsScreen extends BloomMenuScreen {
    private final BloomSettingsScreen parent;
    private final BloomOverride current;
    private StylePresets library;
    private String selected,
            draft = "",
            message = "Choose a saved block style, or save this block's appearance.";
    private boolean confirmDelete, management;
    private int nextY, libraryScroll;

    StylePresetsScreen(BloomSettingsScreen parent, BloomOverride current) {
        super("Saved block styles");
        this.parent = parent;
        this.current = current;
        try {
            library =
                    StylePresets.load(
                            StillBloomClient.CONFIG_PATH.resolveSibling("stillbloom-presets.json"));
        } catch (IOException e) {
            message = e.getMessage();
            StillBloomClient.LOGGER.error(message, e);
        }
    }

    @Override
    protected boolean focusedTool() {
        return true;
    }

    @Override
    protected int section() {
        return 2;
    }

    @Override
    protected BloomSettingsScreen settingsOwner() {
        return parent;
    }

    @Override
    protected String heading() {
        return selected == null ? "Block style library" : "Style / " + selected;
    }

    @Override
    protected void navigate(int page) {
        parent.navigate(page);
        if (page != 0) minecraft.gui.setScreen(parent);
    }

    @Override
    public void onClose() {
        minecraft.gui.setScreen(parent);
    }

    @Override
    protected Object scrollContext() {
        return selected + "/" + management;
    }

    @Override
    protected String footer() {
        return "Block appearance only; masks stay";
    }

    @Override
    protected void init() {
        frame();
        nextY = 0;
        int backW = selected == null ? Math.max(70, contentW - 100) : Math.min(90, contentW / 2);
        button(
                contentX,
                contentTop,
                backW,
                20,
                selected == null ? "< Block editor" : "< Library",
                () -> {
                    if (selected == null) onClose();
                    else {
                        selected = null;
                        draft = "";
                        confirmDelete = false;
                        management = false;
                        scroll = libraryScroll;
                        rebuildWidgets();
                    }
                },
                "Return without applying a saved style.");
        if (selected != null) {
            int actionW = (contentW - backW - 8) / 2;
            button(
                    contentX + backW + 4,
                    contentTop,
                    actionW,
                    20,
                    "Apply",
                    () ->
                            perform(
                                    () -> {
                                        parent.applyPreset(library.get(selected));
                                        minecraft.gui.setScreen(parent);
                                    }),
                    "Apply this style to the selected block. Keep its painted mask, source strength and global settings.");
            button(
                    contentX + backW + 8 + actionW,
                    contentTop,
                    contentW - backW - 8 - actionW,
                    20,
                    "Share",
                    () ->
                            perform(
                                    () -> {
                                        String code = library.export(selected);
                                        minecraft.keyboardHandler.setClipboard(code);
                                        if (!code.equals(
                                                minecraft
                                                        .keyboardHandler
                                                        .getClipboard()
                                                        .replace("\r\n", "\n")))
                                            throw new IllegalArgumentException(
                                                    "Clipboard unavailable. Try Share again.");
                                        message = "Block-style code copied. Share it as text.";
                                        confirmDelete = false;
                                    }),
                    "Copy a Fluorescent block-style code. Complete setup presets are managed in Looks.");
        } else {
            button(
                                    contentX + backW + 4,
                                    contentTop,
                                    contentW - backW - 4,
                                    20,
                                    management ? "Hide tools" : "Save / import",
                                    () -> {
                                        management = !management;
                                        confirmDelete = false;
                                        rebuildWidgets();
                                    },
                                    "Save the current block style or import a shared block-style code.")
                            .selected =
                    management;
        }
        viewTop = contentTop + 26;
        boolean initialHint =
                message.equals("Choose a saved block style, or save this block's appearance.");
        if (selected != null || management || !initialHint) nextY = note(nextY, message);
        if (library == null) {
            finishLayout();
            return;
        }
        if (selected == null) {
            if (management) {
                nextY =
                        sectionHeading(
                                nextY,
                                "Save or import",
                                "Block styles store appearance, not painted masks or complete global setups.");
                nameField();
                action(
                        "Save this block's look",
                        "Save style",
                        () -> {
                            selected = library.add(draft, current);
                            draft = selected;
                            management = false;
                            message = "Style saved. Apply it here or reuse it on another block.";
                            scroll = 0;
                        },
                        "Capture the current style, including the values inherited now. Painted masks and global settings are not stored.");
                action(
                        "Shared block style",
                        "Paste style",
                        () -> {
                            selected = library.importCode(minecraft.keyboardHandler.getClipboard());
                            draft = selected;
                            management = false;
                            message = "Style imported. Review it, then Apply.";
                            scroll = 0;
                        },
                        "Import a Fluorescent block-style code into the library. This does not change any block.");
            }
            for (String saved : library.names())
                action(
                        saved,
                        "Review style",
                        () -> {
                            libraryScroll = scroll;
                            selected = saved;
                            draft = saved;
                            message = "Apply to this block, or Share its code.";
                            confirmDelete = false;
                            management = false;
                            scroll = 0;
                        },
                        summary(library.get(saved)));
            if (library.names().isEmpty())
                nextY =
                        note(
                                nextY,
                                "No saved block styles yet. Choose Save / import above to create one.");
        } else {
            var style = library.get(selected);
            nextY =
                    sectionHeading(
                            nextY,
                            "Style contents",
                            "Applying changes only this block's appearance. Its painted mask and source strength are kept.");
            info("Shape / strength", label(style.shape) + " / " + decimal(style.intensity));
            info(
                    "Color / width",
                    (style.color == null
                                    ? "Natural"
                                    : String.format(Locale.ROOT, "#%06X", style.color))
                            + " / "
                            + decimal(style.width)
                            + " px");
            info(
                    "Flare",
                    label(style.flare)
                            + " / "
                            + decimal(style.flareStrength)
                            + " strength / "
                            + decimal(style.flareLength)
                            + " px");
            info(
                    "Keep block detail",
                    style.preserveDetail == null ? "Global" : decimal(style.preserveDetail));
            action(
                    "Library tools",
                    management ? "Hide management" : "Rename / delete",
                    () -> {
                        management = !management;
                        confirmDelete = false;
                    },
                    "Rename or delete this saved entry. Applied block styles stay intact.");
            if (management) {
                nameField();
                action(
                        "Style name",
                        "Rename",
                        () -> {
                            selected = library.rename(selected, draft);
                            draft = selected;
                            message = "Style renamed.";
                            confirmDelete = false;
                        },
                        "Rename only the saved library entry.");
                action(
                        "Remove saved entry",
                        confirmDelete ? "Confirm delete" : "Delete style",
                        () -> {
                            if (!confirmDelete) {
                                confirmDelete = true;
                                message = "Press Confirm delete to remove this saved entry.";
                            } else {
                                library.delete(selected);
                                selected = null;
                                draft = "";
                                confirmDelete = false;
                                management = false;
                                message = "Saved entry deleted. Applied block styles are kept.";
                                scroll = 0;
                            }
                        },
                        "Delete only this saved entry. Applied block styles remain. Press twice to confirm.");
            }
        }
        finishLayout();
    }

    private void nameField() {
        var r = row(nextY, "Style name", "Name this saved block appearance.", () -> "");
        nextY += r.height();
        var field =
                new EditBox(
                        font, r.controlX(), 0, r.controlW(), 20, Component.literal("Style name"));
        field.setMaxLength(48);
        field.setHint(
                Component.literal("e.g. Ember")
                        .withStyle(style -> style.withColor(MUTED & 0xFFFFFF)));
        field.setValue(draft);
        field.setResponder(v -> draft = v);
        scrolling(field, r.controlY());
        setInitialFocus(field);
    }

    private void info(String title, String text) {
        nextY = note(nextY, title + ": " + text);
    }

    @FunctionalInterface
    private interface Action {
        void run() throws IOException;
    }

    private void perform(Action action) {
        try {
            action.run();
        } catch (IOException e) {
            message = "Could not save block styles. Try again; see log.";
            StillBloomClient.LOGGER.error(message, e);
        } catch (IllegalArgumentException e) {
            message = e.getMessage();
        }
        if (minecraft.gui.screen() == this) rebuildWidgets();
    }

    private void action(String title, String label, Action action, String help) {
        var r = row(nextY, title, help, () -> "");
        nextY += r.height();
        var button =
                new MenuButton(r.controlX(), 0, r.controlW(), 20, label, () -> perform(action));
        button.setTooltip(Tooltip.create(Component.literal(help)));
        scrolling(button, r.controlY());
    }

    private static String decimal(float value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    private static String summary(BloomOverride s) {
        return label(s.shape)
                + ", strength "
                + decimal(s.intensity)
                + ", "
                + (s.color == null ? "Natural color" : String.format(Locale.ROOT, "#%06X", s.color))
                + ", flare "
                + label(s.flare);
    }
}
