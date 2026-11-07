package dev.stillbloom;

import com.mojang.blaze3d.platform.InputConstants;

import dev.stillbloom.config.BloomConfig;
import dev.stillbloom.gui.BloomGuideScreen;
import dev.stillbloom.gui.BloomSettingsScreen;
import dev.stillbloom.gui.PresetStatus;
import dev.stillbloom.mask.ItemMasks;
import dev.stillbloom.mask.TextureMasks;
import dev.stillbloom.preset.DefaultSetup;
import dev.stillbloom.preset.SetupPersistence;
import dev.stillbloom.preset.SetupPresets;
import dev.stillbloom.render.BloomRenderer;
import dev.stillbloom.render.WeatherScene;
import dev.stillbloom.render.item.HeldItemRenderer;
import dev.stillbloom.render.source.PaintedAtlas;
import dev.stillbloom.render.source.SourceMask;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

public final class StillBloomClient implements ClientModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("StillBloom");
    public static final Path CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve(DefaultSetup.SETTINGS);
    public static BloomConfig config;
    public static final Path MASK_PATH = CONFIG_PATH.resolveSibling(DefaultSetup.BLOCKS);
    public static TextureMasks MASKS = new TextureMasks();
    public static final Path ITEM_MASK_PATH = CONFIG_PATH.resolveSibling(DefaultSetup.ITEMS);
    public static ItemMasks ITEM_MASKS = new ItemMasks();
    public static final WeatherScene WEATHER_SCENE = new WeatherScene();
    public static final BloomRenderer RENDERER = new BloomRenderer();
    public static final SourceMask SOURCE_MASK = new SourceMask();
    public static final HeldItemRenderer HELD_RENDERER = new HeldItemRenderer();
    private ClientLevel activeLevel;

    @Override
    public void onInitializeClient() {
        loadSetup();
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents.CLIENT_STARTED
                .register(client -> PaintedAtlas.register(client.getTextureManager()));
        var category =
                KeyMapping.Category.register(
                        Identifier.fromNamespaceAndPath("stillbloom", "controls"));
        var settings =
                KeyMappingHelper.registerKeyMapping(
                        new KeyMapping("key.stillbloom.settings", InputConstants.KEY_F7, category));
        var toggle =
                KeyMappingHelper.registerKeyMapping(
                        new KeyMapping("key.stillbloom.toggle", InputConstants.KEY_F8, category));
        var size =
                KeyMappingHelper.registerKeyMapping(
                        new KeyMapping("key.stillbloom.size", InputConstants.KEY_F9, category));
        var reload =
                KeyMappingHelper.registerKeyMapping(
                        new KeyMapping("key.stillbloom.reload", InputConstants.KEY_F10, category));
        var pick =
                KeyMappingHelper.registerKeyMapping(
                        new KeyMapping("key.stillbloom.pick", InputConstants.KEY_G, category));
        ClientTickEvents.END_CLIENT_TICK.register(
                client -> {
                    if (activeLevel != client.level) {
                        activeLevel = client.level;
                        // I close chains before the textures they use, then release the old world's
                        // snapshots.
                        RENDERER.close();
                        HELD_RENDERER.close();
                        WEATHER_SCENE.close();
                        SOURCE_MASK.close();
                        RENDERER.invalidate();
                    }
                    SOURCE_MASK.refresh(client);
                    while (pick.consumeClick()) BlockPicker.open(client);
                    while (settings.consumeClick()) {
                        if (!(client.gui.screen() instanceof BloomGuideScreen)) {
                            client.gui.setScreen(
                                    BloomGuideScreen.firstOpen(
                                            new BloomSettingsScreen(client.gui.screen())));
                        }
                    }
                    while (toggle.consumeClick()) {
                        config.enabled = !config.enabled;
                        boolean saved = config.save(CONFIG_PATH);
                        if (client.player != null)
                            client.player.sendOverlayMessage(
                                    Component.literal(
                                            "Fluorescent: "
                                                    + (config.enabled ? "on" : "off")
                                                    + (saved
                                                            ? ""
                                                            : " (could not save; see the log)")));
                    }
                    while (size.consumeClick()) {
                        config.cyclePixelSize();
                        PresetStatus.changed();
                        boolean saved = config.save(CONFIG_PATH);
                        RENDERER.invalidate();
                        if (client.player != null)
                            client.player.sendOverlayMessage(
                                    Component.literal(
                                            "Bloom squares: "
                                                    + config.pixelSize
                                                    + " px"
                                                    + (saved
                                                            ? ""
                                                            : " (could not save; see the log)")));
                    }
                    while (reload.consumeClick()) {
                        loadSetup();
                        PresetStatus.changed();
                        RENDERER.invalidate();
                        String recovery = SetupPersistence.recoveryMessage(CONFIG_PATH.getParent());
                        if (client.player != null)
                            client.player.sendOverlayMessage(
                                    Component.literal(
                                            recovery.isEmpty()
                                                    ? "Fluorescent settings reloaded"
                                                    : "Could not reload every file. Previous settings kept. "
                                                            + recovery));
                    }
                });
    }

    private static void loadSetup() {
        var previous = config == null ? null : new SetupPresets.Setup(config, MASKS, ITEM_MASKS);
        var setup = DefaultSetup.load(CONFIG_PATH.getParent(), previous);
        config = setup.settings();
        MASKS = setup.masks();
        ITEM_MASKS = setup.itemMasks();
    }
}
