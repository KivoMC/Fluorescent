package dev.stillbloom.gui;

import com.mojang.blaze3d.platform.InputConstants;

import dev.stillbloom.gui.layout.ColorPickerLayout;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.Objects;
import java.util.function.Consumer;

final class BloomColorScreen extends Screen {
    private static final int TEXT = BloomMenuScreen.TEXT,
            MUTED = BloomMenuScreen.MUTED,
            ACCENT = BloomMenuScreen.ACCENT,
            ERROR = 0xFFFF9292;
    private static final int[] SWATCHES = {
        0xFFFFFF, 0xFFD08A, 0xFF8F40, 0xFF5252, 0xFF81CF, 0xBA8AFF, 0x6B9CFF, 0x66DCFF, 0x79F2AA,
        0xFFE766, 0x808080, 0x000000
    };
    private final Screen parent;
    private final Runnable finish;
    private final String blockName;
    private final Consumer<Integer> onChange;
    private final Integer inheritedColor;
    private final EditBox[] rgb = new EditBox[3];
    private EditBox hex;
    private Spectrum square;
    private BloomMenuScreen.MenuButton natural, copy;
    private int panelX, panelY, panelW, panelH, bodyY, rightX, rightW, size, swatchY;
    private ColorPickerLayout layout;
    private int chosenColor, ticks;
    private float hue, saturation, value;
    private Integer selected, delivered;
    private boolean syncing, pending;
    private String status = "";

    BloomColorScreen(
            BloomSettingsScreen parent,
            String blockName,
            Integer initialColor,
            Consumer<Integer> onChange) {
        this(parent, blockName, initialColor, onChange, parent::finishColorEdit);
    }

    BloomColorScreen(
            Screen parent,
            String blockName,
            Integer initialColor,
            Consumer<Integer> onChange,
            Runnable finish) {
        this(parent, blockName, initialColor, null, onChange, finish);
    }

    BloomColorScreen(
            Screen parent,
            String blockName,
            Integer initialColor,
            Integer inheritedColor,
            Consumer<Integer> onChange,
            Runnable finish) {
        super(Component.literal("Bloom color"));
        this.parent = parent;
        this.blockName = blockName;
        this.onChange = onChange;
        this.finish = finish;
        this.inheritedColor = inheritedColor;
        selected = delivered = initialColor;
        chosenColor =
                initialColor != null
                        ? initialColor
                        : inheritedColor == null ? 0xFFFFFF : inheritedColor;
        float[] hsv = BloomColors.hsv(chosenColor, 0);
        hue = hsv[0];
        saturation = hsv[1];
        value = hsv[2];
    }

    BloomSettingsScreen settingsOwner() {
        return BloomMenuScreen.findSettingsOwner(parent);
    }

    @Override
    protected void init() {
        BloomMenuScreen.enterTool(this, settingsOwner());
        layout = ColorPickerLayout.fit(width, height);
        panelX = layout.panelX();
        panelY = layout.panelY();
        panelW = layout.panelW();
        panelH = layout.panelH();
        bodyY = layout.bodyY();
        size = layout.size();
        rightX = layout.rightX();
        rightW = layout.rightW();
        swatchY = layout.swatchY();
        square = addRenderableWidget(new Spectrum(panelX + 12, bodyY, size));
        addRenderableWidget(new HueStrip(panelX + size + 19, bodyY, 14, size));
        hex = field(rightX + 25, layout.hexY(), rightW - 25, "Hex color, six digits", 64);
        hex.setResponder(
                text -> {
                    if (syncing) return;
                    Integer color = BloomColors.parse(text);
                    hex.setTextColor(color == null ? ERROR : TEXT);
                    if (color != null) chooseRgb(color, hex);
                    else status = "Six hex digits";
                });
        int channelWidth = layout.channelW();
        for (int i = 0; i < 3; i++) {
            rgb[i] =
                    field(
                            rightX + i * (channelWidth + 4) + (layout.compact() ? 8 : 0),
                            layout.rgbY(),
                            (i == 2 ? rightW - 2 * (channelWidth + 4) : channelWidth)
                                    - (layout.compact() ? 8 : 0),
                            new String[] {"Red, 0 to 255", "Green, 0 to 255", "Blue, 0 to 255"}[i],
                            16);
            final EditBox input = rgb[i];
            input.setResponder(text -> channelChanged(input));
        }
        int half = (rightW - 4) / 2;
        copy =
                button(
                        rightX,
                        layout.copyY(),
                        half,
                        layout.fieldH(),
                        "Copy",
                        () -> {
                            minecraft.keyboardHandler.setClipboard(
                                    "#" + BloomColors.hex(chosenColor));
                            status = "Color copied";
                        },
                        "Copy the selected color as #RRGGBB.");
        button(
                rightX + half + 4,
                layout.copyY(),
                rightW - half - 4,
                layout.fieldH(),
                "Paste",
                () -> {
                    Integer color = BloomColors.parse(minecraft.keyboardHandler.getClipboard());
                    if (color != null) chooseRgb(color, null);
                    else status = "Invalid clipboard";
                },
                "Paste a six-digit hex color, with or without #.");
        int swatchWidth = (panelW - 24 - 3 * (SWATCHES.length - 1)) / SWATCHES.length;
        for (int i = 0; i < SWATCHES.length; i++) {
            int color = SWATCHES[i];
            var b =
                    button(
                            panelX + 12 + i * (swatchWidth + 3),
                            swatchY,
                            swatchWidth,
                            layout.swatchH(),
                            "#" + BloomColors.hex(color),
                            () -> chooseRgb(color, null),
                            "Use #" + BloomColors.hex(color));
            b.swatch = color;
        }
        boolean item = parent instanceof ItemSettingsScreen;
        natural =
                button(
                        panelX + 12,
                        panelY + panelH - 28,
                        92,
                        20,
                        item ? "Inherit" : "Natural",
                        () -> {
                            selected = null;
                            if (item) {
                                chosenColor = inheritedColor == null ? 0xFFFFFF : inheritedColor;
                                float[] hsv = BloomColors.hsv(chosenColor, hue);
                                hue = hsv[0];
                                saturation = hsv[1];
                                value = hsv[2];
                            }
                            status = item ? inheritedLabel() : "Texture color";
                            refreshFields(null);
                            queue();
                        },
                        item
                                ? "Use the block's glow color, or the texture's own color if none is set. Keep your other settings."
                                : "Use the texture's own color for the glow. Keep your other settings.");
        button(
                panelX + panelW - 84,
                panelY + panelH - 28,
                72,
                20,
                "Done",
                this::onClose,
                "Keep these changes and return to style controls. Escape also returns.");
        refreshFields(null);
        setInitialFocus(square);
    }

    private EditBox field(int x, int y, int w, String label, int max) {
        var field = new EditBox(font, x, y, w, layout.fieldH(), Component.literal(label));
        field.setMaxLength(max);
        field.setTextColor(TEXT);
        field.setTooltip(Tooltip.create(Component.literal(label)));
        field.setTooltipDelay(java.time.Duration.ofMillis(600));
        return addRenderableWidget(field);
    }

    private BloomMenuScreen.MenuButton button(
            int x, int y, int w, int h, String label, Runnable action, String help) {
        var b = new BloomMenuScreen.MenuButton(x, y, w, h, label, action);
        b.setTooltip(Tooltip.create(Component.literal(help)));
        return addRenderableWidget(b);
    }

    private void channelChanged(EditBox origin) {
        if (syncing) return;
        Integer[] channels = new Integer[3];
        boolean valid = true;
        for (int i = 0; i < 3; i++) {
            channels[i] = BloomColors.channel(rgb[i].getValue());
            rgb[i].setTextColor(channels[i] == null ? ERROR : TEXT);
            valid &= channels[i] != null;
        }
        if (valid) chooseRgb(channels[0] << 16 | channels[1] << 8 | channels[2], origin);
        else status = "RGB: 0 to 255";
    }

    private void chooseRgb(int color, EditBox origin) {
        chosenColor = color & 0xFFFFFF;
        float[] hsv = BloomColors.hsv(chosenColor, hue);
        hue = hsv[0];
        saturation = hsv[1];
        value = hsv[2];
        selected = chosenColor;
        status = "";
        refreshFields(origin);
        queue();
    }

    private void chooseHsv() {
        chosenColor = BloomColors.rgb(hue, saturation, value);
        selected = chosenColor;
        status = "";
        refreshFields(null);
        queue();
    }

    private void refreshFields(EditBox origin) {
        if (hex == null) return;
        syncing = true;
        if (hex != origin) {
            hex.setValue(BloomColors.hex(chosenColor));
            hex.setTextColor(TEXT);
        }
        for (int i = 0; i < 3; i++)
            if (rgb[i] != origin) {
                rgb[i].setValue(Integer.toString((chosenColor >> (16 - i * 8)) & 255));
                rgb[i].setTextColor(TEXT);
            }
        syncing = false;
        if (natural != null) natural.selected = selected == null;
        if (copy != null) copy.active = selected != null;
    }

    private void queue() {
        pending = !Objects.equals(selected, delivered);
    }

    private String inheritedLabel() {
        return inheritedColor == null
                ? "Inherited texture color"
                : "Inherited #" + BloomColors.hex(inheritedColor);
    }

    private void flush() {
        if (pending) {
            pending = false;
            delivered = selected;
            onChange.accept(selected);
        }
    }

    @Override
    public void tick() {
        if (++ticks % 3 == 0) flush();
    }

    @Override
    public void removed() {
        flush();
        finish.run();
    }

    @Override
    public void onClose() {
        if (BloomMenuScreen.currentClosing()) return;
        flush();
        minecraft.gui.setScreen(parent);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean twice) {
        return BloomMenuScreen.currentClosing() || super.mouseClicked(event, twice);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        return BloomMenuScreen.currentClosing() || super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        return BloomMenuScreen.currentClosing() || super.mouseReleased(event);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        return BloomMenuScreen.currentClosing() || super.keyPressed(event);
    }

    @Override
    public boolean charTyped(net.minecraft.client.input.CharacterEvent event) {
        return BloomMenuScreen.currentClosing() || super.charTyped(event);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        return BloomMenuScreen.currentClosing() || super.mouseScrolled(x, y, horizontal, vertical);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public boolean isInGameUi() {
        return minecraft.level != null;
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int x, int y, float tick) {}

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int x, int y, float tick) {
        g.fill(panelX + 3, panelY + 4, panelX + panelW + 3, panelY + panelH + 4, 0x80000000);
        g.fill(panelX, panelY, panelX + panelW, panelY + panelH, 0xED202428);
        g.fill(panelX, panelY, panelX + panelW, panelY + 27, 0xF51B2024);
        g.fill(panelX + 12, panelY + 12, panelX + 15, panelY + 15, ACCENT);
        g.fill(panelX + 17, panelY + 9, panelX + 20, panelY + 12, ACCENT);
        g.text(font, "COLOR STUDIO", panelX + 26, panelY + 10, TEXT, false);
        g.fill(panelX + 12, panelY + 27, panelX + panelW - 12, panelY + 28, 0xFF2E3B49);
        g.text(
                font,
                font.plainSubstrByWidth(blockName, panelW - 24),
                panelX + 12,
                panelY + (layout.compact() ? 30 : 36),
                TEXT,
                false);
        g.fill(rightX, bodyY, rightX + rightW, bodyY + (layout.compact() ? 16 : 23), 0xFF1E2835);
        if (selected == null) {
            g.centeredText(
                    font,
                    parent instanceof ItemSettingsScreen
                            ? inheritedColor == null
                                    ? "Inherit"
                                    : "Inherit #" + BloomColors.hex(inheritedColor)
                            : "Natural",
                    rightX + rightW / 2,
                    bodyY + (layout.compact() ? 4 : 8),
                    MUTED);
        } else {
            g.fill(
                    rightX + 3,
                    bodyY + 3,
                    rightX + rightW - 3,
                    bodyY + (layout.compact() ? 13 : 20),
                    0xFF000000 | chosenColor);
            int brightness =
                    ((chosenColor >> 16) & 255) * 299
                            + ((chosenColor >> 8) & 255) * 587
                            + (chosenColor & 255) * 114;
            String label = "#" + BloomColors.hex(chosenColor);
            g.text(
                    font,
                    label,
                    rightX + (rightW - font.width(label)) / 2,
                    bodyY + (layout.compact() ? 4 : 8),
                    brightness > 140000 ? 0xFF111111 : TEXT,
                    false);
        }
        g.text(font, "Hex", rightX, layout.hexY() + 4, MUTED, false);
        String[] labels = {"R", "G", "B"};
        for (int i = 0; i < 3; i++)
            g.text(
                    font,
                    labels[i],
                    rgb[i].getX() - (layout.compact() ? 8 : 0),
                    layout.compact() ? layout.rgbY() + 4 : bodyY + 57,
                    MUTED,
                    false);
        if (size > 135 && !layout.compact()) {
            g.text(font, "Drag to explore", rightX, bodyY + 126, MUTED, false);
            g.text(font, "Arrow keys / Shift: fine", rightX, bodyY + 138, MUTED, false);
        }
        String hint =
                status.isEmpty()
                        ? (parent instanceof ItemSettingsScreen
                                ? (selected == null ? inheritedLabel() : "Live preview")
                                : parent instanceof BloomSettingsScreen
                                        ? (selected == null ? "Texture color" : "Live preview")
                                        : "Draft: apply in Review")
                        : status;
        g.text(
                font,
                font.plainSubstrByWidth(hint, panelW - 204),
                panelX + 112,
                panelY + panelH - 22,
                status.startsWith("Six") || status.startsWith("RGB") || status.startsWith("Invalid")
                        ? ERROR
                        : MUTED,
                false);
        super.extractRenderState(g, x, y, tick);
    }

    private abstract class ColorControl extends AbstractWidget {
        ColorControl(int x, int y, int w, int h, String name) {
            super(x, y, w, h, Component.literal(name));
            setTooltipDelay(java.time.Duration.ofMillis(600));
        }

        abstract void position(double x, double y);

        @Override
        public void onClick(MouseButtonEvent event, boolean doubled) {
            position(event.x(), event.y());
        }

        @Override
        protected void onDrag(MouseButtonEvent event, double dx, double dy) {
            position(event.x(), event.y());
        }

        @Override
        public void onRelease(MouseButtonEvent event) {
            flush();
        }

        void border(GuiGraphicsExtractor g) {
            g.outline(
                    getX() - 1,
                    getY() - 1,
                    getWidth() + 2,
                    getHeight() + 2,
                    isFocused() ? ACCENT : 0xFF3C4B5C);
        }
    }

    private final class Spectrum extends ColorControl {
        Spectrum(int x, int y, int size) {
            super(x, y, size, size, "Color saturation and brightness");
            setTooltip(
                    Tooltip.create(
                            Component.literal(
                                    "Left/right changes saturation. Up/down changes brightness. Hold Shift for finer adjustments.")));
        }

        @Override
        void position(double x, double y) {
            saturation = (float) Math.clamp((x - getX()) / Math.max(1, getWidth() - 1), 0, 1);
            value = 1 - (float) Math.clamp((y - getY()) / Math.max(1, getHeight() - 1), 0, 1);
            chooseHsv();
        }

        @Override
        public boolean keyPressed(KeyEvent event) {
            float step = event.hasShiftDown() ? .001f : .01f;
            switch (event.key()) {
                case InputConstants.KEY_LEFT -> saturation = Math.max(0, saturation - step);
                case InputConstants.KEY_RIGHT -> saturation = Math.min(1, saturation + step);
                case InputConstants.KEY_UP -> value = Math.min(1, value + step);
                case InputConstants.KEY_DOWN -> value = Math.max(0, value - step);
                default -> {
                    return false;
                }
            }
            chooseHsv();
            return true;
        }

        @Override
        protected void extractWidgetRenderState(GuiGraphicsExtractor g, int x, int y, float tick) {
            // I use 64 gradients here to avoid drawing each color pixel separately.
            int bands = Math.min(64, getWidth());
            for (int i = 0; i < bands; i++) {
                int left = getX() + i * getWidth() / bands,
                        right = getX() + (i + 1) * getWidth() / bands;
                g.fillGradient(
                        left,
                        getY(),
                        right,
                        getBottom(),
                        0xFF000000 | BloomColors.rgb(hue, i / (float) (bands - 1), 1),
                        0xFF000000);
            }
            border(g);
            int cx = getX() + Math.round(saturation * (getWidth() - 1)),
                    cy = getY() + Math.round((1 - value) * (getHeight() - 1));
            g.outline(cx - 3, cy - 3, 7, 7, 0xFF111111);
            g.outline(cx - 2, cy - 2, 5, 5, 0xFFFFFFFF);
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            output.add(
                    NarratedElementType.TITLE,
                    "Color saturation "
                            + Math.round(saturation * 100)
                            + " percent, brightness "
                            + Math.round(value * 100)
                            + " percent");
            output.add(
                    NarratedElementType.USAGE,
                    "Use left and right for saturation, up and down for brightness. Hold Shift for finer adjustments.");
        }
    }

    private final class HueStrip extends ColorControl {
        HueStrip(int x, int y, int w, int h) {
            super(x, y, w, h, "Color hue");
            setTooltip(
                    Tooltip.create(
                            Component.literal(
                                    "Up/down changes hue. Hold Shift for finer adjustments.")));
        }

        @Override
        void position(double x, double y) {
            hue = (float) Math.clamp((y - getY()) / Math.max(1, getHeight() - 1), 0, 1);
            chooseHsv();
        }

        @Override
        public boolean keyPressed(KeyEvent event) {
            float step = (event.hasShiftDown() ? 1f : 5f) / 360;
            switch (event.key()) {
                case InputConstants.KEY_UP, InputConstants.KEY_LEFT ->
                        hue = Math.max(0, hue - step);
                case InputConstants.KEY_DOWN, InputConstants.KEY_RIGHT ->
                        hue = Math.min(1, hue + step);
                default -> {
                    return false;
                }
            }
            chooseHsv();
            return true;
        }

        @Override
        protected void extractWidgetRenderState(GuiGraphicsExtractor g, int x, int y, float tick) {
            for (int i = 0; i < 6; i++)
                g.fillGradient(
                        getX(),
                        getY() + i * getHeight() / 6,
                        getRight(),
                        getY() + (i + 1) * getHeight() / 6,
                        0xFF000000 | BloomColors.rgb(i / 6f, 1, 1),
                        0xFF000000 | BloomColors.rgb((i + 1) / 6f, 1, 1));
            border(g);
            int cy = getY() + Math.round(hue * (getHeight() - 1));
            g.outline(getX() - 2, cy - 2, getWidth() + 4, 5, 0xFF111111);
            g.outline(getX() - 1, cy - 1, getWidth() + 2, 3, 0xFFFFFFFF);
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            output.add(
                    NarratedElementType.TITLE, "Color hue " + Math.round(hue * 360) + " degrees");
            output.add(
                    NarratedElementType.USAGE,
                    "Use up and down to change hue. Hold Shift for finer adjustments.");
        }
    }
}
