package dev.stillbloom.gui;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.NativeImage;

import dev.stillbloom.StillBloomClient;
import dev.stillbloom.gui.layout.BrushFootprint;
import dev.stillbloom.gui.layout.MaskCanvasTransform;
import dev.stillbloom.gui.layout.MaskEditorLayout;
import dev.stillbloom.mask.BlockLinks;
import dev.stillbloom.mask.ItemMasks;
import dev.stillbloom.mask.LinkedMaskEdits;
import dev.stillbloom.mask.TextureMasks;
import dev.stillbloom.model.BlockMaskVariants;
import dev.stillbloom.model.ItemModelTextures;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.BlockHitResult;

import java.util.*;

// I edit by texture, since a model's faces may share or use different textures.
public final class TextureEditorScreen extends BloomMenuScreen
        implements net.minecraft.client.gui.narration.NarratableEntry {
    private static final int PAINTED_MASK_FILL = 0x68B3EF8A;
    private static final int PAINTED_MASK_EDGE = 0xD03E8254;
    private final Screen parent;
    private boolean embedded;
    private int embeddedX, embeddedY, embeddedW, embeddedH;
    private Runnable expandEditor;
    private Object retainedContents, retainedGeneration, observedStore;
    private TextureMasks.Mask observedMask;
    private String observedTexture;

    private void observeMask() {
        observedStore = itemMode() ? StillBloomClient.ITEM_MASKS : StillBloomClient.MASKS;
        observedTexture = sprite() == null ? null : texture();
        observedMask = mask();
    }

    private void refreshMaskContext() {
        Object store = itemMode() ? StillBloomClient.ITEM_MASKS : StillBloomClient.MASKS;
        String texture = sprite() == null ? null : texture();
        if (observedStore != null
                && (observedStore != store
                        || Objects.equals(texture, observedTexture) && observedMask != mask())) {
            undo.clear();
            clearSelection();
        }
        observeMask();
    }

    // I share painting and Undo with the full editor so both views behave the same way.
    void mount(int x, int y, int w, int h, int screenW, int screenH, Runnable expand) {
        finishStroke();
        embedded = true;
        embeddedX = x;
        embeddedY = y;
        embeddedW = w;
        embeddedH = h;
        expandEditor = expand;
        init(screenW, screenH);
    }

    void suspendEmbedded() {
        finishStroke();
        refreshMaskContext();
        commit();
        releaseSnapshot();
    }

    void flushEmbedded() {
        finishStroke();
        if (pending || unsaved || saveFailed) commit();
    }

    @Override
    protected boolean embeddedPanel() {
        return embedded;
    }

    @Override
    protected int scrollViewportWidth() {
        return embedded && moreTools ? controlW : contentW;
    }

    @Override
    public boolean isMouseOver(double x, double y) {
        return embedded
                ? x >= embeddedX
                        && y >= embeddedY
                        && x < embeddedX + embeddedW
                        && y < embeddedY + embeddedH
                : super.isMouseOver(x, y);
    }

    @Override
    public net.minecraft.client.gui.navigation.ScreenRectangle getRectangle() {
        return embedded
                ? new net.minecraft.client.gui.navigation.ScreenRectangle(
                        embeddedX, embeddedY, embeddedW, embeddedH)
                : super.getRectangle();
    }

    @Override
    public NarrationPriority narrationPriority() {
        return isFocused() ? NarrationPriority.FOCUSED : NarrationPriority.NONE;
    }

    @Override
    public void updateNarration(net.minecraft.client.gui.narration.NarrationElementOutput out) {
        out.add(
                net.minecraft.client.gui.narration.NarratedElementType.TITLE,
                "Texture mask editor");
        if (getFocused() instanceof net.minecraft.client.gui.narration.NarratableEntry entry)
            entry.updateNarration(out.nest());
    }

    private final String blockId;
    private final ItemStack itemStack;
    private List<ItemModelTextures.Texture> sprites = List.of();
    private int textureIndex,
            controlX,
            controlW,
            previewX,
            previewY,
            previewW,
            previewH,
            brush = 1,
            ticks;
    private int lastX = -1, lastY = -1;
    private boolean erase, overlay = true, painting, pending, strokeErase;
    private boolean saveFailed, unsaved;
    private boolean maskDirty;
    private BlockLinksScreen.Session links;
    private BlockMaskVariants.TextureQuery textureInspection;
    private long editRevision;

    long editRevision() {
        return editRevision;
    }

    boolean hasSaveFailure() {
        return saveFailed;
    }

    boolean hasCanvasDrag() {
        return painting || panning;
    }

    private boolean colorMode, connected = true, moreTools;
    private MaskEditorLayout canvasLayout;
    private MaskCanvasTransform canvasTransform;
    private int canvasLeft, canvasTop, canvasRight, canvasBottom;
    private double zoom = 1, centerU = .5, centerV = .5;
    private boolean panning, canvasFocused;

    private record CanvasView(double zoom, double u, double v) {}

    private final Map<String, CanvasView> canvasViews = new HashMap<>();
    private int tolerance = 12, seedX = -1, seedY = -1;
    private BitSet selection = new BitSet();
    private int[] snapshotPixels;
    private Object snapshotContents;
    private Object snapshotGeneration;
    private int snapshotWidth, snapshotHeight;
    private Identifier snapshotId;
    private MenuButton paintButton, eraseButton, clearSelectionButton, undoButton;
    private MenuSlider toleranceSlider, strengthSlider;

    private record Undo(TextureMasks.Mask mask, LinkedMaskEdits.Snapshot linked) {}

    private final Deque<Undo> undo = new ArrayDeque<>();

    public TextureEditorScreen(Screen parent) {
        this(parent, targetedBlock());
    }

    public TextureEditorScreen(Screen parent, String block) {
        super("Texture bloom masks");
        this.parent = parent;
        this.blockId = block;
        this.itemStack = null;
    }

    private TextureEditorScreen(Screen parent, ItemStack stack) {
        super("Item bloom masks");
        this.parent = parent;
        this.itemStack = stack.copy();
        this.blockId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    public static TextureEditorScreen forItem(Screen parent, ItemStack stack) {
        return new TextureEditorScreen(parent, stack);
    }

    private boolean itemMode() {
        return itemStack != null;
    }

    private static String targetedBlock() {
        var hit = net.minecraft.client.Minecraft.getInstance().hitResult;
        var level = net.minecraft.client.Minecraft.getInstance().level;
        if (hit instanceof BlockHitResult b
                && level != null
                && !level.getBlockState(b.getBlockPos()).isAir())
            return BuiltInRegistries.BLOCK
                    .getKey(level.getBlockState(b.getBlockPos()).getBlock())
                    .toString();
        return "minecraft:sea_lantern";
    }

    @Override
    protected boolean focusedTool() {
        return true;
    }

    @Override
    protected int section() {
        return itemMode() ? 4 : 2;
    }

    @Override
    protected String heading() {
        return (itemMode() ? itemStack.getHoverName().getString() : block().getName().getString())
                + " / "
                + (itemMode() ? "Item mask" : "Texture mask");
    }

    @Override
    protected BloomSettingsScreen settingsOwner() {
        return findSettingsOwner(parent);
    }

    @Override
    protected void navigate(int page) {
        finishStroke();
        commit();
        var menu = settingsOwner();
        if (menu == null) menu = new BloomSettingsScreen(parent);
        minecraft.gui.setScreen(menu);
        menu.navigate(page);
    }

    @Override
    protected void init() {
        if (embedded) {
            initEmbedded();
            return;
        }
        frame();
        loadSprites();
        refreshMaskContext();
        controlX = contentX;
        controlW = MaskEditorLayout.toolWidth(contentW);
        viewTop = contentTop + 26;
        toleranceSlider = null;
        clearSelectionButton = null;
        strengthSlider = null;
        int backW = 58, navW = 18, undoW = 42, textureX = contentX + backW + navW * 2 + 12;
        button(
                contentX,
                contentTop,
                backW,
                20,
                "< Editor",
                this::onClose,
                "Return to the same block or held-item editor. Your paint is saved.");
        button(
                contentX + backW + 4,
                contentTop,
                navW,
                20,
                "<",
                () -> switchTexture(-1),
                "Previous model texture. Each texture has its own painted mask.");
        button(
                contentX + backW + navW + 8,
                contentTop,
                navW,
                20,
                ">",
                () -> switchTexture(1),
                "Next model texture. Each texture has its own painted mask.");
        var textureButton =
                button(
                        textureX,
                        contentTop,
                        contentX + contentW - undoW - 4 - textureX,
                        20,
                        sprites.isEmpty()
                                ? "No texture"
                                : "Texture " + (textureIndex + 1) + " / " + sprites.size(),
                        () -> switchTexture(1),
                        sprite() == null
                                ? "This model has no editable texture."
                                : texture()
                                        + " ("
                                        + sprite().width()
                                        + " x "
                                        + sprite().height()
                                        + "). Click to cycle; the arrows also change textures."
                                        + animatedPreviewNote());
        textureButton.active = !sprites.isEmpty();
        textureButton.accessibleName = "Model texture";
        undoButton =
                button(
                        contentX + contentW - undoW,
                        contentTop,
                        undoW,
                        20,
                        "Undo",
                        this::undo,
                        "Undo the last edit on this texture, including a brush stroke or an entire color selection.");
        undoButton.active = editable() && !undo.isEmpty();
        ensureSnapshot();
        int half = (controlW - 4) / 2, y = 0;
        if (!itemMode()) y = linkControls(y, false);
        scrolled(
                                controlX,
                                y,
                                half,
                                "Brush",
                                () -> mode(false),
                                "Freehand paint. Drag over the texture; right-click erases.")
                        .selected =
                !colorMode;
        scrolled(
                                controlX + half + 4,
                                y,
                                controlW - half - 4,
                                "Color",
                                () -> mode(true),
                                "Select similar colors, then Paint or Erase. Cyan shows your selection. Green shows what glows.")
                        .selected =
                colorMode;
        y += 22;
        paintButton =
                tool(
                        controlX,
                        y,
                        half,
                        "Paint",
                        () -> {
                            if (colorMode) applySelection(false);
                            else {
                                erase = false;
                                updateSelectionButtons();
                            }
                        });
        eraseButton =
                tool(
                        controlX + half + 4,
                        y,
                        controlW - half - 4,
                        "Erase",
                        () -> {
                            if (colorMode) applySelection(true);
                            else {
                                erase = true;
                                updateSelectionButtons();
                            }
                        });
        y += 22;
        if (colorMode) {
            var area =
                    scrolled(
                            controlX,
                            y,
                            controlW,
                            connected ? "Connected" : "All matches",
                            () -> {
                                connected = !connected;
                                refreshSelection();
                                rebuildWidgets();
                            },
                            "Connected selects touching matching pixels. All matches selects similar colors anywhere in the texture.");
            area.active = snapshotPixels != null;
            area.accessibleName = "Selection area";
            y += 32;
            toleranceSlider =
                    new MenuSlider(
                            controlX,
                            0,
                            controlW,
                            0,
                            100,
                            tolerance,
                            "%",
                            v -> {
                                int next = (int) Math.round(v);
                                if (next != tolerance) {
                                    tolerance = next;
                                    refreshSelection();
                                }
                            });
            toleranceSlider.accessibleName = "Color tolerance";
            toleranceSlider.setTooltip(
                    Tooltip.create(
                            Component.literal(
                                    "0% matches the exact color. Higher values select more similar colors. Transparent pixels are excluded.")));
            scrolling(toleranceSlider, y);
            toleranceSlider.active = snapshotPixels != null;
            y += 26;
        } else {
            var size =
                    scrolled(
                            controlX,
                            y,
                            controlW,
                            "Size: " + brush + " px",
                            () -> {
                                brush = brush == 4 ? 1 : brush + 1;
                                rebuildWidgets();
                            },
                            "Brush size: 1 to 4 texture pixels.");
            size.active = editable();
            size.accessibleName = "Brush size";
            y += 22;
        }
        var more =
                scrolled(
                        controlX,
                        y,
                        controlW,
                        moreTools ? "- More tools" : "+ More tools",
                        () -> {
                            finishStroke();
                            moreTools = !moreTools;
                            rebuildWidgets();
                        },
                        "Mask visibility, fill, clear, mask removal and source strength. Color mode also has Deselect.");
        more.selected = moreTools;
        more.accessibleName = "Mask editing tools";
        y += 24;
        if (moreTools) {
            if (!itemMode()) y = syncControl(y);
            if (colorMode) {
                clearSelectionButton =
                        scrolled(
                                controlX,
                                y,
                                controlW,
                                "Deselect",
                                this::clearSelection,
                                "Clear the cyan selection without changing the saved green bloom mask.");
                y += 22;
            }
            var visibility =
                    scrolled(
                            controlX,
                            y,
                            controlW,
                            overlay ? "Mask: shown" : "Mask: hidden",
                            () -> {
                                overlay = !overlay;
                                rebuildWidgets();
                            },
                            "Show or hide the green painted-mask overlay. Cyan still marks the pending selection.");
            visibility.selected = overlay;
            visibility.accessibleName = "Mask overlay";
            y += 22;
            tool(controlX, y, half, "Fill", () -> fill(true));
            tool(controlX + half + 4, y, controlW - half - 4, "Clear", () -> fill(false));
            y += 22;
            tool(
                    controlX,
                    y,
                    controlW,
                    itemMode() ? "Automatic" : "Remove mask",
                    () -> {
                        if (sprite() != null) {
                            remember();
                            resetMask();
                            commit();
                            rebuildWidgets();
                        }
                    });
            y += 32;
            strengthSlider =
                    new MenuSlider(controlX, 0, controlW, 0, 8, strength(), "x", this::setStrength);
            strengthSlider.accessibleName = "Painted source strength";
            strengthSlider.setTooltip(
                    Tooltip.create(Component.literal("How strongly the painted parts glow.")));
            scrolling(strengthSlider, y);
            strengthSlider.active = editable();
            y += 24;
        }
        if (moreTools) y = zoomControls(y);
        contentHeight = y;
        finishLayout();
        layoutPreview();
        updateSelectionButtons();
    }

    private void initEmbedded() {
        frame();
        clearWidgets();
        clearScrolling();
        loadSprites();
        refreshMaskContext();
        contentX = embeddedX;
        contentW = embeddedW;
        contentTop = embeddedY;
        contentBottom = embeddedY + embeddedH;
        controlX = contentX;
        controlW = 96;
        scroll = Math.max(0, scroll);
        toleranceSlider = null;
        clearSelectionButton = null;
        strengthSlider = null;
        int nav = 18, undoW = 40, textureX = contentX + nav * 2 + 6;
        button(
                                contentX,
                                contentTop,
                                nav,
                                18,
                                "<",
                                () -> switchTexture(-1),
                                "Previous model texture.")
                        .active =
                !sprites.isEmpty();
        button(
                                contentX + nav + 2,
                                contentTop,
                                nav,
                                18,
                                ">",
                                () -> switchTexture(1),
                                "Next model texture.")
                        .active =
                !sprites.isEmpty();
        var textureButton =
                button(
                        textureX,
                        contentTop,
                        contentW - (textureX - contentX) - undoW - 3,
                        18,
                        sprites.isEmpty() ? "None" : (textureIndex + 1) + " / " + sprites.size(),
                        () -> switchTexture(1),
                        sprite() == null
                                ? "No editable model texture."
                                : texture()
                                        + " / "
                                        + sprite().width()
                                        + " x "
                                        + sprite().height()
                                        + animatedPreviewNote());
        textureButton.accessibleName = "Model texture";
        textureButton.active = !sprites.isEmpty();
        undoButton =
                button(
                        contentX + contentW - undoW,
                        contentTop,
                        undoW,
                        18,
                        "Undo",
                        this::undo,
                        "Undo the last mask edit. Ctrl+Z also works while this pane is focused.");
        undoButton.active = editable() && !undo.isEmpty();
        boolean compactToolbar = embeddedH < 160 && contentW >= 240;
        int top = contentTop + 22,
                columns = compactToolbar ? 5 : contentW >= 196 ? 4 : 2,
                cell = (contentW - (columns - 1) * 3) / columns;
        var brushButton =
                button(
                        contentX,
                        top,
                        cell,
                        18,
                        "Brush",
                        () -> mode(false),
                        "Freehand paint. Right-click erases.");
        brushButton.selected = !colorMode;
        var colorButton =
                button(
                        contentX + cell + 3,
                        top,
                        columns == 2 ? contentW - cell - 3 : cell,
                        18,
                        "Color",
                        () -> mode(true),
                        "Select matching colors, then choose Paint or Erase.");
        colorButton.selected = colorMode;
        int actionTop = columns >= 4 ? top : top + 21,
                actionX = columns >= 4 ? contentX + (cell + 3) * 2 : contentX;
        paintButton =
                button(
                        actionX,
                        actionTop,
                        cell,
                        18,
                        "Paint",
                        () -> {
                            if (colorMode) applySelection(false);
                            else {
                                erase = false;
                                updateSelectionButtons();
                            }
                        },
                        "Paint the parts that should glow. Cyan shows your color selection.");
        eraseButton =
                button(
                        actionX + cell + 3,
                        actionTop,
                        compactToolbar ? cell : contentX + contentW - actionX - cell - 3,
                        18,
                        "Erase",
                        () -> {
                            if (colorMode) applySelection(true);
                            else {
                                erase = true;
                                updateSelectionButtons();
                            }
                        },
                        "Erase paint to stop those parts glowing. Right-click also erases.");
        top = actionTop + 22;
        if (compactToolbar) {
            var more =
                    button(
                            contentX + (cell + 3) * 4,
                            actionTop,
                            contentW - (cell + 3) * 4,
                            18,
                            moreTools ? "Mask" : "Tools",
                            () -> {
                                finishStroke();
                                moreTools = !moreTools;
                                scroll = 0;
                                rebuildWidgets();
                            },
                            "Brush size, mask overlay, color selection tools, fill, clear, mask removal and source strength.");
            more.selected = moreTools;
            more.accessibleName = "Mask editing tools";
            viewTop = top + 1;
        } else {
            int expandW = expandEditor == null ? 0 : contentW >= 220 ? 52 : 34,
                    sizeW = contentW >= 220 ? 68 : 48,
                    moreW = contentW - sizeW - expandW - (expandW > 0 ? 6 : 3);
            var size =
                    button(
                            contentX,
                            top,
                            sizeW,
                            18,
                            "Size: " + brush,
                            () -> {
                                brush = brush == 4 ? 1 : brush + 1;
                                rebuildWidgets();
                            },
                            "Brush size: 1 to 4 texture pixels.");
            size.active = editable() && !colorMode;
            size.accessibleName = "Brush size";
            var more =
                    button(
                            contentX + sizeW + 3,
                            top,
                            moreW,
                            18,
                            moreTools ? "< Mask" : "Tools",
                            () -> {
                                finishStroke();
                                moreTools = !moreTools;
                                scroll = 0;
                                rebuildWidgets();
                            },
                            "Mask overlay, color tolerance, selection area, fill, clear, mask removal and source strength.");
            more.selected = moreTools;
            more.accessibleName = "Mask editing tools";
            if (expandEditor != null)
                button(
                        contentX + contentW - expandW,
                        top,
                        expandW,
                        18,
                        contentW >= 220 ? "Expand" : "[+]",
                        () -> {
                            finishStroke();
                            expandEditor.run();
                        },
                        "Use more of this workspace for precise painting. Your current texture, brush and Undo history stay here.");
            viewTop = top + 23;
        }
        ensureSnapshot();
        int y = 0, half = (controlW - 4) / 2;
        if (moreTools) {
            if (contentW < 220) controlW = contentW - 8;
            half = (controlW - 4) / 2;
            y = linkControls(y, true);
            if (compactToolbar) {
                var size =
                        scrolled(
                                controlX,
                                y,
                                controlW,
                                "Size: " + brush + " px",
                                () -> {
                                    brush = brush == 4 ? 1 : brush + 1;
                                    rebuildWidgets();
                                },
                                "Brush size: 1 to 4 texture pixels.");
                size.active = editable() && !colorMode;
                size.accessibleName = "Brush size";
                y += 22;
                if (expandEditor != null) {
                    scrolled(
                            controlX,
                            y,
                            controlW,
                            "Expand",
                            () -> {
                                finishStroke();
                                expandEditor.run();
                            },
                            "Expand the mask workspace.");
                    y += 22;
                }
            }
            if (colorMode) {
                var area =
                        scrolled(
                                controlX,
                                y,
                                controlW,
                                connected ? "Connected" : "All matches",
                                () -> {
                                    connected = !connected;
                                    refreshSelection();
                                    rebuildWidgets();
                                },
                                "Select connected colors or matching pixels across the texture.");
                area.active = snapshotPixels != null;
                area.accessibleName = "Selection area";
                y += 32;
                toleranceSlider =
                        new MenuSlider(
                                controlX,
                                0,
                                controlW,
                                0,
                                100,
                                tolerance,
                                "%",
                                v -> {
                                    int next = (int) Math.round(v);
                                    if (next != tolerance) {
                                        tolerance = next;
                                        refreshSelection();
                                    }
                                });
                toleranceSlider.accessibleName = "Color tolerance";
                scrolling(toleranceSlider, y);
                toleranceSlider.active = snapshotPixels != null;
                y += 26;
                clearSelectionButton =
                        scrolled(
                                controlX,
                                y,
                                controlW,
                                "Deselect",
                                this::clearSelection,
                                "Clear the pending cyan selection.");
                y += 22;
            }
            var visibility =
                    scrolled(
                            controlX,
                            y,
                            controlW,
                            overlay ? "Mask: shown" : "Mask: hidden",
                            () -> {
                                overlay = !overlay;
                                rebuildWidgets();
                            },
                            "Show or hide the green mask overlay.");
            visibility.selected = overlay;
            visibility.accessibleName = "Mask overlay";
            y += 22;
            tool(controlX, y, half, "Fill", () -> fill(true));
            tool(controlX + half + 4, y, controlW - half - 4, "Clear", () -> fill(false));
            y += 22;
            tool(
                    controlX,
                    y,
                    controlW,
                    itemMode() ? "Automatic" : "Remove mask",
                    () -> {
                        if (sprite() != null) {
                            remember();
                            resetMask();
                            commit();
                            rebuildWidgets();
                        }
                    });
            y += 32;
            strengthSlider =
                    new MenuSlider(controlX, 0, controlW, 0, 8, strength(), "x", this::setStrength);
            strengthSlider.accessibleName = "Painted source strength";
            scrolling(strengthSlider, y);
            strengthSlider.active = editable();
            y += 24;
        }
        if (moreTools) y = zoomControls(y);
        contentHeight = y;
        finishLayout();
        layoutPreview();
        updateSelectionButtons();
    }

    private int zoomControls(int y) {
        int half = (controlW - 4) / 2;
        var out =
                scrolled(
                        controlX,
                        y,
                        half,
                        "Zoom -",
                        () ->
                                zoomAt(
                                        zoom / 2,
                                        (canvasLeft + canvasRight) / 2.0,
                                        (canvasTop + canvasBottom) / 2.0),
                        "Zoom out. Mouse wheel over the canvas also zooms.");
        out.accessibleName = "Zoom out";
        var in =
                scrolled(
                        controlX + half + 4,
                        y,
                        controlW - half - 4,
                        "Zoom +",
                        () ->
                                zoomAt(
                                        zoom * 2,
                                        (canvasLeft + canvasRight) / 2.0,
                                        (canvasTop + canvasBottom) / 2.0),
                        "Zoom in to paint individual high-resolution texture pixels.");
        in.accessibleName = "Zoom in";
        y += 22;
        scrolled(
                controlX,
                y,
                controlW,
                "Fit texture",
                this::fitCanvas,
                "Reset zoom and pan. Press 0 with the canvas focused. Middle-drag pans; arrow keys also pan.");
        return y + 22;
    }

    private void useTransform(MaskCanvasTransform transform) {
        canvasTransform = transform;
        zoom = transform.zoom();
        centerU = transform.centerU();
        centerV = transform.centerV();
        previewX = transform.x();
        previewY = transform.y();
        previewW = transform.width();
        previewH = transform.height();
    }

    private void zoomAt(double next, double x, double y) {
        finishStroke();
        if (canvasTransform != null)
            useTransform(
                    canvasTransform.zoomAt(
                            canvasLayout.previewW(), canvasLayout.previewH(), next, x, y));
    }

    private void panCanvas(double dx, double dy) {
        if (painting) finishStroke();
        if (canvasTransform != null)
            useTransform(
                    canvasTransform.pan(canvasLayout.previewW(), canvasLayout.previewH(), dx, dy));
    }

    private void fitCanvas() {
        finishStroke();
        zoom = 1;
        centerU = centerV = .5;
        layoutPreview();
    }

    private boolean inCanvas(double x, double y) {
        return !(embedded && moreTools && contentW < 220)
                && x >= canvasLeft
                && y >= canvasTop
                && x < canvasRight
                && y < canvasBottom;
    }

    private void mode(boolean useColor) {
        finishStroke();
        colorMode = useColor;
        rebuildWidgets();
    }

    private Block block() {
        return BuiltInRegistries.BLOCK.getValue(Identifier.parse(blockId));
    }

    private float strength() {
        var p =
                itemMode()
                        ? StillBloomClient.ITEM_MASKS.profile(blockId)
                        : StillBloomClient.MASKS.profile(blockId);
        return p == null ? 1 : p.strength;
    }

    private void setStrength(double value) {
        var p =
                itemMode()
                        ? StillBloomClient.ITEM_MASKS.items.computeIfAbsent(
                                blockId, k -> new TextureMasks.Profile())
                        : StillBloomClient.MASKS.ensureProfile(blockId);
        if (p.strength != (float) value) {
            p.strength = (float) value;
            pending = true;
        }
    }

    private void loadSprites() {
        try {
            if (itemMode()) sprites = ItemModelTextures.sprites(itemStack);
            else {
                if (links == null) {
                    var owner = settingsOwner();
                    links =
                            owner == null
                                    ? new BlockLinksScreen.Session(blockId)
                                    : owner.linkSession(blockId);
                }
                textureInspection = BlockMaskVariants.inspect(block());
                sprites =
                        textureInspection.ready()
                                ? textureInspection.sprites().stream()
                                        .map(
                                                s ->
                                                        new ItemModelTextures.Texture(
                                                                s.contents().name().toString(),
                                                                s.atlasLocation(),
                                                                s,
                                                                s.contents().width(),
                                                                s.contents().height()))
                                        .toList()
                                : List.of();
            }
        } catch (Exception e) {
            sprites = List.of();
            StillBloomClient.LOGGER.warn("Cannot inspect model {}", blockId, e);
        }
        textureIndex = Math.clamp(textureIndex, 0, Math.max(0, sprites.size() - 1));
    }

    private boolean linking() {
        return !itemMode()
                && (StillBloomClient.MASKS.group(blockId) != null
                        || StillBloomClient.MASKS.linksMasks(blockId));
    }

    private boolean findingVariants() {
        return !itemMode() && (links == null || !links.ready());
    }

    private List<String> linkedTargets() {
        return BlockLinks.members(StillBloomClient.MASKS, blockId);
    }

    private String linkLabel() {
        return links == null ? "Link blocks" : links.label();
    }

    private int linkControls(int y, boolean sync) {
        var b =
                scrolled(
                        controlX,
                        y,
                        controlW,
                        linkLabel(),
                        () -> {
                            finishStroke();
                            commit();
                            minecraft.gui.setScreen(
                                    new BlockLinksScreen(
                                            embedded ? parent : this,
                                            blockId,
                                            links == null ? linkedTargets() : links.candidates()));
                        },
                        links == null
                                ? "Manage shared lighting configurations and group members."
                                : links.help());
        b.selected = linking();
        b.accessibleName = "Link matching blocks";
        y += 22;
        return sync ? syncControl(y) : y;
    }

    private int syncControl(int y) {
        boolean compatible =
                sprite() != null
                        && (mask() == null
                                || ItemMasks.compatibleMask(
                                                mask(), sprite().width(), sprite().height())
                                        != null);
        var b =
                scrolled(
                        controlX,
                        y,
                        controlW,
                        "Sync Blocks",
                        () -> {
                            syncSelectedMask();
                            rebuildWidgets();
                        },
                        !compatible
                                ? "This mask belongs to a different texture size. Repaint it before syncing."
                                : "Share this texture's paint with linked blocks. Other settings stay the same. Linked blocks may already share this paint.");
        b.active = compatible && editable() && linking() && linkedTargets().size() > 1;
        b.accessibleName = "Sync mask to matching blocks";
        return y + 22;
    }

    boolean syncSelectedMask() {
        finishStroke();
        if (sprite() == null) loadSprites();
        if (!editable() || linkedTargets().size() < 2) return false;
        var current = mask();
        if (current != null
                && ItemMasks.compatibleMask(current, sprite().width(), sprite().height()) == null)
            return false;
        var before = LinkedMaskEdits.capture(StillBloomClient.MASKS, texture(), linkedTargets());
        if (!LinkedMaskEdits.sync(StillBloomClient.MASKS, blockId, texture(), linkedTargets()))
            return false;
        undo.push(new Undo(current == null ? null : current.copy(), before));
        while (undo.size() > 24) undo.removeLast();
        unsaved = true;
        editRevision++;
        PresetStatus.changed();
        commit();
        return true;
    }

    private Object sourceIdentity() {
        var t = sprite();
        return t == null
                ? null
                : t.sprite() != null
                        ? t.sprite().contents()
                        : minecraft.getTextureManager().getTexture(t.target());
    }

    // I snapshot the pack's actual sprite once, using its first declared animation frame.
    private void ensureSnapshot() {
        var contents = sourceIdentity();
        if (contents == snapshotContents && snapshotId != null) return;
        boolean sameSource =
                contents == retainedContents
                        && ItemModelTextures.generation() == retainedGeneration;
        if (retainedContents != null && !sameSource) undo.clear();
        releaseSnapshot();
        if (!sameSource) clearSelection();
        if (contents == null || !editable()) return;
        NativeImage copy = null;
        DynamicTexture owned = null;
        try {
            copy = ItemModelTextures.snapshot(sprite());
            if (copy == null) return;
            int w = copy.getWidth(), h = copy.getHeight();
            int[] pixels = new int[w * h];
            for (int y = 0; y < h; y++)
                for (int x = 0; x < w; x++) pixels[x + y * w] = copy.getPixel(x, y);
            owned = new DynamicTexture(() -> "Fluorescent texture editor", copy);
            copy = null;
            Identifier id =
                    Identifier.fromNamespaceAndPath("stillbloom", "editor/" + UUID.randomUUID());
            minecraft.getTextureManager().register(id, owned);
            owned = null;
            snapshotId = id;
            snapshotContents = contents;
            snapshotGeneration = ItemModelTextures.generation();
            retainedContents = contents;
            retainedGeneration = snapshotGeneration;
            snapshotPixels = pixels;
            snapshotWidth = w;
            snapshotHeight = h;
        } catch (Exception e) {
            StillBloomClient.LOGGER.warn("Cannot read texture colors for {}", blockId, e);
        } finally {
            if (owned != null) owned.close();
            if (copy != null) copy.close();
        }
    }

    private void releaseSnapshot() {
        if (snapshotId != null && minecraft != null)
            minecraft.getTextureManager().release(snapshotId);
        snapshotId = null;
        snapshotContents = null;
        snapshotGeneration = null;
        snapshotPixels = null;
        snapshotWidth = snapshotHeight = 0;
    }

    private ItemModelTextures.Texture sprite() {
        return sprites.isEmpty() ? null : sprites.get(textureIndex);
    }

    private String texture() {
        return sprite().id();
    }

    private String animatedPreviewNote() {
        return sprite() != null
                        && sprite().sprite() != null
                        && sprite().sprite().contents().isAnimated()
                ? " Animated texture: preview shows the first frame."
                : "";
    }

    private TextureMasks.Mask mask() {
        return sprite() == null
                ? null
                : itemMode()
                        ? StillBloomClient.ITEM_MASKS.mask(blockId, texture())
                        : StillBloomClient.MASKS.mask(blockId, texture());
    }

    private void putMask(TextureMasks.Mask mask) {
        if (itemMode()) StillBloomClient.ITEM_MASKS.put(blockId, texture(), mask);
        else StillBloomClient.MASKS.put(blockId, texture(), mask);
        maskDirty = true;
        unsaved = true;
        editRevision++;
        PresetStatus.changed();
    }

    private void resetMask() {
        boolean custom = mask() != null;
        if (itemMode()) StillBloomClient.ITEM_MASKS.reset(blockId, texture());
        else StillBloomClient.MASKS.reset(blockId, texture());
        if (custom || !itemMode()) {
            maskDirty = true;
            unsaved = true;
            editRevision++;
            PresetStatus.changed();
        }
    }

    private void publish() {
        unsaved = true;
        editRevision++;
        PresetStatus.changed();
        if (itemMode()) StillBloomClient.ITEM_MASKS.changed();
        else StillBloomClient.MASKS.changed();
        maskDirty = false;
        observeMask();
    }

    private void layoutPreview() {
        canvasLayout =
                embedded
                        ? MaskEditorLayout.embedded(
                                contentX,
                                viewTop,
                                contentW,
                                contentBottom,
                                sprite() == null ? 16 : sprite().width(),
                                sprite() == null ? 16 : sprite().height(),
                                moreTools && contentW >= 220)
                        : MaskEditorLayout.fit(
                                contentX,
                                viewTop,
                                contentW,
                                contentBottom,
                                sprite() == null ? 16 : sprite().width(),
                                sprite() == null ? 16 : sprite().height());
        canvasLeft = canvasLayout.stageX() + (embedded ? 6 : 8);
        canvasRight = canvasLayout.stageX() + canvasLayout.stageW() - (embedded ? 6 : 8);
        canvasTop = viewTop + (embedded ? 13 : 14);
        canvasBottom = contentBottom - (embedded ? 17 : 18);
        useTransform(
                MaskCanvasTransform.fit(
                        canvasLeft,
                        canvasTop,
                        canvasRight - canvasLeft,
                        canvasBottom - canvasTop,
                        canvasLayout.previewW(),
                        canvasLayout.previewH(),
                        zoom,
                        centerU,
                        centerV,
                        sprite() == null ? 16 : sprite().width(),
                        sprite() == null ? 16 : sprite().height()));
    }

    private void switchTexture(int amount) {
        if (sprites.isEmpty()) return;
        finishStroke();
        canvasViews.put(texture(), new CanvasView(zoom, centerU, centerV));
        textureIndex = Math.floorMod(textureIndex + amount, sprites.size());
        var view = canvasViews.getOrDefault(texture(), new CanvasView(1, .5, .5));
        zoom = view.zoom();
        centerU = view.u();
        centerV = view.v();
        undo.clear();
        rebuildWidgets();
    }

    private MenuButton scrolled(int x, int y, int w, String text, Runnable action, String help) {
        var b = new MenuButton(x, 0, w, 18, text, action);
        b.setTooltip(Tooltip.create(Component.literal(help)));
        return scrolling(b, y);
    }

    private MenuButton tool(int x, int y, int w, String text, Runnable action) {
        String help =
                switch (text) {
                    case "Fill" -> "Make the whole texture glow.";
                    case "Clear" -> "Stop this texture glowing.";
                    case "Automatic" ->
                            "Remove this texture's item paint. Use matching block paint or the item's automatic glow. Items with neither stop glowing.";
                    case "Remove mask" ->
                            "Remove this texture's paint to stop it glowing. Linked textures change too. Undo brings the paint back.";
                    case "Undo" ->
                            "Undo the last mask edit on this texture, including a whole color selection.";
                    case "Paint" ->
                            colorMode
                                    ? "Make the cyan selection glow. Undo reverses the whole selection. A new mask starts blank."
                                    : "Paint the parts that should glow. A new mask starts blank.";
                    case "Erase" ->
                            colorMode
                                    ? "Stop the cyan selection glowing. Undo reverses the whole selection. Unpainted block textures stay dark."
                                    : "Erase paint to stop those parts glowing. Right-click also erases. Unpainted block textures stay dark.";
                    default -> "";
                };
        if (itemMode() && (text.equals("Fill") || text.equals("Clear")))
            help +=
                    " Other hand textures keep their current masks. Use the texture arrows to edit those parts.";
        var b = scrolled(x, y, w, text, action, help);
        b.active = editable();
        return b;
    }

    private void clearSelection() {
        selection.clear();
        seedX = seedY = -1;
        updateSelectionButtons();
    }

    private void refreshSelection() {
        selection =
                snapshotPixels == null
                        ? new BitSet()
                        : TextureColorSelection.select(
                                snapshotPixels,
                                snapshotWidth,
                                snapshotHeight,
                                seedX,
                                seedY,
                                (int) Math.round(tolerance * 2.55),
                                connected);
        updateSelectionButtons();
    }

    private void updateSelectionButtons() {
        if (paintButton != null) {
            paintButton.active = editable() && (!colorMode || !selection.isEmpty());
            paintButton.selected = !colorMode && !erase;
        }
        if (eraseButton != null) {
            eraseButton.active = editable() && (!colorMode || !selection.isEmpty());
            eraseButton.selected = !colorMode && erase;
        }
        if (clearSelectionButton != null)
            clearSelectionButton.active = colorMode && !selection.isEmpty();
    }

    private void select(double x, double y) {
        if (snapshotPixels == null) return;
        seedX = canvasTransform.pixelX(x, snapshotWidth);
        seedY = canvasTransform.pixelY(y, snapshotHeight);
        refreshSelection();
    }

    private void applySelection(boolean eraseSelection) {
        if (!editable() || snapshotPixels == null || selection.isEmpty()) return;
        var current = mask();
        var next =
                TextureColorSelection.apply(
                        current,
                        snapshotWidth,
                        snapshotHeight,
                        selection,
                        eraseSelection,
                        itemMode());
        if (next == current) return;
        remember();
        putMask(next);
        commit();
    }

    private void remember() {
        var m = mask();
        undo.push(
                new Undo(
                        m == null ? null : m.copy(),
                        itemMode()
                                ? null
                                : LinkedMaskEdits.capture(
                                        StillBloomClient.MASKS, texture(), linkedTargets())));
        while (undo.size() > 24) undo.removeLast();
        if (undoButton != null) undoButton.active = true;
    }

    private TextureMasks.Mask editable(boolean initiallyFull) {
        var m = mask();
        int w = sprite().width(), h = sprite().height();
        if (m == null || m.width != w || m.height != h) {
            m = new TextureMasks.Mask(w, h);
            if (initiallyFull) Arrays.fill(m.words, -1);
            putMask(m);
        }
        return m;
    }

    private boolean editable() {
        return !findingVariants()
                && sprite() != null
                && sprite().width() > 0
                && sprite().height() > 0
                && sprite().width() <= 256
                && sprite().height() <= 256;
    }

    private void fill(boolean value) {
        if (!editable()) return;
        remember();
        var m = editable(false);
        Arrays.fill(m.words, value ? -1 : 0);
        maskDirty = true;
        pending = true;
        commit();
    }

    private void undo() {
        finishStroke();
        if (undo.isEmpty() || sprite() == null) return;
        var previous = undo.pop();
        if (previous.linked() != null) {
            if (!previous.linked().restore(StillBloomClient.MASKS)) {
                undo.clear();
                return;
            }
            maskDirty = false;
            pending = false;
            unsaved = true;
            editRevision++;
            observeMask();
        } else if (previous.mask() == null) resetMask();
        else putMask(previous.mask());
        PresetStatus.changed();
        commit();
        rebuildWidgets();
    }

    private void commit() {
        if (pending || maskDirty) {
            publish();
            pending = false;
        }
        if (unsaved || saveFailed) {
            saveFailed =
                    itemMode()
                            ? !StillBloomClient.ITEM_MASKS.save(StillBloomClient.ITEM_MASK_PATH)
                            : !StillBloomClient.MASKS.save(StillBloomClient.MASK_PATH);
            if (!saveFailed) unsaved = false;
        }
        observeMask();
    }

    private void finishStroke() {
        panning = false;
        if (painting) {
            painting = false;
            lastX = lastY = -1;
            commit();
        }
    }

    private boolean inPreview(double x, double y) {
        return inCanvas(x, y) && canvasTransform != null && canvasTransform.contains(x, y);
    }

    BrushFootprint hoveredFootprint(double mouseX, double mouseY) {
        if (colorMode || panning || !editable() || !inPreview(mouseX, mouseY)) return null;
        return BrushFootprint.at(
                canvasTransform.pixelX(mouseX, sprite().width()),
                canvasTransform.pixelY(mouseY, sprite().height()),
                brush,
                sprite().width(),
                sprite().height());
    }

    boolean erasingPreview() {
        return painting ? strokeErase : erase;
    }

    private void extractBrushPreview(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        var footprint = hoveredFootprint(mouseX, mouseY);
        if (footprint == null || footprint.empty()) return;
        var rect =
                footprint.canvasBounds(
                        previewX,
                        previewY,
                        previewW,
                        previewH,
                        sprite().width(),
                        sprite().height());
        boolean erasing = erasingPreview();
        g.fill(
                rect.left(),
                rect.top(),
                rect.right(),
                rect.bottom(),
                erasing ? 0x35DB947C : 0x307DE5E1);
        g.outline(
                rect.left(),
                rect.top(),
                rect.right() - rect.left(),
                rect.bottom() - rect.top(),
                erasing ? 0xFFF0B39D : 0xFFA6F5F0);
    }

    private void paint(double mouseX, double mouseY) {
        if (!inPreview(mouseX, mouseY)) {
            lastX = lastY = -1;
            return;
        }
        var m = editable(strokeErase && itemMode());
        int x = canvasTransform.pixelX(mouseX, m.width),
                y = canvasTransform.pixelY(mouseY, m.height);
        if (lastX < 0) {
            lastX = x;
            lastY = y;
        }
        int steps = Math.max(Math.abs(x - lastX), Math.abs(y - lastY));
        for (int step = 0; step <= steps; step++) {
            int px = steps == 0 ? x : Math.round(lastX + (x - lastX) * step / (float) steps),
                    py = steps == 0 ? y : Math.round(lastY + (y - lastY) * step / (float) steps);
            var footprint = BrushFootprint.at(px, py, brush, m.width, m.height);
            for (int by = footprint.top(); by < footprint.bottom(); by++)
                for (int bx = footprint.left(); bx < footprint.right(); bx++)
                    m.set(bx, by, !strokeErase);
        }
        lastX = x;
        lastY = y;
        pending = true;
        maskDirty = true;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent e, boolean doubleClick) {
        if (isClosing()) return true;
        if (embedded && !isMouseOver(e.x(), e.y())) return false;
        if (inCanvas(e.x(), e.y())
                && e.button() == com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_MIDDLE) {
            finishStroke();
            canvasFocused = true;
            setFocused(null);
            panning = true;
            return true;
        }
        if (inCanvas(e.x(), e.y())) {
            canvasFocused = true;
            setFocused(null);
        } else canvasFocused = false;
        if (editable()
                && inPreview(e.x(), e.y())
                && (e.button() == com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT
                        || e.button()
                                == com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_RIGHT)) {
            if (colorMode) {
                if (e.button() == com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT)
                    select(e.x(), e.y());
                else clearSelection();
                return true;
            }
            remember();
            painting = true;
            strokeErase =
                    erase
                            || e.button()
                                    == com.mojang.blaze3d.platform.InputConstants
                                            .MOUSE_BUTTON_RIGHT;
            paint(e.x(), e.y());
            return true;
        }
        return super.mouseClicked(e, doubleClick);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent e, double dx, double dy) {
        if (isClosing()) return true;
        if (panning) {
            panCanvas(dx, dy);
            return true;
        }
        if (painting) {
            paint(e.x(), e.y());
            return true;
        }
        return super.mouseDragged(e, dx, dy);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent e) {
        if (isClosing()) {
            finishStroke();
            return true;
        }
        if (panning) {
            panning = false;
            return true;
        }
        if (painting) {
            finishStroke();
            return true;
        }
        return super.mouseReleased(e);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        if (isClosing()) return true;
        if (inCanvas(x, y) && vertical != 0) {
            zoomAt(zoom * Math.pow(2, Math.clamp(vertical, -2, 2)), x, y);
            return true;
        }
        if (embedded && (!moreTools || x > controlX + controlW + 8 || !isMouseOver(x, y)))
            return false;
        return super.mouseScrolled(x, y, horizontal, vertical);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (isClosing()) return true;
        if (event.key() == InputConstants.KEY_Z && event.hasControlDown()) {
            undo();
            return true;
        }
        if (embedded && event.key() == InputConstants.KEY_ESCAPE) return false;
        if (canvasFocused && getFocused() == null) {
            int key = event.key();
            if (key == InputConstants.KEY_EQUALS || key == InputConstants.KEY_ADD) {
                zoomAt(
                        zoom * 2,
                        (canvasLeft + canvasRight) / 2.0,
                        (canvasTop + canvasBottom) / 2.0);
                return true;
            }
            if (key == InputConstants.KEY_MINUS
                    || key == org.lwjgl.sdl.SDLScancode.SDL_SCANCODE_KP_MINUS) {
                zoomAt(
                        zoom / 2,
                        (canvasLeft + canvasRight) / 2.0,
                        (canvasTop + canvasBottom) / 2.0);
                return true;
            }
            if (key == InputConstants.KEY_0 || key == InputConstants.KEY_NUMPAD0) {
                fitCanvas();
                return true;
            }
            if (key == InputConstants.KEY_LEFT
                    || key == InputConstants.KEY_RIGHT
                    || key == InputConstants.KEY_UP
                    || key == InputConstants.KEY_DOWN) {
                panCanvas(
                        key == InputConstants.KEY_LEFT
                                ? 24
                                : key == InputConstants.KEY_RIGHT ? -24 : 0,
                        key == InputConstants.KEY_UP
                                ? 24
                                : key == InputConstants.KEY_DOWN ? -24 : 0);
                return true;
            }
        }
        // I leave enough overlap to keep a control and its label together on small screens.
        int page = 44 * Math.max(1, (contentBottom - viewTop - 40) / 44);
        if (event.key() == InputConstants.KEY_PAGEDOWN) {
            scrollBy(page);
            return true;
        }
        if (event.key() == InputConstants.KEY_PAGEUP) {
            scrollBy(-page);
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public void tick() {
        if (textureInspection != null && textureInspection.stale()) {
            finishStroke();
            undo.clear();
            rebuildWidgets();
        }
        boolean update = links != null && links.tick();
        if (textureInspection != null && !textureInspection.ready()) {
            textureInspection.advance();
            if (textureInspection.ready()) update = true;
        }
        if (update) rebuildWidgets();
        if (++ticks % 3 == 0 && pending) {
            publish();
            pending = false;
        }
        if (snapshotContents != null
                && (sourceIdentity() != snapshotContents
                        || snapshotGeneration != ItemModelTextures.generation())) {
            finishStroke();
            undo.clear();
            releaseSnapshot();
            clearSelection();
            rebuildWidgets();
        }
    }

    @Override
    public void onClose() {
        finishStroke();
        commit();
        if (!saveFailed && !embedded) minecraft.gui.setScreen(parent);
    }

    @Override
    public void removed() {
        finishStroke();
        commit();
        releaseSnapshot();
        clearSelection();
    }

    @Override
    public boolean isPauseScreen() {
        return true;
    }

    @Override
    protected String footer() {
        String status =
                saveFailed
                        ? "Save failed: check log"
                        : colorMode
                                ? selection.cardinality() + " selected"
                                : mask() != null
                                                && itemMode()
                                                && ItemMasks.compatibleMask(
                                                                mask(),
                                                                sprite().width(),
                                                                sprite().height())
                                                        == null
                                        ? "Texture size changed: repaint"
                                        : mask() == null
                                                ? itemMode()
                                                        ? "Inherited mask"
                                                        : "No mask: bloom off"
                                                : "Green = emits";
        return itemMode() ? status : status + " / " + linkLabel();
    }

    private void extractToolLabels(GuiGraphicsExtractor g) {
        g.enableScissor(controlX, viewTop, controlX + controlW, contentBottom);
        if (toleranceSlider != null && toleranceSlider.visible)
            g.text(font, "Tolerance", controlX, toleranceSlider.getY() - 10, MUTED, false);
        if (strengthSlider != null && strengthSlider.visible)
            g.text(
                    font,
                    controlW < 100 ? "Strength" : "Source strength",
                    controlX,
                    strengthSlider.getY() - 10,
                    MUTED,
                    false);
        if (clearSelectionButton != null && clearSelectionButton.visible)
            g.text(
                    font,
                    font.plainSubstrByWidth(selection.cardinality() + " selected", controlW),
                    controlX,
                    clearSelectionButton.getY() - 10,
                    0xFF76E0EC,
                    false);
        g.disableScissor();
    }

    // I merge outline edges and visit only visible pixels to keep zoomed painting responsive.
    private void extractPaintedMaskEdges(GuiGraphicsExtractor g, TextureMasks.Mask mask) {
        if (previewW / mask.width < 3 || previewH / mask.height < 3) return;
        int minX = canvasTransform.pixelX(canvasLeft, mask.width),
                maxX = Math.min(mask.width, canvasTransform.pixelX(canvasRight, mask.width) + 1);
        int minY = canvasTransform.pixelY(canvasTop, mask.height),
                maxY = Math.min(mask.height, canvasTransform.pixelY(canvasBottom, mask.height) + 1);
        for (int y = minY; y < maxY; y++)
            for (int x = minX; x < maxX; ) {
                if (!mask.get(x, y)) {
                    x++;
                    continue;
                }
                int from = x++;
                while (x < maxX && mask.get(x, y)) x++;
                int x0 = previewX + from * previewW / mask.width,
                        x1 = previewX + x * previewW / mask.width;
                int y0 = previewY + y * previewH / mask.height,
                        y1 = previewY + (y + 1) * previewH / mask.height;
                if (from == 0 || !mask.get(from - 1, y))
                    g.fill(x0, y0, x0 + 1, y1, PAINTED_MASK_EDGE);
                if (x == mask.width || !mask.get(x, y))
                    g.fill(x1 - 1, y0, x1, y1, PAINTED_MASK_EDGE);
                paintedHorizontalEdges(g, mask, from, x, y - 1, y0);
                paintedHorizontalEdges(g, mask, from, x, y + 1, y1 - 1);
            }
    }

    private void paintedHorizontalEdges(
            GuiGraphicsExtractor g,
            TextureMasks.Mask mask,
            int from,
            int to,
            int neighborY,
            int screenY) {
        boolean outside = neighborY < 0 || neighborY >= mask.height;
        for (int x = from; x < to; ) {
            if (!outside && mask.get(x, neighborY)) {
                x++;
                continue;
            }
            int start = x++;
            while (x < to && (outside || !mask.get(x, neighborY))) x++;
            g.fill(
                    previewX + start * previewW / mask.width,
                    screenY,
                    previewX + x * previewW / mask.width,
                    screenY + 1,
                    PAINTED_MASK_EDGE);
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float tick) {
        super.extractRenderState(g, mouseX, mouseY, tick);
        int right = canvasLayout.stageX();
        if (embedded && moreTools && contentW < 220) {
            extractToolLabels(g);
            return;
        }
        g.fill(right, viewTop, right + canvasLayout.stageW(), contentBottom, 0xFF10151D);
        g.fill(right, viewTop, right + canvasLayout.stageW(), viewTop + 1, 0xFF344453);
        if (sprite() == null) {
            g.textWithWordWrap(
                    font,
                    Component.literal(
                            textureInspection != null && !textureInspection.ready()
                                    ? "Loading model textures..."
                                    : itemMode()
                                            ? "No editable texture was found for this item model."
                                            : "No editable texture was found for this block."),
                    right,
                    viewTop + 5,
                    contentX + contentW - right,
                    MUTED);
            return;
        }
        extractToolLabels(g);
        String instruction = "Wheel: zoom / MMB: pan / 0: fit";
        String viewInfo =
                String.format(Locale.ROOT, "%.0f%%", zoom * 100)
                        + (inPreview(mouseX, mouseY)
                                ? "  "
                                        + canvasTransform.pixelX(mouseX, sprite().width())
                                        + ", "
                                        + canvasTransform.pixelY(mouseY, sprite().height())
                                : "  Wheel: zoom");
        boolean oversized = sprite().width() > 256 || sprite().height() > 256;
        if (oversized) viewInfo = "Texture exceeds 256 px";
        else if (findingVariants())
            viewInfo =
                    links != null && links.conflict()
                            ? "Choose a source to edit"
                            : "Finding matching blocks...";
        g.text(
                font,
                shorten(font, viewInfo, canvasLayout.stageW() - 12),
                right + 6,
                viewTop + 4,
                oversized ? 0xFFFF8888 : MUTED,
                false);
        if (mouseX >= right
                && mouseX < right + canvasLayout.stageW()
                && mouseY >= viewTop
                && mouseY < canvasTop)
            g.setTooltipForNextFrame(
                    font,
                    Component.literal(
                            instruction
                                    + ". +/- and arrow keys also work after clicking the canvas."
                                    + animatedPreviewNote()),
                    mouseX,
                    mouseY);
        g.enableScissor(canvasLeft, canvasTop, canvasRight, canvasBottom);
        MenuPresentation.Checkerboard.draw(g, canvasLeft, canvasTop, canvasRight, canvasBottom);
        if (snapshotId != null)
            g.blit(
                    RenderPipelines.GUI_TEXTURED,
                    snapshotId,
                    previewX,
                    previewY,
                    0,
                    0,
                    previewW,
                    previewH,
                    snapshotWidth,
                    snapshotHeight,
                    snapshotWidth,
                    snapshotHeight);
        else if (sprite().sprite() != null)
            g.blitSprite(
                    RenderPipelines.GUI_TEXTURED,
                    sprite().sprite(),
                    previewX,
                    previewY,
                    previewW,
                    previewH);
        var mask = mask();
        if (overlay
                && mask != null
                && (!itemMode()
                        || ItemMasks.compatibleMask(mask, sprite().width(), sprite().height())
                                != null)) {
            int minY = canvasTransform.pixelY(canvasTop, mask.height),
                    maxY =
                            Math.min(
                                    mask.height,
                                    canvasTransform.pixelY(canvasBottom, mask.height) + 1),
                    minX = canvasTransform.pixelX(canvasLeft, mask.width),
                    maxX =
                            Math.min(
                                    mask.width,
                                    canvasTransform.pixelX(canvasRight, mask.width) + 1);
            for (int y = minY; y < maxY; y++)
                for (int x = minX; x < maxX; ) {
                    if (!mask.get(x, y)) {
                        x++;
                        continue;
                    }
                    int from = x++;
                    while (x < maxX && mask.get(x, y)) x++;
                    g.fill(
                            previewX + from * previewW / mask.width,
                            previewY + y * previewH / mask.height,
                            previewX + x * previewW / mask.width,
                            previewY + (y + 1) * previewH / mask.height,
                            PAINTED_MASK_FILL);
                }
        }
        int tw = sprite().width(), th = sprite().height();
        int minX = canvasTransform.pixelX(canvasLeft, tw),
                maxX = Math.min(tw, canvasTransform.pixelX(canvasRight, tw) + 1),
                minY = canvasTransform.pixelY(canvasTop, th),
                maxY = Math.min(th, canvasTransform.pixelY(canvasBottom, th) + 1);
        if (colorMode)
            for (int row = minY; row < maxY; row++)
                for (int i = selection.nextSetBit(row * tw + minX);
                        i >= 0 && i < row * tw + maxX; ) {
                    int x = i % tw, end = Math.min(selection.nextClearBit(i), row * tw + maxX);
                    int x0 = previewX + x * previewW / tw,
                            y0 = previewY + row * previewH / th,
                            x1 = previewX + (end - row * tw) * previewW / tw,
                            y1 = previewY + (row + 1) * previewH / th;
                    g.fill(x0, y0, x1, y1, 0xA04ED7EA);
                    if (previewW / tw >= 3 && previewH / th >= 3)
                        for (int p = i; p < end; p++) {
                            int px0 = previewX + (p % tw) * previewW / tw,
                                    px1 = previewX + (p % tw + 1) * previewW / tw;
                            g.outline(px0, y0, px1 - px0, y1 - y0, 0xFF76E0EC);
                        }
                    i = selection.nextSetBit(end);
                }
        if (previewW / tw >= 4 && previewH / th >= 4) {
            int drawLeft = Math.max(canvasLeft, previewX),
                    drawRight = Math.min(canvasRight, previewX + previewW),
                    drawTop = Math.max(canvasTop, previewY),
                    drawBottom = Math.min(canvasBottom, previewY + previewH);
            for (int x = minX; x <= maxX; x++)
                g.verticalLine(previewX + x * previewW / tw, drawTop, drawBottom, 0x503E4B5B);
            for (int y = minY; y <= maxY; y++)
                g.horizontalLine(drawLeft, drawRight, previewY + y * previewH / th, 0x503E4B5B);
        }
        if (overlay
                && mask != null
                && (!itemMode()
                        || ItemMasks.compatibleMask(mask, sprite().width(), sprite().height())
                                != null)) extractPaintedMaskEdges(g, mask);
        extractBrushPreview(g, mouseX, mouseY);
        g.outline(previewX - 1, previewY - 1, previewW + 2, previewH + 2, 0xFF3D4B5C);
        g.disableScissor();
        String identity = saveFailed ? "Save failed - check log" : "MMB: pan / 0: fit";
        g.text(
                font,
                font.plainSubstrByWidth(identity, Math.max(1, canvasLayout.stageW() - 12)),
                right + 6,
                contentBottom - 11,
                saveFailed ? 0xFFFFAAAA : MUTED,
                false);
    }
}
