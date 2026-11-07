package dev.stillbloom.gui;

import com.mojang.blaze3d.platform.InputConstants;

import dev.stillbloom.StillBloomClient;
import dev.stillbloom.config.BloomConfig;
import dev.stillbloom.config.BloomOverride;
import dev.stillbloom.config.BloomRange;
import dev.stillbloom.gui.layout.SmoothScroll;
import dev.stillbloom.mask.BlockLinks;
import dev.stillbloom.mask.TextureMasks;
import dev.stillbloom.model.BlockCatalog;
import dev.stillbloom.model.BlockSearchIndex;
import dev.stillbloom.preset.SetupPersistence;
import dev.stillbloom.preset.SetupPresets;
import dev.stillbloom.preset.StylePresets;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.*;
import java.util.function.*;

public class BloomSettingsScreen extends BloomMenuScreen {
    private final Screen parent;
    private int page = 1, ticks, y, blockOffset;
    private SetupPresetsScreen looks;
    private final LinkedHashMap<String, TextureEditorScreen> maskEditors =
            new LinkedHashMap<>(8, .75f, true);
    private TextureEditorScreen visiblePainter;
    private long painterRevision = -1;
    private boolean maskView, expandedMask, threePane, showBlockBrowser = true;
    private int maskX, maskW;

    private TextureEditorScreen painter() {
        var result =
                maskEditors.computeIfAbsent(selectedBlock, id -> new TextureEditorScreen(this, id));
        if (maskEditors.size() > 8) {
            var eldest = maskEditors.entrySet().iterator();
            var old = eldest.next();
            old.getValue().suspendEmbedded();
            eldest.remove();
        }
        return result;
    }

    private void mountPainter(int x, int top, int w, int bottom) {
        visiblePainter = painter();
        painterRevision = visiblePainter.editRevision();
        visiblePainter.mount(
                x,
                top,
                w,
                Math.max(60, bottom - top),
                width,
                height,
                () -> {
                    rememberScroll();
                    maskView = true;
                    expandedMask = true;
                    preview = false;
                    rebuildWidgets();
                });
        addRenderableWidget(visiblePainter);
    }

    private void flushPainter() {
        if (visiblePainter != null) visiblePainter.flushEmbedded();
    }

    private void suspendPainters() {
        for (var painter : maskEditors.values()) painter.suspendEmbedded();
    }

    private int browserX,
            browserW,
            browserTop,
            browserBottom,
            workspaceX,
            workspaceW,
            browserLayoutW = -1,
            browserLayoutH = -1;
    private final SmoothScroll blockScroll = new SmoothScroll();
    private int blockPixels;
    private double browserGrab;
    private String activeBrowserKey;
    private boolean browserDrag, previewChooser, focusEditor, revealSelection, browserDirty;
    private final Map<String, Integer> browserOffsets = new HashMap<>();
    private boolean pending,
            maskPending,
            saveFailed,
            customOnly,
            preview,
            confirmReset,
            focusBrowser;
    private String saveError = "";
    private String openChoice,
            query = "",
            selectedBlock,
            jumpId,
            lastGroup = "look",
            previewGroup = "look",
            previewEntryGroup = "look",
            blockGroup = "look";
    private BloomOverride editing;
    private EditBox search;
    private final Set<String> expanded = new LinkedHashSet<>(List.of("look"));
    private final Set<String> blockExpanded = new LinkedHashSet<>(List.of("look"));
    private final Map<String, Integer> fullScrolls = new HashMap<>(),
            previewScrolls = new HashMap<>();
    private final Map<String, Row> anchors = new HashMap<>();
    private final Map<String, AbstractWidget> controls = new HashMap<>();
    private final List<Runnable> inheritedStates = new ArrayList<>();
    private List<String> filtered = List.of();
    private final List<MenuButton> blockRows = new ArrayList<>();
    private BlockSearchIndex blockIndex;
    private String blockSource = "", blockMessage = "";
    private final Map<String, BlockLinksScreen.Session> linkSessions = new HashMap<>();

    BlockLinksScreen.Session linkSession(String id) {
        return linkSessions.computeIfAbsent(id, BlockLinksScreen.Session::new);
    }

    void refreshLinks() {
        linkSessions.values().forEach(BlockLinksScreen.Session::reset);
        refreshBlockStyle();
        browserDirty = true;
    }

    private void manageLinks() {
        flushPainter();
        apply();
        minecraft.gui.setScreen(
                new BlockLinksScreen(this, selectedBlock, linkSession(selectedBlock).candidates()));
    }

    private void syncBlocks() {
        flushPainter();
        apply();
        var editor = painter();
        blockMessage =
                editor.syncSelectedMask()
                        ? "Selected texture mask is synchronized."
                        : "No mask changed. Linked members already share this texture.";
        browserDirty = true;
    }

    public BloomSettingsScreen(Screen parent) {
        super("Fluorescent");
        this.parent = parent;
        PresetStatus.changed();
    }

    public BloomSettingsScreen withPreview() {
        preview = true;
        return this;
    }

    @Override
    protected BloomSettingsScreen settingsOwner() {
        return this;
    }

    @Override
    protected boolean focusedTool() {
        return page == 2
                && selectedBlock != null
                && (maskView || expandedMask)
                && !previewMode()
                && height < 300;
    }

    @Override
    protected boolean canPreview() {
        return minecraft != null && minecraft.level != null && (page != 2 || selectedBlock != null);
    }

    @Override
    protected boolean previewMode() {
        return preview && canPreview();
    }

    private String scrollKey() {
        return page
                + "/"
                + (page == 2 ? Objects.toString(selectedBlock, "list") : "settings")
                + (previewMode()
                        ? "/" + previewGroup
                        : page == 2 && (maskView || expandedMask) ? "/mask" : "");
    }

    @Override
    protected Object scrollContext() {
        return scrollKey() + "/" + previewChooser;
    }

    private void rememberScroll() {
        if (previewMode() && previewChooser) return;
        (previewMode() ? previewScrolls : fullScrolls).put(scrollKey(), scroll);
    }

    @Override
    protected void togglePreview() {
        flushPainter();
        boolean leaving = previewMode();
        apply();
        rememberScroll();
        previewChooser = false;
        preview = !preview;
        if (preview) {
            previewGroup =
                    page == 2 ? (maskView ? "mask" : blockGroup) : page == 4 ? "items" : lastGroup;
            previewEntryGroup = previewGroup;
        } else if (leaving && page == 2 && previewGroup.equals("mask")) {
            maskView = true;
            expandedMask = true;
        } else if (leaving && page != 3 && !previewGroup.equals(previewEntryGroup)) {
            (page == 2 ? blockExpanded : expanded).add(previewGroup);
            jumpId = "@" + previewGroup;
        }
        scroll = (previewMode() ? previewScrolls : fullScrolls).getOrDefault(scrollKey(), 0);
        rebuildWidgets();
    }

    @Override
    protected int section() {
        return page;
    }

    @Override
    protected String heading() {
        if (saveFailed || visiblePainter != null && visiblePainter.hasSaveFailure())
            return "Save failed";
        if (page == 2 && selectedBlock != null)
            return previewMode()
                    ? blockName(selectedBlock)
                    : "Blocks / " + blockName(selectedBlock);
        return page == 4 ? "Held Items" : page == 3 ? "Performance" : page == 2 ? "Blocks" : "Glow";
    }

    @Override
    protected int headingRightSpace() {
        return previewMode() ? 0 : page == 1 ? 174 : page == 2 ? 70 : 0;
    }

    private void openLooks() {
        if (looks == null) looks = new SetupPresetsScreen(this);
        minecraft.gui.setScreen(looks);
    }

    @Override
    protected void navigate(int destination) {
        flushPainter();
        apply();
        rememberScroll();
        if (destination == 0) {
            openLooks();
            return;
        }
        setFocused(null);
        page = destination;
        preview = false;
        previewChooser = false;
        openChoice = null;
        confirmReset = false;
        scroll = fullScrolls.getOrDefault(scrollKey(), 0);
        if (width > 0 && height > 0) rebuildWidgets();
    }

    void openBlock(String block) {
        flushPainter();
        apply();
        rememberScroll();
        boolean same = Objects.equals(selectedBlock, block);
        selectedBlock = block;
        page = 2;
        confirmReset = false;
        previewChooser = false;
        loadEditing();
        if (!same) {
            expandedMask = false;
            revealSelection = true;
            scroll = fullScrolls.getOrDefault(scrollKey(), 0);
            blockGroup = "look";
            previewGroup = "look";
        }
    }

    private void loadEditing() {
        var p = StillBloomClient.MASKS.profile(selectedBlock);
        editing = p == null || p.override == null ? new BloomOverride() : p.override;
    }

    void openSetting(SettingCatalog.Entry entry) {
        apply();
        rememberScroll();
        // I let an explicit destination override focus saved for a value-only rebuild.
        setFocused(null);
        if (entry.page() == 0) {
            openLooks();
            return;
        }
        page = entry.page();
        preview = false;
        previewChooser = false;
        openChoice = null;
        jumpId = entry.id();
        scroll = 0;
        if (page == 1) {
            expanded.add(entry.group());
            lastGroup = entry.group();
        }
        if (page == 2) {
            selectedBlock = null;
            focusBrowser = true;
        }
        if (minecraft.gui.screen() == this) rebuildWidgets();
        else minecraft.gui.setScreen(this);
    }

    @Override
    protected void init() {
        var previousPainter = visiblePainter;
        visiblePainter = null;
        frame();
        if (page == 2 && selectedBlock != null) loadEditing();
        blockRows.clear();
        anchors.clear();
        controls.clear();
        inheritedStates.clear();
        y = 0;
        PresetStatus.refresh(this);
        if (page == 2) {
            if (previewMode()) blockControls();
            else blockWorkspace();
        } else if (page == 3) performanceControls();
        else if (page == 4) heldControls();
        else globalControls();
        finishLayout();
        if (previousPainter != null && previousPainter != visiblePainter)
            previousPainter.suspendEmbedded();
        if (jumpId != null) {
            var r = anchors.get(jumpId);
            if (r != null) highlight(r.y(), r.height());
            var widget = controls.get(jumpId);
            if (widget != null && widget.active) setFocused(widget);
            jumpId = null;
        }
        if (focusBrowser && search != null) {
            setInitialFocus(search);
            focusBrowser = false;
        }
        if (focusEditor && controls.containsKey("block/Strength")) {
            setFocused(controls.get("block/Strength"));
            focusEditor = false;
        }
    }

    private void heldControls() {
        if (!previewMode())
            y =
                    sectionHeading(
                            y,
                            "Held item bloom",
                            "Choose whose items glow, then customize their textures and styles.");
        globalGroup("items");
    }

    private void globalControls() {
        if (previewMode()) {
            previewSwitcher(SettingCatalog.GROUPS);
            if (!previewChooser) globalGroup(previewGroup);
            return;
        }
        var master =
                button(
                        contentX + contentW - 80,
                        contentTop - 20,
                        80,
                        18,
                        StillBloomClient.config.enabled ? "Bloom On" : "Bloom Off",
                        () -> {
                            StillBloomClient.config.enabled = !StillBloomClient.config.enabled;
                            changed();
                            rebuildWidgets();
                        },
                        "Turn all bloom on or off. Press F8 while playing to do the same.");
        master.accessibleName = "Bloom";
        master.settingId = "enabled";
        master.selected = StillBloomClient.config.enabled;
        controls.put("enabled", master);
        button(
                contentX + contentW - 167,
                contentTop - 20,
                80,
                18,
                "Looks >",
                () -> navigate(0),
                "Save this setup, import a shared preset, or try your saved looks in the world. "
                        + PresetStatus.status(this));
        for (String group : SettingCatalog.GROUPS) {
            if (group.equals("look")) {
                anchors.put("@look", new Row(contentX, y, contentW, contentX, y, contentW, 20));
                y =
                        sectionHeading(
                                y, "Appearance", "Tune your glow here. Save or share it in Looks.");
                globalGroup(group);
            } else if (disclosure(
                    group, SettingCatalog.groupTitle(group), groupSummary(group), expanded))
                globalGroup(group);
        }
    }

    private String groupSummary(String group) {
        var c = StillBloomClient.config;
        return switch (group) {
            case "color" -> "Warmth and color intensity";
            case "shape" ->
                    label(c.bloomShape)
                            + " / "
                            + (c.flareStyle == BloomConfig.Flare.OFF
                                    ? "no flare"
                                    : label(c.flareStyle) + " flare");
            case "response" -> "Which areas glow and how steadily they shine";
            case "distance" ->
                    c.distancePixels
                            ? c.pixelSize + " px near / " + c.finePixelSize() + " px far"
                            : "Off - optional smaller distant pixels";
            case "items" ->
                    "Your items "
                            + (c.heldItemBloom ? "On" : "Off")
                            + " / other players "
                            + (c.otherPlayerItemBloom ? "On" : "Off");
            default -> "Strength, pixels and texture detail";
        };
    }

    private boolean disclosure(String key, String title, String summary, Set<String> open) {
        int at = y;
        anchors.put("@" + key, new Row(contentX, at, contentW, contentX, at, contentW, 28));
        var b =
                new MenuButton(
                        contentX,
                        0,
                        contentW,
                        24,
                        title,
                        () -> {
                            if (!open.add(key)) open.remove(key);
                            if (page == 2) blockGroup = key;
                            else lastGroup = key;
                            rebuildWidgets();
                        });
        b.badge = open.contains(key) ? "-" : "+";
        b.leftAligned = true;
        b.accessibleName = title;
        b.selected = open.contains(key);
        b.setTooltip(
                Tooltip.create(Component.literal(summary + ". Expand or collapse this section.")));
        scrolling(b, at);
        y += 28;
        contentHeight = Math.max(contentHeight, y);
        return open.contains(key);
    }

    private void previewSwitcher(List<String> groups) {
        if (!groups.contains(previewGroup)) previewGroup = groups.getFirst();
        String name =
                page == 2
                        ? switch (previewGroup) {
                            case "look" -> "Appearance";
                            case "shape" -> "Lens effects";
                            case "mask" -> "Texture mask";
                            default -> "Block tools";
                        }
                        : SettingCatalog.groupTitle(previewGroup);
        button(
                contentX,
                contentTop,
                contentW,
                18,
                name + " v",
                () -> {
                    if (!previewChooser) rememberScroll();
                    previewChooser = !previewChooser;
                    scroll = previewChooser ? 0 : previewScrolls.getOrDefault(scrollKey(), 0);
                    rebuildWidgets();
                },
                "Choose which controls to keep in the live dock. Studio opens the complete workspace.");
        viewTop = contentTop + 23;
        if (previewChooser) {
            for (String group : groups) {
                String title =
                        page == 2
                                ? switch (group) {
                                    case "look" -> "Appearance";
                                    case "shape" -> "Lens effects";
                                    case "mask" -> "Texture mask";
                                    default -> "Block tools";
                                }
                                : SettingCatalog.groupTitle(group);
                var b =
                        new MenuButton(
                                contentX,
                                0,
                                contentW,
                                20,
                                title,
                                () -> {
                                    previewGroup = group;
                                    previewChooser = false;
                                    if (page == 2) {
                                        blockGroup = group;
                                        blockExpanded.add(group);
                                    } else {
                                        lastGroup = group;
                                        expanded.add(group);
                                    }
                                    scroll = previewScrolls.getOrDefault(scrollKey(), 0);
                                    rebuildWidgets();
                                });
                b.selected = group.equals(previewGroup);
                scrolling(b, y);
                y += 24;
            }
            contentHeight = y;
        }
    }

    private void globalGroup(String group) {
        var c = StillBloomClient.config;
        switch (group) {
            case "look" -> {
                number("intensity", 0, 8, c.intensity, "", v -> c.intensity = (float) v);
                integerChoice(
                        "pixelSize", c.pixelSize, BloomConfig.pixelSizes(), v -> c.pixelSize = v);
                number(
                        "smoothBlend",
                        0,
                        1,
                        c.smoothBlend,
                        "",
                        v -> c.smoothBlend = (float) v,
                        v ->
                                v <= 0
                                        ? "Pixelated"
                                        : v >= 1
                                                ? "Smooth"
                                                : String.format(
                                                        Locale.ROOT, "%.0f%% blend", v * 100));
                number(
                        "preserveDetail",
                        0,
                        1,
                        c.preserveDetail,
                        "",
                        v -> c.preserveDetail = (float) v);
                number("glowWidth", 8, 128, c.glowWidth, " px", v -> c.glowWidth = (float) v);
                if (previewMode())
                    option(
                            "enabled",
                            c.enabled ? "On" : "Off",
                            () -> {
                                c.enabled = !c.enabled;
                                changed();
                                rebuildWidgets();
                            });
            }
            case "color" -> {
                number("saturation", 0, 2.5, c.saturation, "", v -> c.saturation = (float) v);
                number("warmth", -1, 1, c.warmth, "", v -> c.warmth = (float) v);
            }
            case "shape" -> {
                enumChoice(
                        "bloomShape",
                        c.bloomShape,
                        BloomConfig.Shape.values(),
                        v -> c.bloomShape = v);
                enumChoice(
                        "flareStyle",
                        c.flareStyle,
                        BloomConfig.Flare.values(),
                        v -> c.flareStyle = v);
                number(
                        "flareStrength",
                        0,
                        2,
                        c.flareStrength,
                        "",
                        v -> c.flareStrength = (float) v);
                number("flareLength", 8, 128, c.flareLength, " px", v -> c.flareLength = (float) v);
                number("wideHalo", 0, 1, c.wideHalo, "", v -> c.wideHalo = (float) v);
                number("nearRadius", .4, 3, c.nearRadius, "", v -> c.nearRadius = (float) v);
                number("wideRadius", 1, 6, c.wideRadius, "", v -> c.wideRadius = (float) v);
            }
            case "response" -> {
                option(
                        "selfLitPaint",
                        c.selfLitPaint ? "On" : "Off",
                        () -> {
                            c.selfLitPaint = !c.selfLitPaint;
                            changed();
                            rebuildWidgets();
                        });
                number("threshold", .1, 1, c.threshold, "", v -> c.threshold = (float) v);
                number("softKnee", .01, .5, c.softKnee, "", v -> c.softKnee = (float) v);
                number("coreStrength", 0, 2, c.coreStrength, "", v -> c.coreStrength = (float) v);
                number(
                        "additiveBlend",
                        0,
                        1,
                        c.additiveBlend,
                        "",
                        v -> c.additiveBlend = (float) v);
                option(
                        "uniformEmission",
                        c.uniformEmission ? "On" : "Off",
                        () -> {
                            c.uniformEmission = !c.uniformEmission;
                            changed();
                            rebuildWidgets();
                        });
                number(
                        "stabilityMillis",
                        0,
                        120,
                        c.stabilityMillis,
                        " ms",
                        v -> c.stabilityMillis = (float) v);
            }
            case "distance" -> {
                option(
                        "distancePixels",
                        c.distancePixels ? "On" : "Off",
                        () -> {
                            c.distancePixels = !c.distancePixels;
                            changed();
                            rebuildWidgets();
                        });
                if (!c.distancePixels && Objects.equals(openChoice, "farPixelSize"))
                    openChoice = null;
                integerChoice(
                                        "farPixelSize",
                                        c.finePixelSize(),
                                        Arrays.stream(BloomConfig.pixelSizes())
                                                .filter(size -> size <= c.pixelSize)
                                                .toArray(),
                                        v -> c.farPixelSize = v)
                                .active =
                        c.distancePixels;
                number(
                                        "pixelDistanceStart",
                                        0,
                                        32,
                                        c.pixelDistanceStart,
                                        " blocks",
                                        v -> c.pixelDistanceStart = (float) v)
                                .active =
                        c.distancePixels;
                number(
                                        "pixelDistanceSpan",
                                        4,
                                        24,
                                        c.pixelDistanceSpan,
                                        " blocks",
                                        v -> c.pixelDistanceSpan = (float) v)
                                .active =
                        c.distancePixels;
                if (!c.distancePixels)
                    y = note(y, "Turn on Distance pixels to edit the distant size and transition.");
            }
            case "items" -> {
                option(
                        "heldItemBloom",
                        c.heldItemBloom ? "On" : "Off",
                        () -> {
                            c.heldItemBloom = !c.heldItemBloom;
                            changed();
                            apply();
                            rebuildWidgets();
                        });
                option(
                        "otherPlayerItemBloom",
                        c.otherPlayerItemBloom ? "On" : "Off",
                        () -> {
                            c.otherPlayerItemBloom = !c.otherPlayerItemBloom;
                            changed();
                            apply();
                            rebuildWidgets();
                        });
                number(
                        "heldItemRange",
                        8,
                        64,
                        c.heldItemRange,
                        " blocks",
                        v -> c.heldItemRange = (float) v);
                action(
                        "editItems",
                        "Edit items",
                        () -> {
                            apply();
                            minecraft.gui.setScreen(new ItemSettingsScreen(this));
                        });
            }
        }
    }

    private Row settingRow(String id, Supplier<String> status) {
        var entry = SettingCatalog.get(id);
        boolean needsSizing =
                Set.of("farPixelSize", "pixelDistanceStart", "pixelDistanceSpan").contains(id)
                        && !StillBloomClient.config.distancePixels;
        var r =
                row(
                        y,
                        entry.title(),
                        needsSizing
                                ? "Turn on Distance pixels to adjust this control."
                                : entry.description(),
                        needsSizing ? () -> "Off" : status);
        y += r.height();
        anchors.put(id, r);
        return r;
    }

    private MenuButton option(String id, String value, Runnable action) {
        var r = settingRow(id, () -> "");
        var b =
                new MenuButton(
                        r.controlX(),
                        0,
                        r.controlW(),
                        20,
                        value,
                        () -> {
                            lastGroup = SettingCatalog.get(id).group();
                            action.run();
                        });
        b.accessibleName = SettingCatalog.get(id).title();
        b.settingId = id;
        b.settingHelp(SettingCatalog.get(id).description());
        scrolling(b, r.controlY());
        controls.put(id, b);
        return b;
    }

    private void closeChoice(String id) {
        openChoice = null;
        jumpId = id;
        setFocused(null);
        rebuildWidgets();
    }

    private void choose(String id, Runnable setter) {
        setter.run();
        changed();
        closeChoice(id);
    }

    private <T extends Enum<T>> void enumChoice(
            String id, T current, T[] values, Consumer<T> setter) {
        option(
                id,
                label(current) + " v",
                () -> {
                    openChoice = Objects.equals(openChoice, id) ? null : id;
                    rebuildWidgets();
                });
        if (Objects.equals(openChoice, id))
            for (T value : values) {
                var b =
                        new MenuButton(
                                contentX,
                                0,
                                contentW,
                                20,
                                label(value),
                                () -> {
                                    if (value == current) closeChoice(id);
                                    else choose(id, () -> setter.accept(value));
                                });
                b.selected = value == current;
                b.accessibleName = SettingCatalog.get(id).title() + ": " + label(value);
                b.settingId = "choice/" + id + "/" + value;
                scrolling(b, y);
                y += 24;
            }
        contentHeight = Math.max(contentHeight, y);
    }

    private MenuButton integerChoice(String id, int current, int[] values, IntConsumer setter) {
        var parent =
                option(
                        id,
                        pixelLabel(current) + " v",
                        () -> {
                            openChoice = Objects.equals(openChoice, id) ? null : id;
                            rebuildWidgets();
                        });
        parent.wrap = true;
        if (Objects.equals(openChoice, id))
            for (int value : values) {
                var b =
                        new MenuButton(
                                contentX,
                                0,
                                contentW,
                                20,
                                pixelLabel(value),
                                () -> {
                                    if (value == current) closeChoice(id);
                                    else choose(id, () -> setter.accept(value));
                                });
                b.selected = value == current;
                b.accessibleName = SettingCatalog.get(id).title() + ": " + pixelLabel(value);
                b.settingId = "choice/" + id + "/" + value;
                scrolling(b, y);
                y += 24;
            }
        contentHeight = Math.max(contentHeight, y);
        return parent;
    }

    private static String pixelLabel(int size) {
        return size
                + " - "
                + switch (size) {
                    case 2 -> "Fine";
                    case 3 -> "Balanced (Default)";
                    case 4 -> "Chunky";
                    default -> throw new IllegalArgumentException("Unsupported pixel size");
                };
    }

    private MenuSlider number(
            String id,
            double min,
            double max,
            double current,
            String suffix,
            DoubleConsumer setter) {
        return number(id, min, max, current, suffix, setter, null);
    }

    private MenuSlider number(
            String id,
            double min,
            double max,
            double current,
            String suffix,
            DoubleConsumer setter,
            DoubleFunction<String> formatter) {
        var r = settingRow(id, () -> "");
        var entry = SettingCatalog.get(id);
        var slider =
                new MenuSlider(
                        r.controlX(),
                        0,
                        r.controlW(),
                        min,
                        max,
                        current,
                        suffix,
                        value -> {
                            setter.accept(value);
                            lastGroup = entry.group();
                            changed();
                        },
                        formatter);
        slider.accessibleName = entry.title();
        slider.settingId = id;
        slider.setTooltip(Tooltip.create(Component.literal(entry.description())));
        scrolling(slider, r.controlY());
        controls.put(id, slider);
        return slider;
    }

    private void action(String id, String value, Runnable run) {
        option(id, value, run);
    }

    private void performanceControls() {
        if (previewMode()) {
            var c = StillBloomClient.config;
            number(
                    "bloomRange",
                    16,
                    BloomRange.MAX_RANGE,
                    c.bloomRange,
                    " blocks",
                    v -> c.bloomRange = (float) v);
            number(
                    "fadeFraction",
                    .1,
                    .75,
                    c.fadeFraction,
                    "",
                    v -> c.fadeFraction = (float) v,
                    v -> String.format(Locale.ROOT, "%.0f%% of range", v * 100));
            toolRow(
                    "More performance controls",
                    "Open the full menu for links to appearance controls.",
                    "Full menu",
                    this::togglePreview);
            return;
        }
        y =
                sectionHeading(
                        y,
                        "Source distance",
                        "Limit how much of the world is checked for glowing blocks.");
        var c = StillBloomClient.config;
        number(
                "bloomRange",
                16,
                BloomRange.MAX_RANGE,
                c.bloomRange,
                " blocks",
                v -> c.bloomRange = (float) v);
        number(
                "fadeFraction",
                .1,
                .75,
                c.fadeFraction,
                "",
                v -> c.fadeFraction = (float) v,
                v -> String.format(Locale.ROOT, "%.0f%% of range", v * 100));
        y = sectionHeading(y, "Cost and quality", "Jump to settings that can affect FPS.");
        costLink("pixelSize", c.pixelSize + " px  >");
        costLink("distancePixels", (c.distancePixels ? "On" : "Off") + "  >");
        costLink("glowWidth", Math.round(c.glowWidth) + " px  >");
        costLink("flareStyle", label(c.flareStyle) + "  >");
        costLink("heldItemRange", Math.round(c.heldItemRange) + " blocks  >");
        costLink("uniformEmission", (c.uniformEmission ? "On" : "Off") + "  >");
    }

    private void costLink(String id, String value) {
        var e = SettingCatalog.get(id);
        var r = row(y, e.title(), "Shared control in " + e.location() + ".", () -> "");
        y += r.height();
        var b = new MenuButton(r.controlX(), 0, r.controlW(), 20, value, () -> openSetting(e));
        b.wrap = true;
        b.accessibleName = "Open " + e.title();
        b.setTooltip(Tooltip.create(Component.literal(e.description())));
        scrolling(b, r.controlY());
    }

    private void blockWorkspace() {
        workspaceX = contentX;
        workspaceW = contentW;
        threePane = selectedBlock != null && workspaceW >= 580 && !maskView && !expandedMask;
        boolean paintOnly = selectedBlock != null && (maskView || expandedMask);
        showBlockBrowser = !paintOnly || workspaceW >= 440;
        browserX = contentX;
        browserW =
                threePane
                        ? Math.clamp(workspaceW / 5, 112, 170)
                        : contentW < 360 ? 90 : Math.clamp(contentW / 3, 136, 200);
        browserBottom = contentBottom;
        button(
                workspaceX + workspaceW - 64,
                contentTop - 20,
                64,
                18,
                "Bulk edit",
                () -> {
                    flushPainter();
                    apply();
                    minecraft.gui.setScreen(new BulkOverrideScreen(this, selectedBlock));
                },
                "Select blocks, draft changes and review before applying. Existing masks can be kept.");
        if (showBlockBrowser) {
            search =
                    new EditBox(
                            font,
                            browserX + 4,
                            contentTop + 1,
                            browserW - 8,
                            18,
                            Component.literal("Search blocks"));
            search.setBordered(false);
            search.setTextColor(TEXT);
            search.setHint(
                    Component.literal("Find block...")
                            .withStyle(style -> style.withColor(MUTED & 0xFFFFFF)));
            search.setMaxLength(128);
            search.setValue(query);
            addRenderableWidget(search);
            blockIndex = BlockCatalog.current();
            if (!blockIndex.hasSource(blockSource)) blockSource = "";
            button(
                                    browserX,
                                    contentTop + 24,
                                    browserW,
                                    18,
                                    blockIndex.sourceName(blockSource) + " v",
                                    () ->
                                            minecraft.gui.setScreen(
                                                    new BlockSourceFilterScreen(
                                                            this,
                                                            blockSource,
                                                            value -> {
                                                                blockSource = value;
                                                                blockPixels = 0;
                                                            })),
                                    "Filter All Blocks, Vanilla, or a mod. The text search stays active within this source.")
                            .accessibleName =
                    "Block source filter";
            var filter =
                    button(
                            browserX,
                            contentTop + 46,
                            browserW,
                            18,
                            customOnly ? "Custom only" : "All settings",
                            () -> {
                                browserOffsets.put(browserKey(), blockPixels);
                                customOnly = !customOnly;
                                blockPixels = browserOffsets.getOrDefault(browserKey(), 0);
                                rebuildWidgets();
                            },
                            "Show all blocks in this source, or only customized blocks. Your selection is retained.");
            filter.selected = customOnly;
            browserTop = contentTop + 69;
            int listHeight = browserBottom - browserTop;
            if (browserLayoutW != browserW || browserLayoutH != listHeight) revealSelection = true;
            browserLayoutW = browserW;
            browserLayoutH = listHeight;
            contentX = browserX + browserW + 12;
            contentW = workspaceW - browserW - 12;
            search.setResponder(
                    text -> {
                        browserOffsets.put(browserKey(), blockPixels);
                        query = text;
                        blockPixels = browserOffsets.getOrDefault(browserKey(), 0);
                        filter();
                    });
            filter();
        } else search = null;
        if (selectedBlock == null) {
            y =
                    sectionHeading(
                            0,
                            "Choose a block",
                            "Search by name or registry ID. Properties and painting share this workspace.");
            y =
                    note(
                            y,
                            "Choose a block to change its glow or paint the parts that glow. Global uses the settings from Glow.");
            toolRow(
                    "Pick from the world",
                    "Aim at a block before opening the studio, or use G during gameplay.",
                    "Pick targeted block",
                    this::pickTarget);
        } else if (paintOnly) {
            int backW = Math.min(120, contentW / 2);
            button(
                                    contentX,
                                    contentTop,
                                    backW,
                                    18,
                                    "< Properties",
                                    () -> {
                                        flushPainter();
                                        maskView = false;
                                        expandedMask = false;
                                        scroll = fullScrolls.getOrDefault(scrollKey(), 0);
                                        rebuildWidgets();
                                    },
                                    "Return to this block's appearance. Its texture, brush, selection and Undo history are retained.")
                            .accessibleName =
                    "Block properties";
            if (!showBlockBrowser)
                button(
                        contentX + backW + 4,
                        contentTop,
                        contentW - backW - 4,
                        18,
                        "Choose block",
                        () -> {
                            flushPainter();
                            maskView = false;
                            expandedMask = false;
                            scroll = fullScrolls.getOrDefault(scrollKey(), 0);
                            focusBrowser = true;
                            rebuildWidgets();
                        },
                        "Return to the block list without losing this mask editor.");
            blockLinkToolbar(contentTop + 23);
            mountPainter(contentX, contentTop + 46, contentW, contentBottom);
            viewTop = contentBottom;
            contentHeight = 0;
        } else {
            if (threePane) {
                int remaining = contentW;
                contentW = Math.clamp((remaining - 12) * 43 / 100, 184, 270);
                maskX = contentX + contentW + 12;
                maskW = remaining - contentW - 12;
            }
            blockControls();
            if (threePane) mountPainter(maskX, contentTop, maskW, contentBottom);
        }
    }

    private void pickTarget() {
        if (minecraft.hitResult instanceof net.minecraft.world.phys.BlockHitResult hit
                && minecraft.level != null
                && !minecraft.level.getBlockState(hit.getBlockPos()).isAir()) {
            openBlock(
                    BuiltInRegistries.BLOCK
                            .getKey(minecraft.level.getBlockState(hit.getBlockPos()).getBlock())
                            .toString());
            preview = true;
            previewGroup = "look";
            previewEntryGroup = "look";
            scroll = 0;
            rebuildWidgets();
        }
    }

    private void blockControls() {
        if (previewMode()) {
            previewSwitcher(List.of("look", "shape", "mask", "tools"));
            if (!previewChooser) {
                if (previewGroup.equals("mask")) {
                    if (contentW >= 196 && contentBottom - viewTop >= 150) {
                        mountPainter(contentX, viewTop, contentW, contentBottom);
                        viewTop = contentBottom;
                    } else {
                        y =
                                note(
                                        y,
                                        "Open the mask workspace for precise painting at this GUI scale. Your block and current edits are kept.");
                        toolRow(
                                "Texture mask",
                                maskSummary(),
                                "Expand mask",
                                () -> {
                                    rememberScroll();
                                    maskView = true;
                                    expandedMask = true;
                                    preview = false;
                                    rebuildWidgets();
                                });
                    }
                } else blockPart(previewGroup);
            }
            return;
        }
        boolean narrow = contentW < 240;
        int toolbar = contentTop + (narrow ? 0 : 15), half = (contentW - 4) / 2;
        button(
                contentX,
                toolbar,
                half,
                18,
                "Paint mask",
                this::openTexture,
                "Paint the parts of the texture that should glow. " + maskSummary());
        button(
                contentX + half + 4,
                toolbar,
                contentW - half - 4,
                18,
                "Tools",
                () -> {
                    blockExpanded.add("tools");
                    jumpId = "@tools";
                    rebuildWidgets();
                },
                "Saved block styles, bulk editing and texture painting.");
        blockLinkToolbar(toolbar + 22);
        viewTop = toolbar + 46;
        var links = linkSession(selectedBlock);
        if (!links.ready()) {
            y =
                    note(
                            y,
                            links.conflict()
                                    ? "Matching blocks have different saved settings. Choose a source in Link blocks, or keep this block independent."
                                    : "Finding blocks with matching textures...");
            toolRow(
                    "Linked configuration",
                    links.help(),
                    links.conflict() ? "Choose group source" : "Manage links",
                    this::manageLinks);
            return;
        }
        anchors.put("@look", new Row(contentX, 0, contentW, contentX, 0, contentW, 20));
        blockPart("look");
        if (disclosure("shape", "Lens effects", "Flare type, strength and reach", blockExpanded))
            blockPart("shape");
        if (disclosure(
                "tools",
                "Block tools",
                "Texture paint, saved styles and bulk editing",
                blockExpanded)) blockPart("tools");
        resetBlockStyle();
    }

    private void blockLinkToolbar(int top) {
        int half = (contentW - 4) / 2;
        var session = linkSession(selectedBlock);
        button(contentX, top, half, 18, session.label(), this::manageLinks, session.help())
                        .accessibleName =
                "Manage linked blocks";
        var sync =
                button(
                        contentX + half + 4,
                        top,
                        contentW - half - 4,
                        18,
                        "Sync Blocks",
                        this::syncBlocks,
                        "Share this texture's paint with linked blocks. Colors and other textures stay the same. Linked blocks may already share this paint.");
        sync.accessibleName = "Sync Blocks";
        sync.active =
                session.ready()
                        && BlockLinks.members(StillBloomClient.MASKS, selectedBlock).size() > 1;
    }

    private String maskSummary() {
        var p = StillBloomClient.MASKS.profile(selectedBlock);
        return p != null && p.textures.values().stream().anyMatch(TextureMasks.Mask::hasPaint)
                ? "Texture mask: painted."
                : "No painted pixels: bloom off.";
    }

    private void blockPart(String group) {
        if (!linkSession(selectedBlock).ready()) {
            y = note(y, linkSession(selectedBlock).help());
            toolRow(
                    "Link blocks",
                    "Choose which block's settings to share, or edit this block separately.",
                    "Manage links",
                    this::manageLinks);
            return;
        }
        var g = StillBloomClient.config;
        var o = editing;
        if (group.equals("look")) {
            overrideNumber(
                    "Strength",
                    0,
                    8,
                    () -> o.intensity,
                    v -> o.intensity = v,
                    g.intensity,
                    "",
                    "Brightness for this block type.");
            overrideOption(
                    "Shape",
                    () -> o.shape,
                    g.bloomShape,
                    v -> o.shape = v,
                    BloomConfig.Shape.values());
            colorControls();
            overrideNumber(
                    "Glow width",
                    8,
                    128,
                    () -> o.width,
                    v -> o.width = v,
                    g.glowWidth,
                    " px",
                    "How far this block's glow spreads.");
            overrideNumber(
                    "Keep block detail",
                    0,
                    1,
                    () -> o.preserveDetail,
                    v -> o.preserveDetail = v,
                    g.preserveDetail,
                    "",
                    "Turn up to keep this block's texture easier to see through the glow.");
        } else if (group.equals("shape")) {
            overrideOption(
                    "Flare",
                    () -> o.flare,
                    g.flareStyle,
                    v -> o.flare = v,
                    BloomConfig.Flare.values());
            overrideNumber(
                    "Flare strength",
                    0,
                    2,
                    () -> o.flareStrength,
                    v -> o.flareStrength = v,
                    g.flareStrength,
                    "",
                    "How bright this block's flare is. Set to 0 to turn it off.");
            overrideNumber(
                    "Flare reach",
                    8,
                    128,
                    () -> o.flareLength,
                    v -> o.flareLength = v,
                    g.flareLength,
                    " px",
                    "How far the flare stretches from the light.");
        } else {
            toolRow(
                    "Linked blocks",
                    linkSession(selectedBlock).help(),
                    linkSession(selectedBlock).label(),
                    this::manageLinks);
            toolRow(
                    "Sync Blocks",
                    "Share this texture's paint with linked blocks. Keep their other settings.",
                    "Sync Blocks",
                    this::syncBlocks);
            toolRow(
                    "Texture mask",
                    "Paint which pixels can glow.",
                    "Paint texture mask",
                    this::openTexture);
            toolRow(
                    "Saved block styles",
                    "Save, load or share this block's style.",
                    "Open style library",
                    () -> {
                        apply();
                        minecraft.gui.setScreen(
                                new StylePresetsScreen(
                                        this,
                                        StylePresets.capture(editing, StillBloomClient.config)));
                    });
            toolRow(
                    "Copy to other blocks",
                    "Apply this style, color or shape to a selection.",
                    "Bulk edit blocks",
                    () -> {
                        apply();
                        minecraft.gui.setScreen(new BulkOverrideScreen(this, selectedBlock));
                    });
            if (previewMode()) resetBlockStyle();
        }
    }

    private void openTexture() {
        flushPainter();
        apply();
        rememberScroll();
        if (previewMode()) {
            previewGroup = "mask";
            previewChooser = false;
        } else {
            maskView = true;
            expandedMask = true;
        }
        rebuildWidgets();
    }

    private void toolRow(String title, String description, String text, Runnable action) {
        var r = row(y, title, description, () -> "");
        y += r.height();
        var b = new MenuButton(r.controlX(), 0, r.controlW(), 20, text, action);
        b.wrap = true;
        b.accessibleName = title;
        scrolling(b, r.controlY());
    }

    private void resetBlockStyle() {
        var r =
                row(
                        y,
                        "Reset block style",
                        confirmReset
                                ? "Reset this block's glow settings? Your paint and paint strength are kept."
                                : "Use the settings from Glow for this block. Keep your paint.",
                        () -> "");
        y += r.height();
        if (confirmReset) {
            int half = (r.controlW() - 4) / 2;
            var yes =
                    new MenuButton(
                            r.controlX(),
                            0,
                            half,
                            20,
                            "Reset style",
                            () -> {
                                StillBloomClient.MASKS.removeOverride(selectedBlock);
                                editing = new BloomOverride();
                                maskPending = true;
                                confirmReset = false;
                                PresetStatus.changed();
                                apply();
                                rebuildWidgets();
                            });
            yes.wrap = true;
            var no =
                    new MenuButton(
                            r.controlX() + half + 4,
                            0,
                            r.controlW() - half - 4,
                            20,
                            "Cancel",
                            () -> {
                                confirmReset = false;
                                rebuildWidgets();
                            });
            scrolling(yes, r.controlY());
            scrolling(no, r.controlY());
        } else {
            var b =
                    new MenuButton(
                            r.controlX(),
                            0,
                            r.controlW(),
                            20,
                            "Reset style to global",
                            () -> {
                                confirmReset = true;
                                rebuildWidgets();
                            });
            b.wrap = true;
            b.active = !editing.empty();
            inheritedStates.add(() -> b.active = !editing.empty());
            scrolling(b, r.controlY());
        }
    }

    private <T extends Enum<T>> void overrideOption(
            String name, Supplier<T> get, T global, Consumer<T> set, T[] values) {
        var r =
                row(
                        y,
                        name,
                        "Global uses the setting from Glow.",
                        () -> get.get() == null ? "Global" : "Custom");
        y += r.height();
        anchors.put("block/" + name, r);
        T value = get.get() == null ? global : get.get();
        int resetW = 48;
        String id = "block-choice/" + name;
        var b =
                new MenuButton(
                        r.controlX(),
                        0,
                        r.controlW() - resetW - 4,
                        20,
                        label(value) + " v",
                        () -> {
                            openChoice = Objects.equals(openChoice, id) ? null : id;
                            rebuildWidgets();
                        });
        b.accessibleName = name;
        b.settingId = id;
        b.setTooltip(
                Tooltip.create(
                        Component.literal(
                                "Choose "
                                        + name.toLowerCase(Locale.ROOT)
                                        + " or use the setting from Glow.")));
        scrolling(b, r.controlY());
        controls.put("block/" + name, b);
        fieldReset(
                r,
                resetW,
                () -> get.get() != null,
                () -> {
                    set.accept(null);
                    overrideChanged();
                    rebuildWidgets();
                });
        if (Objects.equals(openChoice, id)) {
            List<T> choices = new ArrayList<>();
            choices.add(null);
            choices.addAll(Arrays.asList(values));
            for (T choice : choices) {
                String title = choice == null ? "Global / " + label(global) : label(choice);
                var option =
                        new MenuButton(
                                contentX,
                                0,
                                contentW,
                                20,
                                title,
                                () -> {
                                    if (get.get() != choice) {
                                        set.accept(choice);
                                        overrideChanged();
                                    }
                                    closeChoice("block/" + name);
                                });
                option.selected = get.get() == choice;
                option.accessibleName = name + ": " + title;
                option.settingId = id + "/" + choice;
                scrolling(option, y);
                y += 24;
            }
            contentHeight = Math.max(contentHeight, y);
        }
    }

    private void overrideNumber(
            String name,
            float min,
            float max,
            Supplier<Float> get,
            Consumer<Float> set,
            float global,
            String suffix,
            String help) {
        var r = row(y, name, help, () -> get.get() == null ? "Global" : "Custom");
        y += r.height();
        int resetW = 48;
        anchors.put("block/" + name, r);
        var slider =
                new MenuSlider(
                        r.controlX(),
                        0,
                        r.controlW() - resetW - 4,
                        min,
                        max,
                        get.get() == null ? global : get.get(),
                        suffix,
                        value -> {
                            set.accept((float) value);
                            overrideChanged();
                        });
        controls.put("block/" + name, slider);
        slider.accessibleName = name;
        slider.setTooltip(
                Tooltip.create(
                        Component.literal(
                                help
                                        + " Drag to change this block. Global uses the setting from Glow.")));
        scrolling(slider, r.controlY());
        fieldReset(
                r,
                resetW,
                () -> get.get() != null,
                () -> {
                    set.accept(null);
                    overrideChanged();
                    rebuildWidgets();
                });
    }

    private void fieldReset(Row r, int w, BooleanSupplier custom, Runnable run) {
        var b = new MenuButton(r.controlX() + r.controlW() - w, 0, w, 20, "Global", run);
        b.active = custom.getAsBoolean();
        inheritedStates.add(() -> b.active = custom.getAsBoolean());
        b.setTooltip(
                Tooltip.create(
                        Component.literal(
                                "Use the setting from Glow for this control. Keep your other settings and paint.")));
        scrolling(b, r.controlY());
    }

    private void colorControls() {
        var r =
                row(
                        y,
                        "Bloom color",
                        "Change the glow color without recoloring the block.",
                        () ->
                                editing.color == null
                                        ? "Natural"
                                        : "#" + BloomColors.hex(editing.color));
        y += r.height();
        int resetW = 49;
        var choose =
                new MenuButton(
                        r.controlX(),
                        0,
                        r.controlW() - resetW - 4,
                        20,
                        "Pick color",
                        () -> {
                            apply();
                            minecraft.gui.setScreen(
                                    new BloomColorScreen(
                                            this,
                                            blockName(selectedBlock),
                                            editing.color,
                                            color -> {
                                                editing.color = color;
                                                overrideChanged();
                                                apply();
                                            }));
                        });
        choose.wrap = true;
        choose.accessibleName = "Bloom color";
        scrolling(choose, r.controlY());
        var natural =
                new MenuButton(
                        r.controlX() + r.controlW() - resetW,
                        0,
                        resetW,
                        20,
                        "Natural",
                        () -> {
                            editing.color = null;
                            overrideChanged();
                            rebuildWidgets();
                        });
        natural.active = editing.color != null;
        natural.setTooltip(
                Tooltip.create(
                        Component.literal(
                                "Use the texture's own color for the glow. Keep your other settings and paint.")));
        scrolling(natural, r.controlY());
    }

    void finishColorEdit() {
        save();
    }

    void refreshBlockStyle() {
        if (selectedBlock != null) loadEditing();
    }

    private void overrideChanged() {
        editing.sanitize();
        StillBloomClient.MASKS.setOverride(selectedBlock, editing);
        maskPending = true;
        browserDirty = true;
        PresetStatus.changed();
        for (var update : inheritedStates) update.run();
        updateBrowserBadges();
    }

    void applyPreset(BloomOverride style) {
        editing = style;
        overrideChanged();
        apply();
        save();
    }

    private static String blockName(String id) {
        return BuiltInRegistries.BLOCK.getValue(Identifier.parse(id)).getName().getString();
    }

    private static boolean customized(String id) {
        var p = StillBloomClient.MASKS.profile(id);
        return p != null
                && (!p.textures.isEmpty()
                        || p.strength != 1
                        || p.override != null && !p.override.empty());
    }

    private String browserKey() {
        return blockSource + "/" + customOnly + "/" + query;
    }

    private int blockMaximum() {
        return Math.max(0, filtered.size() * 30 - 3 - (browserBottom - browserTop));
    }

    private void filter() {
        browserDirty = false;
        blockIndex = BlockCatalog.current();
        filtered = blockIndex.search(query, blockSource, id -> !customOnly || customized(id));
        long now = System.nanoTime();
        String key = browserKey();
        if (!Objects.equals(activeBrowserKey, key)) {
            activeBrowserKey = key;
            blockScroll.reset(browserOffsets.getOrDefault(key, 0), blockMaximum(), now);
        } else blockScroll.bounds(blockMaximum(), now);
        if (revealSelection) {
            int at = filtered.indexOf(selectedBlock),
                    px = blockScroll.pixels(now),
                    h = browserBottom - browserTop;
            if (at >= 0 && (at * 30 < px || at * 30 + 27 > px + h))
                blockScroll.reset(at * 30, blockMaximum(), now);
            revealSelection = false;
        }
        blockPixels = blockScroll.pixels(now);
        blockOffset = blockPixels / 30;
        blockRows();
    }

    private void updateBrowserBadges() {
        for (var row : blockRows) {
            String id = row.settingId.substring("block/".length());
            boolean custom = customized(id);
            row.badge = browserW >= 160 ? (custom ? "Custom" : "Global") : custom ? "*" : null;
        }
    }

    private int visibleBlockRows() {
        return Math.max(1, (browserBottom - browserTop) / 30);
    }

    private void blockRows() {
        boolean owned = blockRows.contains(getFocused());
        String focusedId = getFocused() instanceof MenuButton focused ? focused.settingId : null;
        for (var row : blockRows) removeWidget(row);
        blockRows.clear();
        int count = visibleBlockRows() + 2;
        blockOffset = blockPixels / 30;
        for (int i = 0; i < count && i + blockOffset < filtered.size(); i++) {
            String id = filtered.get(i + blockOffset);
            boolean custom = customized(id);
            var b =
                    button(
                            browserX,
                            browserTop + i * 30 - blockPixels % 30,
                            browserW,
                            27,
                            blockName(id),
                            () -> {
                                openBlock(id);
                                rebuildWidgets();
                            },
                            id + " / " + (custom ? "Customized" : "Global appearance"));
            b.secondary = browserW < 140 ? id.substring(id.indexOf(':') + 1) : id;
            b.badge = browserW >= 160 ? (custom ? "Custom" : "Global") : custom ? "*" : null;
            b.selected = id.equals(selectedBlock);
            b.settingId = "block/" + id;
            b.clipTo(browserTop, browserBottom);
            b.visible = b.getY() < browserBottom && b.getY() + b.getHeight() > browserTop;
            blockRows.add(b);
        }
        if (owned) {
            var replacement =
                    blockRows.stream()
                            .filter(row -> Objects.equals(row.settingId, focusedId))
                            .findFirst()
                            .orElse(null);
            setFocused(replacement == null ? search : replacement);
        }
    }

    private void updateBlockScroll() {
        long now = System.nanoTime();
        blockScroll.bounds(blockMaximum(), now);
        int pixels = blockScroll.pixels(now), first = pixels / 30;
        boolean changed = first != blockOffset;
        blockPixels = pixels;
        blockOffset = first;
        if (changed) blockRows();
        else
            for (int i = 0; i < blockRows.size(); i++) {
                var b = blockRows.get(i);
                b.setY(browserTop + i * 30 - pixels % 30);
                b.visible = b.getY() < browserBottom && b.getY() + b.getHeight() > browserTop;
            }
        browserOffsets.put(browserKey(), pixels);
    }

    private void scrollBlocks(int amount) {
        blockScroll.reset(blockScroll.target() + amount * 30, blockMaximum(), System.nanoTime());
        updateBlockScroll();
    }

    private boolean inBrowser(double x, double y) {
        return page == 2
                && !previewMode()
                && showBlockBrowser
                && x >= browserX
                && x <= browserX + browserW + 7
                && y >= browserTop
                && y < browserBottom;
    }

    @Override
    public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        if (isClosing()) return true;
        if (inBrowser(x, y)) {
            blockScroll.wheel(-vertical * 90, blockMaximum(), System.nanoTime());
            return true;
        }
        if (visiblePainter != null && visiblePainter.isMouseOver(x, y))
            return visiblePainter.mouseScrolled(x, y, horizontal, vertical);
        return super.mouseScrolled(x, y, horizontal, vertical);
    }

    @Override
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent e, boolean twice) {
        if (isClosing()) return true;
        if (e.button() == com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT
                && inBrowser(e.x(), e.y())
                && e.x() >= browserX + browserW) {
            browserDrag = true;
            int thumb = browserThumb();
            double top =
                    browserTop
                            + (browserBottom - browserTop - thumb)
                                    * blockPixels
                                    / (double) Math.max(1, blockMaximum());
            browserGrab = e.y() >= top && e.y() < top + thumb ? e.y() - top : thumb / 2.0;
            moveBrowserScrollbar(e.y());
            return true;
        }
        return super.mouseClicked(e, twice);
    }

    private int browserThumb() {
        int h = browserBottom - browserTop;
        return Math.min(h, Math.max(10, h * h / Math.max(1, filtered.size() * 30 - 3)));
    }

    private void moveBrowserScrollbar(double mouseY) {
        blockScroll.reset(
                (mouseY - browserTop - browserGrab)
                        / Math.max(1, browserBottom - browserTop - browserThumb())
                        * blockMaximum(),
                blockMaximum(),
                System.nanoTime());
        updateBlockScroll();
    }

    @Override
    public boolean mouseDragged(
            net.minecraft.client.input.MouseButtonEvent e, double dx, double dy) {
        if (isClosing()) return true;
        if (visiblePainter != null && visiblePainter.hasCanvasDrag())
            return visiblePainter.mouseDragged(e, dx, dy);
        if (browserDrag) {
            moveBrowserScrollbar(e.y());
            return true;
        }
        return super.mouseDragged(e, dx, dy);
    }

    @Override
    public boolean mouseReleased(net.minecraft.client.input.MouseButtonEvent e) {
        browserDrag = false;
        boolean handled = super.mouseReleased(e);
        flushPainter();
        return handled;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (isClosing()) return true;
        if (visiblePainter != null
                && getFocused() == visiblePainter
                && (event.key() == InputConstants.KEY_PAGEUP
                        || event.key() == InputConstants.KEY_PAGEDOWN))
            return visiblePainter.keyPressed(event);
        if (event.key() == InputConstants.KEY_RETURN
                && page == 2
                && !previewMode()
                && getFocused() == search
                && !filtered.isEmpty()) {
            openBlock(filtered.get(Math.min(blockOffset, filtered.size() - 1)));
            focusEditor = true;
            setFocused(null);
            rebuildWidgets();
            return true;
        }
        if (page == 2
                && !previewMode()
                && (getFocused() == search || blockRows.contains(getFocused()))) {
            if (event.key() == InputConstants.KEY_PAGEDOWN
                    || event.key() == InputConstants.KEY_PAGEUP) {
                scrollBlocks(
                        (event.key() == InputConstants.KEY_PAGEDOWN ? 1 : -1) * visibleBlockRows());
                return true;
            }
        }
        return super.keyPressed(event);
    }

    @Override
    protected String footer() {
        if (saveFailed) return "Save failed: " + saveError;
        if (visiblePainter != null && visiblePainter.hasSaveFailure())
            return "Mask save failed - check the log";
        String recovery =
                SetupPersistence.recoveryMessage(StillBloomClient.CONFIG_PATH.getParent());
        if (!recovery.isEmpty()) return recovery;
        return page == 2
                ? !blockMessage.isEmpty()
                        ? blockMessage
                        : filtered.size()
                                + " blocks / "
                                + (selectedBlock == null
                                        ? "Choose a block"
                                        : linkSession(selectedBlock).label())
                : PresetStatus.status(this);
    }

    @Override
    protected void extractWorkspaceBackground(
            GuiGraphicsExtractor g, int x, int mouseY, float tick) {
        if (page == 2 && !previewMode() && showBlockBrowser) {
            updateBlockScroll();
            g.fill(browserX - 1, contentTop - 1, browserX + browserW + 1, contentTop + 20, SURFACE);
            g.fill(
                    browserX + browserW + 7,
                    contentTop,
                    browserX + browserW + 8,
                    contentBottom,
                    0xFF293642);
            if (selectedBlock != null && !maskView && !expandedMask && contentW >= 240) {
                g.text(
                        font,
                        shorten(font, blockName(selectedBlock), contentW - 67),
                        contentX,
                        contentTop + 1,
                        TEXT,
                        false);
                g.text(
                        font,
                        editing.empty() ? "Global style" : "Custom",
                        contentX + contentW - (editing.empty() ? 66 : 36),
                        contentTop + 1,
                        editing.empty() ? MUTED : VIOLET,
                        false);
            }
            if (filtered.isEmpty())
                g.textWithWordWrap(
                        font,
                        Component.literal(
                                "No matches in this source. Try another mod or clear the search."),
                        browserX + 3,
                        browserTop + 4,
                        browserW - 6,
                        MUTED);
            int h = browserBottom - browserTop, count = visibleBlockRows();
            if (blockMaximum() > 0) {
                int thumb = browserThumb(),
                        top = browserTop + (h - thumb) * blockPixels / blockMaximum();
                g.fill(
                        browserX + browserW + 2,
                        browserTop,
                        browserX + browserW + 4,
                        browserBottom,
                        0xFF24313D);
                g.fill(browserX + browserW + 2, top, browserX + browserW + 4, top + thumb, ACCENT);
            }
        }
    }

    private void installSetup(SetupPresets.Setup setup) {
        var copy = SetupPresets.copy(setup);
        boolean enabled = StillBloomClient.config.enabled;
        StillBloomClient.config = copy.settings();
        StillBloomClient.config.enabled = enabled;
        StillBloomClient.MASKS = copy.masks();
        StillBloomClient.ITEM_MASKS = copy.itemMasks();
        if (selectedBlock != null) loadEditing();
        pending = true;
        maskPending = true;
        PresetStatus.changed();
        apply();
    }

    boolean applySetup(SetupPresets.Setup setup) {
        var staged = SetupPresets.copy(setup);
        staged.settings().enabled = StillBloomClient.config.enabled;
        var result = SetupPersistence.save(StillBloomClient.CONFIG_PATH.getParent(), staged, true);
        saveFailed = !result.success();
        saveError = result.message();
        if (saveFailed) return false;
        installSetup(staged);
        return true;
    }

    String saveError() {
        return saveError;
    }

    boolean hasSaveFailure() {
        return saveFailed;
    }

    void previewSetup(SetupPresets.Setup setup) {
        installSetup(setup);
    }

    private void changed() {
        pending = true;
        PresetStatus.changed();
    }

    private void apply() {
        if (pending) {
            StillBloomClient.config.sanitize();
            StillBloomClient.RENDERER.invalidate();
            pending = false;
        }
        if (maskPending) {
            StillBloomClient.RENDERER.sourcesChanged();
            maskPending = false;
        }
    }

    private void save() {
        apply();
        var setup =
                new SetupPresets.Setup(
                        StillBloomClient.config,
                        StillBloomClient.MASKS,
                        StillBloomClient.ITEM_MASKS);
        var result = SetupPersistence.save(StillBloomClient.CONFIG_PATH.getParent(), setup, false);
        saveFailed = !result.success();
        saveError = result.message();
    }

    @Override
    public void tick() {
        if (page == 2 && selectedBlock != null && linkSession(selectedBlock).tick()) {
            loadEditing();
            rebuildWidgets();
        }
        if (visiblePainter != null) visiblePainter.tick();
        if (++ticks % 3 == 0) {
            apply();
            if (visiblePainter != null && painterRevision != visiblePainter.editRevision()) {
                painterRevision = visiblePainter.editRevision();
                browserDirty = true;
                updateBrowserBadges();
            }
            if (browserDirty && customOnly && page == 2 && !previewMode()) filter();
        }
    }

    @Override
    public void removed() {
        rememberScroll();
        suspendPainters();
        save();
    }

    @Override
    public void onClose() {
        if (isClosing()) return;
        flushPainter();
        save();
        if (!saveFailed) requestClose(() -> minecraft.gui.setScreen(parent));
    }
}
