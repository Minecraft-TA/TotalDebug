package com.github.minecraft_ta.totalDebugCompanion.ui.components.catalog;

import com.github.minecraft_ta.totalDebugCompanion.ui.Tooltip;
import com.github.minecraft_ta.totalDebugCompanion.Icons;
import com.github.minecraft_ta.totalDebugCompanion.catalog.ConfigValues;
import com.github.minecraft_ta.totalDebugCompanion.navigation.NavigationTarget;
import com.github.minecraft_ta.totalDebugCompanion.resource.FileTypeResolver;
import com.github.minecraft_ta.totalDebugCompanion.ui.ContextMenus;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.FlatIconTextField;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.ThinSplitPane;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.TypeToFilter;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.editors.ReadOnlyTextPanel;
import com.github.minecraft_ta.totalDebugCompanion.ui.presentation.PrimarySecondaryLabel;
import com.github.minecraft_ta.totalDebugCompanion.ui.presentation.PrimarySecondaryText;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.EditorPalette;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.ThemeColors;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.ThemeManager;
import com.github.minecraft_ta.totaldebug.storage.PackCatalog;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.DefaultComboBoxModel;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JToggleButton;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.ToolTipManager;
import javax.swing.UIManager;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.text.JTextComponent;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Graphics;
import java.awt.event.ActionEvent;
import java.awt.event.HierarchyEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A mod's configuration files. A file shows its settings with the current value beside the default and what the
 * setting accepts, or its text. Values are read from the file whenever the tab is shown, so edits appear without a new
 * capture. Server configurations live in each world; the newest world's file is shown first.
 */
final class ConfigPanel extends JPanel {
    private static final String SETTINGS_CARD = "settings";
    private static final String TEXT_CARD = "text";
    private static final String MESSAGE_CARD = "message";

    /** Where a file's values are read from; {@code label} names the world for server configurations. */
    record Source(String label, Path path) {
        @Override
        public String toString() {
            return this.label;
        }
    }

    private final Path workspace;
    private final Consumer<NavigationTarget> navigator;
    /** The file list's width, kept while Companion runs so every mod page opens with the width last dragged to. */
    private static int fileListWidth = 220;

    /** File list rows are the files, under a heading per folder when the mod keeps them in one. */
    private final DefaultListModel<Object> fileModel = new DefaultListModel<>();
    private final JList<Object> fileList = new JList<>(this.fileModel);
    private final JScrollPane fileScroll = new JScrollPane(this.fileList);
    private final JPanel content = new JPanel(new BorderLayout());
    private final ThinSplitPane split;
    private int lastFileIndex = -1;
    private final DefaultComboBoxModel<Source> sourceModel = new DefaultComboBoxModel<>();
    private final JComboBox<Source> source = new JComboBox<>(this.sourceModel);
    private final FlatIconTextField filter = new FlatIconTextField(Icons.SEARCH_ICON);
    private final JCheckBox changedOnly = new JCheckBox("Modified");
    private final JToggleButton settingsMode = new JToggleButton("Settings");
    private final JToggleButton textMode = new JToggleButton("File");
    private final JButton open = new JButton("Open", Icons.JUMP_TO_SOURCE);
    private final JLabel notice = new JLabel();
    private final SettingsModel model = new SettingsModel();
    private final SettingsTable table = new SettingsTable();
    private final ReadOnlyTextPanel text = new ReadOnlyTextPanel(FileTypeResolver.SYNTAX_STYLE_TOML);
    private final JLabel message = new JLabel();
    private final JPanel cards = new JPanel(new CardLayout());
    private long generation;
    private boolean updating;

    ConfigPanel(Path workspace, Consumer<NavigationTarget> navigator) {
        super(new BorderLayout());
        this.workspace = workspace;
        this.navigator = Objects.requireNonNull(navigator, "navigator");
        configureFiles();
        this.content.add(toolbar(), BorderLayout.NORTH);
        configureTable();
        JPanel settings = new JPanel(new BorderLayout());
        JScrollPane tableScroll = new JScrollPane(this.table);
        tableScroll.setBorder(BorderFactory.createEmptyBorder());
        settings.add(tableScroll, BorderLayout.CENTER);
        this.cards.add(settings, SETTINGS_CARD);
        this.cards.add(this.text, TEXT_CARD);
        this.message.setVerticalAlignment(JLabel.TOP);
        this.message.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
        this.cards.add(this.message, MESSAGE_CARD);
        this.content.add(this.cards, BorderLayout.CENTER);
        this.split = new ThinSplitPane(this.fileScroll, new JPanel());
        this.split.setDividerLocation(fileListWidth);
        this.split.addPropertyChangeListener(JSplitPane.DIVIDER_LOCATION_PROPERTY, event -> {
            if (this.split.isShowing()) fileListWidth = this.split.getDividerLocation();
        });
        add(this.content, BorderLayout.CENTER);

        TypeToFilter.install(this.table, this.filter);
        TypeToFilter.forwardTyping(this.fileList, () -> this.filter);
        addHierarchyListener(event -> {
            if ((event.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0 && isShowing()) load();
        });
    }

    private void configureFiles() {
        this.fileList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        this.fileList.setCellRenderer((list, value, index, selected, focused) -> {
            if (value instanceof String folder) {
                JLabel heading = new JLabel(folder);
                heading.setForeground(ThemeColors.secondaryText());
                heading.setBorder(BorderFactory.createEmptyBorder(index == 0 ? 4 : 10, 8, 2, 8));
                return heading;
            }
            PackCatalog.ConfigFile file = (PackCatalog.ConfigFile) value;
            PrimarySecondaryLabel label = new PrimarySecondaryLabel();
            label.configure(new PrimarySecondaryText(name(file), side(file.type())),
                    FileTypeResolver.resolve(file.fileName()).icon(), list.getFont(), selected,
                    selected ? list.getSelectionForeground() : ThemeColors.text(),
                    selected ? list.getSelectionBackground() : list.getBackground());
            label.setOpaque(true);
            label.setBackground(selected ? list.getSelectionBackground() : list.getBackground());
            label.setBorder(BorderFactory.createEmptyBorder(4, folder(file).isEmpty() ? 8 : 16, 4, 8));
            label.setToolTipText(Tooltip.of(file.fileName())
                    .detail(file.path() == null ? "Created in each world" : Tooltip.shortPath(file.path())).html());
            return label;
        });
        this.fileList.addListSelectionListener(event -> {
            if (event.getValueIsAdjusting() || this.updating) return;
            int index = this.fileList.getSelectedIndex();
            if (index >= 0 && this.fileModel.get(index) instanceof String) {
                // Folder headings are not files; moving onto one continues to the next file in that direction.
                int step = index < this.lastFileIndex ? -1 : 1;
                int next = index + step;
                while (next >= 0 && next < this.fileModel.size() && this.fileModel.get(next) instanceof String) next += step;
                if (next < 0 || next >= this.fileModel.size()) next = this.lastFileIndex;
                this.fileList.setSelectedIndex(next);
                return;
            }
            this.lastFileIndex = index;
            showFile();
        });
        ContextMenus.installList(this.fileList, row -> fileMenu());
        this.fileScroll.setBorder(BorderFactory.createEmptyBorder());
        this.fileScroll.setMinimumSize(new Dimension(120, 0));
    }

    private JPopupMenu fileMenu() {
        Path path = selectedPath();
        if (path == null) return null;
        JPopupMenu menu = new JPopupMenu();
        menu.add(ContextMenus.copyAction("Copy path", path.toString()));
        menu.add(ContextMenus.action("Show in Explorer", null, null, () -> {
            try {
                Desktop.getDesktop().browseFileDirectory(path.toFile());
            } catch (UnsupportedOperationException exception) {
                try {
                    Desktop.getDesktop().open(path.getParent().toFile());
                } catch (IOException ignored) {
                    // The path stays available through Copy path.
                }
            }
        }));
        return menu;
    }

    private JPanel toolbar() {
        this.filter.putClientProperty("JTextField.placeholderText", "Filter settings");
        this.filter.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent event) { applyFilter(); }
            @Override public void removeUpdate(DocumentEvent event) { applyFilter(); }
            @Override public void changedUpdate(DocumentEvent event) { applyFilter(); }
        });
        this.changedOnly.addActionListener(event -> applyFilter());
        this.changedOnly.setToolTipText("Only settings that differ from their default");
        this.settingsMode.setToolTipText("Settings with their values, defaults and accepted values");
        this.textMode.setToolTipText("The file as it is saved");
        ButtonGroup modes = new ButtonGroup();
        modes.add(this.settingsMode);
        modes.add(this.textMode);
        this.settingsMode.setSelected(true);
        for (JToggleButton mode : List.of(this.settingsMode, this.textMode)) {
            mode.putClientProperty("JButton.buttonType", "tab");
            mode.addActionListener(event -> showCard());
        }
        this.source.setToolTipText("World whose server configuration is shown");
        this.source.addActionListener(event -> {
            if (!this.updating) load();
        });
        this.open.setToolTipText("Open the file in an editor tab");
        this.open.addActionListener(event -> {
            Path path = selectedPath();
            if (path != null) this.navigator.accept(new NavigationTarget.LocalFile(path));
        });

        JPanel left = new JPanel(new BorderLayout(10, 0));
        left.add(this.filter, BorderLayout.CENTER);
        left.add(this.changedOnly, BorderLayout.EAST);
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        right.add(this.source);
        right.add(this.settingsMode);
        right.add(this.textMode);
        right.add(this.open);
        JPanel bar = new JPanel(new BorderLayout(12, 0));
        bar.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
        bar.add(left, BorderLayout.CENTER);
        bar.add(right, BorderLayout.EAST);
        ThemeColors.keepForeground(this.notice, ThemeColors::secondaryText);
        this.notice.setBorder(BorderFactory.createEmptyBorder(0, 10, 6, 10));
        this.notice.setVisible(false);
        JPanel top = new JPanel(new BorderLayout());
        top.add(bar, BorderLayout.NORTH);
        top.add(this.notice, BorderLayout.SOUTH);
        return top;
    }

    private void configureTable() {
        this.table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        this.table.setShowGrid(false);
        this.table.setFillsViewportHeight(true);
        this.table.getTableHeader().setReorderingAllowed(false);
        this.table.setDefaultRenderer(Object.class, new SettingRenderer());
        this.table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                int row = ConfigPanel.this.table.rowAtPoint(event.getPoint());
                if (row >= 0 && SwingUtilities.isLeftMouseButton(event) && onChevron(row, event.getPoint())) {
                    toggleSection(row, null);
                }
            }
        });
        for (int key : new int[]{KeyEvent.VK_LEFT, KeyEvent.VK_RIGHT, KeyEvent.VK_ENTER}) {
            Boolean expand = key == KeyEvent.VK_ENTER ? null : key == KeyEvent.VK_RIGHT;
            String name = "configSection" + key;
            this.table.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(key, 0), name);
            this.table.getActionMap().put(name, new AbstractAction() {
                @Override
                public void actionPerformed(ActionEvent event) {
                    int row = ConfigPanel.this.table.getSelectedRow();
                    if (row >= 0) toggleSection(row, expand);
                }
            });
        }
    }

    /** Clicking a section selects it for reading; only its chevron collapses it. */
    private boolean onChevron(int viewRow, Point point) {
        Row row = this.model.shown().get(viewRow);
        if (row.setting() != null || this.table.columnAtPoint(point) != 0) return false;
        Rectangle cell = this.table.getCellRect(viewRow, 0, true);
        int left = cell.x + indent(row.depth());
        return point.x >= left && point.x < left + CHEVRON_WIDTH && point.y < cell.y + this.table.getRowHeight();
    }

    /** Where a row's chevron, or a setting's name, starts inside the name column. */
    private static int indent(int depth) {
        return 6 + depth * 16;
    }

    /** Collapses or expands a section row; {@code expand} null toggles it. Setting rows are left alone. */
    private void toggleSection(int viewRow, Boolean expand) {
        Row row = this.model.shown().get(viewRow);
        if (row.setting() != null || this.model.filtering) return;
        boolean collapse = expand == null ? !this.model.collapsed.contains(row.path()) : !expand;
        if (collapse) this.model.collapsed.add(row.path());
        else this.model.collapsed.remove(row.path());
        applyFilter();
        int index = this.model.shown().indexOf(row);
        if (index >= 0) this.table.setRowSelectionInterval(index, index);
    }

    /** Shows the files of a mod, keeping the selected file when it still exists. */
    void setFiles(List<PackCatalog.ConfigFile> files) {
        PackCatalog.ConfigFile selected = selectedFile();
        this.updating = true;
        try {
            this.fileModel.clear();
            Map<String, List<PackCatalog.ConfigFile>> byFolder = new LinkedHashMap<>();
            for (PackCatalog.ConfigFile file : files) byFolder.computeIfAbsent(folder(file), ignored -> new ArrayList<>()).add(file);
            for (Map.Entry<String, List<PackCatalog.ConfigFile>> folder : byFolder.entrySet()) {
                if (!folder.getKey().isEmpty()) this.fileModel.addElement(folder.getKey());
                folder.getValue().forEach(this.fileModel::addElement);
            }
            int index = -1;
            for (int candidate = 0; candidate < this.fileModel.size(); candidate++) {
                if (!(this.fileModel.get(candidate) instanceof PackCatalog.ConfigFile file)) continue;
                if (index < 0 || selected != null && file.fileName().equals(selected.fileName())) index = candidate;
            }
            this.lastFileIndex = index;
            if (index >= 0) this.fileList.setSelectedIndex(index);
        } finally {
            this.updating = false;
        }
        removeAll();
        if (files.size() > 1) {
            this.split.setRightComponent(this.content);
            this.split.setDividerLocation(fileListWidth);
            add(this.split, BorderLayout.CENTER);
        } else {
            add(this.content, BorderLayout.CENTER);
        }
        revalidate();
        showFile();
    }

    /** The folder a mod keeps a file in, such as {@code Mekanism} for {@code Mekanism/general.toml}. */
    private static String folder(PackCatalog.ConfigFile file) {
        int separator = file.fileName().lastIndexOf('/');
        return separator < 0 ? "" : file.fileName().substring(0, separator);
    }

    private static String name(PackCatalog.ConfigFile file) {
        return file.fileName().substring(file.fileName().lastIndexOf('/') + 1);
    }

    private PackCatalog.ConfigFile selectedFile() {
        return this.fileList.getSelectedValue() instanceof PackCatalog.ConfigFile file ? file : null;
    }

    /** Selects a file by name, for example when the Overview links to it. */
    void select(String fileName) {
        for (int index = 0; index < this.fileModel.size(); index++) {
            if (this.fileModel.get(index) instanceof PackCatalog.ConfigFile file && file.fileName().equals(fileName)) {
                this.fileList.setSelectedIndex(index);
            }
        }
    }

    private void showFile() {
        PackCatalog.ConfigFile file = selectedFile();
        this.model.collapsed.clear();
        this.updating = true;
        try {
            this.sourceModel.removeAllElements();
            if (file != null) sources(file).forEach(this.sourceModel::addElement);
            this.source.setVisible(file != null && file.type() == PackCatalog.ConfigType.SERVER && this.sourceModel.getSize() > 0);
        } finally {
            this.updating = false;
        }
        boolean described = file != null && !file.settings().isEmpty();
        this.model.setColumns(described);
        this.changedOnly.setVisible(described);
        load();
    }

    /** Where a configuration's values are: its loaded file, or for a server configuration each world and the defaults. */
    List<Source> sources(PackCatalog.ConfigFile file) {
        if (file.path() != null) return List.of(new Source(file.fileName(), file.path()));
        if (file.type() != PackCatalog.ConfigType.SERVER || this.workspace == null) return List.of();
        List<Source> worlds = new ArrayList<>();
        Map<Source, FileTime> modified = new HashMap<>();
        try (DirectoryStream<Path> saves = Files.newDirectoryStream(this.workspace.resolve("saves"), Files::isDirectory)) {
            for (Path world : saves) {
                Path path = world.resolve("serverconfig").resolve(file.fileName());
                if (!Files.isRegularFile(path)) continue;
                Source source = new Source(world.getFileName().toString(), path);
                worlds.add(source);
                modified.put(source, Files.getLastModifiedTime(path));
            }
        } catch (IOException noSaves) {
            // A pack that never created a world has no server configuration yet.
        }
        worlds.sort(Comparator.comparing((Source source) -> modified.get(source)).reversed());
        Path defaults = this.workspace.resolve("defaultconfigs").resolve(file.fileName());
        if (Files.isRegularFile(defaults)) worlds.add(new Source("New worlds", defaults));
        return worlds;
    }

    private Path selectedPath() {
        Source selected = (Source) this.source.getSelectedItem();
        return selected == null ? null : selected.path();
    }

    /** Reads the selected file again. */
    void load() {
        PackCatalog.ConfigFile file = selectedFile();
        long current = ++this.generation;
        Path path = selectedPath();
        this.open.setVisible(path != null);
        if (file == null) return;
        if (path == null) {
            show(file, null, "", file.type() == PackCatalog.ConfigType.SERVER
                    ? "No world has created this file yet. A world creates it with these defaults."
                    : "This file does not exist yet. The game creates it with these defaults.");
            return;
        }
        CompletableFuture.supplyAsync(() -> {
            try {
                return new Loaded(ConfigValues.read(path), Files.readString(path, StandardCharsets.UTF_8));
            } catch (IOException exception) {
                throw new CompletionException(exception);
            }
        }).whenComplete((loaded, failure) -> SwingUtilities.invokeLater(() -> {
            if (current != this.generation) return;
            if (failure == null) {
                show(file, loaded.values(), loaded.text(), "");
            } else {
                Throwable cause = failure instanceof CompletionException && failure.getCause() != null ? failure.getCause() : failure;
                show(file, null, "", "Could not read " + path.getFileName() + ": " + cause.getMessage());
            }
        }));
    }

    private record Loaded(ConfigValues values, String text) {
    }

    private void show(PackCatalog.ConfigFile file, ConfigValues values, String fileText, String problem) {
        this.notice.setText(problem);
        this.notice.setVisible(!problem.isEmpty());
        this.text.setContent(fileText);
        this.textMode.setEnabled(!fileText.isEmpty());
        boolean empty = file.settings().isEmpty() && (values == null || values.settings().isEmpty());
        this.message.setText(empty && problem.isEmpty() ? "This file has no settings." : "");
        this.model.setRows(rows(file, values));
        applyFilter();
        showCard();
    }

    /**
     * Section headings and settings in specification order. Without a specification the file's own tables and
     * comments describe it; values missing from a file are shown empty.
     */
    static List<Row> rows(PackCatalog.ConfigFile file, ConfigValues values) {
        boolean described = !file.settings().isEmpty();
        List<PackCatalog.ConfigSetting> settings = described ? file.settings()
                : values == null ? List.of() : values.settings();
        Map<String, String> sectionComments = new HashMap<>();
        for (PackCatalog.ConfigSection section : described ? file.sections()
                : values == null ? List.<PackCatalog.ConfigSection>of() : values.sections()) {
            sectionComments.put(section.path(), section.comment());
        }
        List<Row> rows = new ArrayList<>();
        Set<String> opened = new LinkedHashSet<>();
        for (PackCatalog.ConfigSetting setting : settings) {
            String[] parts = setting.path().split("\\.");
            StringBuilder prefix = new StringBuilder();
            for (int depth = 0; depth < parts.length - 1; depth++) {
                if (depth > 0) prefix.append('.');
                prefix.append(parts[depth]);
                String section = prefix.toString();
                if (opened.add(section)) {
                    rows.add(new Row(depth, section, parts[depth], sectionComments.getOrDefault(section, ""), null, ""));
                }
            }
            String value = values == null ? setting.defaultValue() : values.values().getOrDefault(setting.path(), "");
            rows.add(new Row(parts.length - 1, setting.path(), setting.name(), setting.comment(), setting, value));
        }
        return rows;
    }

    /** How a value is colored, the way the code editor colors literals of the same kind. */
    enum ValueKind { NUMBER, BOOLEAN, STRING, CHOICE, LIST, TEXT }

    private static final Pattern NUMBER = Pattern.compile("-?\\d+(\\.\\d+)?([eE][-+]?\\d+)?");
    private static final Pattern RANGE = Pattern.compile("(\\S+) ~ (\\S+)");

    /**
     * A NeoForge range in words. Its {@code > 1} means at least 1, and a bound at the type's limit, such as
     * {@code 9223372036854775807}, is no bound at all.
     */
    static String readableRange(String range) {
        if (range.startsWith("> ")) return "at least " + number(range.substring(2));
        if (range.startsWith("< ")) return "at most " + number(range.substring(2));
        Matcher bounds = RANGE.matcher(range);
        if (!bounds.matches()) return range;
        boolean noMinimum = unbounded(bounds.group(1), false);
        boolean noMaximum = unbounded(bounds.group(2), true);
        if (noMinimum && noMaximum) return "";
        if (noMaximum) return "at least " + number(bounds.group(1));
        if (noMinimum) return "at most " + number(bounds.group(2));
        return number(bounds.group(1)) + " to " + number(bounds.group(2));
    }

    private static boolean unbounded(String bound, boolean upper) {
        try {
            double value = Double.parseDouble(bound);
            return upper ? value >= Integer.MAX_VALUE : value <= Integer.MIN_VALUE;
        } catch (NumberFormatException notANumber) {
            return false;
        }
    }

    /** Drops a floating-point value's empty fraction, so {@code 4000000.0} reads as {@code 4000000}. */
    private static String number(String value) {
        return value.endsWith(".0") ? value.substring(0, value.length() - 2) : value;
    }

    /** One table row: a section heading when {@code setting} is null. */
    record Row(int depth, String path, String name, String comment, PackCatalog.ConfigSetting setting, String value) {
        boolean changed() {
            return this.setting != null && !this.setting.defaultValue().isEmpty() && !this.value.isEmpty()
                    && !this.value.equals(this.setting.defaultValue());
        }

        String accepts() {
            if (this.setting == null) return "";
            return this.setting.allowed().isEmpty() ? readableRange(this.setting.range()) : String.join(", ", this.setting.allowed());
        }

        /** What kind of value the setting holds, from its accepted values, its default, or the value itself. */
        ValueKind kind() {
            if (this.setting == null) return ValueKind.TEXT;
            if (!this.setting.allowed().isEmpty()) return ValueKind.CHOICE;
            String sample = this.setting.defaultValue().isEmpty() ? this.value : this.setting.defaultValue();
            if (sample.startsWith("[")) return ValueKind.LIST;
            if (sample.equals("true") || sample.equals("false")) return ValueKind.BOOLEAN;
            if (NUMBER.matcher(sample).matches()) return ValueKind.NUMBER;
            return ValueKind.STRING;
        }

        boolean matches(String query) {
            return query.isEmpty() || this.path.toLowerCase(Locale.ROOT).contains(query)
                    || this.comment.toLowerCase(Locale.ROOT).contains(query)
                    || this.value.toLowerCase(Locale.ROOT).contains(query);
        }
    }

    private void applyFilter() {
        String query = this.filter.getText().strip().toLowerCase(Locale.ROOT);
        boolean changed = this.changedOnly.isSelected() && this.changedOnly.isVisible();
        this.model.filter(row -> row.matches(query) && (!changed || row.changed()), !query.isEmpty() || changed);
        if (this.settingsMode.isSelected()) showCard();
    }

    private void showCard() {
        CardLayout layout = (CardLayout) this.cards.getLayout();
        if (this.textMode.isSelected() && this.textMode.isEnabled()) {
            layout.show(this.cards, TEXT_CARD);
        } else if (this.model.all.isEmpty() && !this.message.getText().isEmpty()) {
            layout.show(this.cards, MESSAGE_CARD);
        } else {
            layout.show(this.cards, SETTINGS_CARD);
        }
        boolean settings = !this.textMode.isSelected() || !this.textMode.isEnabled();
        this.filter.setVisible(settings);
        this.changedOnly.setVisible(settings && this.model.described);
    }

    JTable table() {
        return this.table;
    }

    /** The field that filters the settings; null while the file's text is shown instead. */
    JTextComponent filterField() {
        return this.filter.isVisible() ? this.filter : null;
    }

    List<Source> shownSources() {
        List<Source> sources = new ArrayList<>();
        for (int index = 0; index < this.sourceModel.getSize(); index++) sources.add(this.sourceModel.getElementAt(index));
        return sources;
    }

    private static String side(PackCatalog.ConfigType type) {
        return switch (type) {
            case CLIENT -> "Client";
            case COMMON -> "Common";
            case SERVER -> "Server";
            case STARTUP -> "Startup";
        };
    }

    static final class SettingsModel extends AbstractTableModel {
        private List<Row> all = List.of();
        private List<Row> shown = List.of();
        private boolean described = true;
        /** Sections whose settings are hidden; ignored while a filter shows matches from every section. */
        private final Set<String> collapsed = new HashSet<>();
        private boolean filtering;

        void setColumns(boolean described) {
            if (this.described == described) return;
            this.described = described;
            fireTableStructureChanged();
        }

        void setRows(List<Row> rows) {
            this.all = List.copyOf(rows);
            this.shown = this.all;
            fireTableDataChanged();
        }

        void filter(Predicate<Row> keep, boolean filtering) {
            this.filtering = filtering;
            Set<String> sections = new LinkedHashSet<>();
            for (Row row : this.all) {
                if (row.setting() == null || !keep.test(row)) continue;
                String[] parts = row.path().split("\\.");
                StringBuilder prefix = new StringBuilder();
                for (int depth = 0; depth < parts.length - 1; depth++) {
                    if (depth > 0) prefix.append('.');
                    prefix.append(parts[depth]);
                    sections.add(prefix.toString());
                }
            }
            this.shown = this.all.stream()
                    .filter(row -> row.setting() == null ? sections.contains(row.path()) : keep.test(row))
                    .filter(row -> filtering || !insideCollapsed(row))
                    .toList();
            fireTableDataChanged();
        }

        List<Row> shown() {
            return this.shown;
        }

        private boolean insideCollapsed(Row row) {
            for (String section : this.collapsed) {
                if (row.path().startsWith(section + ".")) return true;
            }
            return false;
        }

        boolean isCollapsed(Row row) {
            return row.setting() == null && !this.filtering && this.collapsed.contains(row.path());
        }

        @Override
        public int getRowCount() {
            return this.shown.size();
        }

        @Override
        public int getColumnCount() {
            return this.described ? 3 : 2;
        }

        @Override
        public String getColumnName(int column) {
            return switch (column) {
                case 0 -> "Setting";
                case 1 -> "Value";
                default -> "Accepts";
            };
        }

        @Override
        public Object getValueAt(int row, int column) {
            Row entry = this.shown.get(row);
            if (entry.setting() == null) return column == 0 ? entry.name() : "";
            return switch (column) {
                case 0 -> entry.name();
                case 1 -> entry.value();
                default -> entry.accepts();
            };
        }
    }

    private static final int CHEVRON_WIDTH = 16;

    /** The settings table; hovering any cell of a row shows that row's description. */
    private final class SettingsTable extends JTable {
        private SettingsTable() {
            super(ConfigPanel.this.model);
            ToolTipManager.sharedInstance().registerComponent(this);
        }

        @Override
        public String getToolTipText(MouseEvent event) {
            int row = rowAtPoint(event.getPoint());
            return row < 0 ? null : tooltip(ConfigPanel.this.model.shown().get(row));
        }
    }

    /**
     * A row's description as a tooltip: its key, its comment wrapped to a readable width, and for a setting its
     * default, what it accepts and what must restart after a change.
     */
    static String tooltip(Row row) {
        Tooltip tooltip = Tooltip.of("").detail(row.path()).text(row.comment());
        PackCatalog.ConfigSetting setting = row.setting();
        if (setting != null) {
            boolean string = row.kind() == ValueKind.STRING;
            if (!setting.defaultValue().isEmpty()) {
                tooltip.fact("Default", string ? '"' + setting.defaultValue() + '"' : setting.defaultValue(),
                        SettingRenderer.color(row.kind()));
            }
            tooltip.fact("Accepts", row.accepts());
            switch (setting.restart()) {
                case WORLD -> tooltip.fact("Takes effect", "after rejoining the world");
                case GAME -> tooltip.fact("Takes effect", "after restarting the game");
                case NONE -> {
                }
            }
        }
        return tooltip.html();
    }



    /**
     * Setting names indented under their sections, values colored by kind like code literals, and a changed value
     * marked by a bar at the row's edge with its default beside it.
     */
    private final class SettingRenderer extends DefaultTableCellRenderer {
        private final SettingCell name = new SettingCell();
        private final JPanel valueCell = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        private final JLabel value = new JLabel();
        private final JLabel defaultHint = new JLabel();

        private SettingRenderer() {
            this.valueCell.add(this.value);
            this.valueCell.add(this.defaultHint);
            this.defaultHint.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 0));
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object cell, boolean selected, boolean focused,
                                                       int rowIndex, int column) {
            Row row = ConfigPanel.this.model.shown().get(rowIndex);
            Color background = selected ? table.getSelectionBackground() : table.getBackground();
            Color foreground = selected ? table.getSelectionForeground() : ThemeColors.text();
            if (column == 0) {
                this.name.configure(row, ConfigPanel.this.model.isCollapsed(row), table.getFont(), foreground, background);
                return this.name;
            }
            if (column == 1 && row.setting() != null) {
                boolean string = row.kind() == ValueKind.STRING;
                this.value.setText(string ? '"' + row.value() + '"' : row.value());
                this.value.setFont(table.getFont());
                // The themes' selection is a soft tint, so values keep their colors on it like code does.
                this.value.setForeground(row.kind() == ValueKind.LIST || row.kind() == ValueKind.TEXT ? foreground : color(row.kind()));
                this.defaultHint.setText(row.changed()
                        ? "default " + (string ? '"' + row.setting().defaultValue() + '"' : row.setting().defaultValue()) : "");
                this.defaultHint.setFont(table.getFont());
                this.defaultHint.setForeground(ThemeColors.secondaryText());
                this.valueCell.setBackground(background);
                this.valueCell.setBorder(BorderFactory.createEmptyBorder(0, 4, 0, 4));
                return this.valueCell;
            }
            super.getTableCellRendererComponent(table, cell, selected, false, rowIndex, column);
            setIcon(null);
            setFont(table.getFont());
            setBorder(BorderFactory.createEmptyBorder(0, 4, 0, 4));
            setForeground(selected ? foreground : ThemeColors.secondaryText());
            return this;
        }

        static Color color(ValueKind kind) {
            EditorPalette palette = ThemeManager.palette();
            return switch (kind) {
                case NUMBER -> palette.number();
                case BOOLEAN -> palette.keyword();
                case STRING -> palette.string();
                case CHOICE -> palette.field();
                case LIST, TEXT -> ThemeColors.text();
            };
        }
    }

    /** The name cell: a chevron for sections, indentation by depth, and the change bar at the left edge. */
    private static final class SettingCell extends JLabel {
        private static final int BAR_WIDTH = 3;
        private boolean changed;

        void configure(Row row, boolean collapsed, Font font, Color foreground, Color background) {
            this.changed = row.changed();
            setOpaque(true);
            setBackground(background);
            setForeground(foreground);
            setFont(font);
            setText(row.name());
            setIconTextGap(2);
            boolean section = row.setting() == null;
            setIcon(section ? UIManager.getIcon(collapsed ? "Tree.collapsedIcon" : "Tree.expandedIcon") : null);
            setBorder(BorderFactory.createEmptyBorder(0, indent(row.depth()) + (section ? 0 : CHEVRON_WIDTH + 2), 0, 4));
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            if (this.changed) {
                graphics.setColor(ThemeColors.accent());
                graphics.fillRect(0, 1, BAR_WIDTH, getHeight() - 2);
            }
        }
    }
}
