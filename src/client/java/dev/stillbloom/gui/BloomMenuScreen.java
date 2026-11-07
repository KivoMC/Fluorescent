package dev.stillbloom.gui;

import com.mojang.blaze3d.platform.InputConstants;

import dev.stillbloom.gui.layout.MenuTransition;
import dev.stillbloom.gui.layout.SmoothScroll;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.lang.ref.WeakReference;
import java.util.*;
import java.util.function.*;

abstract class BloomMenuScreen extends Screen {
    private static final String VERSION =
            net.fabricmc.loader.api.FabricLoader.getInstance()
                    .getModContainer("stillbloom")
                    .map(mod -> mod.getMetadata().getVersion().getFriendlyString())
                    .orElse("dev");
    static final int ACCENT = 0xFF7DE5E1, TEXT = 0xFFEAF0F6, MUTED = 0xFF9AAAB9;
    static final int BACKGROUND = 0xD921252A,
            SURFACE = 0xEC30363D,
            VIOLET = 0xFFB0BCCB,
            AMBER = 0xFFE8BC76;
    private static WeakReference<Screen> presentationScreen = new WeakReference<>(null);
    private static WeakReference<BloomSettingsScreen> presentationOwner = new WeakReference<>(null);
    private static MenuTransition transition;
    private static Runnable closingAction;
    private static boolean lastPreview;
    private static long dimStart;
    private static float dimFrom, dimTo = 1;

    protected boolean embeddedPanel() {
        return false;
    }

    protected int scrollViewportWidth() {
        return contentW;
    }

    static void enterTool(Screen screen, BloomSettingsScreen owner) {
        if (screen instanceof BloomMenuScreen menu && menu.embeddedPanel()) return;
        long now = System.nanoTime();
        boolean preview = screen instanceof BloomMenuScreen menu && menu.previewMode();
        boolean newSession =
                transition == null
                        || presentationOwner.get() != owner
                        || (owner == null && presentationScreen.get() != screen);
        if (newSession) {
            transition = new MenuTransition(now);
            closingAction = null;
            dimFrom = dimTo = preview ? 0 : 1;
            dimStart = now;
        } else if (presentationScreen.get() != screen && transition.closing()) {
            transition.reopen(now);
            closingAction = null;
        }
        if (!newSession && preview != lastPreview) {
            float dim = currentDim(now);
            dimFrom = dim;
            dimTo = preview ? 0 : 1;
            dimStart = now;
            transition.view(now);
        }
        lastPreview = preview;
        presentationScreen = new WeakReference<>(screen);
        presentationOwner = new WeakReference<>(owner);
    }

    public static boolean currentClosing() {
        Screen current = Minecraft.getInstance().gui.screen();
        return current != null
                && transition != null
                && transition.closing()
                && presentationScreen.get() == current;
    }

    public final boolean isClosing() {
        return currentClosing();
    }

    public final float animationAlpha() {
        return currentAlpha();
    }

    static float currentAlpha() {
        return transition == null ? 1 : transition.alpha(System.nanoTime());
    }

    private static float currentDim(long now) {
        float t = (float) Math.clamp((now - dimStart) / (double) MenuTransition.VIEW_NANOS, 0, 1);
        t = t * t * (3 - 2 * t);
        return dimFrom + (dimTo - dimFrom) * t;
    }

    protected final void requestClose(Runnable completed) {
        // I defer closing the visible screen, even when Looks still has a hidden owner.
        Screen current = minecraft.gui.screen();
        if (!(current instanceof BloomMenuScreen) && !(current instanceof BloomColorScreen)) {
            completed.run();
            return;
        }
        if (transition == null) {
            enterTool(current, presentationOwner());
        }
        if (transition.closing()) return;
        closingAction = completed;
        transition.close(System.nanoTime());
    }

    static boolean advancePresentation(Screen screen) {
        if (transition == null || presentationScreen.get() != screen) {
            BloomSettingsScreen owner =
                    screen instanceof BloomMenuScreen menu
                            ? menu.presentationOwner()
                            : screen instanceof BloomColorScreen color
                                    ? color.settingsOwner()
                                    : null;
            enterTool(screen, owner);
        }
        if (transition.closing()
                && transition.finished(System.nanoTime())
                && closingAction != null) {
            Runnable action = closingAction;
            closingAction = null;
            action.run();
            if (Minecraft.getInstance().gui.screen() != screen) return false;
            if (transition == null) {
                BloomSettingsScreen owner =
                        screen instanceof BloomMenuScreen menu
                                ? menu.presentationOwner()
                                : screen instanceof BloomColorScreen color
                                        ? color.settingsOwner()
                                        : null;
                enterTool(screen, owner);
            } else if (transition.closing()) transition.reopen(System.nanoTime());
        }
        return true;
    }

    static void clearPresentation() {
        closingAction = null;
        transition = null;
        presentationScreen.clear();
        presentationOwner.clear();
        lastPreview = false;
        dimFrom = dimTo = 1;
        dimStart = 0;
    }

    private EditBox quickSearch;
    private int headingY, baseContentX, baseContentW;
    protected int panelX,
            panelY,
            panelW,
            panelH,
            contentX,
            contentW,
            contentTop,
            contentBottom,
            viewTop;
    protected int scroll, contentHeight;
    private final SmoothScroll formScroll = new SmoothScroll();
    private int publishedScroll = Integer.MIN_VALUE;
    private Object previousScrollContext;
    private final Set<net.minecraft.client.gui.components.events.GuiEventListener> formChildren =
            Collections.newSetFromMap(new IdentityHashMap<>());

    protected Object scrollContext() {
        return getClass().getName() + "/" + section() + "/" + heading() + "/" + previewMode();
    }

    protected final double targetScroll() {
        return formScroll.target();
    }

    private final List<Placed> scrolling = new ArrayList<>();
    private final List<Card> cards = new ArrayList<>();
    private final List<FormRow> formRows = new ArrayList<>();
    private final List<Caption> captions = new ArrayList<>();
    private MenuButton up, down;
    private boolean draggingScroll;
    private GuiEventListener pressedChild;
    private double scrollGrabOffset;
    private int highlightY = -1, highlightH;
    private long highlightUntil;

    private record Placed(AbstractWidget widget, int y) {}

    private record Card(int x, int y, int w, String title, Supplier<String> status) {}

    private record FormRow(
            Row bounds,
            String title,
            String description,
            Supplier<String> status,
            int labelW,
            int titleW,
            int titleH,
            int descriptionH) {}

    private record Caption(
            int y, int height, String title, String description, boolean heading, int titleH) {}

    // I keep X in screen coordinates and Y relative to viewTop until scrolling is applied.
    protected record Row(
            int x, int y, int w, int controlX, int controlY, int controlW, int height) {}

    protected BloomMenuScreen(String title) {
        super(Component.literal(title));
    }

    protected abstract int section();

    protected abstract String heading();

    protected abstract void navigate(int page);

    protected boolean focusedTool() {
        return false;
    }

    protected boolean previewMode() {
        return false;
    }

    protected int headingRightSpace() {
        return 0;
    }

    protected boolean canPreview() {
        return false;
    }

    protected void togglePreview() {}

    protected String closeLabel() {
        return "Done";
    }

    protected String closeHelp() {
        return "Save and return. Escape also goes back.";
    }

    protected BloomSettingsScreen settingsOwner() {
        return null;
    }

    protected BloomSettingsScreen presentationOwner() {
        return settingsOwner();
    }

    protected static BloomSettingsScreen findSettingsOwner(Screen screen) {
        return screen instanceof BloomSettingsScreen settings
                ? settings
                : screen instanceof BloomMenuScreen menu ? menu.settingsOwner() : null;
    }

    protected void findSetting() {
        var owner = settingsOwner();
        if (owner != null) minecraft.gui.setScreen(new SettingsSearchScreen(this, owner));
    }

    protected void frame() {
        if (!embeddedPanel()) enterTool(this, presentationOwner());
        scrolling.clear();
        formChildren.clear();
        cards.clear();
        formRows.clear();
        captions.clear();
        contentHeight = 0;
        draggingScroll = false;
        highlightY = -1;
        quickSearch = null;
        panelH = Math.min(600, height - 16);
        panelY = (height - panelH) / 2;
        if (previewMode()) {
            panelH = Math.min(280, height - 20);
            panelY = (height - panelH) / 2;
            panelW = Math.min(236, width / 2 - 12);
            panelX = width - panelW - 6;
            contentX = panelX + 8;
            contentW = panelW - 20;
            contentTop = panelY + 46;
            viewTop = contentTop;
            contentBottom = panelY + panelH - 27;
            headingY = panelY + 29;
            button(
                    panelX + panelW - 62,
                    panelY + 7,
                    54,
                    18,
                    "Studio",
                    this::togglePreview,
                    "Return to the full studio with this selection and editing context.");
            up =
                    button(
                            panelX + 8,
                            panelY + panelH - 23,
                            18,
                            17,
                            "^",
                            () -> scrollBy(-40),
                            "Scroll controls up. Mouse wheel and Page Up also work.");
            down =
                    button(
                            panelX + 29,
                            panelY + panelH - 23,
                            18,
                            17,
                            "v",
                            () -> scrollBy(40),
                            "Scroll controls down. Mouse wheel and Page Down also work.");
            button(
                    panelX + panelW - 61,
                    panelY + panelH - 23,
                    53,
                    17,
                    closeLabel(),
                    this::onClose,
                    closeHelp());
        } else {
            panelW = Math.min(920, width - 16);
            panelX = (width - panelW) / 2;
            contentX = panelX + 12;
            contentW = panelW - 24;
            boolean small = height < 260;
            String[] navNames = {"Glow", "Blocks", "Held Items", "Looks", "Performance"};
            final int labelPadding = 14;
            int navMinimum = 16;
            for (String name : navNames) navMinimum += font.width(name) + labelPadding;
            boolean wrappedNav = !focusedTool() && contentW < navMinimum;
            contentTop =
                    panelY
                            + (focusedTool() ? (small ? 46 : 52) : (small ? 68 : 82))
                            + (wrappedNav ? 23 : 0);
            viewTop = contentTop;
            contentBottom = panelY + panelH - 28;
            headingY = contentTop - 14;
            int right = panelX + panelW - 12, headerY = panelY + (small ? 5 : 8);
            if (canPreview()) {
                int w = panelW < 440 ? 64 : 78;
                button(
                        right - w,
                        headerY,
                        w,
                        20,
                        panelW < 440 ? "Preview" : "Live preview",
                        this::togglePreview,
                        "Work in the world with a compact dock. Your selection and controls stay with you.");
                right -= w + 7;
            }
            if (settingsOwner() != null
                    && !focusedTool()
                    && !(this instanceof SettingsSearchScreen)) {
                int w = panelW < 440 ? 94 : 152;
                quickSearch =
                        new EditBox(
                                font,
                                right - w + 5,
                                headerY + 1,
                                w - 10,
                                18,
                                Component.literal("Find settings"));
                quickSearch.setBordered(false);
                quickSearch.setTextColor(TEXT);
                quickSearch.setTextColorUneditable(MUTED);
                quickSearch.setHint(
                        Component.literal(panelW < 440 ? "Search..." : "Find settings...")
                                .withStyle(style -> style.withColor(MUTED & 0xFFFFFF)));
                quickSearch.setTooltip(
                        Tooltip.create(
                                Component.literal(
                                        "Find settings by name or description. Ctrl+F.")));
                quickSearch.setMaxLength(100);
                addRenderableWidget(quickSearch);
                quickSearch.setResponder(
                        value -> {
                            if (!value.isBlank())
                                minecraft.gui.setScreen(
                                        new SettingsSearchScreen(this, settingsOwner(), value));
                        });
            }
            if (!focusedTool()) {
                int[] pages = {1, 2, 4, 0, 3};
                int navY = panelY + (small ? 26 : 34), start = 0;
                for (int rowIndex = 0; rowIndex < (wrappedNav ? 2 : 1); rowIndex++) {
                    int count = wrappedNav ? (rowIndex == 0 ? 3 : 2) : 5,
                            required = 4 * (count - 1);
                    for (int i = start; i < start + count; i++)
                        required += font.width(navNames[i]) + labelPadding;
                    int extra = (contentW - required) / count, x = contentX;
                    for (int i = start; i < start + count; i++) {
                        final int page = pages[i];
                        int w =
                                i == start + count - 1
                                        ? contentX + contentW - x
                                        : font.width(navNames[i]) + labelPadding + extra;
                        var nav =
                                button(
                                        x,
                                        navY + rowIndex * 23,
                                        w,
                                        20,
                                        navNames[i],
                                        () -> navigate(page),
                                        "Open " + navNames[i].toLowerCase(Locale.ROOT) + ".");
                        nav.selected = section() == page;
                        nav.accessibleName = navNames[i];
                        nav.settingId =
                                "nav/" + navNames[i].toLowerCase(Locale.ROOT).replace(' ', '_');
                        x += w + 4;
                    }
                    start += count;
                }
            }
            button(
                    panelX + panelW - 64,
                    panelY + panelH - 23,
                    52,
                    18,
                    closeLabel(),
                    this::onClose,
                    closeHelp());
            if (!focusedTool())
                button(
                        panelX + panelW - 156,
                        panelY + panelH - 23,
                        46,
                        18,
                        "Guide",
                        () -> minecraft.gui.setScreen(new BloomGuideScreen(this)),
                        "Read tips for painting, tuning and sharing bloom.");
            up =
                    button(
                            panelX + panelW - 106,
                            panelY + panelH - 23,
                            18,
                            18,
                            "^",
                            () -> scrollBy(-40),
                            "Scroll up. Mouse wheel and Page Up also work.");
            down =
                    button(
                            panelX + panelW - 85,
                            panelY + panelH - 23,
                            18,
                            18,
                            "v",
                            () -> scrollBy(40),
                            "Scroll down. Mouse wheel and Page Down also work.");
        }
        baseContentX = contentX;
        baseContentW = contentW;
    }

    protected MenuButton button(
            int x, int y, int w, int h, String text, Runnable action, String help) {
        var b = new MenuButton(x, y, Math.max(1, w), h, text, action);
        if (help != null) b.setTooltip(Tooltip.create(Component.literal(help)));
        return addRenderableWidget(b);
    }

    protected <T extends AbstractWidget> T scrolling(T widget, int logicalY) {
        addWidget(widget);
        formChildren.add(widget);
        scrolling.add(new Placed(widget, logicalY));
        return widget;
    }

    protected void clearScrolling() {
        for (var p : scrolling) removeWidget(p.widget);
        scrolling.clear();
        formChildren.clear();
        cards.clear();
        formRows.clear();
        captions.clear();
        contentHeight = 0;
        // I clear old wheel movement so a new search doesn't scroll toward missing rows.
        formScroll.reset(scroll, Integer.MAX_VALUE, System.nanoTime());
        publishedScroll = scroll;
    }

    protected void card(int x, int y, int w, String title, Supplier<String> status) {
        cards.add(new Card(x, y, w, title, status));
        contentHeight = Math.max(contentHeight, y + 42);
    }

    protected Row row(int y, String title, String description, Supplier<String> status) {
        boolean stacked = previewMode() || contentW < 240;
        int controlW =
                stacked
                        ? contentW - 4
                        : contentW < 440
                                ? Math.clamp(contentW * 47 / 100, 116, 180)
                                : Math.clamp(contentW * 44 / 100, 160, 260);
        int labelW = stacked ? contentW - 4 : contentW - controlW - 14;
        String state = status.get();
        int stateW = state.isEmpty() ? 0 : Math.min(font.width(state), Math.max(0, labelW / 2 - 3));
        int titleW = Math.max(36, labelW - (stateW > 0 ? stateW + 7 : 0));
        int titleH = font.split(Component.literal(title), titleW).size() * 10;
        int descriptionH =
                stacked || contentW < 440 || description.isBlank()
                        ? 0
                        : Math.min(
                                        2,
                                        font.split(
                                                        Component.literal(description),
                                                        Math.max(40, labelW))
                                                .size())
                                * 10;
        int textH = titleH + (descriptionH > 0 ? descriptionH + 2 : 0);
        int h = stacked ? titleH + 21 : Math.max(24, textH + 6);
        int controlY = y + (stacked ? titleH + 1 : (h - 20) / 2);
        var bounds =
                new Row(
                        contentX,
                        y,
                        contentW,
                        stacked ? contentX + 2 : contentX + contentW - controlW,
                        controlY,
                        controlW,
                        h + 4);
        formRows.add(
                new FormRow(
                        bounds, title, description, status, labelW, titleW, titleH, descriptionH));
        contentHeight = Math.max(contentHeight, y + bounds.height());
        return bounds;
    }

    protected int sectionHeading(int y, String title, String description) {
        int titleH = font.split(Component.literal(title), Math.max(40, contentW - 8)).size() * 10;
        String help = contentW < 360 ? "" : description;
        int h =
                titleH
                        + 6
                        + (help.isBlank()
                                ? 0
                                : Math.min(
                                                2,
                                                font.split(
                                                                Component.literal(help),
                                                                Math.max(40, contentW - 8))
                                                        .size())
                                        * 10);
        captions.add(new Caption(y, h, title, help, true, titleH));
        contentHeight = Math.max(contentHeight, y + h);
        return y + h;
    }

    protected int note(int y, String text) {
        int h = font.split(Component.literal(text), Math.max(40, contentW - 12)).size() * 10 + 10;
        captions.add(new Caption(y, h, "", text, false, 0));
        contentHeight = Math.max(contentHeight, y + h);
        return y + h;
    }

    private record FocusKey(String key, String row) {}

    private FocusKey focusKey(AbstractWidget widget) {
        String rowTitle = null, role = "";
        for (var placed : scrolling)
            if (placed.widget == widget) {
                for (var row : formRows)
                    if (row.bounds.controlY() == placed.y) {
                        rowTitle = row.title;
                        role = widget.getX() == row.bounds.controlX() ? "primary" : "secondary";
                        break;
                    }
                break;
            }
        if (widget instanceof MenuSlider slider)
            return new FocusKey(
                    "slider/"
                            + (slider.settingId.isEmpty()
                                    ? slider.accessibleName
                                    : slider.settingId),
                    rowTitle);
        if (widget instanceof MenuButton button) {
            if (button.settingId != null)
                return new FocusKey("button/id/" + button.settingId, rowTitle);
            if (button.accessibleName != null)
                return new FocusKey("button/name/" + button.accessibleName, rowTitle);
            if (rowTitle != null) return new FocusKey("row/" + rowTitle + "/" + role, rowTitle);
        }
        return new FocusKey(
                widget.getClass().getName() + "/" + widget.getMessage().getString(), rowTitle);
    }

    private void restoreFocus(FocusKey wanted) {
        if (wanted == null) return;
        AbstractWidget replacement = null;
        for (var child : children())
            if (child instanceof AbstractWidget widget
                    && widget.active
                    && focusKey(widget).key.equals(wanted.key)) {
                replacement = widget;
                break;
            }
        // I return focus to the editable value because Reset may disable its own button.
        if (replacement == null && wanted.row != null)
            for (var placed : scrolling)
                if (placed.widget.active && wanted.row.equals(focusKey(placed.widget).row)) {
                    replacement = placed.widget;
                    break;
                }
        if (replacement == null) return;
        for (var placed : scrolling)
            if (placed.widget == replacement) {
                var row =
                        formRows.stream().filter(r -> r.bounds.controlY() == placed.y).findFirst();
                if (row.isPresent()) reveal(row.get().bounds.y(), row.get().bounds.height());
                else reveal(placed.y, replacement.getHeight());
                break;
            }
        if (replacement.visible) setFocused(replacement);
    }

    private FocusKey rebuiltFocus;

    @Override
    protected void setInitialFocus() {
        // I keep the search or jump target that init already focused.
        if (getFocused() != null && children().contains(getFocused())) return;
        setFocused(null);
        super.setInitialFocus();
    }

    @Override
    protected void rebuildWidgets() {
        var focused = getFocused();
        var remembered = focused instanceof AbstractWidget widget ? focusKey(widget) : null;
        super.rebuildWidgets();
        restoreFocus(remembered);
        // I remember the new destination because Minecraft may still focus the old button after
        // onPress.
        rebuiltFocus =
                getFocused() instanceof AbstractWidget widget && children().contains(widget)
                        ? focusKey(widget)
                        : null;
    }

    protected void finishLayout() {
        advanceScroll(System.nanoTime());
    }

    private int maxScroll() {
        return Math.max(0, contentHeight - Math.max(1, contentBottom - viewTop));
    }

    private void syncScroll(long now) {
        Object context = scrollContext();
        if (publishedScroll != scroll || !Objects.equals(previousScrollContext, context)) {
            formScroll.reset(scroll, maxScroll(), now);
            previousScrollContext = context;
        } else formScroll.bounds(maxScroll(), now);
    }

    private void advanceScroll(long now) {
        syncScroll(now);
        scroll = formScroll.pixels(now);
        publishedScroll = scroll;
        position();
    }

    private void directScroll(int next) {
        formScroll.reset(next, maxScroll(), System.nanoTime());
        scroll = formScroll.pixels(System.nanoTime());
        publishedScroll = scroll;
        previousScrollContext = scrollContext();
        position();
    }

    private void position() {
        for (var p : scrolling) {
            int y = viewTop + p.y - scroll;
            p.widget.setY(y);
            p.widget.visible = y + p.widget.getHeight() > viewTop && y < contentBottom;
            if (p.widget instanceof MenuButton button) button.clipTo(viewTop, contentBottom);
        }
        arrows(scroll > 0, scroll < maxScroll());
    }

    @Override
    public Optional<net.minecraft.client.gui.components.events.GuiEventListener> getChildAt(
            double x, double y) {
        for (var child : children()) {
            if (formChildren.contains(child)
                    && (x < contentX
                            || x >= contentX + contentW
                            || y < viewTop
                            || y >= contentBottom)) continue;
            if (child.isMouseOver(x, y)) return Optional.of(child);
        }
        return Optional.empty();
    }

    protected void arrows(boolean previous, boolean next) {
        if (up != null) up.active = previous;
        if (down != null) down.active = next;
    }

    protected void scrollBy(int amount) {
        directScroll(Math.clamp(scroll + amount, 0, maxScroll()));
    }

    protected void smoothWheel(double amount) {
        long now = System.nanoTime();
        syncScroll(now);
        formScroll.wheel(amount, maxScroll(), now);
    }

    protected void reveal(int y, int h) {
        int viewport = Math.max(1, contentBottom - viewTop), next = scroll;
        if (y < next) next = y;
        else if (y + h > next + viewport) next = y + Math.min(h, viewport) - viewport;
        directScroll(Math.clamp(next, 0, maxScroll()));
    }

    protected void highlight(int y, int h) {
        highlightY = y;
        highlightH = h;
        highlightUntil = System.nanoTime() + 3_000_000_000L;
        reveal(y, h);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        if (isClosing()) return true;
        if (x >= contentX
                && x < contentX + scrollViewportWidth() + 8
                && y >= viewTop
                && y < contentBottom) {
            smoothWheel(-vertical * 36);
            return true;
        }
        return super.mouseScrolled(x, y, horizontal, vertical);
    }

    private boolean scrollbar(double x, double y) {
        return maxScroll() > 0
                && x >= contentX + scrollViewportWidth()
                && x <= contentX + scrollViewportWidth() + 8
                && y >= viewTop
                && y < contentBottom;
    }

    private int scrollThumbHeight() {
        int h = Math.max(1, contentBottom - viewTop);
        return Math.min(h, Math.max(10, h * h / Math.max(1, contentHeight)));
    }

    private int scrollThumbTop() {
        int travel = Math.max(0, contentBottom - viewTop - scrollThumbHeight());
        return viewTop + travel * scroll / Math.max(1, maxScroll());
    }

    private void grabScrollbar(double y) {
        int top = scrollThumbTop(), thumb = scrollThumbHeight();
        draggingScroll = true;
        if (y >= top && y < top + thumb) {
            int travel = Math.max(1, contentBottom - viewTop - thumb);
            scrollGrabOffset = y - viewTop - travel * (scroll / (double) Math.max(1, maxScroll()));
            directScroll(scroll);
        } else {
            scrollGrabOffset = thumb / 2.0;
            moveScrollbar(y);
        }
    }

    private void moveScrollbar(double y) {
        int travel = Math.max(1, contentBottom - viewTop - scrollThumbHeight());
        directScroll(
                (int)
                        Math.round(
                                Math.clamp((y - viewTop - scrollGrabOffset) / travel, 0, 1)
                                        * maxScroll()));
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (isClosing()) return true;
        if (event.button() == com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT) {
            pressedChild = null;
            setDragging(false);
        }
        if (event.button() == com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT
                && scrollbar(event.x(), event.y())) {
            grabScrollbar(event.y());
            return true;
        }
        // I remember the clicked control before its callback replaces the controls.
        var directChild = getChildAt(event.x(), event.y()).orElse(null);
        if (formChildren.contains(directChild)) directScroll(scroll);
        var clicked = embeddedPanel() ? directChild : null;
        var clickedFocus = clicked instanceof AbstractWidget widget ? focusKey(widget) : null;
        // I track this click before rebuilding so focus doesn't jump back to the previous slider.
        if (event.button() == com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT
                && directChild instanceof AbstractWidget
                && directChild.shouldTakeFocusAfterInteraction()) setFocused(directChild);
        boolean handled = super.mouseClicked(event, doubleClick);
        if (event.button() == com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT
                && isDragging()) {
            if (minecraft.gui.screen() == this || embeddedPanel()) pressedChild = directChild;
            else setDragging(false);
        }
        // I defer this rebuild because Minecraft still holds the old button during the click.
        if ((minecraft.gui.screen() == this || embeddedPanel())
                && getFocused() != null
                && !children().contains(getFocused())) {
            restoreFocus(rebuiltFocus != null ? rebuiltFocus : clickedFocus);
            if (getFocused() != null && !children().contains(getFocused())) setInitialFocus();
        }
        return handled;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (isClosing()) return true;
        if (event.button() != com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT)
            return false;
        if (draggingScroll) {
            moveScrollbar(event.y());
            return true;
        }
        // I keep the drag with its original control, even if focus moves to a replacement.
        return pressedChild != null
                && isDragging()
                && children().contains(pressedChild)
                && pressedChild.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (event.button() != com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT)
            return isClosing();
        var pressed = pressedChild;
        pressedChild = null;
        setDragging(false);
        if (isClosing()) {
            draggingScroll = false;
            return true;
        }
        if (draggingScroll) {
            draggingScroll = false;
            return true;
        }
        // I release the control that received the press; releasing a new slider would play a second
        // click.
        return pressed != null && pressed.mouseReleased(event);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (isClosing()) return true;
        if (formChildren.contains(getFocused()) && formScroll.moving(System.nanoTime()))
            directScroll(scroll);
        if (event.key() == InputConstants.KEY_F
                && event.hasControlDown()
                && settingsOwner() != null) {
            findSetting();
            return true;
        }
        if (event.key() == InputConstants.KEY_PAGEDOWN) {
            scrollBy(Math.max(36, contentBottom - viewTop - 28));
            return true;
        }
        if (event.key() == InputConstants.KEY_PAGEUP) {
            scrollBy(-Math.max(36, contentBottom - viewTop - 28));
            return true;
        }
        if (event.key() == InputConstants.KEY_TAB && getFocused() != null) {
            int current = -1;
            for (int i = 0; i < scrolling.size(); i++)
                if (scrolling.get(i).widget == getFocused()) {
                    current = i;
                    break;
                }
            if (current >= 0) {
                int step = event.hasShiftDown() ? -1 : 1;
                for (int i = current + step; i >= 0 && i < scrolling.size(); i += step) {
                    var next = scrolling.get(i);
                    if (next.widget.active) {
                        var row =
                                formRows.stream()
                                        .filter(r -> r.bounds.controlY() == next.y)
                                        .findFirst();
                        if (row.isPresent())
                            reveal(row.get().bounds.y(), row.get().bounds.height());
                        else reveal(next.y, next.widget.getHeight());
                        setFocused(next.widget);
                        return true;
                    }
                }
            }
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean charTyped(net.minecraft.client.input.CharacterEvent event) {
        if (isClosing()) return true;
        return super.charTyped(event);
    }

    protected String footer() {
        return "Changes preview in the world";
    }

    protected void extractWorkspaceBackground(GuiGraphicsExtractor g, int x, int y, float tick) {}

    @Override
    public boolean isPauseScreen() {
        return !previewMode();
    }

    @Override
    public boolean isInGameUi() {
        return previewMode();
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int x, int y, float tick) {
        if (!embeddedPanel()) {
            int alpha = Math.round(50 * currentDim(System.nanoTime()));
            if (alpha > 0) g.fill(0, 0, width, height, (alpha << 24) | 0x080C11);
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int x, int y, float tick) {
        advanceScroll(System.nanoTime());
        if (!embeddedPanel()) {
            g.fill(panelX + 3, panelY + 4, panelX + panelW + 3, panelY + panelH + 4, 0x70000000);
            g.fill(panelX, panelY, panelX + panelW, panelY + panelH, BACKGROUND);
            g.outline(panelX, panelY, panelW, panelH, 0xFF101419);
            g.fill(panelX + 1, panelY + 1, panelX + panelW - 1, panelY + 2, 0xFF59636D);
            if (previewMode()) g.text(font, "LIVE", panelX + 12, panelY + 10, ACCENT, false);
            else {
                g.text(font, "FLUORESCENT", panelX + 12, panelY + 10, TEXT, false);
            }
            if (quickSearch != null) {
                g.fill(
                        quickSearch.getX() - 5,
                        quickSearch.getY() - 1,
                        quickSearch.getX() + quickSearch.getWidth() + 5,
                        quickSearch.getY() + 19,
                        SURFACE);
                g.fill(
                        quickSearch.getX() - 5,
                        quickSearch.getY() + 18,
                        quickSearch.getX() + quickSearch.getWidth() + 5,
                        quickSearch.getY() + 19,
                        quickSearch.isFocused() ? ACCENT : 0xFF344451);
            }
            int headingWidth = Math.max(30, baseContentW - headingRightSpace());
            g.text(
                    font,
                    shorten(font, heading(), headingWidth),
                    baseContentX,
                    headingY,
                    TEXT,
                    false);
        }
        g.enableScissor(contentX, viewTop, contentX + contentW, contentBottom);
        for (var c : cards) {
            int cy = viewTop + c.y - scroll;
            if (cy + 39 < viewTop || cy > contentBottom) continue;
            String state = c.status.get();
            int reserve = state.isEmpty() ? 0 : font.width(state) + 7;
            g.text(
                    font,
                    shorten(font, c.title, Math.max(8, c.w - 12 - reserve)),
                    c.x + 4,
                    cy + 3,
                    TEXT,
                    false);
            if (!state.isEmpty())
                g.text(
                        font,
                        shorten(font, state, c.w / 2),
                        c.x + c.w - 4 - Math.min(font.width(state), c.w / 2),
                        cy + 3,
                        state.equals("Custom") ? VIOLET : MUTED,
                        false);
            g.fill(c.x, cy + 38, c.x + c.w, cy + 39, 0xFF25313C);
        }
        for (var caption : captions) {
            int cy = viewTop + caption.y - scroll;
            if (cy + caption.height < viewTop || cy > contentBottom) continue;
            if (caption.heading) {
                drawWrapped(
                        g,
                        caption.title,
                        contentX + 1,
                        cy + 1,
                        contentW - 8,
                        Integer.MAX_VALUE,
                        ACCENT);
                if (!caption.description.isBlank())
                    drawWrapped(
                            g,
                            caption.description,
                            contentX + 1,
                            cy + caption.titleH + 4,
                            contentW - 8,
                            2,
                            MUTED);
            } else
                drawWrapped(
                        g,
                        caption.description,
                        contentX + 2,
                        cy + 3,
                        contentW - 4,
                        Integer.MAX_VALUE,
                        MUTED);
        }
        for (var r : formRows) {
            var bounds = r.bounds;
            int cy = viewTop + bounds.y - scroll, h = bounds.height - 4;
            if (cy + h < viewTop || cy > contentBottom) continue;
            String state = r.status.get();
            int stateW =
                    state.isEmpty()
                            ? 0
                            : Math.min(font.width(state), Math.max(0, r.labelW / 2 - 3));
            boolean stacked = previewMode() || contentW < 240;
            int textH = r.titleH + (r.descriptionH > 0 ? r.descriptionH + 2 : 0),
                    textY = stacked ? cy : cy + (h - textH) / 2;
            drawWrapped(g, r.title, bounds.x + 1, textY, r.titleW, Integer.MAX_VALUE, TEXT);
            if (!state.isEmpty())
                g.text(
                        font,
                        shorten(font, state, stateW),
                        bounds.x + 1 + r.labelW - stateW,
                        textY,
                        state.equals("Custom") ? VIOLET : MUTED,
                        false);
            if (r.descriptionH > 0)
                drawWrapped(
                        g, r.description, bounds.x + 1, textY + r.titleH + 2, r.labelW, 2, MUTED);
            g.fill(bounds.x, cy + h + 2, bounds.x + bounds.w, cy + h + 3, 0xFF202B36);
        }
        if (highlightY >= 0 && System.nanoTime() < highlightUntil)
            g.fill(
                    contentX - 3,
                    viewTop + highlightY - scroll,
                    contentX - 1,
                    viewTop + highlightY - scroll + highlightH - 4,
                    ACCENT);
        g.disableScissor();
        extractWorkspaceBackground(g, x, y, tick);
        super.extractRenderState(g, x, y, tick);
        g.enableScissor(contentX, viewTop, contentX + contentW, contentBottom);
        for (var placed : scrolling) placed.widget.extractRenderState(g, x, y, tick);
        g.disableScissor();
        if (!previewMode() && !embeddedPanel()) {
            g.text(font, VERSION, panelX + 12, panelY + panelH - 17, 0xFF697D8D, false);
            int footerX = panelX + Math.max(63, 24 + font.width(VERSION)),
                    footerWidth = panelX + panelW - (focusedTool() ? 117 : 162) - footerX;
            if (footerWidth > 35)
                g.text(
                        font,
                        shorten(font, footer(), footerWidth),
                        footerX,
                        panelY + panelH - 17,
                        MUTED,
                        false);
        }
        if (contentHeight > contentBottom - viewTop) {
            int thumb = scrollThumbHeight(), sy = scrollThumbTop();
            g.fill(
                    contentX + scrollViewportWidth() + 4,
                    viewTop,
                    contentX + scrollViewportWidth() + 6,
                    contentBottom,
                    0xFF24313D);
            g.fill(
                    contentX + scrollViewportWidth() + 4,
                    sy,
                    contentX + scrollViewportWidth() + 6,
                    sy + thumb,
                    ACCENT);
        }
    }

    static String shorten(net.minecraft.client.gui.Font font, String text, int width) {
        if (width <= 0) return "";
        if (font.width(text) <= width) return text;
        return font.plainSubstrByWidth(text, Math.max(1, width - font.width("..."))) + "...";
    }

    private void drawWrapped(
            GuiGraphicsExtractor g, String text, int x, int y, int w, int maxLines, int color) {
        if (maxLines == 1) {
            String shown = font.plainSubstrByWidth(text, Math.max(20, w));
            if (shown.length() < text.length())
                shown = font.plainSubstrByWidth(text, Math.max(10, w - font.width("..."))) + "...";
            g.text(font, shown, x, y, color, false);
            return;
        }
        var lines = font.split(Component.literal(text), Math.max(20, w));
        for (int i = 0; i < Math.min(maxLines, lines.size()); i++)
            g.text(font, lines.get(i), x, y + i * 10, color, false);
    }

    static String label(Enum<?> value) {
        String s = value.name().toLowerCase(Locale.ROOT);
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    static void drawButtonFace(
            GuiGraphicsExtractor g,
            int x,
            int y,
            int w,
            int h,
            boolean selected,
            boolean hovered,
            boolean active,
            boolean pressed) {
        int face = !active ? 0xFF292E34 : selected ? 0xFF36565E : hovered ? 0xFF505B65 : 0xFF3C454E;
        g.fill(x, y, x + w, y + h, 0xFF10151B);
        g.fill(x + 1, y + 1, x + w - 1, y + h - 1, face);
        int light = !active ? 0xFF3C434A : pressed ? 0xFF19242C : hovered ? 0xFF8C9AA5 : 0xFF697681;
        int dark = !active ? 0xFF20262C : pressed ? 0xFF778992 : 0xFF212C35;
        g.fill(x + 1, y + 1, x + w - 1, y + 2, light);
        g.fill(x + 1, y + 1, x + 2, y + h - 1, light);
        g.fill(x + 1, y + h - 2, x + w - 1, y + h - 1, dark);
        g.fill(x + w - 2, y + 1, x + w - 1, y + h - 1, dark);
        if (selected) g.fill(x + 2, y + h - 3, x + w - 2, y + h - 2, ACCENT);
    }

    static final class MenuButton extends Button {
        boolean selected, wrap, multiline, leftAligned;
        int leading;
        int clipTop = Integer.MIN_VALUE, clipBottom = Integer.MAX_VALUE;
        private long pressedUntil;
        String secondary, accessibleName, badge, settingId;
        Integer swatch;

        MenuButton(int x, int y, int w, int h, String text, Runnable action) {
            super(x, y, w, h, Component.literal(text), b -> action.run(), DEFAULT_NARRATION);
            setTooltipDelay(java.time.Duration.ofMillis(500));
        }

        @Override
        public void onPress(net.minecraft.client.input.InputWithModifiers input) {
            if (currentClosing()) return;
            pressedUntil = System.nanoTime() + 80_000_000L;
            super.onPress(input);
        }

        void clipTo(int top, int bottom) {
            clipTop = top;
            clipBottom = bottom;
        }

        void clearClip() {
            clipTop = Integer.MIN_VALUE;
            clipBottom = Integer.MAX_VALUE;
        }

        @Override
        public boolean isMouseOver(double x, double y) {
            return y >= clipTop && y < clipBottom && super.isMouseOver(x, y);
        }

        @Override
        public boolean mouseClicked(MouseButtonEvent event, boolean twice) {
            return event.y() >= clipTop
                    && event.y() < clipBottom
                    && super.mouseClicked(event, twice);
        }

        void settingHelp(String text) {
            setTooltip(Tooltip.create(Component.literal(text)));
        }

        @Override
        protected net.minecraft.network.chat.MutableComponent createNarrationMessage() {
            return Component.literal(
                    (accessibleName == null ? "" : accessibleName + ": ")
                            + getMessage().getString());
        }

        @Override
        protected void extractContents(GuiGraphicsExtractor g, int x, int y, float tick) {
            boolean clipped = clipTop != Integer.MIN_VALUE || clipBottom != Integer.MAX_VALUE;
            if (y < clipTop || y >= clipBottom) isHovered = false;
            int top = Math.max(getY(), clipTop),
                    bottom = Math.min(getY() + getHeight(), clipBottom);
            if (top >= bottom) return;
            if (clipped) g.enableScissor(getX(), top, getX() + getWidth(), bottom);
            try {
                drawContents(g, x, y, tick);
            } finally {
                if (clipped) g.disableScissor();
            }
        }

        private void drawContents(GuiGraphicsExtractor g, int x, int y, float tick) {
            var font = Minecraft.getInstance().font;
            int px = getX(), py = getY(), w = getWidth(), h = getHeight();
            boolean pressed = System.nanoTime() < pressedUntil;
            drawButtonFace(g, px, py, w, h, selected, isHoveredOrFocused(), active, pressed);
            if (isFocused()) g.outline(px, py, w, h, 0xFFB8D7E2);
            if (swatch != null) {
                g.fill(px + 2, py + 2, px + w - 2, py + h - 2, 0xFF000000 | swatch);
                if (selected) g.outline(px, py, w, h, ACCENT);
                return;
            }
            int textX = px + 6 + leading, textW = Math.max(8, w - 12 - leading);
            int color = active ? TEXT : 0xFF657686;
            if (secondary != null) {
                int reserve = badge == null ? 0 : font.width(badge) + 8;
                g.text(
                        font,
                        shorten(font, getMessage().getString(), Math.max(10, textW - reserve)),
                        textX,
                        py + 4,
                        color,
                        false);
                if (badge != null)
                    g.text(
                            font,
                            badge,
                            px + w - font.width(badge) - 6,
                            py + 4,
                            badge.equals("Custom") ? VIOLET : MUTED,
                            false);
                g.text(font, shorten(font, secondary, textW), textX, py + 15, MUTED, false);
            } else {
                if (badge != null) {
                    g.text(
                            font,
                            badge,
                            px + w - font.width(badge) - 7,
                            py + (h - 8) / 2,
                            selected ? ACCENT : MUTED,
                            false);
                    textW = Math.max(8, textW - font.width(badge) - 10);
                }
                String raw = getMessage().getString();
                boolean toggle = raw.equals("On") || raw.equals("Off");
                if (toggle && w >= 62) {
                    boolean on = raw.equals("On");
                    g.text(font, raw, textX, py + (h - 8) / 2, color, false);
                    int tx = px + w - 32, ty = py + (h - 12) / 2;
                    g.fill(tx, ty, tx + 25, ty + 12, 0xFF131B21);
                    g.fill(tx + 1, ty + 1, tx + 24, ty + 11, on ? 0xFF3C858A : 0xFF39424B);
                    int knob = tx + (on ? 14 : 1);
                    drawButtonFace(g, knob, ty, 11, 12, false, false, active, false);
                    g.fill(knob + 3, ty + 3, knob + 8, ty + 9, on ? ACCENT : 0xFFB1BAC2);
                } else if ((wrap || multiline) && font.width(raw) > textW) {
                    var lines = font.split(Component.literal(raw), textW);
                    int count = Math.min(lines.size(), Math.max(1, (h - 2) / 9)),
                            top = py + (h - count * 9) / 2;
                    for (int i = 0; i < count; i++)
                        g.text(font, lines.get(i), textX, top + i * 9, color, false);
                } else if (leftAligned)
                    g.text(font, shorten(font, raw, textW), textX, py + (h - 8) / 2, color, false);
                else
                    g.centeredText(
                            font, shorten(font, raw, textW), px + w / 2, py + (h - 8) / 2, color);
            }
        }
    }

    static final class MenuSlider extends AbstractSliderButton {
        String accessibleName = "Value", settingId = "";
        private final double min, max;
        private final DoubleConsumer setter;
        private final String suffix;
        private final DoubleFunction<String> formatter;

        MenuSlider(
                int x,
                int y,
                int w,
                double min,
                double max,
                double current,
                String suffix,
                DoubleConsumer setter) {
            this(x, y, w, min, max, current, suffix, setter, null);
        }

        MenuSlider(
                int x,
                int y,
                int w,
                double min,
                double max,
                double current,
                String suffix,
                DoubleConsumer setter,
                DoubleFunction<String> formatter) {
            super(x, y, w, 20, Component.empty(), Math.clamp((current - min) / (max - min), 0, 1));
            this.min = min;
            this.max = max;
            this.suffix = suffix;
            this.setter = setter;
            this.formatter = formatter;
            setTooltipDelay(java.time.Duration.ofMillis(500));
            updateMessage();
        }

        protected void updateMessage() {
            double current = min + value * (max - min);
            setMessage(
                    Component.literal(
                            formatter == null
                                    ? String.format(Locale.ROOT, "%.2f", current) + suffix
                                    : formatter.apply(current)));
        }

        protected void applyValue() {
            setter.accept(min + value * (max - min));
        }

        @Override
        protected net.minecraft.network.chat.MutableComponent createNarrationMessage() {
            return Component.literal(accessibleName + ": " + getMessage().getString());
        }

        @Override
        public void extractWidgetRenderState(GuiGraphicsExtractor g, int x, int y, float tick) {
            var font = Minecraft.getInstance().font;
            int px = getX(),
                    py = getY(),
                    w = getWidth(),
                    knob = px + 2 + (int) (value * Math.max(0, w - 12));
            g.fill(px, py, px + w, py + 20, 0xFF11171D);
            g.fill(px + 1, py + 1, px + w - 1, py + 19, active ? 0xFF2E3841 : 0xFF252B31);
            g.fill(px + 2, py + 2, knob + 4, py + 18, active ? 0xFF385F68 : 0xFF303B42);
            g.fill(px + 1, py + 18, px + w - 1, py + 19, 0xFF65717B);
            g.fill(px + w - 2, py + 1, px + w - 1, py + 19, 0xFF65717B);
            drawButtonFace(g, knob, py + 1, 9, 18, false, isHoveredOrFocused(), active, false);
            if (active)
                g.fill(
                        knob + 3,
                        py + 4,
                        knob + 6,
                        py + 15,
                        isHoveredOrFocused() ? ACCENT : 0xFFB3C5D0);
            g.centeredText(
                    font,
                    shorten(font, getMessage().getString(), w - 8),
                    px + w / 2,
                    py + 6,
                    active ? TEXT : 0xFF78858E);
            if (isFocused()) g.outline(px, py, w, 20, 0xFFB8D7E2);
        }
    }
}
