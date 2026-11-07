package dev.stillbloom.gui;

import dev.stillbloom.StillBloomClient;
import dev.stillbloom.mask.BlockLinks;
import dev.stillbloom.mask.TextureMasks;
import dev.stillbloom.model.BlockCatalog;
import dev.stillbloom.model.BlockMaskVariants;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.*;
import java.util.function.Consumer;

final class BlockLinksScreen extends BloomMenuScreen {
    private final Screen parent;
    private final String openedBlock;
    private final Set<String> selected = new TreeSet<>();
    private final List<MenuButton> rows = new ArrayList<>();
    private List<String> filtered = List.of();
    private String source,
            query = "",
            namespace = "",
            message =
                    "Choose members and a source. Apply shares its complete lighting configuration.";
    private boolean membersOnly = true, preparing, review;
    private int listX, listW, listTop, listBottom, sideX, sideW, offset;
    private TextureMasks undo;
    private long undoRevision;
    private Iterator<String> inspecting;
    private String inspectingId;
    private BlockMaskVariants.TextureQuery inspection;
    private Set<String> proposed = Set.of();
    private final Map<String, List<BlockLinks.Texture>> textures = new TreeMap<>();

    BlockLinksScreen(Screen parent, String block, Collection<String> candidates) {
        super("Linked blocks");
        this.parent = parent;
        openedBlock = block;
        source = block;
        selected.addAll(candidates);
        selected.add(block);
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
    protected String heading() {
        return "Linked blocks / " + selected.size() + " selected";
    }

    @Override
    protected BloomSettingsScreen settingsOwner() {
        return findSettingsOwner(parent);
    }

    @Override
    protected void navigate(int page) {
        onClose();
    }

    @Override
    public void onClose() {
        var owner = settingsOwner();
        if (owner != null) owner.refreshLinks();
        minecraft.gui.setScreen(parent);
    }

    @Override
    protected String footer() {
        return preparing
                ? "Inspecting textures in small steps..."
                : review
                        ? "Review the source, then Apply links"
                        : "Changes save only when you apply an action";
    }

    @Override
    protected void init() {
        frame();
        rows.clear();
        listX = contentX;
        sideW = Math.clamp(contentW / 3, 136, 230);
        listW = contentW - sideW - 12;
        sideX = listX + listW + 12;
        listTop = contentTop + 48;
        listBottom = contentBottom;
        int filterW = Math.min(128, listW / 2);
        var search =
                new EditBox(
                        font,
                        listX,
                        contentTop,
                        listW - filterW - 4,
                        20,
                        Component.literal("Search blocks to link"));
        search.setMaxLength(128);
        search.setHint(Component.literal("Find block..."));
        search.setValue(query);
        addRenderableWidget(search);
        setInitialFocus(search);
        button(
                listX + listW - filterW,
                contentTop,
                filterW,
                20,
                BlockCatalog.current().sourceName(namespace) + " v",
                () ->
                        minecraft.gui.setScreen(
                                new BlockSourceFilterScreen(
                                        this,
                                        namespace,
                                        value -> {
                                            namespace = value;
                                            offset = 0;
                                        })),
                "Filter by Vanilla or a mod. Selections hidden by filters are retained.");
        int half = (listW - 4) / 2;
        button(
                                listX,
                                contentTop + 24,
                                half,
                                18,
                                membersOnly ? "View: selected" : "View: all blocks",
                                () -> {
                                    membersOnly = !membersOnly;
                                    offset = 0;
                                    rebuildWidgets();
                                },
                                "Switch between selected members (including absent mods) and the full registry. Choose All blocks to add members.")
                        .accessibleName =
                "Link member view";
        button(
                                listX + half + 4,
                                contentTop + 24,
                                listW - half - 4,
                                18,
                                "Clear selection",
                                () -> {
                                    selected.clear();
                                    source = null;
                                    invalidateDraft();
                                    filter();
                                    rebuildWidgets();
                                },
                                "Clear the draft selection. Saved groups are not changed.")
                        .active =
                !preparing;
        search.setResponder(
                s -> {
                    query = s;
                    offset = 0;
                    filter();
                });
        filter();
        int y = 0;
        y =
                sideButton(
                        y,
                        "Source: "
                                + (source == null
                                        ? "choose a member"
                                        : BlockCatalog.current().name(source)),
                        () -> {},
                        "Click Source beside a selected row. Its colors, shapes, strength and masks will be used by the group.",
                        false);
        if (preparing)
            y =
                    sideButton(
                            y,
                            "Cancel inspection",
                            () -> {
                                preparing = false;
                                inspection = null;
                                rebuildWidgets();
                            },
                            "Return to the draft without changing saved groups.",
                            true);
        else
            y =
                    sideButton(
                            y,
                            review ? "Apply links" : "Review links",
                            review ? this::applyLinks : this::prepare,
                            "Select at least two blocks. Existing groups are included in full; choose a source explicitly before applying.",
                            selected.size() > 1 && source != null);
        y =
                sideButton(
                        y,
                        "Unlink selected",
                        () ->
                                change(
                                        m -> BlockLinks.unlink(m, selected),
                                        "Selected blocks are independent; their appearance is preserved."),
                        "Remove selected blocks from their groups and opt them out of automatic linking. Keep their current lighting.",
                        !selected.isEmpty() && !preparing);
        y =
                sideButton(
                        y,
                        "Unlink whole group",
                        () ->
                                change(
                                        m -> BlockLinks.unlinkGroup(m, openedBlock),
                                        "Group dissolved. Members keep their appearance and stay independent."),
                        "Unlink every member of the group opened from the block editor. Keep each current configuration.",
                        StillBloomClient.MASKS.group(openedBlock) != null && !preparing);
        y =
                sideButton(
                        y,
                        "Restore automatic",
                        () ->
                                change(
                                        m -> BlockLinks.restoreAutomatic(m, source),
                                        "Automatic matching restored for the source block. Reopen its editor to review any conflicts."),
                        "Detach the source from any manual group, keep its appearance and allow automatic matching again. Other members keep their choices.",
                        source != null && !preparing);
        y =
                sideButton(
                        y,
                        "Undo last action",
                        this::undo,
                        "Restore all configurations and link preferences from before this screen's last action.",
                        undo != null
                                && undoRevision == StillBloomClient.MASKS.revision()
                                && !preparing);

        contentX = sideX;
        contentW = sideW;
        viewTop = contentTop;
        int end = note(y + 2, message);
        if (review) {
            int different = 0;
            var p = StillBloomClient.MASKS.profile(source);
            for (var id : proposed)
                if (!BlockLinks.same(p, StillBloomClient.MASKS.profile(id))) different++;
            end =
                    note(
                            end,
                            "Source: "
                                    + source
                                    + ". "
                                    + proposed.size()
                                    + " members; "
                                    + different
                                    + " differing configurations will be replaced. Undo is available after Apply.");
            var pairs = BlockLinks.textureLinks(source, proposed, textures);
            var sourceTextures = new HashSet<String>();
            for (var t : textures.getOrDefault(source, List.of())) sourceTextures.add(t.id());
            int shared = 0, unpaired = 0;
            for (var id : proposed)
                if (!id.equals(source))
                    for (var t : textures.getOrDefault(id, List.of())) {
                        if (sourceTextures.contains(t.id()) || pairs.containsKey(t.id())) shared++;
                        else unpaired++;
                    }
            end =
                    note(
                            end,
                            "Paint sharing: "
                                    + shared
                                    + " matching texture slots, including corresponding block states and faces.");
            if (unpaired > 0)
                end =
                        note(
                                end,
                                unpaired
                                        + " texture slots remain unpaired. Paint those separately; no matching source pattern was found.");
        }
        contentHeight = end;
        finishLayout();
    }

    private int sideButton(int y, String title, Runnable action, String help, boolean active) {
        var b = new MenuButton(sideX, 0, sideW, 20, title, action);
        b.setTooltip(Tooltip.create(Component.literal(help)));
        b.accessibleName = title;
        b.active = active;
        scrolling(b, y);
        return y + 24;
    }

    private void invalidateDraft() {
        review = false;
        preparing = false;
        inspection = null;
        proposed = Set.of();
    }

    private void filter() {
        var index = BlockCatalog.current();
        if (!index.hasSource(namespace)) namespace = "";
        if (!membersOnly) filtered = index.search(query, namespace, id -> true);
        else {
            String q = query.strip().toLowerCase(Locale.ROOT);
            filtered =
                    selected.stream()
                            .filter(
                                    id ->
                                            (namespace.isEmpty() || id.startsWith(namespace + ":"))
                                                    && (id + " " + index.name(id))
                                                            .toLowerCase(Locale.ROOT)
                                                            .contains(q))
                            .toList();
        }
        offset = Math.clamp(offset, 0, Math.max(0, filtered.size() - visibleRows()));
        listRows();
    }

    private int visibleRows() {
        return Math.max(1, (listBottom - listTop) / 32);
    }

    private void listRows() {
        for (var row : rows) removeWidget(row);
        rows.clear();
        var index = BlockCatalog.current();
        int sourceW = Math.min(54, listW / 3);
        for (int i = 0; i < visibleRows() && i + offset < filtered.size(); i++) {
            String id = filtered.get(i + offset);
            int at = listTop + i * 32;
            boolean chosen = selected.contains(id);
            var b =
                    button(
                            listX,
                            at,
                            listW - sourceW - 3,
                            29,
                            index.name(id),
                            () -> {
                                if (!selected.add(id)) {
                                    selected.remove(id);
                                    if (id.equals(source))
                                        source = selected.stream().findFirst().orElse(null);
                                } else if (source == null) source = id;
                                invalidateDraft();
                                rebuildWidgets();
                            },
                            id
                                    + ". Click to add/remove from the draft. Saved links change only after an action.");
            b.selected = chosen;
            b.secondary = id;
            b.settingId = "link/member/" + id;
            b.active = !preparing;
            rows.add(b);
            var pick =
                    button(
                            listX + listW - sourceW,
                            at,
                            sourceW,
                            29,
                            "Source",
                            () -> {
                                source = id;
                                invalidateDraft();
                                rebuildWidgets();
                            },
                            "Use " + id + " as the source configuration for the proposed group.");
            pick.selected = id.equals(source);
            pick.active = chosen && !preparing;
            pick.settingId = "link/source/" + id;
            rows.add(pick);
        }
    }

    private void prepare() {
        proposed = BlockLinks.expanded(StillBloomClient.MASKS, selected);
        inspecting = proposed.iterator();
        textures.clear();
        inspection = null;
        preparing = true;
        review = false;
        rebuildWidgets();
    }

    @Override
    public void tick() {
        if (!preparing) return;
        if (inspection != null && inspection.stale()) {
            message = "Resources changed. Review links again with the new models.";
            invalidateDraft();
            rebuildWidgets();
            return;
        }
        if (inspection == null) {
            if (!inspecting.hasNext()) {
                preparing = false;
                review = true;
                selected.addAll(proposed);
                membersOnly = true;
                query = "";
                namespace = "";
                offset = 0;
                rebuildWidgets();
                return;
            }
            inspectingId = inspecting.next();
            var block =
                    BuiltInRegistries.BLOCK
                            .getOptional(Identifier.parse(inspectingId))
                            .orElse(null);
            if (block == null) return;
            inspection = BlockMaskVariants.inspect(block);
        }
        inspection.advance();
        if (inspection.ready()) {
            textures.put(inspectingId, inspection.descriptions());
            inspection = null;
        }
    }

    private void applyLinks() {
        change(
                m -> BlockLinks.link(m, proposed, source, false, textures),
                "Linked "
                        + proposed.size()
                        + " blocks using "
                        + source
                        + ". Every member edits the same lighting configuration.");
    }

    private void change(Consumer<TextureMasks> edit, String success) {
        try {
            var current = StillBloomClient.MASKS;
            var before = BlockLinks.copyStore(current);
            var staged = BlockLinks.copyStore(current);
            edit.accept(staged);
            if (!staged.save(StillBloomClient.MASK_PATH)) {
                message = "Could not save. No configuration or group changed.";
                rebuildWidgets();
                return;
            }
            BlockLinks.install(current, staged);
            undo = before;
            undoRevision = current.revision();
            PresetStatus.changed();
            StillBloomClient.RENDERER.invalidate();
            message = success;
            invalidateDraft();
            rebuildWidgets();
        } catch (IllegalArgumentException e) {
            message = e.getMessage();
            rebuildWidgets();
        }
    }

    private void undo() {
        var current = StillBloomClient.MASKS;
        if (undo == null || undoRevision != current.revision()) return;
        if (!undo.save(StillBloomClient.MASK_PATH)) {
            message = "Could not save Undo. Current configuration retained.";
            rebuildWidgets();
            return;
        }
        BlockLinks.install(current, undo);
        undo = null;
        PresetStatus.changed();
        StillBloomClient.RENDERER.invalidate();
        message = "Restored the previous configurations and group memberships.";
        invalidateDraft();
        rebuildWidgets();
    }

    @Override
    public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        if (isClosing()) return true;
        if (x >= listX && x <= listX + listW && y >= listTop && y < listBottom) {
            offset =
                    Math.clamp(
                            offset - (int) Math.signum(vertical) * 3,
                            0,
                            Math.max(0, filtered.size() - visibleRows()));
            listRows();
            return true;
        }
        return super.mouseScrolled(x, y, horizontal, vertical);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int x, int y, float tick) {
        super.extractRenderState(g, x, y, tick);
        if (filtered.isEmpty())
            g.textWithWordWrap(
                    font,
                    Component.literal(
                            "No matches. Try All blocks, another mod, or a shorter search."),
                    listX + 3,
                    listTop + 6,
                    listW - 6,
                    MUTED);
        if (filtered.size() > visibleRows()) {
            int h = listBottom - listTop, thumb = Math.max(10, h * visibleRows() / filtered.size());
            int top = listTop + (h - thumb) * offset / Math.max(1, filtered.size() - visibleRows());
            g.fill(listX + listW + 3, listTop, listX + listW + 5, listBottom, SURFACE);
            g.fill(listX + listW + 3, top, listX + listW + 5, top + thumb, ACCENT);
        }
    }

    static final class Session {
        final String block;
        private BlockMaskVariants.TextureQuery textures;
        private BlockMaskVariants.Query query;
        private TextureMasks owner;
        private boolean checked, conflict, saveFailed;
        private long lastAdvance;

        Session(String block) {
            this.block = block;
        }

        List<String> candidates() {
            return query != null && query.ready()
                    ? BlockLinks.automaticCandidates(StillBloomClient.MASKS, block, query.matches())
                    : BlockLinks.members(StillBloomClient.MASKS, block);
        }

        boolean ready() {
            var masks = StillBloomClient.MASKS;
            var g = masks.group(block);
            return g != null && !g.automatic
                    || masks.independentBlocks.contains(block)
                    || checked && !conflict;
        }

        boolean conflict() {
            return conflict;
        }

        String label() {
            var masks = StillBloomClient.MASKS;
            var g = masks.group(block);
            if (g != null) return (g.automatic ? "Auto: " : "Linked: ") + g.members.size();
            if (conflict) return "Choose source";
            if (masks.independentBlocks.contains(block)) return "Independent";
            return checked ? "Link blocks" : "Finding matches";
        }

        boolean tick() {
            if (System.nanoTime() - lastAdvance < 20_000_000) return false;
            lastAdvance = System.nanoTime();
            var masks = StillBloomClient.MASKS;
            boolean refresh = false;
            if (owner != masks || textures != null && textures.stale()) {
                owner = masks;
                checked = false;
                conflict = false;
                query = null;
                textures = null;
                refresh = true;
            }
            var g = masks.group(block);
            if (g != null && !g.automatic || masks.independentBlocks.contains(block))
                return refresh;
            if (checked) return refresh;
            var key = Identifier.tryParse(block);
            var b = key == null ? null : BuiltInRegistries.BLOCK.getOptional(key).orElse(null);
            if (b == null) {
                checked = true;
                return true;
            }
            if (textures == null) textures = BlockMaskVariants.inspect(b);
            if (!textures.ready()) {
                textures.advance();
                return refresh;
            }
            if (query == null) query = BlockMaskVariants.request(b, textures.sprites());
            if (!query.ready()) {
                query.advance();
                return refresh;
            }
            if (!BlockLinks.automaticUpdateNeeded(masks, block, query.matches())) {
                conflict = BlockLinks.conflict(masks, block, query.matches());
                checked = true;
                return true;
            }
            var staged = BlockLinks.copyStore(masks);
            long previous = staged.revision();
            BlockLinks.acceptAutomatic(staged, block, query.matches());
            if (staged.revision() != previous) {
                if (staged.save(StillBloomClient.MASK_PATH)) {
                    BlockLinks.install(masks, staged);
                    PresetStatus.changed();
                } else saveFailed = true;
            }
            conflict = BlockLinks.conflict(masks, block, query.matches());
            checked = true;
            return true;
        }

        void reset() {
            checked = false;
            conflict = false;
            saveFailed = false;
        }

        String help() {
            return saveFailed
                    ? "Could not save links. Your previous settings are kept."
                    : conflict
                            ? "These blocks have different settings. Choose which block's settings to share, or edit this block separately."
                            : "Linked blocks use the same glow settings and paint. Open to add or remove blocks, or choose which settings to share. Unlinking keeps the current look.";
        }
    }
}
