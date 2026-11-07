package dev.stillbloom.gui;

import dev.stillbloom.StillBloomClient;
import dev.stillbloom.config.BloomConfig;
import dev.stillbloom.config.BloomOverride;
import dev.stillbloom.gui.layout.SmoothScroll;
import dev.stillbloom.mask.TextureMasks;
import dev.stillbloom.model.ItemAppearance;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

import java.util.*;
import java.util.function.*;

// I leave item and block profiles untouched when someone is only browsing.
public final class ItemSettingsScreen extends BloomMenuScreen {
    private final Screen parent;
    private ItemStack selected = ItemStack.EMPTY;
    private String selectedId, query = "";
    private BloomOverride editing;
    private boolean customOnly, preview, pending, saveFailed;
    private int nextY, ticks, fullScroll, previewScroll;
    private boolean confirmReset, advanced;
    private List<String> itemIds = List.of(), filtered = List.of();
    private final List<MenuButton> rows = new ArrayList<>();
    private final SmoothScroll itemScroll = new SmoothScroll();
    private EditBox itemSearch;
    private int rowStart = -1, rowCount = -1, itemPixels;
    private boolean itemScrollDrag;
    private double thumbGrab;

    public ItemSettingsScreen(Screen parent) {
        super("Item bloom");
        this.parent = parent;
    }

    public ItemSettingsScreen(Screen parent, ItemStack item) {
        this(parent);
        if (item != null && !item.isEmpty()) select(item);
    }

    public ItemSettingsScreen withPreview() {
        preview = true;
        return this;
    }

    @Override
    protected boolean focusedTool() {
        return true;
    }

    @Override
    protected int section() {
        return 4;
    }

    @Override
    protected String heading() {
        return saveFailed
                ? "Save failed: check log"
                : selectedId == null
                        ? "Held items"
                        : "Item / " + selected.getHoverName().getString();
    }

    @Override
    protected boolean canPreview() {
        return selectedId != null && minecraft != null && minecraft.level != null;
    }

    @Override
    protected boolean previewMode() {
        return preview && canPreview();
    }

    @Override
    protected void togglePreview() {
        save();
        if (previewMode()) previewScroll = scroll;
        else fullScroll = scroll;
        preview = !preview;
        scroll = preview ? previewScroll : fullScroll;
        rebuildWidgets();
    }

    @Override
    protected BloomSettingsScreen settingsOwner() {
        return findSettingsOwner(parent);
    }

    @Override
    protected void navigate(int page) {
        save();
        var menu = parent instanceof BloomSettingsScreen s ? s : new BloomSettingsScreen(parent);
        minecraft.gui.setScreen(menu);
        menu.navigate(page);
    }

    @Override
    protected void init() {
        frame();
        rows.clear();
        rowStart = -1;
        rowCount = -1;
        itemScrollDrag = false;
        nextY = 0;
        if (selectedId == null) browser();
        else {
            controls();
            finishLayout();
        }
    }

    private void select(ItemStack item) {
        selected = item.copy();
        selectedId = BuiltInRegistries.ITEM.getKey(selected.getItem()).toString();
        var profile = StillBloomClient.ITEM_MASKS.profile(selectedId);
        editing =
                profile == null || profile.override == null
                        ? new BloomOverride()
                        : profile.override;
        scroll = 0;
    }

    private void selectRegistry(String id) {
        if (minecraft.player != null) {
            for (var held :
                    List.of(
                            minecraft.player.getMainHandItem(),
                            minecraft.player.getOffhandItem())) {
                if (!held.isEmpty()
                        && BuiltInRegistries.ITEM.getKey(held.getItem()).toString().equals(id)) {
                    select(held);
                    rebuildWidgets();
                    return;
                }
            }
        }
        select(BuiltInRegistries.ITEM.getValue(Identifier.parse(id)).getDefaultInstance());
        rebuildWidgets();
    }

    private static String itemName(String id) {
        return BuiltInRegistries.ITEM
                .getValue(Identifier.parse(id))
                .getDefaultInstance()
                .getHoverName()
                .getString();
    }

    private static boolean customized(String id) {
        var p = StillBloomClient.ITEM_MASKS.profile(id);
        return p != null
                && (!p.textures.isEmpty()
                        || p.strength != 1
                        || p.override != null && !p.override.empty());
    }

    private void browser() {
        int filterWidth = contentW < 230 ? 64 : 94;
        var search =
                new EditBox(
                        font,
                        contentX,
                        contentTop,
                        contentW - filterWidth - 5,
                        20,
                        Component.literal("Search items"));
        itemSearch = search;
        search.setHint(
                Component.literal("Search items...")
                        .withStyle(style -> style.withColor(MUTED & 0xFFFFFF)));
        search.setMaxLength(128);
        search.setValue(query);
        addRenderableWidget(search);
        button(
                                contentX + contentW - filterWidth,
                                contentTop,
                                filterWidth,
                                20,
                                customOnly
                                        ? (contentW < 230 ? "Custom" : "Custom only")
                                        : "All items",
                                () -> {
                                    customOnly = !customOnly;
                                    itemScroll.reset(0, 0, System.nanoTime());
                                    rebuildWidgets();
                                },
                                "Show items you have painted or customized here. Changes made in Blocks are not included.")
                        .selected =
                customOnly;
        int half = (contentW - 4) / 2;
        quickHand(contentX, contentTop + 26, half, "Main hand", true);
        quickHand(contentX + half + 4, contentTop + 26, contentW - half - 4, "Offhand", false);
        viewTop = contentTop + 52;
        if (itemIds.isEmpty())
            itemIds =
                    BuiltInRegistries.ITEM.keySet().stream()
                            .map(Object::toString)
                            .filter(id -> !id.equals("minecraft:air"))
                            .sorted()
                            .toList();
        search.setResponder(
                value -> {
                    query = value;
                    itemScroll.reset(0, 0, System.nanoTime());
                    filter();
                });
        filter();
        setInitialFocus(search);
    }

    private void quickHand(int x, int y, int w, String label, boolean main) {
        ItemStack stack =
                minecraft.player == null
                        ? ItemStack.EMPTY
                        : main
                                ? minecraft.player.getMainHandItem()
                                : minecraft.player.getOffhandItem();
        button(
                                x,
                                y,
                                w,
                                20,
                                label,
                                () -> {
                                    select(stack);
                                    rebuildWidgets();
                                },
                                stack.isEmpty()
                                        ? "This hand is empty."
                                        : "Edit "
                                                + stack.getHoverName().getString()
                                                + " using this held stack's actual model and components.")
                        .active =
                !stack.isEmpty();
    }

    private void filter() {
        String text = query.toLowerCase(Locale.ROOT).trim();
        filtered =
                itemIds.stream()
                        .filter(
                                id ->
                                        (!customOnly || customized(id))
                                                && (id.contains(text)
                                                        || itemName(id)
                                                                .toLowerCase(Locale.ROOT)
                                                                .contains(text)))
                        .toList();
        rowStart = -1;
        itemRows();
    }

    private int itemScrollMax() {
        return Math.max(0, filtered.size() * 34 - Math.max(1, contentBottom - viewTop));
    }

    private void itemRows() {
        long now = System.nanoTime();
        int max = itemScrollMax();
        itemScroll.bounds(max, now);
        int displayed = itemScroll.pixels(now),
                first = displayed / 34,
                count = Math.max(1, (contentBottom - viewTop + 33) / 34 + 1);
        itemPixels = displayed;
        if (first != rowStart || count != rowCount) {
            String focusedId =
                    getFocused() instanceof MenuButton focused && rows.contains(focused)
                            ? focused.settingId
                            : null;
            for (var row : rows) removeWidget(row);
            rows.clear();
            rowStart = first;
            rowCount = count;
            for (int i = 0; i < count && i + first < filtered.size(); i++) {
                String id = filtered.get(i + first);
                boolean custom = customized(id);
                var row =
                        button(
                                contentX,
                                viewTop + (i + first) * 34 - displayed,
                                contentW,
                                31,
                                itemName(id),
                                () -> selectRegistry(id),
                                id
                                        + " - "
                                        + (custom
                                                ? "Custom item settings"
                                                : "Inherits block and global settings"));
                row.secondary = id;
                row.badge = custom ? "Custom" : "Inherited";
                row.selected = false;
                row.settingId = "item/" + id;
                row.clipTo(viewTop, contentBottom);
                rows.add(row);
            }
            if (focusedId != null) {
                var replacement =
                        rows.stream()
                                .filter(row -> focusedId.equals(row.settingId))
                                .findFirst()
                                .orElse(null);
                setFocused(replacement == null ? itemSearch : replacement);
            }
        }
        for (int i = 0; i < rows.size(); i++) {
            var row = rows.get(i);
            int top = viewTop + (rowStart + i) * 34 - displayed;
            row.setY(top);
            row.clipTo(viewTop, contentBottom);
            row.visible = top < contentBottom && top + row.getHeight() > viewTop;
        }
        arrows(itemScroll.target() > 0, itemScroll.target() < max);
    }

    @Override
    protected void scrollBy(int amount) {
        if (selectedId == null) {
            itemScroll.reset(itemScroll.target() + amount, itemScrollMax(), System.nanoTime());
            itemRows();
        } else super.scrollBy(amount);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        if (isClosing()) return true;
        if (selectedId == null
                && x >= contentX
                && x <= contentX + contentW + 8
                && y >= viewTop
                && y < contentBottom) {
            itemScroll.wheel(-vertical * 34, itemScrollMax(), System.nanoTime());
            itemRows();
            return true;
        }
        return super.mouseScrolled(x, y, horizontal, vertical);
    }

    private int itemThumbHeight() {
        int h = contentBottom - viewTop;
        return Math.max(10, h * h / Math.max(1, filtered.size() * 34));
    }

    private void dragItemScrollbar(double y) {
        int max = itemScrollMax(),
                travel = Math.max(1, contentBottom - viewTop - itemThumbHeight());
        itemScroll.reset(
                Math.clamp((y - viewTop - thumbGrab) / travel, 0, 1) * max, max, System.nanoTime());
        itemRows();
    }

    @Override
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean twice) {
        if (isClosing()) return true;
        if (selectedId == null
                && event.button() == com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT
                && itemScrollMax() > 0
                && event.x() >= contentX + contentW + 1
                && event.x() <= contentX + contentW + 8
                && event.y() >= viewTop
                && event.y() < contentBottom) {
            int thumb = itemThumbHeight(), travel = contentBottom - viewTop - thumb;
            double top = viewTop + travel * itemPixels / (double) itemScrollMax();
            thumbGrab = event.y() >= top && event.y() < top + thumb ? event.y() - top : thumb / 2.0;
            itemScrollDrag = true;
            dragItemScrollbar(event.y());
            return true;
        }
        return super.mouseClicked(event, twice);
    }

    @Override
    public boolean mouseDragged(
            net.minecraft.client.input.MouseButtonEvent event, double dx, double dy) {
        if (isClosing()) return true;
        if (itemScrollDrag) {
            dragItemScrollbar(event.y());
            return true;
        }
        return super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseReleased(net.minecraft.client.input.MouseButtonEvent event) {
        if (itemScrollDrag) {
            itemScrollDrag = false;
            return true;
        }
        return super.mouseReleased(event);
    }

    private ItemAppearance.Resolved appearance() {
        return ItemAppearance.resolve(
                StillBloomClient.config,
                ItemAppearance.blockProfile(selectedId, StillBloomClient.MASKS),
                StillBloomClient.ITEM_MASKS.profile(selectedId));
    }

    private void controls() {
        int backW = previewMode() ? 46 : 76;
        button(
                contentX,
                contentTop,
                backW,
                20,
                previewMode() ? "Items" : "< All items",
                () -> {
                    save();
                    preview = false;
                    selectedId = null;
                    selected = ItemStack.EMPTY;
                    scroll = 0;
                    rebuildWidgets();
                },
                "Return to the filtered item list. These settings affect held items of this type.");
        button(
                contentX + backW + 4,
                contentTop,
                contentW - backW - 4,
                20,
                previewMode() ? "Paint mask" : "Paint textures",
                () -> {
                    save();
                    minecraft.gui.setScreen(TextureEditorScreen.forItem(this, selected));
                },
                "Choose a texture and paint the parts that should glow. This does not change placed blocks.");
        viewTop = contentTop + 26;
        var inherited = appearance();
        var style = inherited.style();
        if (!previewMode()) {
            nextY =
                    sectionHeading(
                            nextY,
                            "Item appearance",
                            "Items use their block's settings, or Glow if none are set. Change them here for this item only.");
        }
        overrideNumber(
                "Strength",
                0,
                8,
                () -> editing.intensity,
                v -> editing.intensity = v,
                style.intensity(),
                "",
                "Brightness of this item's glow.");
        overrideOption(
                "Shape",
                () -> editing.shape,
                style.shape(),
                v -> editing.shape = v,
                BloomConfig.Shape.values(),
                "Choose the shape of the surrounding glow.");
        colorControls();
        overrideNumber(
                "Glow width",
                8,
                128,
                () -> editing.width,
                v -> editing.width = v,
                style.width(),
                " px",
                "How far the glow spreads around the item.");
        overrideNumber(
                "Keep item detail",
                0,
                1,
                () -> editing.preserveDetail,
                v -> editing.preserveDetail = v,
                inherited.detail(),
                "",
                "Turn up to keep the item's texture easier to see. Turn down to let more glow cover it.");
        var more =
                new MenuButton(
                        contentX,
                        0,
                        contentW,
                        20,
                        (advanced ? "- " : "+ ") + "Flare & reset",
                        () -> {
                            advanced = !advanced;
                            rebuildWidgets();
                        });
        more.accessibleName = "More item controls";
        more.selected = advanced;
        more.setTooltip(
                Tooltip.create(
                        Component.literal(
                                "Adjust flares or reset this item's glow settings. Your paint is kept.")));
        scrolling(more, nextY);
        nextY += 26;
        if (advanced) {
            overrideOption(
                    "Flare",
                    () -> editing.flare,
                    style.flare(),
                    v -> editing.flare = v,
                    BloomConfig.Flare.values(),
                    "Off, horizontal streak, or cross.");
            overrideNumber(
                    "Flare strength",
                    0,
                    2,
                    () -> editing.flareStrength,
                    v -> editing.flareStrength = v,
                    style.flareStrength(),
                    "",
                    "How bright the flare is. Set to 0 to turn it off.");
            overrideNumber(
                    "Flare reach",
                    8,
                    128,
                    () -> editing.flareLength,
                    v -> editing.flareLength = v,
                    style.flareLength(),
                    " px",
                    "How far the flare stretches from the light.");
            nextY =
                    sectionHeading(
                            nextY,
                            "Reset appearance",
                            "Use this item's block settings, or Glow if none are set. Keep your paint.");
            action(
                    "Item style",
                    confirmReset ? "Confirm reset" : "Reset style",
                    () -> {
                        if (!confirmReset) {
                            confirmReset = true;
                            rebuildWidgets();
                            return;
                        }
                        StillBloomClient.ITEM_MASKS.removeOverride(selectedId);
                        editing = new BloomOverride();
                        pending = true;
                        confirmReset = false;
                        PresetStatus.changed();
                        save();
                        rebuildWidgets();
                    },
                    "Reset this item's glow settings to its block settings, or Glow if none are set. Keep your paint.");
        }
    }

    private Row nextRow(String name, String description, Supplier<String> status) {
        var result = row(nextY, name, description, status);
        nextY += result.height();
        return result;
    }

    private void action(String name, String text, Runnable action, String help) {
        var r = nextRow(name, help, () -> "");
        var b = new MenuButton(r.controlX(), 0, r.controlW(), 20, text, action);
        b.setTooltip(Tooltip.create(Component.literal(help)));
        scrolling(b, r.controlY());
    }

    private <T extends Enum<T>> void overrideOption(
            String name, Supplier<T> get, T inherited, Consumer<T> set, T[] values, String help) {
        var r = nextRow(name, help, () -> get.get() == null ? "Inherited" : "Custom");
        T current = get.get() == null ? inherited : get.get();
        var b =
                new MenuButton(
                        r.controlX(),
                        0,
                        r.controlW() - 46,
                        20,
                        label(current),
                        () -> {
                            T value = get.get();
                            set.accept(
                                    value == null
                                            ? values[0]
                                            : value.ordinal() + 1 < values.length
                                                    ? values[value.ordinal() + 1]
                                                    : null);
                            overrideChanged();
                            rebuildWidgets();
                        });
        b.setTooltip(
                Tooltip.create(
                        Component.literal(
                                help
                                        + " Reset uses the block's setting, or Glow if none is set.")));
        scrolling(b, r.controlY());
        reset(
                r,
                get.get() != null,
                () -> {
                    set.accept(null);
                    overrideChanged();
                    rebuildWidgets();
                });
    }

    private void overrideNumber(
            String name,
            float min,
            float max,
            Supplier<Float> get,
            Consumer<Float> set,
            float inherited,
            String suffix,
            String help) {
        var r = nextRow(name, help, () -> get.get() == null ? "Inherited" : "Custom");
        var resetButton =
                reset(
                        r,
                        get.get() != null,
                        () -> {
                            set.accept(null);
                            overrideChanged();
                            rebuildWidgets();
                        });
        var slider =
                new MenuSlider(
                        r.controlX(),
                        0,
                        r.controlW() - 46,
                        min,
                        max,
                        get.get() == null ? inherited : get.get(),
                        suffix,
                        value -> {
                            set.accept((float) value);
                            overrideChanged();
                            resetButton.active = true;
                        });
        slider.accessibleName = name;
        slider.setTooltip(
                Tooltip.create(
                        Component.literal(
                                name
                                        + ": "
                                        + help
                                        + " Adjust this item only. Reset uses the block's setting, or Glow if none is set.")));
        scrolling(slider, r.controlY());
    }

    private MenuButton reset(Row r, boolean custom, Runnable action) {
        var b = new MenuButton(r.controlX() + r.controlW() - 42, 0, 42, 20, "Reset", action);
        b.active = custom;
        b.setTooltip(
                Tooltip.create(
                        Component.literal(
                                "Use the block's setting for this control, or Glow if none is set.")));
        scrolling(b, r.controlY());
        return b;
    }

    private void colorControls() {
        Integer inheritedColor =
                ItemAppearance.resolve(
                                StillBloomClient.config,
                                ItemAppearance.blockProfile(selectedId, StillBloomClient.MASKS),
                                null)
                        .color();
        var r =
                nextRow(
                        "Bloom color",
                        "Choose a glow color. Reset uses the block's glow color, or the texture's own color.",
                        () ->
                                editing.color == null
                                        ? "Inherited"
                                        : "#" + BloomColors.hex(editing.color));
        var choose =
                new MenuButton(
                        r.controlX(),
                        0,
                        r.controlW() - 46,
                        20,
                        "Pick color",
                        () -> {
                            save();
                            minecraft.gui.setScreen(
                                    new BloomColorScreen(
                                            this,
                                            selected.getHoverName().getString(),
                                            editing.color,
                                            inheritedColor,
                                            color -> {
                                                editing.color = color;
                                                overrideChanged();
                                                apply();
                                            },
                                            this::save));
                        });
        choose.setTooltip(
                Tooltip.create(Component.literal("Pick a glow color or enter a color code.")));
        scrolling(choose, r.controlY());
        reset(
                r,
                editing.color != null,
                () -> {
                    editing.color = null;
                    overrideChanged();
                    rebuildWidgets();
                });
    }

    private void overrideChanged() {
        confirmReset = false;
        PresetStatus.changed();
        editing.sanitize();
        if (editing.empty()) StillBloomClient.ITEM_MASKS.removeOverride(selectedId);
        else
            StillBloomClient.ITEM_MASKS.items.computeIfAbsent(
                                    selectedId, id -> new TextureMasks.Profile())
                            .override =
                    editing;
        pending = true;
    }

    private void apply() {
        if (pending) {
            StillBloomClient.ITEM_MASKS.stylesChanged();
            StillBloomClient.HELD_RENDERER.sourcesChanged();
            pending = false;
        }
    }

    private void save() {
        apply();
        saveFailed = !StillBloomClient.ITEM_MASKS.save(StillBloomClient.ITEM_MASK_PATH);
    }

    @Override
    public void tick() {
        if (++ticks % 3 == 0) apply();
    }

    @Override
    public void removed() {
        save();
    }

    @Override
    public void onClose() {
        save();
        if (!saveFailed) minecraft.gui.setScreen(parent);
    }

    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
        if (isClosing()) return true;
        if (event.key() == com.mojang.blaze3d.platform.InputConstants.KEY_ESCAPE && previewMode()) {
            onClose();
            return true;
        }
        if (event.key() == com.mojang.blaze3d.platform.InputConstants.KEY_ESCAPE
                && selectedId != null) {
            save();
            selectedId = null;
            selected = ItemStack.EMPTY;
            scroll = 0;
            rebuildWidgets();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    protected String footer() {
        return saveFailed
                ? "Save failed: check log"
                : selectedId == null ? filtered.size() + " items" : "Unset values inherit";
    }

    @Override
    protected void extractWorkspaceBackground(GuiGraphicsExtractor g, int x, int y, float tick) {
        if (selectedId == null) itemRows();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int x, int y, float tick) {
        super.extractRenderState(g, x, y, tick);
        if (selectedId == null && filtered.isEmpty())
            g.textWithWordWrap(
                    font,
                    Component.literal(
                            customOnly
                                    ? "No customized items match. Choose All items to paint a new item."
                                    : "No matching items. Try a name or registry ID."),
                    contentX + 5,
                    viewTop + 10,
                    contentW - 10,
                    MUTED);
        if (selectedId == null) {
            int h = contentBottom - viewTop, max = itemScrollMax();
            if (max > 0) {
                int thumb = itemThumbHeight(),
                        y0 = viewTop + (int) Math.round((h - thumb) * itemPixels / (double) max);
                g.fill(
                        contentX + contentW + 3,
                        viewTop,
                        contentX + contentW + 5,
                        contentBottom,
                        0xFF17212C);
                g.fill(contentX + contentW + 3, y0, contentX + contentW + 5, y0 + thumb, ACCENT);
            }
        }
    }
}
