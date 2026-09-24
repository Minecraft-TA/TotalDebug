package com.github.minecraft_ta.totalDebugCompanion.ui.components.catalog;

import com.github.minecraft_ta.totalDebugCompanion.Icons;
import com.github.minecraft_ta.totalDebugCompanion.catalog.ModResources;
import com.github.minecraft_ta.totalDebugCompanion.navigation.NavigationTarget;
import com.github.minecraft_ta.totalDebugCompanion.resource.FileTypeResolver;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.FlatIconTextField;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.DefaultComboBoxModel;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.RowFilter;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableRowSorter;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Consumer;

/** A mod's resources, narrowed by category and path; opening one shows it in a resource tab. */
public final class ResourceBrowser extends JPanel {
    private static final String ALL = "";

    private final DefaultComboBoxModel<String> categories = new DefaultComboBoxModel<>();
    private final JComboBox<String> category = new JComboBox<>(this.categories);
    private final FlatIconTextField filter = new FlatIconTextField(Icons.SEARCH_ICON);
    private final ResourceModel model = new ResourceModel();
    private final JTable table = new JTable(this.model);
    private final TableRowSorter<ResourceModel> sorter = new TableRowSorter<>(this.model);
    private final JLabel empty = new JLabel();
    private String pendingCategory = ALL;
    private boolean updating;

    public ResourceBrowser(Consumer<NavigationTarget> navigator, Consumer<String> categoryChanged) {
        super(new BorderLayout());
        Objects.requireNonNull(navigator, "navigator");
        Objects.requireNonNull(categoryChanged, "categoryChanged");
        this.category.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected,
                                                          boolean focused) {
                String key = (String) value;
                String text = key == null || key.isEmpty() ? "All categories" : label(key);
                return super.getListCellRendererComponent(list, text, index, selected, focused);
            }
        });
        this.category.addActionListener(event -> {
            if (this.updating) return;
            applyFilter();
            categoryChanged.accept(selectedCategory());
        });
        this.filter.putClientProperty("JTextField.placeholderText", "Filter paths");
        this.filter.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent event) { applyFilter(); }
            @Override public void removeUpdate(DocumentEvent event) { applyFilter(); }
            @Override public void changedUpdate(DocumentEvent event) { applyFilter(); }
        });
        this.table.setRowSorter(this.sorter);
        this.table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        this.table.setShowGrid(false);
        this.table.setFillsViewportHeight(true);
        this.table.getColumnModel().getColumn(0).setCellRenderer(new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable table, Object value, boolean selected,
                                                           boolean focused, int row, int column) {
                super.getTableCellRendererComponent(table, value, selected, false, row, column);
                setIcon(FileTypeResolver.resolve(String.valueOf(value)).icon());
                return this;
            }
        });
        this.table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                int row = ResourceBrowser.this.table.rowAtPoint(event.getPoint());
                if (event.getClickCount() == 2 && row >= 0) navigator.accept(resourceAt(row).target());
            }
        });
        this.table.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "openResource");
        this.table.getActionMap().put("openResource", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent event) {
                int row = ResourceBrowser.this.table.getSelectedRow();
                if (row >= 0) navigator.accept(resourceAt(row).target());
            }
        });
        JPanel top = new JPanel(new BorderLayout(8, 0));
        top.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
        top.add(this.category, BorderLayout.WEST);
        top.add(this.filter, BorderLayout.CENTER);
        add(top, BorderLayout.NORTH);
        add(new JScrollPane(this.table), BorderLayout.CENTER);
        this.empty.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
        this.empty.setVisible(false);
        add(this.empty, BorderLayout.SOUTH);
    }

    /** The category's folder, with its root when both assets and data could have it. */
    static String label(String key) {
        ModResources.Category category = ModResources.Category.parse(key);
        return category.root().equals("data") ? category.folder() + " (data)" : category.folder();
    }

    public void setResources(List<ModResources.Resource> resources) {
        this.updating = true;
        try {
            this.model.resources = List.copyOf(resources);
            this.model.fireTableDataChanged();
            List<String> keys = new ArrayList<>();
            keys.add(ALL);
            ModResources.categories(resources).forEach(category -> keys.add(category.key()));
            this.category.removeAllItems();
            keys.forEach(this.category::addItem);
            this.category.setVisible(keys.size() > 2);
            this.category.setSelectedItem(keys.contains(this.pendingCategory) ? this.pendingCategory : ALL);
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
        if (this.categories.getIndexOf(this.pendingCategory) >= 0) {
            this.category.setSelectedItem(this.pendingCategory);
        }
    }

    public String selectedCategory() {
        Object selected = this.category.getSelectedItem();
        return selected == null ? ALL : (String) selected;
    }

    public int rowCount() {
        return this.table.getRowCount();
    }

    ModResources.Resource resourceAt(int viewRow) {
        return this.model.resources.get(this.table.convertRowIndexToModel(viewRow));
    }

    private void applyFilter() {
        String key = selectedCategory();
        this.pendingCategory = key;
        String text = this.filter.getText().strip().toLowerCase(Locale.ROOT);
        if (key.isEmpty() && text.isEmpty()) {
            this.sorter.setRowFilter(null);
            return;
        }
        this.sorter.setRowFilter(new RowFilter<>() {
            @Override
            public boolean include(Entry<? extends ResourceModel, ? extends Integer> row) {
                ModResources.Resource resource = ResourceBrowser.this.model.resources.get(row.getIdentifier());
                return (key.isEmpty() || resource.category().key().equals(key))
                        && (text.isEmpty() || resource.path().toLowerCase(Locale.ROOT).contains(text));
            }
        });
    }

    private static final class ResourceModel extends AbstractTableModel {
        private List<ModResources.Resource> resources = List.of();

        @Override
        public int getRowCount() {
            return this.resources.size();
        }

        @Override
        public int getColumnCount() {
            return 1;
        }

        @Override
        public String getColumnName(int column) {
            return "Path";
        }

        @Override
        public Object getValueAt(int row, int column) {
            return this.resources.get(row).path();
        }
    }
}
