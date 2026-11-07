package dev.stillbloom.gui;

import net.minecraft.client.gui.screens.Screen;

public final class BloomOverrideScreen extends BloomSettingsScreen {
    public BloomOverrideScreen(Screen parent, String blockId) {
        super(parent);
        openBlock(blockId);
    }
}
