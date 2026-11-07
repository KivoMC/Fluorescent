package dev.stillbloom.gui;

import dev.stillbloom.model.BlockCatalog;

import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.*;
import java.util.function.Consumer;

final class BlockSourceFilterScreen extends BloomMenuScreen {
    private final Screen parent;
    private final Consumer<String> selected;
    private final String active;
    private String query = "";

    BlockSourceFilterScreen(Screen parent, String active, Consumer<String> selected) {
        super("Filter blocks by source");
        this.parent = parent;
        this.active = active;
        this.selected = selected;
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
    protected String heading() {
        return "Filter by mod";
    }

    @Override
    protected BloomSettingsScreen settingsOwner() {
        return findSettingsOwner(parent);
    }

    @Override
    protected void navigate(int page) {
        onClose();
    }

    @Override
    public void onClose() {
        minecraft.gui.setScreen(parent);
    }

    @Override
    protected void init() {
        frame();
        var search =
                new EditBox(
                        font, contentX, contentTop, contentW, 20, Component.literal("Find mod"));
        search.setHint(Component.literal("Find mod name or namespace..."));
        search.setMaxLength(128);
        search.setValue(query);
        addRenderableWidget(search);
        setInitialFocus(search);
        viewTop = contentTop + 26;
        showSources();
        search.setResponder(
                s -> {
                    query = s;
                    rebuildWidgets();
                });
    }

    private void showSources() {
        int y = 0;
        String q = query.strip().toLowerCase(Locale.ROOT);
        for (var source : BlockCatalog.current().sources())
            if ((source.name() + " " + source.id()).toLowerCase(Locale.ROOT).contains(q)) {
                var b =
                        new MenuButton(
                                contentX,
                                0,
                                contentW,
                                30,
                                source.name(),
                                () -> {
                                    selected.accept(source.id());
                                    onClose();
                                });
                b.secondary =
                        (source.id().isEmpty() ? "Every namespace" : source.id())
                                + " / "
                                + source.count()
                                + " blocks";
                b.selected = source.id().equals(active);
                b.settingId = "source/" + source.id();
                scrolling(b, y);
                y += 34;
            }
        if (y == 0) y = note(0, "No matching mod names. Clear the search to see all sources.");
        contentHeight = y;
        finishLayout();
    }
}
