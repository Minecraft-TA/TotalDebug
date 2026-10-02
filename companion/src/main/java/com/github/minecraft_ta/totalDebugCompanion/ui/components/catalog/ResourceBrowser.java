package com.github.minecraft_ta.totalDebugCompanion.ui.components.catalog;

import com.github.minecraft_ta.totalDebugCompanion.ui.components.Sidebar;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.BrowserBody;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.TypeToFilter;
import com.github.minecraft_ta.totalDebugCompanion.Icons;
import com.github.minecraft_ta.totalDebugCompanion.catalog.ModResources;
import com.github.minecraft_ta.totalDebugCompanion.navigation.NavigationTarget;
import com.github.minecraft_ta.totalDebugCompanion.resource.FileTypeResolver;
import com.github.minecraft_ta.totalDebugCompanion.ui.ContextMenus;
import com.github.minecraft_ta.totalDebugCompanion.ui.Tooltip;
import com.github.minecraft_ta.totalDebugCompanion.ui.UiMetrics;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.CenteredIcon;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.FlatIconTextField;
import com.github.minecraft_ta.totalDebugCompanion.ui.presentation.PrimarySecondaryLabel;
import com.github.minecraft_ta.totalDebugCompanion.ui.presentation.PrimarySecondaryText;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.ThemeColors;

import javax.swing.text.JTextComponent;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.AbstractListModel;
import javax.swing.DefaultListModel;
import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.ToolTipManager;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Resources by kind, a mod's or the whole pack's. Categories are listed with their counts; files show their path inside
 * the category, and textures show as a grid of pixel-exact previews. Opening a resource shows it in a resource tab.
 * The list is sorted once when resources are set and rows have one height, so filtering stays quick for a whole pack.
 */
public final class ResourceBrowser extends JPanel {
    private static final String ALL = "";
    private static final String TEXTURES = "assets/textures";
    private static final int CELL_WIDTH = 92;
    private static final int CELL_HEIGHT = 84;
    private static final Icon ANIMATED = Icons.RUN.derive(12, 12);

    private record Category(String key, String label, int count) {
        /** What the category's files are, as their own icons show; All has none. */
        Icon icon() {
            if (this.key.isEmpty()) return Icons.NONE;
            return switch (this.key.substring(this.key.indexOf('/') + 1)) {
                case "textures" -> Icons.IMAGE_FILE;
                case "sounds" -> Icons.SOUND;
                case "lang" -> Icons.TEXT_FILE;
                case "font" -> Icons.FONT_FILE;
                default -> Icons.JSON_FILE;
            };
        }
    }

    private DefaultListModel<Category> categories = new DefaultListModel<>();
    private final JList<Category> categoryList = new JList<>(this.categories);
    private final JScrollPane categoryScroll = new JScrollPane(this.categoryList);
    private final BrowserBody body;
    private final Sidebar sidebar;
    private final FlatIconTextField filter;
    private final ShownModel shown = new ShownModel();
    private final JList<ModResources.Resource> list = new JList<>(this.shown);
    private final TextureThumbnails thumbnails = new TextureThumbnails(UiMetrics.previewPixels(UiMetrics.THUMBNAIL_SIZE));
    /** The resources in the order they are shown, and what the filter matches in each, in lowercase. */
    private List<ModResources.Resource> resources = List.of();
    private List<String> lowercasePaths = List.of();
    /** The pack each resource's copy comes from and the packs it hides, by path, for the whole pack; empty for a mod. */
    private Map<String, String> from = Map.of();
    private Map<String, List<String>> hidden = Map.of();
    private Set<String> animated = Set.of();
    private String pendingCategory = ALL;
    /** Whether a listing was shown, which settles a category asked for; before the first, one waits for it. */
    private boolean listed;
    private boolean updating;

    public ResourceBrowser(Consumer<NavigationTarget> navigator) {
        super(new BorderLayout());
        Objects.requireNonNull(navigator, "navigator");
        this.categoryList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        this.categoryList.setCellRenderer((list, category, index, selected, focused) -> {
            PrimarySecondaryLabel label = new PrimarySecondaryLabel();
            label.configure(new PrimarySecondaryText(category.label(),
                            NumberFormat.getIntegerInstance(Locale.ROOT).format(category.count())), category.icon(), list.getFont(),
                    selected, selected ? list.getSelectionForeground() : ThemeColors.text(),
                    selected ? list.getSelectionBackground() : list.getBackground());
            label.setOpaque(true);
            label.setBackground(selected ? list.getSelectionBackground() : list.getBackground());
            label.setBorder(UiMetrics.listRowPadding());
            return label;
        });
        this.categoryList.addListSelectionListener(event -> {
            if (event.getValueIsAdjusting() || this.updating) return;
            applyFilter();
        });
        this.categoryScroll.setBorder(BorderFactory.createEmptyBorder());

        this.list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        this.list.setCellRenderer(this::render);
        ToolTipManager.sharedInstance().registerComponent(this.list);
        this.list.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                int row = ResourceBrowser.this.list.locationToIndex(event.getPoint());
                if (event.getClickCount() == 2 && row >= 0 && ResourceBrowser.this.list.getCellBounds(row, row).contains(event.getPoint())) {
                    navigator.accept(ResourceBrowser.this.shown.get(row).target());
                }
            }
        });
        this.list.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "openResource");
        this.list.getActionMap().put("openResource", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent event) {
                ModResources.Resource selected = ResourceBrowser.this.list.getSelectedValue();
                if (selected != null) navigator.accept(selected.target());
            }
        });
        ContextMenus.installList(this.list, this::menu);

        JScrollPane listScroll = BrowserBody.scroll(this.list);
        listScroll.getVerticalScrollBar().setUnitIncrement(16);
        this.body = new BrowserBody("Filter resources", listScroll, this.list, this::applyFilter);
        this.filter = this.body.filter();
        this.sidebar = new Sidebar("resource-categories", UiMetrics.CATEGORY_LIST_WIDTH, this.categoryScroll, this.body);
        add(this.sidebar, BorderLayout.CENTER);
        TypeToFilter.forwardTyping(this.categoryList, () -> this.filter);
    }

    /** The field that filters the resources, which typing anywhere on the page reaches. */
    public JTextComponent filterField() {
        return this.filter;
    }

    private JPopupMenu menu(int row) {
        ModResources.Resource resource = this.shown.get(row);
        JPopupMenu menu = new JPopupMenu();
        menu.add(ContextMenus.copyAction("Copy Path", resource.path()));
        menu.add(ContextMenus.copyAction("Copy Resource ID", resource.namespace() + ":" + resource.relativePath()));
        return menu;
    }

    /** The category's folder in words, with its root when both assets and data could have it. */
    static String label(String key) {
        ModResources.Category category = ModResources.Category.parse(key);
        String words = category.folder().replace('_', ' ');
        words = words.isEmpty() ? words : Character.toUpperCase(words.charAt(0)) + words.substring(1);
        return category.root().equals("data") ? words + " (data)" : words;
    }

    /**
     * Resources made ready to show: sorted once, with their paths in lowercase for filtering, the count of each
     * category, and the textures that are animated. Across the whole pack, {@code from} names the pack each copy comes
     * from and {@code hidden} the lower packs that supply the same path, the nearest first; both are empty for a mod.
     */
    public record Prepared(List<ModResources.Resource> resources, List<String> lowercasePaths, Map<String, Integer> counts,
                           Set<String> animated, Map<String, String> from, Map<String, List<String>> hidden) {
        /** No resources, as for a page that could not read them. */
        public static final Prepared NONE = new Prepared(List.of(), List.of(), Map.of(), Set.of(), Map.of(), Map.of());
    }

    /**
     * Prepares {@code resources} to show, in the page's read: sorting them takes a while for a whole pack or a large mod,
     * so it never runs on the Swing thread.
     */
    public static Prepared prepare(List<ModResources.Resource> resources, Map<String, String> from,
                                   Map<String, List<String>> hidden) {
        if (SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Resources are prepared off the Swing thread");
        // Sorted once here, by keys made once, instead of on every keystroke.
        List<String> keys = new ArrayList<>(resources.size());
        for (ModResources.Resource resource : resources) keys.add(resource.relativePath() + '\u0000' + resource.path());
        Integer[] order = new Integer[resources.size()];
        for (int index = 0; index < order.length; index++) order[index] = index;
        Arrays.sort(order, Comparator.comparing(keys::get));
        List<ModResources.Resource> sorted = new ArrayList<>(order.length);
        List<String> lowercase = new ArrayList<>(order.length);
        for (Integer index : order) {
            ModResources.Resource resource = resources.get(index);
            sorted.add(resource);
            // The filter matches everything the row shows: the path and the pack the copy comes from.
            String source = from.getOrDefault(resource.path(), "");
            lowercase.add((resource.path() + (source.isEmpty() ? "" : "\n" + source)).toLowerCase(Locale.ROOT));
        }
        Set<String> animated = new HashSet<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (ModResources.Category category : ModResources.categories(resources)) counts.put(category.key(), 0);
        for (ModResources.Resource resource : resources) {
            counts.merge(resource.category().key(), 1, Integer::sum);
            if (resource.path().endsWith(".png.mcmeta")) animated.add(resource.path().substring(0, resource.path().length() - 7));
        }
        return new Prepared(List.copyOf(sorted), List.copyOf(lowercase), counts, Set.copyOf(animated), Map.copyOf(from),
                Map.copyOf(hidden));
    }

    public void setResources(Prepared prepared) {
        this.resources = prepared.resources();
        this.lowercasePaths = prepared.lowercasePaths();
        this.animated = prepared.animated();
        this.from = prepared.from();
        this.hidden = prepared.hidden();
        this.updating = true;
        try {
            // A new model set at once: adding rows one by one makes the list measure all its rows after each.
            DefaultListModel<Category> categories = new DefaultListModel<>();
            categories.addElement(new Category(ALL, "All", prepared.resources().size()));
            prepared.counts().forEach((key, count) -> categories.addElement(new Category(key, label(key), count)));
            this.categories = categories;
            this.categoryList.setModel(categories);
            this.sidebar.setSidebarShown(prepared.counts().size() > 1);
            selectKey(this.pendingCategory);
        } finally {
            this.updating = false;
        }
        this.listed = true;
        applyFilter();
    }

    /** Shows a message in place of the resources, for example why they could not be read; empty shows the resources. */
    public void setMessage(String message) {
        if (message.isEmpty()) this.body.showContent();
        else this.body.showMessage(message);
    }

    /**
     * Selects the category {@code key}, or All when it is not listed. Against a listing shown the request is settled at
     * once, so All chosen again is the user's; asked before the first listing, that listing settles it.
     */
    public void selectCategory(String key) {
        this.pendingCategory = key == null ? ALL : key;
        selectKey(this.pendingCategory);
        if (this.listed) this.pendingCategory = selectedCategory();
    }

    private void selectKey(String key) {
        for (int index = 0; index < this.categories.size(); index++) {
            if (this.categories.get(index).key().equals(key)) {
                this.categoryList.setSelectedIndex(index);
                return;
            }
        }
        if (!this.categories.isEmpty()) this.categoryList.setSelectedIndex(0);
    }

    public String selectedCategory() {
        Category selected = this.categoryList.getSelectedValue();
        return selected == null ? this.pendingCategory : selected.key();
    }

    /** The list of resources, for tests. */
    JList<ModResources.Resource> resourceList() {
        return this.list;
    }

    public int rowCount() {
        return this.shown.size();
    }

    boolean showsTextures() {
        return TEXTURES.equals(selectedCategory());
    }

    private void applyFilter() {
        String key = selectedCategory();
        if (!this.updating) this.pendingCategory = key;
        boolean textures = TEXTURES.equals(key);
        String text = this.filter.getText().strip().toLowerCase(Locale.ROOT);
        List<ModResources.Resource> matching = new ArrayList<>();
        for (int index = 0; index < this.resources.size(); index++) {
            ModResources.Resource resource = this.resources.get(index);
            if (!key.isEmpty() && !resource.category().key().equals(key)) continue;
            if (textures && !resource.path().endsWith(".png")) continue;
            if (!text.isEmpty() && !this.lowercasePaths.get(index).contains(text)) continue;
            matching.add(resource);
        }
        // Read before the layout changes: asking a list for its rows while it has no fixed row size makes it render every
        // row to measure it, which for every resource of a pack takes seconds on the Swing thread.
        ModResources.Resource selected = this.list.getSelectedValue();
        int firstVisible = this.list.getFirstVisibleIndex();
        this.list.setLayoutOrientation(textures ? JList.HORIZONTAL_WRAP : JList.VERTICAL);
        this.list.setVisibleRowCount(textures ? -1 : 8);
        // Fixed sizes keep the list from rendering every row to measure it. A vertical list paints each row at its own
        // width anyway, so a width of one only makes it follow the view's width instead of the widest row's. Without a
        // resource to measure a row on, the height the list has stays.
        this.list.setFixedCellWidth(textures ? CELL_WIDTH : 1);
        int height = textures ? CELL_HEIGHT : rowHeight();
        if (height > 0) this.list.setFixedCellHeight(height);
        this.shown.show(matching);
        // A refresh keeps the selected resource, and otherwise the rows in view, where they are still listed.
        int kept = selected == null ? -1 : indexOf(matching, selected.path());
        if (kept >= 0) {
            this.list.setSelectedIndex(kept);
            this.list.ensureIndexIsVisible(kept);
        } else if (firstVisible > 0 && firstVisible < matching.size()) {
            this.list.ensureIndexIsVisible(firstVisible);
        }
    }

    private static int indexOf(List<ModResources.Resource> resources, String path) {
        for (int index = 0; index < resources.size(); index++) {
            if (resources.get(index).path().equals(path)) return index;
        }
        return -1;
    }

    /** The height of a list row, measured once on a row of the first resource; -1 while there is none. */
    private int rowHeight() {
        if (this.resources.isEmpty()) return -1;
        return render(this.list, this.resources.getFirst(), 0, false, false).getPreferredSize().height;
    }

    private Component render(JList<? extends ModResources.Resource> list, ModResources.Resource resource, int index,
                             boolean selected, boolean focused) {
        if (showsTextures()) return new TextureCell(resource, selected, list);
        PrimarySecondaryLabel label = new PrimarySecondaryLabel();
        String secondary = resource.folder();
        String source = this.from.get(resource.path());
        // Across the whole pack, a folder is only clear with its namespace, and a copy with the pack it comes from.
        if (source != null) secondary = (resource.namespace() + (secondary.isEmpty() ? "" : ":" + secondary) + "  " + source);
        if (selectedCategory().isEmpty()) secondary = (label(resource.category().key()) + "  " + secondary).strip();
        label.configure(new PrimarySecondaryText(resource.fileName(), secondary),
                FileTypeResolver.resolve(resource.fileName()).icon(), list.getFont(), selected,
                selected ? list.getSelectionForeground() : ThemeColors.text(),
                selected ? list.getSelectionBackground() : list.getBackground());
        label.setOpaque(true);
        label.setBackground(selected ? list.getSelectionBackground() : list.getBackground());
        label.setBorder(UiMetrics.listRowPadding());
        label.setToolTipText(tooltip(resource));
        return label;
    }

    private String tooltip(ModResources.Resource resource) {
        return tooltip(Tooltip.of(resource.path()), resource);
    }

    /** Adds the pack a copy comes from and the lower packs it hides, across the whole pack. */
    private String tooltip(Tooltip tooltip, ModResources.Resource resource) {
        String source = this.from.get(resource.path());
        if (source != null) tooltip.fact("From", source);
        List<String> below = this.hidden.getOrDefault(resource.path(), List.of());
        if (!below.isEmpty()) tooltip.fact("Hides", String.join(", ", below));
        return tooltip.html();
    }

    /** The resources passing the filter; replaced as a whole, so a whole pack's are not copied row by row. */
    private static final class ShownModel extends AbstractListModel<ModResources.Resource> {
        private List<ModResources.Resource> items = List.of();

        void show(List<ModResources.Resource> items) {
            int before = this.items.size();
            this.items = items;
            if (before > 0) fireIntervalRemoved(this, 0, before - 1);
            if (!items.isEmpty()) fireIntervalAdded(this, 0, items.size() - 1);
        }

        ModResources.Resource get(int index) {
            return this.items.get(index);
        }

        int size() {
            return this.items.size();
        }

        @Override
        public int getSize() {
            return this.items.size();
        }

        @Override
        public ModResources.Resource getElementAt(int index) {
            return this.items.get(index);
        }
    }

    /** A texture preview with its name below; animated textures carry a small play mark. */
    private final class TextureCell extends JPanel {
        private TextureCell(ModResources.Resource texture, boolean selected, JList<?> list) {
            super(new BorderLayout(0, 2));
            setOpaque(true);
            setBackground(selected ? list.getSelectionBackground() : list.getBackground());
            setBorder(BorderFactory.createEmptyBorder(6, 4, 4, 4));
            Icon preview = ResourceBrowser.this.thumbnails.icon(texture, list);
            int size = ResourceBrowser.this.thumbnails.size();
            Icon shown = preview == null ? new CenteredIcon(Icons.IMAGE_FILE, size) : preview;
            boolean moving = ResourceBrowser.this.animated.contains(texture.path());
            JLabel image = new JLabel(moving ? new AnimatedMark(shown) : shown, SwingConstants.CENTER);
            add(image, BorderLayout.CENTER);
            String stem = texture.fileName().substring(0, texture.fileName().length() - 4);
            JLabel name = new JLabel(stem, SwingConstants.CENTER);
            name.setFont(list.getFont().deriveFont(list.getFont().getSize2D() - 1f));
            name.setForeground(selected ? list.getSelectionForeground() : ThemeColors.secondaryText());
            add(name, BorderLayout.SOUTH);
            // Across the whole pack, the same name can be in several namespaces.
            String id = ResourceBrowser.this.from.isEmpty() ? texture.relativePath() : texture.namespace() + ":" + texture.relativePath();
            setToolTipText(tooltip(Tooltip.of(id + (moving ? ", animated" : "")), texture));
        }
    }

    /** Draws a small play triangle over the corner of an animated texture's preview. */
    private record AnimatedMark(Icon preview) implements Icon {
        @Override
        public void paintIcon(Component component, Graphics graphics, int x, int y) {
            this.preview.paintIcon(component, graphics, x, y);
            ANIMATED.paintIcon(component, graphics, x + getIconWidth() - 12, y + getIconHeight() - 12);
        }

        @Override
        public int getIconWidth() {
            return this.preview.getIconWidth();
        }

        @Override
        public int getIconHeight() {
            return this.preview.getIconHeight();
        }
    }

    public void dispose() {
        this.thumbnails.dispose();
    }
}
