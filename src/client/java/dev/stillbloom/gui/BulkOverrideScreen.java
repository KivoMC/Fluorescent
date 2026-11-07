package dev.stillbloom.gui;

import dev.stillbloom.StillBloomClient;
import dev.stillbloom.config.BloomConfig;
import dev.stillbloom.config.BloomOverride;
import dev.stillbloom.gui.layout.SmoothScroll;
import dev.stillbloom.mask.BlockLinks;
import dev.stillbloom.mask.BulkOverrides;
import dev.stillbloom.model.BlockCatalog;
import dev.stillbloom.preset.StylePresets;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.io.IOException;
import java.util.*;

final class BulkOverrideScreen extends BloomMenuScreen {
    private enum Page {
        BLOCKS,
        STYLE,
        REVIEW
    }

    private enum Picker {
        NONE,
        BLOCK,
        PRESET
    }

    private final BloomSettingsScreen parent;
    private final Set<String> selected = new LinkedHashSet<>();
    private final List<MenuButton> rows = new ArrayList<>();
    private List<String> blocks = List.of(), filtered = List.of();
    private StylePresets library;
    private BloomOverride draft = new BloomOverride();
    private BulkOverrides.Part part = BulkOverrides.Part.COLOR;
    private BulkOverrides.Undo undo;
    private Page page = Page.BLOCKS;
    private Picker picker = Picker.NONE;
    private String query = "",
            pickerQuery = "",
            source = "Custom draft",
            message = "1. Choose block types. 2. Set a change. 3. Review and apply.";
    private int offset, nextY, targetOffset, listPixels;
    private final SmoothScroll listScroll = new SmoothScroll();
    private String listContext;
    private boolean listDrag;
    private double listGrab;
    private boolean feedback;
    private EditBox search;
    private MenuButton selectFiltered, clearSelection;
    private String namespace = "";
    private final Map<String, BlockLinksScreen.Session> linkSessions = new LinkedHashMap<>();

    private List<String> affected() {
        var ids = new TreeSet<String>();
        for (var id : selected) ids.addAll(BlockLinks.members(StillBloomClient.MASKS, id));
        return List.copyOf(ids);
    }

    private boolean linksReady() {
        return selected.stream()
                .allMatch(
                        id ->
                                linkSessions
                                        .computeIfAbsent(id, BlockLinksScreen.Session::new)
                                        .ready());
    }

    BulkOverrideScreen(BloomSettingsScreen parent) {
        this(parent, null);
    }

    BulkOverrideScreen(BloomSettingsScreen parent, String sourceBlock) {
        super("Bulk block overrides");
        this.parent = parent;
        if (sourceBlock != null) {
            copyBlock(sourceBlock);
            part = BulkOverrides.Part.STYLE;
        }
        try {
            library =
                    StylePresets.load(
                            StillBloomClient.CONFIG_PATH.resolveSibling("stillbloom-presets.json"));
        } catch (IOException e) {
            message = "Block-style library unavailable; block copying still works.";
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
        return picker == Picker.BLOCK
                ? "Copy from a block"
                : picker == Picker.PRESET
                        ? "Choose saved style"
                        : "Batch edit / " + selected.size() + " blocks";
    }

    @Override
    protected void navigate(int page) {
        parent.navigate(page);
        if (page != 0) minecraft.gui.setScreen(parent);
    }

    @Override
    public void onClose() {
        parent.refreshBlockStyle();
        minecraft.gui.setScreen(parent);
    }

    @Override
    protected Object scrollContext() {
        return page + "/" + picker;
    }

    @Override
    protected String footer() {
        return "Draft until Apply";
    }

    @Override
    protected void init() {
        frame();
        rows.clear();
        nextY = 0;
        selectFiltered = clearSelection = null;
        if (blocks.isEmpty())
            blocks =
                    BuiltInRegistries.BLOCK.keySet().stream()
                            .map(Object::toString)
                            .sorted()
                            .toList();
        if (picker != Picker.NONE) {
            picker();
            return;
        }
        int tabW = (contentW - 8) / 3;
        String[] names = {"1 Select", "2 Change", "3 Apply"};
        for (int i = 0; i < 3; i++) {
            final Page target = Page.values()[i];
            button(
                                    contentX + i * (tabW + 4),
                                    contentTop,
                                    i == 2 ? contentW - 2 * (tabW + 4) : tabW,
                                    20,
                                    names[i],
                                    () -> {
                                        if (page == Page.BLOCKS) targetOffset = listPixels;
                                        page = target;
                                        scroll = 0;
                                        offset = page == Page.BLOCKS ? targetOffset : 0;
                                        rebuildWidgets();
                                    },
                                    "Review and apply explicitly. Changing tabs does not change any block.")
                            .selected =
                    page == target;
        }
        if (page == Page.BLOCKS) targets();
        else if (page == Page.STYLE) style();
        else review();
    }

    private void targets() {
        search(
                contentTop + 26,
                query,
                s -> {
                    query = s;
                    targetOffset = 0;
                    offset = 0;
                    filter();
                });
        int half = (contentW - 4) / 2;
        selectFiltered =
                button(
                        contentX,
                        contentTop + 52,
                        half,
                        20,
                        "Select filtered",
                        () -> {
                            selected.addAll(filtered);
                            listRows();
                        },
                        "Add every block matching this search. Existing selections, including hidden ones, are kept.");
        clearSelection =
                button(
                        contentX + half + 4,
                        contentTop + 52,
                        contentW - half - 4,
                        20,
                        "Clear selection",
                        () -> {
                            selected.clear();
                            listRows();
                        },
                        "Clear all selected targets, including those hidden by the search. Does not change block settings.");
        viewTop = contentTop + 78;
        filter();
    }

    private void search(int y, String value, java.util.function.Consumer<String> changed) {
        int filterW = picker == Picker.PRESET ? 0 : Math.min(150, contentW / 3);
        search =
                new EditBox(
                        font,
                        contentX,
                        y,
                        contentW - (filterW == 0 ? 0 : filterW + 4),
                        20,
                        Component.literal(
                                picker == Picker.PRESET ? "Search saved styles" : "Search blocks"));
        search.setHint(
                Component.literal(
                                picker == Picker.PRESET
                                        ? "Search saved styles..."
                                        : "Search blocks...")
                        .withStyle(style -> style.withColor(MUTED & 0xFFFFFF)));
        search.setMaxLength(128);
        search.setValue(value);
        search.setResponder(changed);
        addRenderableWidget(search);
        setInitialFocus(search);
        if (filterW > 0)
            button(
                    contentX + contentW - filterW,
                    y,
                    filterW,
                    20,
                    BlockCatalog.current().sourceName(namespace) + " v",
                    () ->
                            minecraft.gui.setScreen(
                                    new BlockSourceFilterScreen(
                                            this,
                                            namespace,
                                            chosen -> {
                                                namespace = chosen;
                                                offset = 0;
                                                targetOffset = 0;
                                            })),
                    "Search within All Blocks, Vanilla, or a mod. Existing selections stay selected.");
    }

    private void filter() {
        String q = (picker == Picker.NONE ? query : pickerQuery).strip().toLowerCase(Locale.ROOT);
        var candidates =
                picker == Picker.PRESET
                        ? (library == null ? List.<String>of() : library.names())
                        : blocks;
        if (!BlockCatalog.current().hasSource(namespace)) namespace = "";
        filtered =
                picker == Picker.PRESET
                        ? candidates.stream()
                                .filter(id -> id.toLowerCase(Locale.ROOT).contains(q))
                                .toList()
                        : BlockCatalog.current().search(q, namespace, id -> true);
        String context = page + "/" + picker + "/" + namespace + "/" + q;
        long now = System.nanoTime();
        if (!Objects.equals(context, listContext)) {
            listContext = context;
            listScroll.reset(
                    picker == Picker.NONE && page == Page.BLOCKS ? targetOffset : 0,
                    listMaximum(),
                    now);
        } else listScroll.bounds(listMaximum(), now);
        listPixels = listScroll.pixels(now);
        offset = listPixels / 34;
        listRows();
    }

    private boolean hasList() {
        return picker != Picker.NONE || page == Page.BLOCKS;
    }

    private int listMaximum() {
        return Math.max(0, filtered.size() * 34 - 3 - (contentBottom - viewTop));
    }

    private void listRows() {
        boolean owned = rows.contains(getFocused());
        String focusedId = owned ? ((MenuButton) getFocused()).settingId : null;
        for (var b : rows) removeWidget(b);
        rows.clear();
        int count = Math.max(1, (contentBottom - viewTop) / 34) + 2;
        offset = listPixels / 34;
        for (int i = 0; i < count && offset + i < filtered.size(); i++) {
            String id = filtered.get(offset + i);
            boolean preset = picker == Picker.PRESET;
            var b =
                    button(
                            contentX,
                            viewTop + i * 34 - listPixels % 34,
                            contentW,
                            31,
                            preset ? id : blockName(id),
                            () -> {
                                if (picker == Picker.BLOCK) {
                                    copyBlock(id);
                                    closePicker();
                                } else if (picker == Picker.PRESET) {
                                    draft = library.get(id);
                                    source = "Saved style: " + id;
                                    message =
                                            "Saved style copied to draft. Review before applying.";
                                    closePicker();
                                } else {
                                    if (!selected.add(id)) selected.remove(id);
                                    listRows();
                                }
                            },
                            preset
                                    ? "Copy this saved style into the draft. Blocks stay unchanged until Apply."
                                    : id
                                            + (picker == Picker.NONE
                                                    ? " - click to select or deselect this target."
                                                    : " - copy its override, including settings that inherit Global. Masks and source strength are not copied."));
            b.secondary =
                    preset
                            ? "Saved style"
                            : (picker == Picker.NONE && selected.contains(id) ? "Selected | " : "")
                                    + id;
            b.settingId = "bulk/" + id;
            b.selected = picker == Picker.NONE && selected.contains(id);
            b.clipTo(viewTop, contentBottom);
            b.visible = b.getY() < contentBottom && b.getY() + b.getHeight() > viewTop;
            rows.add(b);
        }
        if (owned)
            setFocused(
                    rows.stream()
                            .filter(b -> b.visible && Objects.equals(b.settingId, focusedId))
                            .findFirst()
                            .orElse(null));
        if (selectFiltered != null) {
            selectFiltered.setMessage(Component.literal("Select " + filtered.size()));
            selectFiltered.active = !filtered.isEmpty();
            clearSelection.active = !selected.isEmpty();
        }
        if (picker == Picker.NONE && page == Page.BLOCKS) targetOffset = listPixels;
        arrows(listPixels > 0, listPixels < listMaximum());
    }

    private void picker() {
        button(
                contentX,
                contentTop,
                contentW,
                20,
                "< Back to draft",
                this::closePicker,
                "Return without choosing a source. Your target selection is kept.");
        search(
                contentTop + 26,
                pickerQuery,
                s -> {
                    pickerQuery = s;
                    offset = 0;
                    filter();
                });
        viewTop = contentTop + 52;
        filter();
    }

    private void openPicker(Picker next) {
        picker = next;
        pickerQuery = "";
        offset = 0;
        scroll = 0;
        rebuildWidgets();
    }

    private void closePicker() {
        picker = Picker.NONE;
        page = Page.STYLE;
        offset = 0;
        scroll = 0;
        rebuildWidgets();
    }

    private void copyBlock(String id) {
        var p = StillBloomClient.MASKS.profile(id);
        draft = BloomOverride.copyOf(p == null ? null : p.override);
        source = "Block: " + id;
        message = "Copied override. Global settings still inherit; masks are kept.";
    }

    private void style() {
        viewTop = contentTop + 26;
        action(
                "Change",
                partLabel(),
                () -> {
                    part =
                            BulkOverrides.Part.values()[
                                    (part.ordinal() + 1) % BulkOverrides.Part.values().length];
                    scroll = 0;
                    rebuildWidgets();
                },
                "Color only changes bloom color. Shape only changes the shape. Complete style replaces all style fields, including inherited settings; masks and source strength are always kept.");
        info("Draft source", source);
        if (part == BulkOverrides.Part.COLOR) {
            action(
                    "Bloom color",
                    color(),
                    () ->
                            minecraft.gui.setScreen(
                                    new BloomColorScreen(
                                            this,
                                            "Bulk draft - Apply in Review",
                                            draft.color,
                                            value -> {
                                                draft.color = value;
                                                source = "Custom color draft";
                                            },
                                            () -> {})),
                    "Open the full color picker. This edits only the draft; choose Apply on Review to change blocks.");
            action(
                    "Texture color",
                    "Natural color",
                    () -> {
                        draft.color = null;
                        source = "Natural color draft";
                        rebuildWidgets();
                    },
                    "Clear only the color override on selected targets. Keep their shapes and other settings.");
        } else if (part == BulkOverrides.Part.SHAPE) {
            action(
                    "Bloom shape",
                    shape(),
                    () -> {
                        var values = BloomConfig.Shape.values();
                        draft.shape =
                                draft.shape == null
                                        ? values[0]
                                        : draft.shape.ordinal() + 1 < values.length
                                                ? values[draft.shape.ordinal() + 1]
                                                : null;
                        source = "Custom shape draft";
                        rebuildWidgets();
                    },
                    "Cycle Square, Soft, Diamond, Starburst and Global. Global continues to follow future global changes.");
            action(
                    "Inheritance",
                    "Use global shape",
                    () -> {
                        draft.shape = null;
                        source = "Global shape draft";
                        rebuildWidgets();
                    },
                    "Clear only the shape override; all other target settings remain.");
        } else {
            info("Shape / color", shape() + " / " + color());
            info("Strength / width", value(draft.intensity) + " / " + value(draft.width));
            info(
                    "Flare / detail",
                    (draft.flare == null ? "Global" : label(draft.flare))
                            + " / "
                            + value(draft.preserveDetail));
            info(
                    "Flare strength / reach",
                    value(draft.flareStrength) + " / " + value(draft.flareLength));
        }
        action(
                "Source",
                "Copy block style",
                () -> openPicker(Picker.BLOCK),
                "Choose a block to copy its current nullable overrides into the draft. This does not select it as a target.");
        action(
                                "Saved styles",
                                "Choose saved style",
                                () -> openPicker(Picker.PRESET),
                                "Copy a saved style into the draft. Presets contain saved values; older presets can inherit block detail.")
                        .active =
                library != null && !library.names().isEmpty();
        if (library != null && library.names().isEmpty())
            info("Saved styles", "Save a style from a block's tools first.");
        action(
                "Next",
                "Review " + selected.size() + " blocks",
                () -> {
                    page = Page.REVIEW;
                    scroll = 0;
                    rebuildWidgets();
                },
                "See the source, scope and selected blocks before committing.");
        finishLayout();
    }

    private void review() {
        boolean canUndo = undo != null && undo.available(StillBloomClient.MASKS);
        int undoW = canUndo ? 58 : 0;
        var apply =
                button(
                        contentX,
                        contentTop + 26,
                        contentW - (canUndo ? undoW + 4 : 0),
                        20,
                        "Apply to " + affected().size(),
                        this::applyBatch,
                        "Apply "
                                + partLabel().toLowerCase(Locale.ROOT)
                                + " from "
                                + source
                                + " to selected blocks and their linked members. Painted masks and source strength stay unchanged.");
        apply.active = !selected.isEmpty() && linksReady();
        if (canUndo)
            button(
                    contentX + contentW - undoW,
                    contentTop + 26,
                    undoW,
                    20,
                    "Undo",
                    this::undoBatch,
                    "Undo the last applied batch. Available until another configuration edit; saves once and preserves masks.");
        viewTop = contentTop + 52;
        if (!linksReady()) {
            info(
                    "Linked configurations",
                    "Checking automatic matches. Existing conflicts need a source before applying.");
            for (var id : selected) {
                var session = linkSessions.computeIfAbsent(id, BlockLinksScreen.Session::new);
                if (!session.ready() && session.conflict())
                    action(
                            blockName(id),
                            "Resolve links",
                            () ->
                                    minecraft.gui.setScreen(
                                            new BlockLinksScreen(this, id, session.candidates())),
                            session.help());
            }
        }
        info("Scope", affected().size() + " blocks, including all linked members.");
        if (feedback) info("Status", message);
        info(
                "Apply",
                partLabel()
                        + ": "
                        + (part == BulkOverrides.Part.COLOR
                                ? color()
                                : part == BulkOverrides.Part.SHAPE ? shape() : "all style fields"));
        info("Source", source);
        if (part == BulkOverrides.Part.STYLE) {
            info("Shape / color", shape() + " / " + color());
            info("Strength / width", value(draft.intensity) + " / " + value(draft.width));
            info("Flare", draft.flare == null ? "Global" : label(draft.flare));
            info(
                    "Flare strength / reach",
                    value(draft.flareStrength) + " / " + value(draft.flareLength));
            info("Keep block detail", value(draft.preserveDetail));
        }
        if (!feedback) info("Status", message);
        info(
                "Kept",
                part == BulkOverrides.Part.STYLE
                        ? "Painted masks and source strength"
                        : "Other overrides, painted masks and source strength");
        if (selected.isEmpty()) info("Targets", "Choose targets in step 1: Select.");
        for (String id : selected) info(blockName(id), id);
        finishLayout();
    }

    private void applyBatch() {
        if (!linksReady()) return;
        feedback = true;
        try {
            var result =
                    BulkOverrides.apply(
                            StillBloomClient.MASKS,
                            selected,
                            draft,
                            part,
                            m -> m.save(StillBloomClient.MASK_PATH));
            if (result.changed() > 0) {
                PresetStatus.changed();
                undo = result.undo();
                StillBloomClient.RENDERER.sourcesChanged();
                message = "Applied to " + result.changed() + " blocks. Undo is available.";
            } else message = "Already matches: no block settings changed.";
        } catch (IllegalArgumentException | IllegalStateException e) {
            message = e.getMessage();
        }
        scroll = 0;
        rebuildWidgets();
    }

    @Override
    public void tick() {
        if (page != Page.REVIEW || picker != Picker.NONE) return;
        for (var id : selected) {
            var session = linkSessions.computeIfAbsent(id, BlockLinksScreen.Session::new);
            if (!session.ready() && !session.conflict()) {
                if (session.tick()) rebuildWidgets();
                break;
            }
        }
    }

    private void undoBatch() {
        feedback = true;
        try {
            int count = undo.apply(StillBloomClient.MASKS, m -> m.save(StillBloomClient.MASK_PATH));
            if (count > 0) PresetStatus.changed();
            StillBloomClient.RENDERER.sourcesChanged();
            message = "Restored " + count + " blocks.";
            undo = null;
        } catch (IllegalStateException e) {
            message = e.getMessage();
        }
        scroll = 0;
        rebuildWidgets();
    }

    private MenuButton action(String title, String label, Runnable action, String help) {
        var r = row(nextY, title, help, () -> "");
        nextY += r.height();
        var b = new MenuButton(r.controlX(), 0, r.controlW(), 20, label, action);
        b.setTooltip(Tooltip.create(Component.literal(help)));
        return scrolling(b, r.controlY());
    }

    private void info(String title, String text) {
        nextY = note(nextY, title + ": " + text);
    }

    private String partLabel() {
        return switch (part) {
            case COLOR -> "Color only";
            case SHAPE -> "Shape only";
            case STYLE -> "Complete style";
        };
    }

    private String color() {
        return draft.color == null ? "Natural" : String.format(Locale.ROOT, "#%06X", draft.color);
    }

    private String shape() {
        return draft.shape == null ? "Global" : label(draft.shape);
    }

    private static String value(Float v) {
        return v == null ? "Global" : String.format(Locale.ROOT, "%.2f", v);
    }

    private static String blockName(String id) {
        return BuiltInRegistries.BLOCK.getValue(Identifier.parse(id)).getName().getString();
    }

    private void updateListScroll() {
        long now = System.nanoTime();
        listScroll.bounds(listMaximum(), now);
        int px = listScroll.pixels(now), first = px / 34;
        boolean changed = first != offset;
        listPixels = px;
        offset = first;
        if (changed) listRows();
        else
            for (int i = 0; i < rows.size(); i++) {
                var b = rows.get(i);
                b.setY(viewTop + i * 34 - px % 34);
                b.visible = b.getY() < contentBottom && b.getY() + b.getHeight() > viewTop;
            }
        if (picker == Picker.NONE && page == Page.BLOCKS) targetOffset = px;
        arrows(px > 0, px < listMaximum());
    }

    private boolean inList(double x, double y) {
        return hasList()
                && x >= contentX
                && x <= contentX + contentW + 7
                && y >= viewTop
                && y < contentBottom;
    }

    private int listThumb() {
        int h = contentBottom - viewTop;
        return Math.min(h, Math.max(10, h * h / Math.max(1, filtered.size() * 34 - 3)));
    }

    @Override
    protected void scrollBy(int amount) {
        if (hasList()) {
            listScroll.reset(
                    listScroll.target() + (amount > 0 ? 102 : -102),
                    listMaximum(),
                    System.nanoTime());
            updateListScroll();
        } else super.scrollBy(amount);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        if (isClosing()) return true;
        if (inList(x, y)) {
            listScroll.wheel(-vertical * 102, listMaximum(), System.nanoTime());
            return true;
        }
        return hasList() ? false : super.mouseScrolled(x, y, horizontal, vertical);
    }

    @Override
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent e, boolean twice) {
        if (isClosing()) return true;
        if (e.button() == com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT
                && inList(e.x(), e.y())
                && e.x() >= contentX + contentW) {
            int thumb = listThumb();
            double top =
                    viewTop
                            + (contentBottom - viewTop - thumb)
                                    * listPixels
                                    / (double) Math.max(1, listMaximum());
            listGrab = e.y() >= top && e.y() < top + thumb ? e.y() - top : thumb / 2.0;
            listDrag = true;
            moveListBar(e.y());
            return true;
        }
        return super.mouseClicked(e, twice);
    }

    private void moveListBar(double y) {
        listScroll.reset(
                (y - viewTop - listGrab)
                        / Math.max(1, contentBottom - viewTop - listThumb())
                        * listMaximum(),
                listMaximum(),
                System.nanoTime());
        updateListScroll();
    }

    @Override
    public boolean mouseDragged(
            net.minecraft.client.input.MouseButtonEvent e, double dx, double dy) {
        if (isClosing()) return true;
        if (listDrag) {
            moveListBar(e.y());
            return true;
        }
        return super.mouseDragged(e, dx, dy);
    }

    @Override
    public boolean mouseReleased(net.minecraft.client.input.MouseButtonEvent e) {
        listDrag = false;
        return super.mouseReleased(e);
    }

    @Override
    protected void extractWorkspaceBackground(GuiGraphicsExtractor g, int x, int y, float tick) {
        if (hasList()) updateListScroll();
    }

    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
        if (isClosing()) return true;
        if (event.key() == com.mojang.blaze3d.platform.InputConstants.KEY_ESCAPE
                && picker != Picker.NONE) {
            closePicker();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int x, int y, float tick) {
        super.extractRenderState(g, x, y, tick);
        if (picker != Picker.NONE || page == Page.BLOCKS) {
            if (filtered.isEmpty())
                g.textWithWordWrap(
                        font,
                        Component.literal("No matches. Try a shorter name."),
                        contentX + 4,
                        viewTop + 8,
                        contentW - 8,
                        MUTED);
            int h = contentBottom - viewTop, count = Math.max(1, h / 34);
            if (listMaximum() > 0) {
                int thumb = listThumb(), top = viewTop + (h - thumb) * listPixels / listMaximum();
                g.fill(
                        contentX + contentW + 3,
                        viewTop,
                        contentX + contentW + 5,
                        contentBottom,
                        0xFF17212C);
                g.fill(contentX + contentW + 3, top, contentX + contentW + 5, top + thumb, ACCENT);
            }
        }
    }
}
