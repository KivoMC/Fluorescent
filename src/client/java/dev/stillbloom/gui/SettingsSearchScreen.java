package dev.stillbloom.gui;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

import java.util.*;

// I use search to open the existing controls, not maintain a second set of values.
final class SettingsSearchScreen extends BloomMenuScreen {
    private final Screen parent;
    private final BloomSettingsScreen owner;
    private EditBox search;
    private String query = "";
    private List<SettingCatalog.Entry> results = List.of();

    SettingsSearchScreen(Screen parent, BloomSettingsScreen owner) {
        this(parent, owner, "");
    }

    SettingsSearchScreen(Screen parent, BloomSettingsScreen owner, String query) {
        super("Find settings");
        this.parent = parent;
        this.owner = owner;
        this.query = query;
    }

    @Override
    protected boolean focusedTool() {
        return true;
    }

    @Override
    protected BloomSettingsScreen settingsOwner() {
        return owner;
    }

    @Override
    protected String closeLabel() {
        return "Back";
    }

    @Override
    protected int section() {
        return owner.section();
    }

    @Override
    protected String heading() {
        return "Find settings";
    }

    @Override
    protected void navigate(int page) {
        owner.navigate(page);
        if (page != 0) minecraft.gui.setScreen(owner);
    }

    @Override
    protected void init() {
        frame();
        search =
                new EditBox(
                        font,
                        contentX,
                        contentTop,
                        contentW,
                        20,
                        Component.literal("Find settings"));
        search.setHint(
                Component.literal("Name or description...")
                        .withStyle(style -> style.withColor(MUTED & 0xFFFFFF)));
        search.setMaxLength(100);
        search.setValue(query);
        addRenderableWidget(search);
        viewTop = contentTop + 27;
        search.setResponder(
                text -> {
                    query = text;
                    scroll = 0;
                    populate();
                });
        populate();
        setInitialFocus(search);
    }

    private void populate() {
        clearScrolling();
        results = SettingCatalog.search(query);
        int y = 0;
        if (results.isEmpty()) {
            note(0, "No matching settings. Try 'smooth', 'color', 'distance' or 'held'.");
            finishLayout();
            return;
        }
        for (var entry : results) {
            var b =
                    new MenuButton(
                            contentX,
                            0,
                            contentW,
                            33,
                            entry.title(),
                            () -> owner.openSetting(entry));
            b.secondary = entry.location() + " / " + entry.description();
            b.accessibleName = "Open " + entry.title();
            b.setTooltip(
                    net.minecraft.client.gui.components.Tooltip.create(
                            Component.literal(entry.description())));
            scrolling(b, y);
            y += 38;
        }
        contentHeight = y;
        finishLayout();
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (isClosing()) return true;
        if (event.key() == InputConstants.KEY_F && event.hasControlDown()) {
            setFocused(search);
            return true;
        }
        if (event.key() == InputConstants.KEY_ESCAPE) {
            onClose();
            return true;
        }
        if (event.key() == InputConstants.KEY_RETURN
                && getFocused() == search
                && !results.isEmpty()) {
            owner.openSetting(results.getFirst());
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public void onClose() {
        minecraft.gui.setScreen(parent);
    }

    @Override
    protected String footer() {
        return results.size() + " matches / Enter opens the first";
    }
}
