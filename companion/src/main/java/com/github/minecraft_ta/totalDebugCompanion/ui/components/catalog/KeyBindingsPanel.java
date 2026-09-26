package com.github.minecraft_ta.totalDebugCompanion.ui.components.catalog;

import com.github.minecraft_ta.totalDebugCompanion.Icons;
import com.github.minecraft_ta.totalDebugCompanion.catalog.CatalogIndex;
import com.github.minecraft_ta.totalDebugCompanion.catalog.KeyBindingControl;
import com.github.minecraft_ta.totalDebugCompanion.catalog.KeyBindings;
import com.github.minecraft_ta.totalDebugCompanion.catalog.PackCatalogService;
import com.github.minecraft_ta.totalDebugCompanion.navigation.NavigationTarget;
import com.github.minecraft_ta.totalDebugCompanion.ui.ContextMenus;
import com.github.minecraft_ta.totalDebugCompanion.ui.Tooltip;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.FlatIconTextField;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.TypeToFilter;
import com.github.minecraft_ta.totalDebugCompanion.ui.presentation.PrimarySecondaryLabel;
import com.github.minecraft_ta.totalDebugCompanion.ui.presentation.PrimarySecondaryText;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.ThemeColors;
import com.github.minecraft_ta.totalDebugCompanion.ui.views.debugger.DebuggerShortcuts;
import com.github.minecraft_ta.totaldebug.storage.PackCatalog;

import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JToggleButton;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.ToolTipManager;
import javax.swing.UIManager;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableCellRenderer;
import javax.swing.text.JTextComponent;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.KeyEventDispatcher;
import java.awt.KeyboardFocusManager;
import java.awt.Rectangle;
import java.awt.event.ActionEvent;
import java.awt.event.HierarchyEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Key bindings under their categories: the key each has now as keycaps, its default when it was changed, where it is
 * active and the mod it belongs to. A keycap outlined in the error color collides with another binding on the same
 * key press, one in the warning color overlaps through a modifier; the tooltip names them. The filter matches names,
 * categories, mods and keys, and a key typed as {@code ctrl+g} or pressed after the keyboard button finds what it
 * does. A binding gets a new key by double-clicking it and pressing the key, or from its menu; the key is put on it in
 * the running game or, while the game is closed, in {@code options.txt}. Several bindings, or a whole category, are
 * reset, unbound or reverted together from the menu of the selection. Keys are read from {@code options.txt} whenever
 * the page is shown.
 */
public final class KeyBindingsPanel extends JPanel {
    private static final String TABLE_CARD = "table";
    private static final String MESSAGE_CARD = "message";
    private static final String PLACEHOLDER = "Filter by action, mod or key, such as ctrl+g";
    private static final int CHEVRON_WIDTH = 16;
    private static final int BAR_WIDTH = 3;
    /** Bindings sit one level inside their category, where configuration settings sit inside their section. */
    private static final int BINDING_INDENT = 6 + 16 + CHEVRON_WIDTH + 2;
    private static final KeyBindings.Assignment NOT_BOUND = new KeyBindings.Assignment(KeyBindings.UNBOUND, "NONE");

    /** One row: a category heading with its number of bindings when {@code binding} is null. */
    private record Row(String category, KeyBindings.Binding binding, int count) {
    }

    private final PackCatalogService catalog;
    private final KeyBindingControl control;
    private final Consumer<NavigationTarget> navigator;
    /** The mod whose bindings are shown, or empty for every mod. */
    private final String modId;
    private final Runnable removeCatalogListener;
    private final FlatIconTextField filter = new FlatIconTextField(Icons.SEARCH_ICON);
    private final JToggleButton pressToSearch = new JToggleButton(Icons.KEYBOARD);
    private final JCheckBox changedOnly = new JCheckBox("Changed");
    private final JCheckBox collisionsOnly = new JCheckBox("Collisions");
    private final JCheckBox unboundOnly = new JCheckBox("Not bound");
    private final JLabel notice = new JLabel();
    private final BindingsModel model = new BindingsModel();
    private final JTable table = new JTable(this.model) {
        @Override
        public String getToolTipText(MouseEvent event) {
            int row = rowAtPoint(event.getPoint());
            return row < 0 ? null : tooltip(KeyBindingsPanel.this.model.shown.get(row));
        }
    };
    private final JLabel message = new JLabel();
    private final JPanel cards = new JPanel(new CardLayout());
    private final Set<String> collapsed = new HashSet<>();
    private final KeyEventDispatcher capture = this::capture;
    /** Receives the next key while one is awaited, for the filter or for {@link #editing}; null otherwise. */
    private Consumer<KeyBindings.Assignment> receiver;
    /** The binding waiting for its new key, or null. */
    private String editing;
    private String problem = "";
    private String status = "";
    private CatalogIndex index;
    private KeyBindings bindings;
    private String selectAfterLoad;
    private long generation;
    /** A modifier pressed while capturing, taken as the key itself when it is released alone. */
    private KeyEvent heldModifier;

    /** {@code modId} limits the page to one mod's bindings, or is empty for every mod. */
    public KeyBindingsPanel(PackCatalogService catalog, KeyBindingControl control, String modId,
                            Consumer<NavigationTarget> navigator) {
        super(new BorderLayout());
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.control = Objects.requireNonNull(control, "control");
        this.modId = Objects.requireNonNull(modId, "modId");
        this.navigator = Objects.requireNonNull(navigator, "navigator");

        this.filter.putClientProperty("JTextField.placeholderText", PLACEHOLDER);
        this.filter.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent event) { applyFilter(); }
            @Override public void removeUpdate(DocumentEvent event) { applyFilter(); }
            @Override public void changedUpdate(DocumentEvent event) { applyFilter(); }
        });
        this.pressToSearch.setToolTipText("Press a key, a combination or mouse button 3 to 5 to find what it does");
        this.pressToSearch.putClientProperty("JButton.buttonType", "toolBarButton");
        this.pressToSearch.addActionListener(event -> {
            if (this.pressToSearch.isSelected()) startSearchByKey();
            else stopCapture();
        });
        this.filter.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent event) {
                if (KeyBindingsPanel.this.receiver == null || KeyBindingsPanel.this.editing != null) return;
                KeyBindings.Assignment assignment = KeyPress.of(event);
                if (assignment != null) KeyBindingsPanel.this.receiver.accept(assignment);
            }
        });
        this.changedOnly.setToolTipText("Only bindings on another key than their default");
        this.collisionsOnly.setToolTipText("Only bindings that collide with another on the same key press");
        this.unboundOnly.setToolTipText("Only bindings without a key");
        for (JCheckBox toggle : List.of(this.changedOnly, this.collisionsOnly, this.unboundOnly)) {
            toggle.addActionListener(event -> applyFilter());
        }
        JPanel search = new JPanel(new BorderLayout(4, 0));
        search.add(this.filter, BorderLayout.CENTER);
        search.add(this.pressToSearch, BorderLayout.EAST);
        JPanel toggles = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        toggles.add(this.changedOnly);
        toggles.add(this.collisionsOnly);
        toggles.add(this.unboundOnly);
        JPanel bar = new JPanel(new BorderLayout(10, 0));
        bar.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
        bar.add(search, BorderLayout.CENTER);
        bar.add(toggles, BorderLayout.EAST);
        ThemeColors.keepForeground(this.notice, ThemeColors::secondaryText);
        this.notice.setBorder(BorderFactory.createEmptyBorder(0, 10, 6, 10));
        this.notice.setVisible(false);
        JPanel top = new JPanel(new BorderLayout());
        top.add(bar, BorderLayout.NORTH);
        top.add(this.notice, BorderLayout.SOUTH);
        add(top, BorderLayout.NORTH);

        this.model.fireTableStructureChanged();
        configureTable();
        JScrollPane scroll = new JScrollPane(this.table);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        this.cards.add(scroll, TABLE_CARD);
        this.message.setVerticalAlignment(JLabel.TOP);
        this.message.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
        this.cards.add(this.message, MESSAGE_CARD);
        add(this.cards, BorderLayout.CENTER);

        TypeToFilter.install(this.table, this.filter);
        this.removeCatalogListener = catalog.addListener(() -> SwingUtilities.invokeLater(this::load));
        addHierarchyListener(event -> {
            if ((event.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) == 0) return;
            if (isShowing()) load();
            else stopCapture();
        });
        load();
    }

    private void configureTable() {
        this.table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        this.table.setShowGrid(false);
        this.table.setFillsViewportHeight(true);
        this.table.getTableHeader().setReorderingAllowed(false);
        TableCellRenderer header = this.table.getTableHeader().getDefaultRenderer();
        this.table.getTableHeader().setDefaultRenderer((table, value, selected, focused, row, column) -> {
            Component component = header.getTableCellRendererComponent(table, value, selected, focused, row, column);
            if (component instanceof JLabel label) label.setHorizontalAlignment(SwingConstants.LEADING);
            return component;
        });
        this.table.setDefaultRenderer(Object.class, new BindingRenderer());
        ToolTipManager.sharedInstance().registerComponent(this.table);
        this.table.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent event) {
                if (KeyBindingsPanel.this.editing == null) return;
                KeyBindings.Assignment assignment = KeyPress.of(event);
                if (assignment != null) {
                    KeyBindingsPanel.this.receiver.accept(assignment);
                    event.consume();
                } else if (event.getClickCount() == 1) {
                    stopCapture();
                }
            }

            @Override
            public void mouseClicked(MouseEvent event) {
                int row = KeyBindingsPanel.this.table.rowAtPoint(event.getPoint());
                if (row < 0 || !SwingUtilities.isLeftMouseButton(event)) return;
                if (onChevron(row, event.getX())) {
                    toggle(row, null);
                } else if (event.getClickCount() == 2 && KeyBindingsPanel.this.model.shown.get(row).binding() != null) {
                    startEdit(KeyBindingsPanel.this.model.shown.get(row).binding());
                }
            }
        });
        ContextMenus.installTable(this.table, this::rowMenu);
        for (int key : new int[]{KeyEvent.VK_LEFT, KeyEvent.VK_RIGHT}) {
            Boolean expand = key == KeyEvent.VK_RIGHT;
            String name = "keyCategory" + key;
            this.table.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(key, 0), name);
            this.table.getActionMap().put(name, new AbstractAction() {
                @Override
                public void actionPerformed(ActionEvent event) {
                    int row = KeyBindingsPanel.this.table.getSelectedRow();
                    if (row >= 0) toggle(row, expand);
                }
            });
        }
        this.table.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), "unbindKeys");
        this.table.getActionMap().put("unbindKeys", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent event) {
                if (KeyBindingsPanel.this.bindings != null) apply(unbinding(selectedBindings()));
            }
        });
        int[] weights = this.modId.isEmpty() ? new int[]{50, 30, 20} : new int[]{60, 40};
        for (int column = 0; column < weights.length; column++) {
            this.table.getColumnModel().getColumn(column).setPreferredWidth(weights[column] * 10);
        }
    }

    /** Reads the keys again. */
    public void load() {
        CatalogIndex index = this.catalog.index().orElse(null);
        long current = ++this.generation;
        if (index == null) {
            show(null, null, "The pack catalog is not captured yet.");
            return;
        }
        PackCatalog captured = index.catalog();
        CompletableFuture.supplyAsync(() -> {
            try {
                return new KeyBindings(captured.keyBindings(), captured.keyContexts(), KeyBindings.readOptions(this.control.options()),
                        captured.keyNames());
            } catch (IOException exception) {
                throw new CompletionException(exception);
            }
        }).whenComplete((bindings, failure) -> SwingUtilities.invokeLater(() -> {
            if (current != this.generation) return;
            if (failure != null) {
                Throwable cause = failure instanceof CompletionException && failure.getCause() != null ? failure.getCause() : failure;
                show(index, null, "Could not read options.txt: " + cause.getMessage());
            } else {
                show(index, bindings, "");
            }
        }));
    }

    /** Shows the binding named {@code name}, such as {@code key.jump}, once the keys are read. */
    public void select(String name) {
        this.selectAfterLoad = name;
        selectPending();
    }

    private void selectPending() {
        if (this.selectAfterLoad == null || this.bindings == null) return;
        String name = this.selectAfterLoad;
        for (int row = 0; row < this.model.shown.size(); row++) {
            KeyBindings.Binding binding = this.model.shown.get(row).binding();
            if (binding != null && binding.spec().name().equals(name)) {
                this.selectAfterLoad = null;
                this.table.setRowSelectionInterval(row, row);
                this.table.scrollRectToVisible(this.table.getCellRect(row, 0, true));
                return;
            }
        }
        // Hidden by the filter or a collapsed category: clear both and look again.
        if (!this.collapsed.isEmpty() || filtering()) {
            this.collapsed.clear();
            for (JCheckBox toggle : List.of(this.changedOnly, this.collisionsOnly, this.unboundOnly)) toggle.setSelected(false);
            this.filter.setText("");
            applyFilter();
            selectPending();
        } else {
            this.selectAfterLoad = null;
        }
    }

    private void show(CatalogIndex index, KeyBindings bindings, String problem) {
        this.index = index;
        this.bindings = bindings;
        this.problem = bindings == null ? "" : problem;
        showNotice();
        List<Row> rows = new ArrayList<>();
        if (bindings != null) {
            Map<String, List<KeyBindings.Binding>> byCategory = new LinkedHashMap<>();
            for (KeyBindings.Binding binding : bindings.bindings()) {
                if (!this.modId.isEmpty() && !this.modId.equals(owner(binding.spec()))) continue;
                byCategory.computeIfAbsent(category(binding.spec()), ignored -> new ArrayList<>()).add(binding);
            }
            byCategory.forEach((category, members) -> {
                rows.add(new Row(category, null, members.size()));
                for (KeyBindings.Binding binding : members) rows.add(new Row(category, binding, 0));
            });
        }
        this.model.setRows(rows);
        this.message.setText(bindings == null ? problem : "");
        applyFilter();
        selectPending();
    }

    /** A category's name as the game shows it, or its key when the game has no text for it. */
    private static String category(PackCatalog.KeyBinding spec) {
        return spec.categoryName().isEmpty() ? spec.category() : spec.categoryName();
    }

    private boolean filtering() {
        return !this.filter.getText().isBlank() || this.changedOnly.isSelected() || this.collisionsOnly.isSelected()
                || this.unboundOnly.isSelected();
    }

    private void applyFilter() {
        String query = this.filter.getText().strip();
        String lower = query.toLowerCase(Locale.ROOT);
        boolean changed = this.changedOnly.isSelected();
        boolean collisions = this.collisionsOnly.isSelected();
        boolean unbound = this.unboundOnly.isSelected();
        this.model.filter(binding -> (query.isEmpty() || matches(binding, lower) || this.bindings.namesKey(binding.current(), query))
                && (!changed || binding.changed())
                && (!collisions || !collisions(binding).isEmpty())
                && (!unbound || binding.current().unbound()));
        boolean empty = this.model.getRowCount() == 0;
        if (this.bindings != null && empty) {
            this.message.setText(this.model.all.isEmpty()
                    ? this.modId.isEmpty() ? "The pack has no key bindings." : "This mod has no key bindings."
                    : "No key binding matches the filter.");
        }
        ((CardLayout) this.cards.getLayout()).show(this.cards, empty ? MESSAGE_CARD : TABLE_CARD);
    }

    private boolean matches(KeyBindings.Binding binding, String query) {
        return binding.name().toLowerCase(Locale.ROOT).contains(query)
                || binding.spec().name().toLowerCase(Locale.ROOT).contains(query)
                || category(binding.spec()).toLowerCase(Locale.ROOT).contains(query)
                || modName(owner(binding.spec())).toLowerCase(Locale.ROOT).contains(query);
    }

    private String owner(PackCatalog.KeyBinding spec) {
        return this.index == null ? spec.modId() : this.index.keyBindingOwner(spec);
    }

    private String modName(String modId) {
        if (modId.isEmpty()) return "";
        return this.index == null ? modId : this.index.mod(modId).map(PackCatalog.Mod::name).orElse(modId);
    }

    /** Where a context is active, in words. */
    private String contextName(String contextId) {
        return switch (contextId) {
            case "net.neoforged.neoforge.client.settings.KeyConflictContext.IN_GAME" -> "in game";
            case "net.neoforged.neoforge.client.settings.KeyConflictContext.GUI" -> "in screens";
            case "net.neoforged.neoforge.client.settings.KeyConflictContext.UNIVERSAL" -> "anywhere";
            default -> {
                if (this.index != null) {
                    for (PackCatalog.KeyContext context : this.index.catalog().keyContexts()) {
                        if (context.id().equals(contextId)) yield context.name();
                    }
                }
                yield contextId.substring(contextId.lastIndexOf('.') + 1);
            }
        };
    }

    /** The overlaps of a binding that act on the same key press, collisions first. */
    private List<KeyBindings.Clash> collisions(KeyBindings.Binding binding) {
        List<KeyBindings.Clash> clashes = new ArrayList<>();
        for (KeyBindings.Overlap kind : List.of(KeyBindings.Overlap.COLLISION, KeyBindings.Overlap.MODIFIER)) {
            for (KeyBindings.Clash clash : this.bindings.clashes(binding)) {
                if (clash.overlap() == kind) clashes.add(clash);
            }
        }
        return clashes;
    }

    private String tooltip(Row row) {
        if (row.binding() == null) {
            return Tooltip.of(row.category()).detail(row.count() + (row.count() == 1 ? " key binding" : " key bindings")).html();
        }
        KeyBindings.Binding binding = row.binding();
        Tooltip tooltip = Tooltip.of(binding.name()).detail("key_" + binding.spec().name())
                .fact("Key", this.bindings.display(binding.current()));
        if (binding.changed()) tooltip.fact("Default", this.bindings.display(binding.defaults()));
        String owner = owner(binding.spec());
        if (!owner.isEmpty()) tooltip.fact("Mod", modName(owner));
        if (!binding.spec().modId().isEmpty() && !binding.spec().modId().equals(owner)) {
            tooltip.fact("Registered through", modName(binding.spec().modId()));
        }
        tooltip.fact("Active", contextName(binding.effectiveContext()));
        if (binding.worldOnly()) tooltip.fact("Minecraft handles it", "only in the world");
        List<String> collisions = new ArrayList<>();
        List<String> modifiers = new ArrayList<>();
        List<String> separate = new ArrayList<>();
        for (KeyBindings.Clash clash : this.bindings.clashes(binding)) {
            String other = clash.other().name() + " (" + modName(owner(clash.other().spec())) + ")";
            switch (clash.overlap()) {
                case COLLISION -> collisions.add(other);
                case MODIFIER -> modifiers.add(other);
                case SEPARATE_CONTEXTS -> separate.add(other);
            }
        }
        if (!collisions.isEmpty()) tooltip.fact("Collides with " + collisions.size(), few(collisions), ThemeColors.error());
        if (!modifiers.isEmpty()) tooltip.fact("Modifier overlap " + modifiers.size(), few(modifiers), ThemeColors.warning());
        if (!separate.isEmpty()) tooltip.fact("Same key elsewhere " + separate.size(), few(separate));
        return tooltip.html();
    }

    /** The first three names and how many more; Show bindings on this key lists them all. */
    private static String few(List<String> names) {
        String shown = String.join(", ", names.subList(0, Math.min(3, names.size())));
        return names.size() > 3 ? shown + " and " + (names.size() - 3) + " more" : shown;
    }

    /** Whether {@code x} is on the chevron of a category row. */
    private boolean onChevron(int viewRow, int x) {
        Row row = this.model.shown.get(viewRow);
        if (row.binding() != null) return false;
        Rectangle cell = this.table.getCellRect(viewRow, 0, true);
        return x >= cell.x + 6 && x < cell.x + 6 + CHEVRON_WIDTH;
    }

    /** Collapses or expands a category row; {@code expand} null toggles it. Filtering shows every match regardless. */
    private void toggle(int viewRow, Boolean expand) {
        Row row = this.model.shown.get(viewRow);
        if (row.binding() != null || filtering()) return;
        boolean collapse = expand == null ? !this.collapsed.contains(row.category()) : !expand;
        if (collapse) this.collapsed.add(row.category());
        else this.collapsed.remove(row.category());
        this.model.refilter();
        int index = this.model.shown.indexOf(row);
        if (index >= 0) this.table.setRowSelectionInterval(index, index);
    }

    /** Takes the next key or mouse button as the filter. */
    private void startSearchByKey() {
        startCapture(assignment -> {
            stopCapture();
            this.filter.setText(this.bindings == null ? assignment.key() : this.bindings.display(assignment));
        });
        this.pressToSearch.setSelected(true);
        this.filter.putClientProperty("JTextField.placeholderText", "Press a key, a combination or mouse button 3 to 5");
        this.filter.setText("");
        this.filter.requestFocusInWindow();
        this.filter.repaint();
    }

    /** Takes the next key or mouse button as the new key of {@code binding}. */
    private void startEdit(KeyBindings.Binding binding) {
        startCapture(assignment -> {
            stopCapture();
            apply(binding, assignment);
        });
        this.editing = binding.spec().name();
        setStatus("");
        this.table.requestFocusInWindow();
        this.table.repaint();
    }

    private void startCapture(Consumer<KeyBindings.Assignment> receiver) {
        stopCapture();
        this.receiver = receiver;
        putClientProperty(DebuggerShortcuts.TAKES_ALL_KEYS, true);
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(this.capture);
    }

    /** Stops waiting for a key, for the filter or a binding. */
    private void stopCapture() {
        KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(this.capture);
        putClientProperty(DebuggerShortcuts.TAKES_ALL_KEYS, null);
        boolean wasEditing = this.editing != null;
        this.receiver = null;
        this.editing = null;
        this.heldModifier = null;
        this.pressToSearch.setSelected(false);
        this.filter.putClientProperty("JTextField.placeholderText", PLACEHOLDER);
        this.filter.repaint();
        if (wasEditing) this.table.repaint();
    }

    /** Takes a key press while a key is awaited: a modifier waits for a key, Escape stops without taking one. */
    private boolean capture(KeyEvent event) {
        if (this.receiver == null) return false;
        if (event.getID() == KeyEvent.KEY_PRESSED) {
            if (event.getKeyCode() == KeyEvent.VK_ESCAPE) {
                stopCapture();
            } else if (KeyPress.isModifier(event)) {
                this.heldModifier = event;
            } else {
                KeyBindings.Assignment assignment = KeyPress.of(event, names());
                if (assignment != null) this.receiver.accept(assignment);
            }
        } else if (event.getID() == KeyEvent.KEY_RELEASED && this.heldModifier != null
                && event.getKeyCode() == this.heldModifier.getKeyCode()) {
            KeyBindings.Assignment assignment = KeyPress.of(this.heldModifier, names());
            if (assignment != null) this.receiver.accept(assignment);
        }
        event.consume();
        return true;
    }

    private Map<String, String> names() {
        return this.index == null ? Map.of() : this.index.catalog().keyNames();
    }

    private void apply(KeyBindings.Binding binding, KeyBindings.Assignment assignment) {
        apply(Map.of(binding, assignment));
    }

    /**
     * Puts bindings on keys, in the running game or in options.txt, and reads the keys again once every change is
     * done; only changes that failed are reported.
     */
    private void apply(Map<KeyBindings.Binding, KeyBindings.Assignment> changes) {
        List<KeyBindingControl.Change> requests = new ArrayList<>();
        Map<String, String> names = new HashMap<>();
        changes.forEach((binding, assignment) -> {
            if (assignment.equals(binding.current())) return;
            requests.add(new KeyBindingControl.Change(binding.spec().name(), binding.current(), assignment));
            names.put(binding.spec().name(), binding.name());
        });
        if (requests.isEmpty()) return;
        this.control.setAll(requests).thenAccept(failed -> SwingUtilities.invokeLater(() -> {
            setStatus(notChanged(failed, names));
            load();
        }));
    }

    /**
     * Says which bindings kept their key and why, such as {@code Jump, Sneak were not changed: ...}, naming them by
     * {@code names} where it has them; empty when every change was made.
     */
    static String notChanged(Map<String, String> failed, Map<String, String> names) {
        if (failed.isEmpty()) return "";
        List<String> shown = new ArrayList<>();
        for (String name : failed.keySet()) shown.add(names.getOrDefault(name, name));
        return few(shown) + (shown.size() == 1 ? " was" : " were") + " not changed: " + failed.values().iterator().next();
    }

    /** The selected bindings; a selected category stands for its bindings that pass the filter. */
    private List<KeyBindings.Binding> selectedBindings() {
        Map<String, KeyBindings.Binding> selected = new LinkedHashMap<>();
        for (int viewRow : this.table.getSelectedRows()) {
            Row row = this.model.shown.get(viewRow);
            if (row.binding() != null) {
                selected.put(row.binding().spec().name(), row.binding());
                continue;
            }
            for (Row member : this.model.all) {
                if (member.binding() != null && member.category().equals(row.category()) && this.model.keep.test(member.binding())) {
                    selected.put(member.binding().spec().name(), member.binding());
                }
            }
        }
        return List.copyOf(selected.values());
    }

    private static Map<KeyBindings.Binding, KeyBindings.Assignment> unbinding(List<KeyBindings.Binding> bindings) {
        Map<KeyBindings.Binding, KeyBindings.Assignment> changes = new LinkedHashMap<>();
        for (KeyBindings.Binding binding : bindings) {
            if (!binding.current().unbound()) changes.put(binding, NOT_BOUND);
        }
        return changes;
    }

    private JPopupMenu rowMenu(int viewRow) {
        if (viewRow < 0 || this.bindings == null) return null;
        List<KeyBindings.Binding> selected = selectedBindings();
        if (selected.size() > 1) return bulkMenu(selected);
        if (selected.isEmpty()) return null;
        KeyBindings.Binding binding = selected.getFirst();
        JPopupMenu menu = new JPopupMenu();
        menu.add(ContextMenus.action("Change key", null, "Double-click", () -> startEdit(binding)));
        menu.add(ContextMenus.action("Choose key…", null, null, () -> chooseKey(binding, viewRow)));
        Action reset = ContextMenus.action("Reset to " + this.bindings.display(binding.defaults()), null, null,
                () -> apply(binding, binding.defaults()));
        reset.setEnabled(binding.changed());
        menu.add(reset);
        Action unbind = ContextMenus.action("Unbind", null, "DELETE", () -> apply(binding, NOT_BOUND));
        unbind.setEnabled(!binding.current().unbound());
        menu.add(unbind);
        KeyBindings.Assignment original = this.control.original(binding.spec().name());
        if (original != null) {
            menu.add(ContextMenus.action("Revert to " + this.bindings.display(original), null, null, () -> apply(binding, original)));
        }
        menu.addSeparator();
        Action onKey = ContextMenus.action("Show bindings on this key", null, null,
                () -> this.filter.setText(this.bindings.display(binding.current())));
        onKey.setEnabled(!binding.current().unbound());
        menu.add(onKey);
        menu.add(ContextMenus.action("Find usages of " + binding.spec().name(), Icons.SEARCH_ICON, null,
                () -> this.navigator.accept(new NavigationTarget.LiteralUsages(binding.spec().name()))));
        menu.add(ContextMenus.defaultCopy(ContextMenus.copyAction("Copy name", binding.spec().name())));
        return menu;
    }

    /** What can be done to several bindings at once; each action names how many bindings it changes. */
    private JPopupMenu bulkMenu(List<KeyBindings.Binding> selected) {
        Map<KeyBindings.Binding, KeyBindings.Assignment> defaults = new LinkedHashMap<>();
        Map<KeyBindings.Binding, KeyBindings.Assignment> reverts = new LinkedHashMap<>();
        List<String> names = new ArrayList<>();
        for (KeyBindings.Binding binding : selected) {
            if (binding.changed()) defaults.put(binding, binding.defaults());
            KeyBindings.Assignment original = this.control.original(binding.spec().name());
            if (original != null && !original.equals(binding.current())) reverts.put(binding, original);
            names.add(binding.spec().name());
        }
        JPopupMenu menu = new JPopupMenu();
        menu.add(bulkAction("Reset", " to default", null, defaults));
        menu.add(bulkAction("Unbind", "", "DELETE", unbinding(selected)));
        if (!reverts.isEmpty()) menu.add(bulkAction("Revert", "", null, reverts));
        menu.addSeparator();
        menu.add(ContextMenus.defaultCopy(ContextMenus.copyAction("Copy " + names.size() + " names", String.join("\n", names))));
        return menu;
    }

    /** An action applying {@code changes}, named with their number, such as Reset 4 to default. */
    private Action bulkAction(String verb, String rest, String shortcut, Map<KeyBindings.Binding, KeyBindings.Assignment> changes) {
        String count = changes.isEmpty() ? "" : " " + changes.size();
        Action action = ContextMenus.action(verb + count + rest, null, shortcut, () -> apply(changes));
        action.setEnabled(!changes.isEmpty());
        return action;
    }

    /** Offers every key the game knows in a list, for keys that are hard to press, such as the left mouse button. */
    private void chooseKey(KeyBindings.Binding binding, int viewRow) {
        List<String> keys = new ArrayList<>(names().keySet());
        for (String mouse : List.of("key.mouse.left", "key.mouse.right", "key.mouse.middle", "key.mouse.4", "key.mouse.5")) {
            if (!keys.contains(mouse)) keys.add(mouse);
        }
        keys.sort(Comparator.comparing(key -> this.bindings.keyName(key).toLowerCase(Locale.ROOT)));
        KeyChooser.show(this.table, this.table.getCellRect(viewRow, 1, true), keys, this.bindings::keyName,
                key -> apply(binding, new KeyBindings.Assignment(key, "NONE")));
    }

    private void setStatus(String status) {
        this.status = status;
        showNotice();
    }

    private void showNotice() {
        String text = this.problem.isEmpty() ? this.status : this.problem;
        this.notice.setText(text);
        this.notice.setVisible(!text.isEmpty());
    }

    /** The field that filters the bindings. */
    public JTextComponent filterField() {
        return this.filter;
    }

    public void dispose() {
        stopCapture();
        this.removeCatalogListener.run();
    }

    private final class BindingsModel extends AbstractTableModel {
        private List<Row> all = List.of();
        private List<Row> shown = List.of();
        private Predicate<KeyBindings.Binding> keep = binding -> true;

        void setRows(List<Row> rows) {
            this.all = List.copyOf(rows);
            refilter();
        }

        void filter(Predicate<KeyBindings.Binding> keep) {
            this.keep = keep;
            refilter();
        }

        /** Keeps the matching bindings under their headings; collapsed categories keep only their heading. */
        void refilter() {
            boolean filtering = filtering();
            Map<String, Integer> matching = new HashMap<>();
            for (Row row : this.all) {
                if (row.binding() != null && this.keep.test(row.binding())) matching.merge(row.category(), 1, Integer::sum);
            }
            List<Row> shown = new ArrayList<>();
            for (Row row : this.all) {
                if (!matching.containsKey(row.category())) continue;
                if (row.binding() == null) {
                    shown.add(filtering ? new Row(row.category(), null, matching.get(row.category())) : row);
                } else if (this.keep.test(row.binding()) && (filtering || !KeyBindingsPanel.this.collapsed.contains(row.category()))) {
                    shown.add(row);
                }
            }
            this.shown = List.copyOf(shown);
            fireTableDataChanged();
        }

        @Override
        public int getRowCount() {
            return this.shown.size();
        }

        @Override
        public int getColumnCount() {
            // The table asks while it is built, before the page knows its mod; it asks again once it does.
            String modId = KeyBindingsPanel.this.modId;
            return modId == null || modId.isEmpty() ? 3 : 2;
        }

        @Override
        public String getColumnName(int column) {
            return switch (column) {
                case 0 -> "Action";
                case 1 -> "Key";
                default -> "Mod";
            };
        }

        @Override
        public Object getValueAt(int rowIndex, int column) {
            Row row = this.shown.get(rowIndex);
            if (row.binding() == null) return column == 0 ? row.category() : "";
            return switch (column) {
                case 0 -> row.binding().name();
                case 1 -> KeyBindingsPanel.this.bindings.display(row.binding().current());
                default -> modName(owner(row.binding().spec()));
            };
        }
    }

    /**
     * Category headings with a chevron and their number of bindings, actions with where they are active in muted text
     * and a bar at the edge when their key changed, keys as keycaps, and mods in muted text.
     */
    private final class BindingRenderer extends DefaultTableCellRenderer {
        private final ActionCell action = new ActionCell();
        private final KeyCaps caps = new KeyCaps();

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean selected, boolean focused,
                                                       int rowIndex, int column) {
            Row row = KeyBindingsPanel.this.model.shown.get(rowIndex);
            Color background = selected ? table.getSelectionBackground() : table.getBackground();
            Color foreground = selected ? table.getSelectionForeground() : ThemeColors.text();
            if (row.binding() == null) {
                if (column == 0) {
                    boolean collapsed = !filtering() && KeyBindingsPanel.this.collapsed.contains(row.category());
                    this.action.configure(new PrimarySecondaryText(row.category(), Integer.toString(row.count())),
                            UIManager.getIcon(collapsed ? "Tree.collapsedIcon" : "Tree.expandedIcon"), table, selected,
                            foreground, background, 6, false);
                    return this.action;
                }
                // Setting the background here would stay as the renderer's background for unselected cells.
                super.getTableCellRendererComponent(table, "", selected, false, rowIndex, column);
                return this;
            }
            KeyBindings.Binding binding = row.binding();
            switch (column) {
                case 0 -> {
                    this.action.configure(new PrimarySecondaryText(binding.name(), contextName(binding.effectiveContext())),
                            null, table, selected, foreground, background, BINDING_INDENT, binding.changed());
                    return this.action;
                }
                case 1 -> {
                    if (binding.spec().name().equals(KeyBindingsPanel.this.editing)) {
                        this.caps.configure(List.of(), null, "Press a key", table.getFont(), foreground, background);
                        return this.caps;
                    }
                    List<KeyBindings.Clash> clashes = collisions(binding);
                    Color outline = clashes.isEmpty() ? null
                            : clashes.getFirst().overlap() == KeyBindings.Overlap.COLLISION ? ThemeColors.error() : ThemeColors.warning();
                    String text = binding.current().unbound() ? "Not bound"
                            : binding.changed() ? "default " + KeyBindingsPanel.this.bindings.display(binding.defaults()) : "";
                    this.caps.configure(KeyBindingsPanel.this.bindings.caps(binding.current()), outline, text,
                            table.getFont(), foreground, background);
                    return this.caps;
                }
                default -> {
                    super.getTableCellRendererComponent(table, value, selected, false, rowIndex, column);
                    setIcon(null);
                    setBorder(BorderFactory.createEmptyBorder(0, 6, 0, 6));
                    setForeground(selected ? foreground : ThemeColors.secondaryText());
                    return this;
                }
            }
        }
    }

    /** An action or category name with muted text beside it, indented, with the changed bar at the left edge. */
    private static final class ActionCell extends JPanel {
        private final PrimarySecondaryLabel label = new PrimarySecondaryLabel();
        private boolean changed;

        private ActionCell() {
            super(new BorderLayout());
            add(this.label, BorderLayout.CENTER);
        }

        void configure(PrimarySecondaryText text, Icon icon, JTable table, boolean selected, Color foreground,
                       Color background, int indent, boolean changed) {
            this.changed = changed;
            this.label.configure(text, icon, table.getFont(), selected, foreground, background);
            this.label.setOpaque(false);
            setBackground(background);
            setBorder(BorderFactory.createEmptyBorder(0, indent, 0, 6));
        }

        @Override
        protected void paintChildren(Graphics graphics) {
            super.paintChildren(graphics);
            if (this.changed) {
                graphics.setColor(ThemeColors.accent());
                graphics.fillRect(0, 1, BAR_WIDTH, getHeight() - 2);
            }
        }
    }
}
