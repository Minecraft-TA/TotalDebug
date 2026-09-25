package com.github.minecraft_ta.totalDebugCompanion.ui.components.catalog;

import com.github.minecraft_ta.totalDebugCompanion.Icons;
import com.github.minecraft_ta.totalDebugCompanion.catalog.ConfigChanges;
import com.github.minecraft_ta.totalDebugCompanion.catalog.ConfigSources;
import com.github.minecraft_ta.totalDebugCompanion.catalog.ConfigValues;
import com.github.minecraft_ta.totalDebugCompanion.navigation.NavigationTarget;
import com.github.minecraft_ta.totalDebugCompanion.resource.FileTypeResolver;
import com.github.minecraft_ta.totalDebugCompanion.ui.ContextMenus;
import com.github.minecraft_ta.totalDebugCompanion.ui.Tooltip;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.FlatIconTextField;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.ThinSplitPane;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.TypeToFilter;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.editors.ReadOnlyTextPanel;
import com.github.minecraft_ta.totalDebugCompanion.ui.presentation.PrimarySecondaryLabel;
import com.github.minecraft_ta.totalDebugCompanion.ui.presentation.PrimarySecondaryText;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.ThemeColors;
import com.github.minecraft_ta.totaldebug.storage.PackCatalog;

import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.DefaultComboBoxModel;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JToggleButton;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.JTextComponent;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.event.HierarchyEvent;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;

/**
 * A mod's configuration files. A file shows its settings with the current value beside the default and what the
 * setting accepts, or its text. Values are read from the file whenever the tab is shown, so edits appear without a new
 * capture. Server configurations live in each world; the newest world's file is shown first. An edited value is
 * written to the file at once, where NeoForge reloads it in a running game; Ctrl+Z undoes it.
 */
final class ConfigPanel extends JPanel {
    private static final String SETTINGS_CARD = "settings";
    private static final String TEXT_CARD = "text";
    private static final String MESSAGE_CARD = "message";

    private final Path workspace;
    private final ConfigWriter writer;
    private final Consumer<NavigationTarget> navigator;
    /** What the file could not show, such as a read error; it takes the place of {@link #status}. */
    private String problem = "";
    /** The outcome of the last edit, or why a typed value was refused. */
    private String status = "";
    /** The file list's width, kept while Companion runs so every mod page opens with the width last dragged to. */
    private static int fileListWidth = 220;

    /** File list rows are the files, under a heading per folder when the mod keeps them in one. */
    private final DefaultListModel<Object> fileModel = new DefaultListModel<>();
    private final JList<Object> fileList = new JList<>(this.fileModel);
    private final JScrollPane fileScroll = new JScrollPane(this.fileList);
    private final JPanel content = new JPanel(new BorderLayout());
    private final ThinSplitPane split;
    private int lastFileIndex = -1;
    private final DefaultComboBoxModel<ConfigSources.Source> sourceModel = new DefaultComboBoxModel<>();
    private final JComboBox<ConfigSources.Source> source = new JComboBox<>(this.sourceModel);
    private final FlatIconTextField filter = new FlatIconTextField(Icons.SEARCH_ICON);
    private final JCheckBox modifiedOnly = new JCheckBox("Modified");
    private final JToggleButton settingsMode = new JToggleButton("Settings");
    private final JToggleButton textMode = new JToggleButton("File");
    private final JButton open = new JButton("Open", Icons.JUMP_TO_SOURCE);
    private final JLabel notice = new JLabel();
    private final ConfigSettingsTable table = new ConfigSettingsTable();
    private final ReadOnlyTextPanel text = new ReadOnlyTextPanel(FileTypeResolver.SYNTAX_STYLE_TOML);
    private final JLabel message = new JLabel();
    private final JPanel cards = new JPanel(new CardLayout());
    private long generation;
    private long sourceGeneration;
    private boolean updating;

    ConfigPanel(Path workspace, ConfigChanges changes, Consumer<NavigationTarget> navigator) {
        super(new BorderLayout());
        this.workspace = workspace;
        this.writer = new ConfigWriter(changes, this::setStatus, this::load);
        this.navigator = Objects.requireNonNull(navigator, "navigator");
        configureFiles();
        this.content.add(toolbar(), BorderLayout.NORTH);
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

        this.table.setEditing(this::edit, this::setStatus, row -> {
            Path path = selectedPath();
            return path == null ? null : this.writer.pending(path, row.setting().path());
        }, row -> {
            Path path = selectedPath();
            return path == null ? null : this.writer.original(path, row.setting().path());
        });
        this.writer.bindUndo(this.table);
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
            this.status = "";
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
        this.modifiedOnly.addActionListener(event -> applyFilter());
        this.modifiedOnly.setToolTipText("Only settings that differ from their default");
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
        left.add(this.modifiedOnly, BorderLayout.EAST);
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
        long current = ++this.sourceGeneration;
        this.modifiedOnly.setVisible(file != null && !file.settings().isEmpty());
        if (file == null || file.type() != PackCatalog.ConfigType.SERVER) {
            showSources(file, file == null ? List.of() : ConfigSources.of(this.workspace, file));
            return;
        }
        // A server configuration's copies are found by listing the worlds, which reads the disk.
        CompletableFuture.supplyAsync(() -> ConfigSources.of(this.workspace, file)).whenComplete((found, failure) -> SwingUtilities.invokeLater(() -> {
            if (current == this.sourceGeneration) showSources(file, found == null ? List.of() : found);
        }));
    }

    private void showSources(PackCatalog.ConfigFile file, List<ConfigSources.Source> found) {
        this.updating = true;
        try {
            this.sourceModel.removeAllElements();
            found.forEach(this.sourceModel::addElement);
            this.source.setVisible(file != null && file.type() == PackCatalog.ConfigType.SERVER && !found.isEmpty());
        } finally {
            this.updating = false;
        }
        load();
    }

    private Path selectedPath() {
        ConfigSources.Source selected = (ConfigSources.Source) this.source.getSelectedItem();
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
                this.writer.refreshPending();
                return ConfigValues.read(path);
            } catch (IOException exception) {
                throw new CompletionException(exception);
            }
        }).whenComplete((values, failure) -> SwingUtilities.invokeLater(() -> {
            if (current != this.generation) return;
            if (failure == null) {
                show(file, values, values.text(), "");
            } else {
                Throwable cause = failure instanceof CompletionException && failure.getCause() != null ? failure.getCause() : failure;
                show(file, null, "", "Could not read " + path.getFileName() + ": " + cause.getMessage());
            }
        }));
    }

    private void show(PackCatalog.ConfigFile file, ConfigValues values, String fileText, String problem) {
        this.problem = problem;
        showNotice();
        this.text.setContent(fileText);
        this.textMode.setEnabled(!fileText.isEmpty());
        boolean empty = file.settings().isEmpty() && (values == null || values.settings().isEmpty());
        this.message.setText(empty && problem.isEmpty() ? "This file has no settings." : "");
        this.table.show(ConfigSettingsTable.rows(file, values), !file.settings().isEmpty(),
                values != null && !values.literals().isEmpty());
        applyFilter();
        showCard();
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

    /** Writes an edit made in the table. */
    private void edit(ConfigSettingsTable.Row row, String literal) {
        Path path = selectedPath();
        PackCatalog.ConfigFile file = selectedFile();
        if (path != null && file != null) this.writer.edit(new ConfigWriter.Target(path, file.type(), row.setting()), row.literal(), literal);
    }

    private void applyFilter() {
        this.table.filter(this.filter.getText(), this.modifiedOnly.isSelected() && this.modifiedOnly.isVisible());
        if (this.settingsMode.isSelected()) showCard();
    }

    private void showCard() {
        CardLayout layout = (CardLayout) this.cards.getLayout();
        if (this.textMode.isSelected() && this.textMode.isEnabled()) {
            layout.show(this.cards, TEXT_CARD);
        } else if (this.table.isEmpty() && !this.message.getText().isEmpty()) {
            layout.show(this.cards, MESSAGE_CARD);
        } else {
            layout.show(this.cards, SETTINGS_CARD);
        }
        boolean settings = !this.textMode.isSelected() || !this.textMode.isEnabled();
        this.filter.setVisible(settings);
        PackCatalog.ConfigFile file = selectedFile();
        this.modifiedOnly.setVisible(settings && file != null && !file.settings().isEmpty());
    }

    /** The field that filters the settings; null while the file's text is shown instead. */
    JTextComponent filterField() {
        return this.filter.isVisible() ? this.filter : null;
    }

    private static String side(PackCatalog.ConfigType type) {
        return switch (type) {
            case CLIENT -> "Client";
            case COMMON -> "Common";
            case SERVER -> "Server";
            case STARTUP -> "Startup";
        };
    }
}
