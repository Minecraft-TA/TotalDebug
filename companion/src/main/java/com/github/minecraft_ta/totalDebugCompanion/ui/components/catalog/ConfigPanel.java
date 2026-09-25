package com.github.minecraft_ta.totalDebugCompanion.ui.components.catalog;

import com.github.minecraft_ta.totalDebugCompanion.Icons;
import com.github.minecraft_ta.totalDebugCompanion.catalog.ConfigValues;
import com.github.minecraft_ta.totalDebugCompanion.navigation.NavigationTarget;
import com.github.minecraft_ta.totalDebugCompanion.resource.FileTypeResolver;
import com.github.minecraft_ta.totalDebugCompanion.ui.ContextMenus;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.FlatIconTextField;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.editors.ReadOnlyTextPanel;
import com.github.minecraft_ta.totalDebugCompanion.ui.presentation.PrimarySecondaryLabel;
import com.github.minecraft_ta.totalDebugCompanion.ui.presentation.PrimarySecondaryText;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.DynamicMatteBorder;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.ThemeColors;
import com.github.minecraft_ta.totaldebug.storage.PackCatalog;

import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.DefaultComboBoxModel;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JToggleButton;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.HierarchyEvent;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
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
    private final DefaultListModel<PackCatalog.ConfigFile> fileModel = new DefaultListModel<>();
    private final JList<PackCatalog.ConfigFile> fileList = new JList<>(this.fileModel);
    private final JScrollPane fileScroll = new JScrollPane(this.fileList);
    private final DefaultComboBoxModel<Source> sourceModel = new DefaultComboBoxModel<>();
    private final JComboBox<Source> source = new JComboBox<>(this.sourceModel);
    private final FlatIconTextField filter = new FlatIconTextField(Icons.SEARCH_ICON);
    private final JToggleButton changedOnly = new JToggleButton("Changed");
    private final JToggleButton settingsMode = new JToggleButton("Settings");
    private final JToggleButton textMode = new JToggleButton("File");
    private final JButton open = new JButton("Open", Icons.JUMP_TO_SOURCE);
    private final JLabel notice = new JLabel();
    private final SettingsModel model = new SettingsModel();
    private final JTable table = new JTable(this.model);
    private final JTextArea details = new JTextArea();
    private final JScrollPane detailsScroll = new JScrollPane(this.details);
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
        add(this.fileScroll, BorderLayout.WEST);

        JPanel content = new JPanel(new BorderLayout());
        content.add(toolbar(), BorderLayout.NORTH);
        configureTable();
        JPanel settings = new JPanel(new BorderLayout());
        JScrollPane tableScroll = new JScrollPane(this.table);
        tableScroll.setBorder(BorderFactory.createEmptyBorder());
        settings.add(tableScroll, BorderLayout.CENTER);
        this.details.setEditable(false);
        this.details.setLineWrap(true);
        this.details.setWrapStyleWord(true);
        this.details.setRows(4);
        this.details.setBorder(BorderFactory.createEmptyBorder(6, 10, 6, 10));
        this.detailsScroll.setBorder(DynamicMatteBorder.rule(1, 0, 0, 0));
        settings.add(this.detailsScroll, BorderLayout.SOUTH);
        this.cards.add(settings, SETTINGS_CARD);
        this.cards.add(this.text, TEXT_CARD);
        this.message.setVerticalAlignment(JLabel.TOP);
        this.message.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
        this.cards.add(this.message, MESSAGE_CARD);
        content.add(this.cards, BorderLayout.CENTER);
        add(content, BorderLayout.CENTER);

        addHierarchyListener(event -> {
            if ((event.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0 && isShowing()) load();
        });
    }

    private void configureFiles() {
        this.fileList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        this.fileList.setCellRenderer((list, file, index, selected, focused) -> {
            PrimarySecondaryLabel label = new PrimarySecondaryLabel();
            label.configure(new PrimarySecondaryText(file.fileName(), side(file.type())),
                    FileTypeResolver.resolve(file.fileName()).icon(), list.getFont(), selected,
                    selected ? list.getSelectionForeground() : ThemeColors.text(),
                    selected ? list.getSelectionBackground() : list.getBackground());
            label.setOpaque(true);
            label.setBackground(selected ? list.getSelectionBackground() : list.getBackground());
            label.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
            label.setToolTipText(file.path() == null ? null : file.path().toString());
            return label;
        });
        this.fileList.addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting() && !this.updating) showFile();
        });
        ContextMenus.installList(this.fileList, row -> fileMenu());
        this.fileScroll.setBorder(DynamicMatteBorder.rule(0, 0, 0, 1));
        this.fileScroll.setPreferredSize(new Dimension(240, 0));
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
        this.changedOnly.setToolTipText("Only settings that differ from their default");
        this.changedOnly.addActionListener(event -> applyFilter());
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

        JPanel left = new JPanel(new BorderLayout(6, 0));
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
        this.table.getSelectionModel().addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting()) showDetails();
        });
    }

    /** Shows the files of a mod, keeping the selected file when it still exists. */
    void setFiles(List<PackCatalog.ConfigFile> files) {
        PackCatalog.ConfigFile selected = this.fileList.getSelectedValue();
        this.updating = true;
        try {
            this.fileModel.clear();
            files.forEach(this.fileModel::addElement);
            int index = 0;
            for (int candidate = 0; selected != null && candidate < files.size(); candidate++) {
                if (files.get(candidate).fileName().equals(selected.fileName())) index = candidate;
            }
            if (!files.isEmpty()) this.fileList.setSelectedIndex(index);
        } finally {
            this.updating = false;
        }
        this.fileScroll.setVisible(files.size() > 1);
        showFile();
    }

    /** Selects a file by name, for example when the Overview links to it. */
    void select(String fileName) {
        for (int index = 0; index < this.fileModel.size(); index++) {
            if (this.fileModel.get(index).fileName().equals(fileName)) this.fileList.setSelectedIndex(index);
        }
    }

    private void showFile() {
        PackCatalog.ConfigFile file = this.fileList.getSelectedValue();
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
        PackCatalog.ConfigFile file = this.fileList.getSelectedValue();
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

    /** One table row: a section heading when {@code setting} is null. */
    record Row(int depth, String path, String name, String comment, PackCatalog.ConfigSetting setting, String value) {
        boolean changed() {
            return this.setting != null && !this.setting.defaultValue().isEmpty() && !this.value.isEmpty()
                    && !this.value.equals(this.setting.defaultValue());
        }

        String accepts() {
            if (this.setting == null) return "";
            return this.setting.allowed().isEmpty() ? this.setting.range() : String.join(", ", this.setting.allowed());
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
        this.model.filter(row -> row.matches(query) && (!changed || row.changed()));
        showDetails();
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

    private void showDetails() {
        int selected = this.table.getSelectedRow();
        String description = selected < 0 ? "" : details(this.model.shown.get(selected));
        this.details.setText(description);
        this.details.setCaretPosition(0);
        this.detailsScroll.setVisible(!description.isEmpty());
        revalidate();
    }

    /** The full comment and restart requirement of a row, which the table has no room for. */
    static String details(Row row) {
        StringBuilder text = new StringBuilder(row.path());
        if (!row.comment().isEmpty()) text.append("\n").append(row.comment());
        if (row.setting() != null) {
            switch (row.setting().restart()) {
                case WORLD -> text.append("\nTakes effect after rejoining the world.");
                case GAME -> text.append("\nTakes effect after restarting the game.");
                case NONE -> {
                }
            }
        }
        return text.toString();
    }

    JTable table() {
        return this.table;
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
            case SERVER -> "Server, per world";
            case STARTUP -> "Startup";
        };
    }

    static final class SettingsModel extends AbstractTableModel {
        private List<Row> all = List.of();
        private List<Row> shown = List.of();
        private boolean described = true;

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

        void filter(Predicate<Row> keep) {
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
                    .toList();
            fireTableDataChanged();
        }

        List<Row> shown() {
            return this.shown;
        }

        @Override
        public int getRowCount() {
            return this.shown.size();
        }

        @Override
        public int getColumnCount() {
            return this.described ? 4 : 2;
        }

        @Override
        public String getColumnName(int column) {
            return switch (column) {
                case 0 -> "Setting";
                case 1 -> "Value";
                case 2 -> "Default";
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
                case 2 -> entry.setting().defaultValue();
                default -> entry.accepts();
            };
        }
    }

    private final class SettingRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean selected, boolean focused,
                                                       int rowIndex, int column) {
            super.getTableCellRendererComponent(table, value, selected, false, rowIndex, column);
            Row row = ConfigPanel.this.model.shown().get(rowIndex);
            Font font = table.getFont();
            setIcon(null);
            setToolTipText(null);
            if (row.setting() == null) {
                setFont(font);
                setBorder(BorderFactory.createEmptyBorder(0, 8 + row.depth() * 16, 0, 4));
                if (!selected) setForeground(ThemeColors.text());
                setToolTipText(row.comment().isEmpty() ? null : row.comment());
                return this;
            }
            setFont(font);
            setBorder(BorderFactory.createEmptyBorder(0, column == 0 ? 8 + row.depth() * 16 : 4, 0, 4));
            if (!selected) {
                setForeground(column == 1 && row.changed() ? ThemeColors.accent()
                        : column >= 2 ? ThemeColors.secondaryText() : ThemeColors.text());
            }
            if (column == 0 && !row.comment().isEmpty()) setToolTipText(row.comment());
            if (column == 1 && row.changed()) setToolTipText("Default " + row.setting().defaultValue());
            return this;
        }
    }
}
