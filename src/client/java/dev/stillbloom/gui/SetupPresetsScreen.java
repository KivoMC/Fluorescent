package dev.stillbloom.gui;

import com.mojang.blaze3d.platform.InputConstants;

import dev.stillbloom.StillBloomClient;
import dev.stillbloom.gui.layout.SmoothScroll;
import dev.stillbloom.preset.BuiltinPresets;
import dev.stillbloom.preset.SetupPresets;

import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class SetupPresetsScreen extends BloomMenuScreen {
    private enum Manage {
        NONE,
        SAVE,
        EDIT
    }

    private final BloomSettingsScreen parent;
    private SetupPresets library;
    private BuiltinPresets.Look selectedLook = BuiltinPresets.first();
    private String selected, draft = "", message = "", libraryError;
    private boolean builtIn = selectedLook != null, confirmApply, error, focusSelection;
    private Manage manage = Manage.NONE;
    private int row, listScroll, normalScroll;
    private Trial trial;
    private boolean browser;
    private int browserOffset,
            builtOffset,
            savedOffset,
            visibleCount,
            listX,
            listY,
            listW,
            listH,
            errorTop,
            errorLines;
    private final SmoothScroll browserScroll = new SmoothScroll();
    private final java.util.ArrayList<LookButton> lookRows = new java.util.ArrayList<>();
    private List<String> browserNames = List.of();
    private int browserPixels, browserRowHeight;
    private boolean browserDrag;
    private double browserGrab;
    private MenuButton previousLooks, nextLooks;
    private String browserCaption = "", browserEmpty;
    private final Map<String, String> savedCache = new HashMap<>();

    private record Trial(
            SetupPresets.Setup before,
            PresetStatus.Snapshot status,
            SetupPresets.Setup target,
            String label) {}

    SetupPresetsScreen(BloomSettingsScreen parent) {
        super("Fluorescent looks");
        this.parent = parent;
        try {
            library =
                    SetupPresets.load(
                            StillBloomClient.CONFIG_PATH.resolveSibling("stillbloom-setups.json"));
            if (!builtIn && !library.names().isEmpty()) selected = library.names().getFirst();
        } catch (IOException e) {
            libraryError = e.getMessage();
            StillBloomClient.LOGGER.error(libraryError, e);
        }
    }

    @Override
    protected int section() {
        return 0;
    }

    @Override
    protected Object scrollContext() {
        return heading() + "/" + builtIn + "/" + manage + "/" + confirmApply;
    }

    @Override
    protected String heading() {
        return trial != null
                ? "Preview preset"
                : confirmApply
                        ? "Apply complete setup?"
                        : manage == Manage.SAVE
                                ? "Save current look"
                                : manage == Manage.EDIT ? "Manage saved look" : "Looks";
    }

    @Override
    protected BloomSettingsScreen settingsOwner() {
        return trial == null ? parent : null;
    }

    @Override
    protected BloomSettingsScreen presentationOwner() {
        return parent;
    }

    @Override
    protected boolean previewMode() {
        return trial != null;
    }

    @Override
    protected boolean focusedTool() {
        return confirmApply || manage != Manage.NONE;
    }

    // I restore the whole setup when a live preview is canceled.
    @Override
    protected boolean canPreview() {
        return trial != null;
    }

    @Override
    protected void togglePreview() {
        if (trial != null) cancelPreview();
        else perform(this::startPreview);
    }

    @Override
    protected void navigate(int page) {
        restoreTrial();
        if (page == 0) {
            manage = Manage.NONE;
            confirmApply = false;
            rebuildWidgets();
            return;
        }
        parent.navigate(page);
        minecraft.gui.setScreen(parent);
    }

    @Override
    public void onClose() {
        if (isClosing()) return;
        if (trial != null) {
            cancelPreview();
            return;
        }
        if (confirmApply) {
            confirmApply = false;
            rebuildWidgets();
            return;
        }
        if (manage != Manage.NONE) {
            leaveManage();
            return;
        }
        parent.onClose();
        if (parent.hasSaveFailure()) {
            message = parent.saveError();
            error = true;
            rebuildWidgets();
        }
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (isClosing()) return true;
        if (event.key() == InputConstants.KEY_ESCAPE) {
            onClose();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public void removed() {
        restoreTrial();
    }

    @Override
    protected String footer() {
        return trial != null
                ? "Temporary - Keep or Cancel"
                : message.isEmpty() ? PresetStatus.status(parent) : message;
    }

    @Override
    protected String closeLabel() {
        return trial != null ? "Cancel" : confirmApply || manage != Manage.NONE ? "Back" : "Done";
    }

    @Override
    protected String closeHelp() {
        return trial != null
                ? "Cancel this temporary preset preview and restore your previous complete setup."
                : confirmApply || manage != Manage.NONE
                        ? "Return to preset selection without applying a different look."
                        : "Keep the current applied settings and return to the game.";
    }

    @Override
    protected void init() {
        frame();
        lookRows.clear();
        row = 0;
        browser = false;
        browserEmpty = null;
        if (trial != null) {
            previewControls();
            return;
        }
        PresetStatus.refresh(parent);
        if (confirmApply) {
            confirmation();
            return;
        }
        if (manage != Manage.NONE) {
            management();
            return;
        }
        mainControls();
    }

    // I leave the live setup alone until a look is applied or tried.
    private void mainControls() {
        browser = true;
        int right = contentX + contentW;
        int builtW = font.width("Built-in") + 16, savedW = font.width("Saved") + 16;
        button(
                                contentX,
                                contentTop,
                                builtW,
                                20,
                                "Built-in",
                                () -> tab(true),
                                "Bundled looks, when available. Saved contains your own presets.")
                        .selected =
                builtIn;
        button(
                                contentX + builtW + 4,
                                contentTop,
                                savedW,
                                20,
                                "Saved",
                                () -> tab(false),
                                "Your complete saved setups, including styles and painted masks.")
                        .selected =
                !builtIn;
        int importW = font.width("Import") + 27, saveW = font.width("Save current") + 27;
        boolean splitToolbar = builtW + savedW + saveW + importW + 20 > contentW;
        int toolsY = contentTop + (splitToolbar ? 24 : 0), toolbarH = splitToolbar ? 50 : 26;
        var paste =
                button(
                        right - importW,
                        toolsY,
                        importW,
                        20,
                        "Import",
                        () -> perform(this::importPreset),
                        "Add a shared setup from the clipboard. Import does not apply it.");
        paste.active = library != null;
        var save =
                button(
                        right - importW - saveW - 4,
                        toolsY,
                        saveW,
                        20,
                        "Save current",
                        () -> enterManage(Manage.SAVE),
                        "Save the current settings, styles and masks as your own complete look.");
        save.active = library != null;

        errorLines =
                error
                        ? Math.min(
                                3,
                                font.split(Component.literal(message), Math.max(40, contentW))
                                        .size())
                        : 0;
        errorTop = contentTop + toolbarH - 1;
        int mainTop = contentTop + toolbarH + (errorLines == 0 ? 0 : errorLines * 10 + 4),
                actionY = contentBottom - 20;
        listX = contentX;
        listW = contentW;
        listY = mainTop + 22;
        listH = Math.max(28, actionY - 4 - listY);
        browserRowHeight = 28;
        visibleCount = Math.max(1, listH / browserRowHeight);
        browserNames =
                builtIn
                        ? BuiltinPresets.looks().stream().map(look -> look.title).toList()
                        : library == null ? List.<String>of() : library.names();
        long now = System.nanoTime();
        browserScroll.bounds(browserMaximum(), now);
        int selectedIndex =
                builtIn
                        ? (selectedLook == null ? -1 : BuiltinPresets.looks().indexOf(selectedLook))
                        : (selected == null ? -1 : browserNames.indexOf(selected));
        if (focusSelection && selectedIndex >= 0) {
            int px = browserScroll.pixels(now), start = selectedIndex * browserRowHeight;
            if (start < px || start + browserRowHeight - 3 > px + listH)
                browserScroll.reset(start, browserMaximum(), now);
            focusSelection = false;
        }
        browserPixels = browserScroll.pixels(now);
        browserOffset = browserPixels / browserRowHeight;
        previousLooks =
                button(
                        listX + listW - 44,
                        mainTop,
                        20,
                        20,
                        "<",
                        () -> browse(-visibleCount),
                        "Previous looks. Mouse wheel and Page Up also browse.");
        previousLooks.accessibleName = "Previous looks";
        nextLooks =
                button(
                        listX + listW - 20,
                        mainTop,
                        20,
                        20,
                        ">",
                        () -> browse(visibleCount),
                        "Next looks. Mouse wheel and Page Down also browse.");
        nextLooks.accessibleName = "Next looks";
        lookRows();
        primaryActions(actionY);
        if (browserNames.isEmpty())
            browserEmpty =
                    builtIn
                            ? "No bundled presets. Save your current glow or import a shared setup."
                            : library == null
                                    ? "Saved library unavailable. Original file kept."
                                    : "Save your current glow or import a shared setup.";
        updateBrowserArrows();
    }

    private void lookRows() {
        String focus =
                lookRows.contains(getFocused())
                        ? ((LookButton) getFocused()).getMessage().getString()
                        : null;
        for (var b : lookRows) removeWidget(b);
        lookRows.clear();
        for (int i = 0; i < visibleCount + 2 && browserOffset + i < browserNames.size(); i++) {
            int index = browserOffset + i;
            String name = browserNames.get(index);
            BuiltinPresets.Look look = builtIn ? BuiltinPresets.looks().get(index) : null;
            String brief = builtIn ? BuiltinPresets.summary(look) : savedSummary(name);
            String status = lookStatus(builtIn ? name : "My presets / " + name);
            boolean selectedHere = builtIn ? look == selectedLook : name.equals(selected);
            var b =
                    new LookButton(
                            listX,
                            listY + i * browserRowHeight - browserPixels % browserRowHeight,
                            listW,
                            browserRowHeight - 3,
                            name,
                            brief,
                            status,
                            selectedHere,
                            () -> {
                                if (look != null) selectedLook = look;
                                else selected = name;
                                message = "";
                                error = false;
                                rebuildWidgets();
                            });
            b.setTooltip(
                    Tooltip.create(
                            Component.literal(
                                    name
                                            + ". "
                                            + (builtIn
                                                    ? look.description + " " + look.help
                                                    : "Complete setup: bloom settings, block/item styles and painted masks.")
                                            + " Try in world shows this look in your scene.")));
            b.visible = b.getY() < listY + listH && b.getY() + b.getHeight() > listY;
            addRenderableWidget(b);
            lookRows.add(b);
        }
        if (focus != null)
            setFocused(
                    lookRows.stream()
                            .filter(b -> b.getMessage().getString().equals(focus))
                            .findFirst()
                            .orElse(null));
    }

    private void updateBrowserArrows() {
        boolean before = browserPixels > 0, after = browserPixels < browserMaximum();
        previousLooks.active = before;
        nextLooks.active = after;
        arrows(before, after);
        browserCaption =
                browserNames.isEmpty()
                        ? (builtIn ? "No built-in looks" : "No saved looks")
                        : (browserOffset + 1)
                                + "-"
                                + Math.min(
                                        browserNames.size(),
                                        (browserPixels + listH + browserRowHeight - 1)
                                                / browserRowHeight)
                                + " / "
                                + browserNames.size();
    }

    private void primaryActions(int y) {
        List<String> labels =
                builtIn
                        ? List.of("Apply", "Try in world", "Undo")
                        : List.of("Apply", "Try in world", "Undo", "Manage");
        int total = 4 * (labels.size() - 1);
        for (String text : labels) total += font.width(text) + 14;
        int spare = Math.max(0, contentW - total), x = contentX;
        for (int i = 0; i < labels.size(); i++) {
            String text = labels.get(i);
            int w =
                    font.width(text)
                            + 14
                            + spare / labels.size()
                            + (i == labels.size() - 1 ? spare % labels.size() : 0);
            Runnable run =
                    switch (text) {
                        case "Apply" ->
                                () ->
                                        perform(
                                                () -> {
                                                    if (completeSelection()) {
                                                        listScroll = scroll;
                                                        confirmApply = true;
                                                        scroll = 0;
                                                    } else applySelected();
                                                });
                        case "Try in world" -> () -> perform(this::startPreview);
                        case "Undo" -> () -> perform(this::undo);
                        default -> () -> enterManage(Manage.EDIT);
                    };
            String help =
                    switch (text) {
                        case "Apply" ->
                                "Apply this look. Review a complete setup before replacing your styles and masks.";
                        case "Try in world" ->
                                "Temporarily try this look in your scene. Keep it or cancel with your entire previous setup restored.";
                        case "Undo" ->
                                "Restore the setup from before your last applied look, even after reopening the menu.";
                        default ->
                                "Rename, copy or delete this saved entry. Applied settings stay.";
                    };
            var b = button(x, y, w, 20, text, run, help);
            b.selected = text.equals("Apply");
            b.active =
                    text.equals("Undo")
                            ? PresetStatus.hasUndo(parent)
                            : available()
                                    && (!text.equals("Try in world") || minecraft.level != null);
            x += w + 4;
        }
    }

    private int browserMaximum() {
        return Math.max(0, browserNames.size() * browserRowHeight - 3 - listH);
    }

    private void browse(int delta) {
        browserScroll.reset(
                browserScroll.target() + delta * browserRowHeight,
                browserMaximum(),
                System.nanoTime());
        updateBrowserScroll();
    }

    private void updateBrowserScroll() {
        long now = System.nanoTime();
        browserScroll.bounds(browserMaximum(), now);
        int px = browserScroll.pixels(now), first = px / browserRowHeight;
        boolean changed = first != browserOffset;
        browserPixels = px;
        browserOffset = first;
        if (changed) lookRows();
        else
            for (int i = 0; i < lookRows.size(); i++) {
                var b = lookRows.get(i);
                b.setY(listY + i * browserRowHeight - px % browserRowHeight);
                b.visible = b.getY() < listY + listH && b.getY() + b.getHeight() > listY;
            }
        if (builtIn) builtOffset = px;
        else savedOffset = px;
        updateBrowserArrows();
    }

    private boolean inBrowser(double x, double y) {
        return browser && x >= listX && x <= listX + listW + 7 && y >= listY && y < listY + listH;
    }

    private int browserThumb() {
        return Math.min(
                listH,
                Math.max(
                        10,
                        listH * listH / Math.max(1, browserNames.size() * browserRowHeight - 3)));
    }

    @Override
    protected void scrollBy(int amount) {
        if (browser) browse(amount > 0 ? visibleCount : -visibleCount);
        else super.scrollBy(amount);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        if (isClosing()) return true;
        if (inBrowser(x, y)) {
            browserScroll.wheel(
                    -vertical * browserRowHeight * 2, browserMaximum(), System.nanoTime());
            return true;
        }
        return browser ? false : super.mouseScrolled(x, y, horizontal, vertical);
    }

    @Override
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent e, boolean twice) {
        if (isClosing()) return true;
        if (e.button() == com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT
                && inBrowser(e.x(), e.y())
                && e.x() >= listX + listW) {
            int thumb = browserThumb();
            double top =
                    listY
                            + (listH - thumb)
                                    * browserPixels
                                    / (double) Math.max(1, browserMaximum());
            browserGrab = e.y() >= top && e.y() < top + thumb ? e.y() - top : thumb / 2.0;
            browserDrag = true;
            moveBrowserBar(e.y());
            return true;
        }
        return super.mouseClicked(e, twice);
    }

    private void moveBrowserBar(double y) {
        browserScroll.reset(
                (y - listY - browserGrab) / Math.max(1, listH - browserThumb()) * browserMaximum(),
                browserMaximum(),
                System.nanoTime());
        updateBrowserScroll();
    }

    @Override
    public boolean mouseDragged(
            net.minecraft.client.input.MouseButtonEvent e, double dx, double dy) {
        if (isClosing()) return true;
        if (browserDrag) {
            moveBrowserBar(e.y());
            return true;
        }
        return super.mouseDragged(e, dx, dy);
    }

    @Override
    public boolean mouseReleased(net.minecraft.client.input.MouseButtonEvent e) {
        browserDrag = false;
        return super.mouseReleased(e);
    }

    @Override
    protected void extractWorkspaceBackground(
            net.minecraft.client.gui.GuiGraphicsExtractor g, int x, int y, float tick) {
        if (browser) updateBrowserScroll();
    }

    private String lookStatus(String label) {
        String current = PresetStatus.status(parent);
        return current.equals(label + " - applied")
                ? "Applied"
                : current.equals(label + " - modified") ? "Modified" : "";
    }

    private String savedSummary(String name) {
        return savedCache.computeIfAbsent(name, key -> summary(library.get(key)));
    }

    @Override
    public void extractRenderState(
            net.minecraft.client.gui.GuiGraphicsExtractor g, int x, int y, float tick) {
        super.extractRenderState(g, x, y, tick);
        if (!browser) return;
        if (browserMaximum() > 0) {
            int thumb = browserThumb(),
                    top = listY + (listH - thumb) * browserPixels / browserMaximum();
            g.fill(listX + listW + 3, listY, listX + listW + 5, listY + listH, 0xFF24313D);
            g.fill(listX + listW + 3, top, listX + listW + 5, top + thumb, ACCENT);
        }
        if (errorLines > 0)
            wrapped(g, message, contentX, errorTop, contentW, errorLines, 0xFFE9B478);
        g.text(
                font,
                font.plainSubstrByWidth(browserCaption, Math.max(1, listW - 50)),
                listX,
                listY - 17,
                MUTED,
                false);
        if (browserEmpty != null)
            g.textWithWordWrap(
                    font, Component.literal(browserEmpty), listX + 4, listY + 5, listW - 8, MUTED);
    }

    private void wrapped(
            net.minecraft.client.gui.GuiGraphicsExtractor g,
            String text,
            int x,
            int y,
            int w,
            int lines,
            int color) {
        var split = font.split(Component.literal(text), Math.max(20, w));
        for (int i = 0; i < Math.min(lines, split.size()); i++)
            g.text(font, split.get(i), x, y + i * 10, color, false);
    }

    private final class LookButton extends net.minecraft.client.gui.components.Button {
        private final String brief, status;
        private final boolean chosen;

        LookButton(
                int x,
                int y,
                int w,
                int h,
                String title,
                String brief,
                String status,
                boolean chosen,
                Runnable run) {
            super(x, y, w, h, Component.literal(title), b -> run.run(), DEFAULT_NARRATION);
            this.brief = brief;
            this.status = status;
            this.chosen = chosen;
            setTooltipDelay(java.time.Duration.ofMillis(500));
        }

        @Override
        protected net.minecraft.network.chat.MutableComponent createNarrationMessage() {
            return Component.literal(
                    getMessage().getString()
                            + ", "
                            + (chosen ? "selected, " : "")
                            + (status.isEmpty() ? brief : status + ", " + brief));
        }

        @Override
        public boolean isMouseOver(double x, double y) {
            return y >= listY && y < listY + listH && super.isMouseOver(x, y);
        }

        @Override
        public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent e, boolean twice) {
            return e.y() >= listY && e.y() < listY + listH && super.mouseClicked(e, twice);
        }

        @Override
        protected void extractContents(
                net.minecraft.client.gui.GuiGraphicsExtractor g, int x, int y, float tick) {
            if (getY() + getHeight() <= listY || getY() >= listY + listH) return;
            g.enableScissor(listX, listY, listX + listW, listY + listH);
            try {
                drawLook(g, x, y, tick);
            } finally {
                g.disableScissor();
            }
        }

        private void drawLook(
                net.minecraft.client.gui.GuiGraphicsExtractor g, int x, int y, float tick) {
            int bx = getX(), by = getY(), w = getWidth(), h = getHeight();
            if (y < listY || y >= listY + listH) isHovered = false;
            drawButtonFace(g, bx, by, w, h, chosen, isHoveredOrFocused(), active, false);
            if (chosen) g.fill(bx, by, bx + 2, by + h, ACCENT);
            if (isFocused()) g.outline(bx, by, w, h, ACCENT);
            int tx = bx + 8, reserve = status.isEmpty() ? 0 : font.width(status) + 8;
            g.text(
                    font,
                    font.plainSubstrByWidth(
                            getMessage().getString(), Math.max(12, w - (tx - bx) - 8 - reserve)),
                    tx,
                    by + 4,
                    TEXT,
                    false);
            if (!status.isEmpty())
                g.text(
                        font,
                        status,
                        bx + w - font.width(status) - 6,
                        by + 4,
                        status.equals("Applied") ? ACCENT : MUTED,
                        false);
            g.text(
                    font,
                    font.plainSubstrByWidth(brief, Math.max(12, w - (tx - bx) - 8)),
                    tx,
                    by + 14,
                    MUTED,
                    false);
        }
    }

    private void tab(boolean showBuiltIn) {
        if (builtIn == showBuiltIn) return;
        if (builtIn) builtOffset = browserPixels;
        else savedOffset = browserPixels;
        builtIn = showBuiltIn;
        confirmApply = false;
        confirmDelete = false;
        manage = Manage.NONE;
        message = "";
        error = false;
        scroll = 0;
        browserPixels = builtIn ? builtOffset : savedOffset;
        browserScroll.reset(browserPixels, Double.MAX_VALUE, System.nanoTime());
        if (!builtIn
                && library != null
                && (selected == null || !library.names().contains(selected)))
            selected = library.names().isEmpty() ? null : library.names().getFirst();
        rebuildWidgets();
    }

    private boolean available() {
        return builtIn
                ? BuiltinPresets.contains(selectedLook)
                : library != null && selected != null && library.names().contains(selected);
    }

    private boolean completeSelection() {
        return !builtIn || BuiltinPresets.includesSourceProfiles(selectedLook);
    }

    private String selectionTitle() {
        return builtIn
                ? (selectedLook == null ? "Built-in preset" : selectedLook.title)
                : selected == null ? "Saved preset" : selected;
    }

    private String selectionLabel() {
        return builtIn ? selectionTitle() : "My presets / " + selected;
    }

    private SetupPresets.Setup selection() {
        return builtIn
                ? BuiltinPresets.setup(
                        selectedLook,
                        StillBloomClient.config,
                        StillBloomClient.MASKS,
                        StillBloomClient.ITEM_MASKS)
                : library.get(selected);
    }

    private static SetupPresets.Setup capture() {
        return SetupPresets.capture(
                StillBloomClient.config, StillBloomClient.MASKS, StillBloomClient.ITEM_MASKS);
    }

    private static String summary(SetupPresets.Setup setup) {
        var c = setup.settings();
        return c.pixelSize
                + " px / "
                + label(c.bloomShape)
                + " / "
                + setup.masks().profiles().size()
                + " blocks / "
                + setup.itemMasks().items.size()
                + " items";
    }

    private void confirmation() {
        button(
                contentX,
                contentTop,
                contentW,
                20,
                "< Looks",
                () -> {
                    confirmApply = false;
                    scroll = listScroll;
                    rebuildWidgets();
                },
                "Return without applying this setup.");
        viewTop = contentTop + 26;
        int choiceY = contentBottom - 20, half = (contentW - 4) / 2;
        var apply =
                button(
                        contentX,
                        choiceY,
                        half,
                        20,
                        "Apply complete setup",
                        () -> perform(this::applySelected),
                        "Replace settings, all styles and all painted masks. Undo will be available.");
        apply.wrap = true;
        apply.selected = true;
        button(
                contentX + half + 4,
                choiceY,
                contentW - half - 4,
                20,
                "Cancel",
                () -> {
                    confirmApply = false;
                    scroll = listScroll;
                    rebuildWidgets();
                },
                "Keep the current setup and return to Looks.");
        contentBottom = choiceY - 6;
        row = sectionHeading(row, selectionTitle(), "This is a complete setup.");
        row =
                note(
                        row,
                        "Applying replaces bloom settings, every block and item override, and every painted texture mask. Your current master On/Off switch stays. Undo restores your previous setup.");
        if (error) row = note(row, message);
        contentHeight = Math.max(contentHeight, row);
        finishLayout();
    }

    private void applySelected() {
        if (!available()) return;
        var before = capture();
        var previous = PresetStatus.remember(parent);
        var setup = selection();
        if (!apply(setup)) return;
        PresetStatus.recordUndo(parent, before, previous);
        PresetStatus.applied(parent, selectionLabel(), capture());
        confirmApply = false;
        message = "Applied " + selectionTitle() + ". Undo available.";
        error = false;
        focusSelection = true;
    }

    private void undo() {
        var saved = PresetStatus.undo(parent);
        if (saved == null) return;
        if (!apply(saved.setup())) return;
        PresetStatus.restore(parent, saved.status());
        PresetStatus.clearUndo(parent);
        PresetStatus.refresh(parent);
        message = "Previous setup restored.";
        error = false;
        confirmApply = false;
        focusSelection = true;
    }

    private void startPreview() {
        if (trial != null || !available() || minecraft.level == null) return;
        normalScroll = browserOffset;
        var before = capture();
        var status = PresetStatus.remember(parent);
        var target = selection();
        trial = new Trial(before, status, target, selectionLabel());
        try {
            parent.previewSetup(target);
            scroll = 0;
            message = "";
            error = false;
        } catch (RuntimeException e) {
            restoreTrial();
            throw e;
        }
    }

    private void restoreTrial() {
        if (trial == null) return;
        var previous = trial;
        trial = null;
        parent.previewSetup(previous.before());
        PresetStatus.restore(parent, previous.status());
    }

    private void cancelPreview() {
        restoreTrial();
        scroll = 0;
        browserOffset = normalScroll;
        message = "Trial cancelled. Previous look restored.";
        error = false;
        focusSelection = true;
        rebuildWidgets();
    }

    private void keepPreview() {
        if (trial == null) return;
        var chosen = trial;
        // I keep the rollback copy until saving and applying both succeed.
        if (!apply(chosen.target())) return;
        trial = null;
        PresetStatus.recordUndo(parent, chosen.before(), chosen.status());
        PresetStatus.applied(parent, chosen.label(), capture());
        message = "Preset applied. Undo available.";
        error = false;
        scroll = 0;
        browserOffset = normalScroll;
        focusSelection = true;
    }

    private boolean apply(SetupPresets.Setup setup) {
        if (parent.applySetup(setup)) return true;
        message = "Could not save this setup. " + parent.saveError();
        error = true;
        return false;
    }

    private void cyclePreview(int direction) {
        if (trial == null || !available()) return;
        var original = trial;
        parent.previewSetup(original.before());
        if (builtIn) {
            var looks = BuiltinPresets.looks();
            selectedLook =
                    looks.get(Math.floorMod(looks.indexOf(selectedLook) + direction, looks.size()));
        } else {
            var names = library.names();
            if (names.isEmpty()) return;
            selected = names.get(Math.floorMod(names.indexOf(selected) + direction, names.size()));
        }
        var target = selection();
        trial = new Trial(original.before(), original.status(), target, selectionLabel());
        parent.previewSetup(target);
        scroll = 0;
    }

    private void previewControls() {
        int half = (contentW - 4) / 2;
        var keep =
                button(
                        contentX,
                        contentTop,
                        half,
                        20,
                        "Keep preset",
                        () -> perform(this::keepPreview),
                        "Save this preview as the applied setup. Undo will restore your previous look.");
        keep.wrap = true;
        var cancel =
                button(
                        contentX + half + 4,
                        contentTop,
                        contentW - half - 4,
                        20,
                        "Cancel preview",
                        this::cancelPreview,
                        "Restore the complete previous setup without saving the trial. Escape and Studio also cancel.");
        cancel.wrap = true;
        int choicesY = contentTop + 24;
        button(
                contentX,
                choicesY,
                half,
                20,
                "Previous",
                () -> perform(() -> cyclePreview(-1)),
                "Try the previous preset without changing the cancellation checkpoint.");
        button(
                contentX + half + 4,
                choicesY,
                contentW - half - 4,
                20,
                "Next",
                () -> perform(() -> cyclePreview(1)),
                "Try the next preset. Keep or cancel when ready.");
        viewTop = choicesY + 26;
        row = note(row, "Preview: " + selectionTitle());
        row =
                note(
                        row,
                        builtIn
                                ? selectedLook.description
                                : "Full setup: settings, overrides and masks.");
        row = note(row, "Temporary. Keep commits this look; Cancel restores your previous setup.");
        if (error) row = note(row, message);
        contentHeight = Math.max(contentHeight, row);
        finishLayout();
    }

    private void enterManage(Manage mode) {
        listScroll = scroll;
        manage = mode;
        confirmApply = false;
        confirmDelete = false;
        draft = mode == Manage.EDIT && selected != null ? selected : "";
        message = "";
        error = false;
        scroll = 0;
        rebuildWidgets();
    }

    private void leaveManage() {
        manage = Manage.NONE;
        confirmApply = false;
        confirmDelete = false;
        message = "";
        error = false;
        scroll = listScroll;
        rebuildWidgets();
    }

    private void management() {
        button(
                contentX,
                contentTop,
                contentW,
                20,
                "< Looks",
                this::leaveManage,
                "Return to look selection. Current settings stay.");
        viewTop = contentTop + 26;
        row =
                note(
                        row,
                        manage == Manage.SAVE
                                ? "Save the current settings, block/item styles and painted masks as a separate look."
                                : "Manage this library entry. Your currently applied look is kept.");
        if (error) row = note(row, message);
        if (library == null) {
            row = note(row, "Saved library unavailable. The original file is kept.");
            finishLayout();
            return;
        }
        var nameRow = row(row, "Preset name", "Use a name you will recognize.", () -> "");
        var name =
                new EditBox(
                        font,
                        nameRow.controlX(),
                        0,
                        nameRow.controlW(),
                        20,
                        Component.literal("Preset name"));
        name.setMaxLength(48);
        name.setHint(
                Component.literal("Name your look...")
                        .withStyle(style -> style.withColor(MUTED & 0xFFFFFF)));
        name.setValue(draft);
        name.setResponder(
                value -> {
                    draft = value;
                    confirmDelete = false;
                });
        scrolling(name, nameRow.controlY());
        row = nameRow.y() + nameRow.height();
        setInitialFocus(name);
        if (manage == Manage.SAVE) {
            var save =
                    new MenuButton(
                            contentX,
                            0,
                            contentW,
                            20,
                            "Save current setup",
                            () ->
                                    perform(
                                            () -> {
                                                selected = library.add(draft, capture());
                                                builtIn = false;
                                                manage = Manage.NONE;
                                                savedCache.clear();
                                                message =
                                                        "Saved. Apply or Try in world when ready.";
                                                error = false;
                                                scroll = listScroll;
                                                focusSelection = true;
                                            }));
            scrolling(save, row);
            row += 26;
        } else {
            var rename =
                    new MenuButton(
                            contentX,
                            0,
                            contentW,
                            20,
                            "Rename preset",
                            () ->
                                    perform(
                                            () -> {
                                                String previous = selected;
                                                selected = library.rename(selected, draft);
                                                draft = selected;
                                                savedCache.clear();
                                                PresetStatus.rename(
                                                        parent,
                                                        "My presets / " + previous,
                                                        "My presets / " + selected);
                                                message = "Preset renamed.";
                                                error = false;
                                                confirmDelete = false;
                                            }));
            scrolling(rename, row);
            row += 26;
            row = sectionHeading(row, "Share", "Export this complete setup as a text code.");
            var export =
                    new MenuButton(
                            contentX,
                            0,
                            contentW,
                            20,
                            "Copy preset",
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
                                                            "Clipboard unavailable. Try Copy again.");
                                                message = "Preset copied. Friends choose Import.";
                                                error = false;
                                                confirmDelete = false;
                                            }));
            scrolling(export, row);
            row += 26;
            row =
                    sectionHeading(
                            row,
                            "Delete saved entry",
                            "Removes only this library entry. Your applied settings and masks stay.");
            var delete =
                    new MenuButton(
                            contentX,
                            0,
                            contentW,
                            20,
                            confirmDelete ? "Confirm delete" : "Delete preset",
                            () ->
                                    perform(
                                            () -> {
                                                if (!confirmDelete) {
                                                    confirmDelete = true;
                                                    message =
                                                            "Press Confirm delete to remove this saved entry.";
                                                } else {
                                                    library.delete(selected);
                                                    savedCache.clear();
                                                    selected =
                                                            library.names().isEmpty()
                                                                    ? null
                                                                    : library.names().getFirst();
                                                    manage = Manage.NONE;
                                                    confirmDelete = false;
                                                    message =
                                                            "Saved entry deleted. Applied look kept.";
                                                    scroll = listScroll;
                                                    focusSelection = true;
                                                }
                                            }));
            scrolling(delete, row);
            row += 26;
        }
        contentHeight = Math.max(contentHeight, row);
        finishLayout();
    }

    private boolean confirmDelete;

    private void importPreset() throws IOException {
        if (library == null) return;
        selected = library.importCode(minecraft.keyboardHandler.getClipboard());
        savedCache.clear();
        builtIn = false;
        manage = Manage.NONE;
        message = "Imported. Apply or Try in world when ready.";
        error = false;
        confirmDelete = false;
        focusSelection = true;
    }

    @FunctionalInterface
    private interface Action {
        void run() throws IOException;
    }

    private void perform(Action action) {
        try {
            action.run();
        } catch (IOException e) {
            message = "Could not save presets. The original file is kept; check the log.";
            error = true;
            StillBloomClient.LOGGER.error(message, e);
        } catch (IllegalArgumentException e) {
            message =
                    e.getMessage() == null
                            ? "Could not use this preset."
                            : e.getMessage().replace("Still Bloom", "Fluorescent");
            error = true;
        }
        if (minecraft.gui.screen() == this) rebuildWidgets();
    }
}
