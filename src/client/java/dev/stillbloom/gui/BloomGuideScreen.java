package dev.stillbloom.gui;

import dev.stillbloom.StillBloomClient;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class BloomGuideScreen extends Screen {
    private record Tip(String title, String body) {}

    private static final List<Tip> TIPS =
            List.of(
                    new Tip(
                            "Paint your first light",
                            "Open Blocks, select a block and choose Paint mask. Paint just the part you want to glow. Try a torch flame or the two purple eyes on a spawner."),
                    new Tip(
                            "See your changes in the world",
                            "Choose Live preview while editing. Start at 3 px for a balanced glow; 2 is finer and 4 costs less. Raise Glow smoothness if the squares stand out as you move. By default, F8 lets you compare bloom on and off."),
                    new Tip(
                            "Customize a held item",
                            "Open Held Items and turn on Your held items. Hold the item you want to change, then open its mask editor. Other players’ held items have their own switch."),
                    new Tip(
                            "Keep and share a setup",
                            "Open Looks and choose Save current. Export a saved look to your clipboard to share it. A friend can import the code in Looks and apply it when ready."));
    private static final Path SEEN =
            StillBloomClient.CONFIG_PATH.resolveSibling("stillbloom-guide-seen");
    private static boolean dismissed;
    private final Screen destination;
    private int page, left, top, cardWidth, cardHeight;
    private List<FormattedCharSequence> lines;

    BloomGuideScreen(Screen destination) {
        super(Component.literal("Tips and tricks"));
        this.destination = destination;
    }

    public static Screen firstOpen(Screen destination) {
        if (!dismissed) dismissed = Files.exists(SEEN);
        return dismissed ? destination : new BloomGuideScreen(destination);
    }

    @Override
    protected void init() {
        cardWidth = Math.min(328, width - 20);
        left = (width - cardWidth) / 2;
        lines = font.split(Component.literal(TIPS.get(page).body()), Math.max(30, cardWidth - 28));
        cardHeight = 84 + lines.size() * (font.lineHeight + 2);
        top = (height - cardHeight) / 2;
        int bottom = top + cardHeight - 29;
        int right = left + cardWidth - 12;
        var back =
                new BloomMenuScreen.MenuButton(
                        right - 116, bottom, 52, 18, "Back", () -> show(page - 1));
        back.active = page > 0;
        addRenderableWidget(back);
        boolean last = page == TIPS.size() - 1;
        var next =
                new BloomMenuScreen.MenuButton(
                        right - 60,
                        bottom,
                        60,
                        18,
                        last ? "Done" : "Next",
                        () -> {
                            if (last) onClose();
                            else show(page + 1);
                        });
        addRenderableWidget(next);
        if (!last)
            addRenderableWidget(
                    new BloomMenuScreen.MenuButton(
                            left + 12, bottom, 48, 18, "Skip", this::onClose));
        setInitialFocus(next);
    }

    private void show(int index) {
        page = index;
        rebuildWidgets();
        triggerImmediateNarration(false);
    }

    @Override
    public Component getNarrationMessage() {
        var tip = TIPS.get(page);
        return Component.literal(
                "Tips and tricks. "
                        + (page + 1)
                        + " of "
                        + TIPS.size()
                        + ". "
                        + tip.title()
                        + ". "
                        + tip.body());
    }

    @Override
    public void extractRenderState(
            GuiGraphicsExtractor graphics, int mouseX, int mouseY, float tick) {
        int right = left + cardWidth;
        graphics.fill(left, top, right, top + cardHeight, 0xFF272D32);
        graphics.outline(left, top, cardWidth, cardHeight, 0xFF52626A);
        graphics.text(font, getTitle(), left + 12, top + 10, BloomMenuScreen.ACCENT, false);
        String progress = (page + 1) + " / " + TIPS.size();
        graphics.text(
                font,
                progress,
                right - 12 - font.width(progress),
                top + 10,
                BloomMenuScreen.MUTED,
                false);
        graphics.fill(left + 12, top + 24, right - 12, top + 25, 0xFF414B52);
        graphics.text(
                font, TIPS.get(page).title(), left + 12, top + 33, BloomMenuScreen.TEXT, false);
        int y = top + 48;
        for (var line : lines) {
            graphics.text(font, line, left + 12, y, BloomMenuScreen.MUTED, false);
            y += font.lineHeight + 2;
        }
        super.extractRenderState(graphics, mouseX, mouseY, tick);
    }

    @Override
    public void onClose() {
        if (!dismissed) {
            dismissed = true;
            try {
                Files.createFile(SEEN);
            } catch (FileAlreadyExistsException seenInAnotherSession) {

            } catch (IOException failure) {
                StillBloomClient.LOGGER.warn("Could not remember guide dismissal", failure);
            }
        }
        minecraft.gui.setScreen(destination);
    }
}
