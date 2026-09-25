package com.github.minecraft_ta.totalDebugCompanion.ui.components.catalog;

import com.github.minecraft_ta.totalDebugCompanion.ui.components.TypeToFilter;
import com.github.minecraft_ta.totalDebugCompanion.Icons;
import com.github.minecraft_ta.totalDebugCompanion.catalog.ModResources;
import com.github.minecraft_ta.totalDebugCompanion.navigation.NavigationTarget;
import com.github.minecraft_ta.totalDebugCompanion.resource.FileTypeResolver;
import com.github.minecraft_ta.totalDebugCompanion.ui.ContextMenus;
import com.github.minecraft_ta.totalDebugCompanion.ui.UiMetrics;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.CenteredIcon;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.FlatIconTextField;
import com.github.minecraft_ta.totalDebugCompanion.ui.presentation.PrimarySecondaryLabel;
import com.github.minecraft_ta.totalDebugCompanion.ui.presentation.PrimarySecondaryText;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.DynamicMatteBorder;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.ThemeColors;

import javax.swing.text.JTextComponent;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
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
import javax.swing.ToolTipManager;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.text.NumberFormat;
import java.util.ArrayList;
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
 * A mod's resources by kind. Categories are listed with their counts; files show their path inside the category, and
 * textures show as a grid of pixel-exact previews. Opening a resource shows it in a resource tab.
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

    private final DefaultListModel<Category> categories = new DefaultListModel<>();
    private final JList<Category> categoryList = new JList<>(this.categories);
    private final JScrollPane categoryScroll = new JScrollPane(this.categoryList);
    private final FlatIconTextField filter = new FlatIconTextField(Icons.SEARCH_ICON);
    private final DefaultListModel<ModResources.Resource> shown = new DefaultListModel<>();
    private final JList<ModResources.Resource> list = new JList<>(this.shown);
    private final JLabel empty = new JLabel();
    private final TextureThumbnails thumbnails = new TextureThumbnails(UiMetrics.previewPixels(UiMetrics.THUMBNAIL_SIZE));
    private final Consumer<String> categoryChanged;
    private List<ModResources.Resource> resources = List.of();
    private Set<String> animated = Set.of();
    private String pendingCategory = ALL;
    private boolean updating;

    public ResourceBrowser(Consumer<NavigationTarget> navigator, Consumer<String> categoryChanged) {
        super(new BorderLayout());
        Objects.requireNonNull(navigator, "navigator");
        this.categoryChanged = Objects.requireNonNull(categoryChanged, "categoryChanged");
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
            this.categoryChanged.accept(selectedCategory());
        });
        this.categoryScroll.setBorder(DynamicMatteBorder.rule(0, 0, 0, 1));
        this.categoryScroll.setPreferredSize(new Dimension(190, 0));
        add(this.categoryScroll, BorderLayout.WEST);

        this.filter.putClientProperty("JTextField.placeholderText", "Filter resources");
        this.filter.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent event) { applyFilter(); }
            @Override public void removeUpdate(DocumentEvent event) { applyFilter(); }
            @Override public void changedUpdate(DocumentEvent event) { applyFilter(); }
        });
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

        JPanel top = new JPanel(new BorderLayout());
        top.setBorder(UiMetrics.barPadding());
        top.add(this.filter, BorderLayout.CENTER);
        JScrollPane listScroll = new JScrollPane(this.list);
        listScroll.setBorder(BorderFactory.createEmptyBorder());
        listScroll.getVerticalScrollBar().setUnitIncrement(16);
        JPanel content = new JPanel(new BorderLayout());
        content.add(top, BorderLayout.NORTH);
        content.add(listScroll, BorderLayout.CENTER);
        this.empty.setBorder(UiMetrics.messagePadding());
        this.empty.setVisible(false);
        content.add(this.empty, BorderLayout.SOUTH);
        add(content, BorderLayout.CENTER);
        TypeToFilter.install(this.list, this.filter);
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

    public void setResources(List<ModResources.Resource> resources) {
        this.resources = List.copyOf(resources);
        Set<String> animated = new HashSet<>();
        for (ModResources.Resource resource : resources) {
            if (resource.path().endsWith(".png.mcmeta")) animated.add(resource.path().substring(0, resource.path().length() - 7));
        }
        this.animated = animated;
        this.updating = true;
        try {
            this.categories.clear();
            Map<String, Integer> counts = new LinkedHashMap<>();
            for (ModResources.Category category : ModResources.categories(resources)) counts.put(category.key(), 0);
            for (ModResources.Resource resource : resources) counts.merge(resource.category().key(), 1, Integer::sum);
            this.categories.addElement(new Category(ALL, "All", resources.size()));
            counts.forEach((key, count) -> this.categories.addElement(new Category(key, label(key), count)));
            this.categoryScroll.setVisible(counts.size() > 1);
            selectKey(this.pendingCategory);
        } finally {
            this.updating = false;
        }
        applyFilter();
    }

    /** Shows a message instead of resources, for example why they could not be read. */
    public void setMessage(String message) {
        this.empty.setText(message);
        this.empty.setVisible(!message.isEmpty());
    }

    public void selectCategory(String key) {
        this.pendingCategory = key == null ? ALL : key;
        selectKey(this.pendingCategory);
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
        for (ModResources.Resource resource : this.resources) {
            if (!key.isEmpty() && !resource.category().key().equals(key)) continue;
            if (textures && !resource.path().endsWith(".png")) continue;
            if (!text.isEmpty() && !resource.path().toLowerCase(Locale.ROOT).contains(text)) continue;
            matching.add(resource);
        }
        matching.sort(Comparator.comparing(ModResources.Resource::relativePath)
                .thenComparing(ModResources.Resource::path));
        this.list.setLayoutOrientation(textures ? JList.HORIZONTAL_WRAP : JList.VERTICAL);
        this.list.setVisibleRowCount(textures ? -1 : 8);
        this.list.setFixedCellWidth(textures ? CELL_WIDTH : -1);
        this.list.setFixedCellHeight(textures ? CELL_HEIGHT : -1);
        this.shown.clear();
        this.shown.addAll(matching);
    }

    private Component render(JList<? extends ModResources.Resource> list, ModResources.Resource resource, int index,
                             boolean selected, boolean focused) {
        if (showsTextures()) return new TextureCell(resource, selected, list);
        PrimarySecondaryLabel label = new PrimarySecondaryLabel();
        String secondary = resource.folder();
        if (selectedCategory().isEmpty()) secondary = (label(resource.category().key()) + "  " + secondary).strip();
        label.configure(new PrimarySecondaryText(resource.fileName(), secondary),
                FileTypeResolver.resolve(resource.fileName()).icon(), list.getFont(), selected,
                selected ? list.getSelectionForeground() : ThemeColors.text(),
                selected ? list.getSelectionBackground() : list.getBackground());
        label.setOpaque(true);
        label.setBackground(selected ? list.getSelectionBackground() : list.getBackground());
        label.setBorder(UiMetrics.listRowPadding());
        label.setToolTipText(resource.path());
        return label;
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
            setToolTipText(texture.relativePath() + (moving ? ", animated" : ""));
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
