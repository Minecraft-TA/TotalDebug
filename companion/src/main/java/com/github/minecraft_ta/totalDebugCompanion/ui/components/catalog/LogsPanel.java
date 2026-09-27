package com.github.minecraft_ta.totalDebugCompanion.ui.components.catalog;

import com.formdev.flatlaf.util.UIScale;
import com.github.minecraft_ta.totalDebugCompanion.Icons;
import com.github.minecraft_ta.totalDebugCompanion.catalog.CatalogIndex;
import com.github.minecraft_ta.totalDebugCompanion.catalog.GameLogs;
import com.github.minecraft_ta.totalDebugCompanion.catalog.PackCatalogService;
import com.github.minecraft_ta.totalDebugCompanion.navigation.NavigationTarget;
import com.github.minecraft_ta.totalDebugCompanion.resource.ResourceLoader;
import com.github.minecraft_ta.totalDebugCompanion.ui.ContextMenus;
import com.github.minecraft_ta.totalDebugCompanion.ui.Tooltip;
import com.github.minecraft_ta.totalDebugCompanion.ui.UiMetrics;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.FlatIconTextField;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.TypeToFilter;
import com.github.minecraft_ta.totalDebugCompanion.ui.presentation.PrimarySecondaryLabel;
import com.github.minecraft_ta.totalDebugCompanion.ui.presentation.PrimarySecondaryText;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.DynamicMatteBorder;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.ThemeColors;
import com.github.minecraft_ta.totaldebug.storage.PackCatalog;

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
import javax.swing.SwingUtilities;
import javax.swing.ToolTipManager;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Dimension;
import java.awt.event.ActionEvent;
import java.awt.event.HierarchyEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.text.NumberFormat;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * The game's logs and crash reports. A log lists its warnings and errors; a crash report the mods that failed to load,
 * then each exception with its stack frames. A row opens the file at its line; a frame opens its class. Files are read
 * again when the page is shown and they changed, keeping the selection.
 */
public final class LogsPanel extends JPanel {
    private static final String ROWS_CARD = "rows";
    private static final String MESSAGE_CARD = "message";
    private static final DateTimeFormatter WRITTEN = DateTimeFormatter.ofPattern("d MMM HH:mm", Locale.ROOT);
    private static final DateTimeFormatter REPORTED = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT);

    /**
     * A row of the selected file: what it shows, what the filter matches, and what opening it opens, or null when the
     * file is too large for Companion to open.
     */
    private record Row(Icon icon, String primary, String secondary, String tooltip, NavigationTarget target, int indent) {
        String matched() {
            return (this.primary + "\n" + this.secondary).toLowerCase(Locale.ROOT);
        }
    }

    /**
     * A read file, kept while it is unchanged. A file that could not be read has neither part but the reason, and is
     * not kept, so it is read again next time.
     */
    private record Parsed(FileTime modified, long size, GameLogs.Log log, GameLogs.CrashReport report, String problem) {
        boolean current(GameLogs.LogFile file) {
            return this.modified.equals(file.modified()) && this.size == file.size();
        }
    }

    /**
     * A file with what its row says: a log by its name and counts, a crash report by what happened and when, since its
     * file name is long and says little.
     */
    private record Listed(GameLogs.LogFile file, String primary, String secondary, String tooltip) {
    }

    private final PackCatalogService catalog;
    private final Path workspace;
    private final Runnable removeCatalogListener;
    private final Consumer<NavigationTarget> navigator;
    private final DefaultListModel<Listed> files = new DefaultListModel<>();
    private final JList<Listed> fileList = new JList<>(this.files);
    private final FlatIconTextField filter = new FlatIconTextField(Icons.SEARCH_ICON);
    private final DefaultListModel<Row> shown = new DefaultListModel<>();
    private final JList<Row> rows = new JList<>(this.shown);
    private final JLabel message = new JLabel();
    private final JLabel notice = new JLabel();
    private final JPanel cards = new JPanel(new CardLayout());
    /** Read files by path, guarded by itself. */
    private final Map<Path, Parsed> parsed = new HashMap<>();
    private List<Row> all = List.of();
    /** What the selected file's rows were read from, to read them again only when the file changed. */
    private GameLogs.LogFile rowsOf;
    /** A file to select once the files are listed, or null to keep the selection. */
    private Path wanted;
    private boolean updatingFiles;
    private long generation;
    /** Whether the files are being listed, and whether they are to be listed again once that is done. */
    private boolean listing;
    private boolean listAgain;
    private long rowGeneration;
    private boolean disposed;

    public LogsPanel(PackCatalogService catalog, Path workspace, Consumer<NavigationTarget> navigator) {
        super(new BorderLayout());
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.workspace = workspace;
        this.navigator = Objects.requireNonNull(navigator, "navigator");

        this.fileList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        this.fileList.setCellRenderer((list, listed, index, selected, focused) -> {
            PrimarySecondaryLabel label = label(list, selected, listed.file().kind() == GameLogs.Kind.CRASH_REPORT ? Icons.ERROR : Icons.TEXT_FILE,
                    new PrimarySecondaryText(listed.primary(), listed.secondary()), 0);
            label.setToolTipText(listed.tooltip());
            return label;
        });
        ToolTipManager.sharedInstance().registerComponent(this.fileList);
        // Both lists follow the view's width: a long row ends at the edge, and its tooltip has all of it.
        this.fileList.setFixedCellWidth(1);
        this.fileList.addListSelectionListener(event -> {
            if (event.getValueIsAdjusting() || this.updatingFiles) return;
            showNotice("");
            showRows();
        });
        JScrollPane fileScroll = new JScrollPane(this.fileList);
        fileScroll.setBorder(DynamicMatteBorder.rule(0, 0, 0, 1));
        fileScroll.setPreferredSize(new Dimension(UIScale.scale(300), 0));
        add(fileScroll, BorderLayout.WEST);

        this.filter.putClientProperty("JTextField.placeholderText", "Filter messages, loggers and mods");
        this.filter.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent event) { applyFilter(); }
            @Override public void removeUpdate(DocumentEvent event) { applyFilter(); }
            @Override public void changedUpdate(DocumentEvent event) { applyFilter(); }
        });
        this.rows.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        this.rows.setFixedCellWidth(1);
        this.rows.setCellRenderer((list, row, index, selected, focused) -> {
            PrimarySecondaryLabel label = label(list, selected, row.icon(), new PrimarySecondaryText(row.primary(), row.secondary()), row.indent());
            label.setToolTipText(row.tooltip());
            return label;
        });
        ToolTipManager.sharedInstance().registerComponent(this.rows);
        this.rows.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                int index = LogsPanel.this.rows.locationToIndex(event.getPoint());
                if (event.getClickCount() == 2 && index >= 0 && SwingUtilities.isLeftMouseButton(event)
                        && LogsPanel.this.rows.getCellBounds(index, index).contains(event.getPoint())) {
                    open(LogsPanel.this.shown.get(index));
                }
            }
        });
        this.rows.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "openRow");
        this.rows.getActionMap().put("openRow", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent event) {
                Row selected = LogsPanel.this.rows.getSelectedValue();
                if (selected != null) open(selected);
            }
        });
        ContextMenus.installList(this.rows, this::menu);
        JPanel bar = new JPanel(new BorderLayout());
        bar.setBorder(UiMetrics.barPadding());
        bar.add(this.filter, BorderLayout.CENTER);
        ThemeColors.keepForeground(this.notice, ThemeColors::secondaryText);
        this.notice.setBorder(UiMetrics.noticePadding());
        this.notice.setVisible(false);
        JPanel top = new JPanel(new BorderLayout());
        top.add(bar, BorderLayout.NORTH);
        top.add(this.notice, BorderLayout.SOUTH);
        JScrollPane rowScroll = new JScrollPane(this.rows);
        rowScroll.setBorder(BorderFactory.createEmptyBorder());
        this.cards.add(rowScroll, ROWS_CARD);
        this.message.setVerticalAlignment(JLabel.TOP);
        this.message.setBorder(UiMetrics.messagePadding());
        this.cards.add(this.message, MESSAGE_CARD);
        JPanel content = new JPanel(new BorderLayout());
        content.add(top, BorderLayout.NORTH);
        content.add(this.cards, BorderLayout.CENTER);
        add(content, BorderLayout.CENTER);
        TypeToFilter.install(this.rows, this.filter);

        // The game writes its logs while it runs, so the page reads them again whenever it is shown.
        addHierarchyListener(event -> {
            if ((event.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0 && isShowing()) load();
        });
        // Rows name the mods behind frames and failures as the catalog knows them.
        this.removeCatalogListener = catalog.addListener(() -> SwingUtilities.invokeLater(() -> {
            if (this.disposed) return;
            this.rowsOf = null;
            showRows();
        }));
    }

    private static PrimarySecondaryLabel label(JList<?> list, boolean selected, Icon icon, PrimarySecondaryText text, int indent) {
        PrimarySecondaryLabel label = new PrimarySecondaryLabel();
        label.configure(text, icon, list.getFont(), selected, selected ? list.getSelectionForeground() : ThemeColors.text(),
                selected ? list.getSelectionBackground() : list.getBackground());
        label.setOpaque(true);
        label.setBackground(selected ? list.getSelectionBackground() : list.getBackground());
        label.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createEmptyBorder(0, UIScale.scale(16) * indent, 0, 0),
                UiMetrics.listRowPadding()));
        return label;
    }

    /** Selects {@code file} the next time the files are listed; null keeps the selection. */
    public void select(Path file) {
        this.wanted = file == null ? null : file.toAbsolutePath().normalize();
    }

    /**
     * Lists the logs and crash reports again, reading only files that changed, and keeps the selection. A request while
     * they are being listed lists them once more afterwards, rather than reading the same files twice at once.
     */
    public void load() {
        if (this.disposed) return;
        if (this.listing) {
            this.listAgain = true;
            return;
        }
        this.listing = true;
        long current = ++this.generation;
        CompletableFuture.supplyAsync(() -> {
            List<Listed> listed = new ArrayList<>();
            try {
                for (GameLogs.LogFile file : GameLogs.list(this.workspace)) listed.add(listed(file, read(file)));
            } catch (Exception exception) {
                throw new IllegalStateException(exception.getMessage(), exception);
            }
            return listed;
        }).whenComplete((listed, failure) -> SwingUtilities.invokeLater(() -> {
            this.listing = false;
            if (this.disposed || current != this.generation) return;
            if (failure != null) {
                showMessage("The logs could not be listed: " + (failure.getCause() == null ? failure : failure.getCause()).getMessage());
            } else {
                showFiles(listed);
            }
            if (this.listAgain) {
                this.listAgain = false;
                load();
            }
        }));
    }

    /** Shows the listed files, replacing the list only when it changed, and the selected file's rows. */
    private void showFiles(List<Listed> listed) {
        Listed selected = this.fileList.getSelectedValue();
        Path requested = this.wanted;
        Path keep = requested != null ? requested : selected == null ? null : selected.file().path();
        this.wanted = null;
        List<Listed> before = new ArrayList<>();
        for (int index = 0; index < this.files.size(); index++) before.add(this.files.get(index));
        this.updatingFiles = true;
        try {
            if (!before.equals(listed)) {
                this.files.clear();
                this.files.addAll(listed);
            }
            int index = keep == null ? -1 : index(listed, keep);
            if (!listed.isEmpty()) this.fileList.setSelectedIndex(Math.max(index, 0));
        } finally {
            this.updatingFiles = false;
        }
        if (requested != null) {
            showNotice(index(listed, requested) >= 0 ? "" : requested.getFileName() + " is not among the current logs and crash reports.");
        }
        if (listed.isEmpty()) {
            this.rowsOf = null;
            // A file still being read must not bring its rows back.
            this.rowGeneration++;
            this.all = List.of();
            showMessage("The game has written no log or crash report yet.");
            return;
        }
        showRows();
    }

    /** Where {@code file} is in {@code listed}, or -1. */
    private static int index(List<Listed> listed, Path file) {
        Path wanted = file.toAbsolutePath().normalize();
        for (int index = 0; index < listed.size(); index++) {
            if (listed.get(index).file().path().toAbsolutePath().normalize().equals(wanted)) return index;
        }
        return -1;
    }

    /** A file as read before, or read now when it changed. Blocking. */
    private Parsed read(GameLogs.LogFile file) {
        synchronized (this.parsed) {
            Parsed known = this.parsed.get(file.path());
            if (known != null && known.current(file)) return known;
        }
        Parsed read;
        try {
            read = file.kind() == GameLogs.Kind.LOG
                    ? new Parsed(file.modified(), file.size(), GameLogs.readLog(file.path()), null, null)
                    : new Parsed(file.modified(), file.size(), null, GameLogs.readCrashReport(file.path()), null);
        } catch (Exception unreadable) {
            // A file the game holds or is replacing may be readable a moment later.
            return new Parsed(file.modified(), file.size(), null, null, String.valueOf(unreadable.getMessage()));
        }
        synchronized (this.parsed) {
            this.parsed.put(file.path(), read);
        }
        return read;
    }

    /** What a file's row says: a log's name and counts, a crash report's description and when it happened. */
    private static Listed listed(GameLogs.LogFile file, Parsed parsed) {
        String written = WRITTEN.format(file.modified().toInstant().atZone(ZoneId.systemDefault()));
        Tooltip tooltip = Tooltip.of(file.name()).detail(Tooltip.shortPath(file.path()));
        if (tooLarge(file)) tooltip.text("Too large for Companion to open; each row has its text in its tooltip");
        if (parsed.report() != null) {
            String description = parsed.report().description();
            return new Listed(file, description.isEmpty() ? file.name() : description, happened(parsed.report().time(), written),
                    tooltip.html());
        }
        if (parsed.log() == null) return new Listed(file, file.name(), "Could not be read", tooltip.text(parsed.problem()).html());
        GameLogs.Log log = parsed.log();
        List<String> counts = new ArrayList<>();
        if (log.errors() > 0) counts.add(count(log.errors(), "error"));
        if (log.warnings() > 0) counts.add(count(log.warnings(), "warning"));
        if (log.truncated()) {
            tooltip.text("Lists the first " + NumberFormat.getIntegerInstance(Locale.ROOT).format(GameLogs.MAX_ENTRIES)
                    + " warnings and errors");
        }
        return new Listed(file, file.name(), counts.isEmpty() ? written : String.join(", ", counts), tooltip.html());
    }

    private static boolean tooLarge(GameLogs.LogFile file) {
        return file.size() > ResourceLoader.MAXIMUM_TEXT_BYTES;
    }

    private static String count(long count, String noun) {
        return NumberFormat.getIntegerInstance(Locale.ROOT).format(count) + " " + noun + (count == 1 ? "" : "s");
    }

    /** When a crash happened, from its report's {@code 2026-09-27 10:00:00}; {@code otherwise} when it cannot be read. */
    private static String happened(String time, String otherwise) {
        try {
            return WRITTEN.format(LocalDateTime.parse(time, REPORTED));
        } catch (DateTimeParseException unreadable) {
            return otherwise;
        }
    }

    /** Shows the selected file's rows, read again only when the file changed since they were shown. */
    private void showRows() {
        Listed listed = this.fileList.getSelectedValue();
        if (listed == null || listed.file().equals(this.rowsOf)) return;
        this.rowsOf = listed.file();
        long current = ++this.rowGeneration;
        CatalogIndex index = this.catalog.index().orElse(null);
        CompletableFuture.supplyAsync(() -> rows(listed.file(), read(listed.file()), index))
                .whenComplete((read, failure) -> SwingUtilities.invokeLater(() -> {
                    if (this.disposed || current != this.rowGeneration) return;
                    if (failure != null) {
                        showMessage(listed.file().name() + " could not be read: "
                                + (failure.getCause() == null ? failure : failure.getCause()).getMessage());
                        return;
                    }
                    Row selected = this.rows.getSelectedValue();
                    this.all = read;
                    applyFilter();
                    // Reading a file again keeps the row that was selected, where it still is.
                    for (int row = 0; selected != null && row < this.shown.size(); row++) {
                        Row candidate = this.shown.get(row);
                        if (candidate.primary().equals(selected.primary()) && Objects.equals(candidate.target(), selected.target())) {
                            this.rows.setSelectedIndex(row);
                            this.rows.ensureIndexIsVisible(row);
                            break;
                        }
                    }
                }));
    }

    private static List<Row> rows(GameLogs.LogFile file, Parsed parsed, CatalogIndex index) {
        if (parsed.problem() != null) throw new IllegalStateException(parsed.problem());
        boolean opens = !tooLarge(file);
        List<Row> rows = new ArrayList<>();
        if (parsed.log() != null) {
            for (GameLogs.LogEntry entry : parsed.log().entries()) {
                Icon icon = entry.level() == GameLogs.Level.WARN ? Icons.WARNING : Icons.ERROR;
                String logger = entry.logger().substring(entry.logger().lastIndexOf('.') + 1);
                String time = entry.time().substring(entry.time().indexOf(' ') + 1);
                Tooltip tooltip = Tooltip.of(entry.logger()).detail(entry.thread() + ", " + entry.time()).code(entry.text());
                rows.add(new Row(icon, entry.message(), logger + "  " + time, tooltip.html(),
                        opens ? new NavigationTarget.LocalFile(file.path(), entry.offset()) : null, 0));
            }
        }
        if (parsed.report() != null) {
            for (GameLogs.ModIssue issue : parsed.report().modIssues()) {
                rows.add(new Row(Icons.ERROR, issue.message(), modName(index, issue.modId()), Tooltip.of(issue.message()).html(),
                        opens ? new NavigationTarget.LocalFile(file.path(), issue.offset()) : null, 0));
            }
            for (GameLogs.Failure failure : parsed.report().failures()) {
                rows.add(new Row(Icons.ERROR, failure.message(), "", Tooltip.of(failure.message()).code(failure.text()).html(),
                        opens ? new NavigationTarget.LocalFile(file.path(), failure.offset()) : null, 0));
                for (GameLogs.Frame frame : failure.frames()) {
                    String mod = frame.modId().isEmpty() ? "" : modName(index, frame.modId()) + "  ";
                    rows.add(new Row(Icons.JAVA_CLASS, frame.shortName(), mod + frame.source(),
                            Tooltip.of(frame.className() + "." + frame.method()).detail(frame.source()).html(),
                            new NavigationTarget.RuntimeClass(frame.className()), 1));
                }
            }
        }
        return rows;
    }

    /** A mod's name as the catalog knows it, or its id. */
    private static String modName(CatalogIndex index, String modId) {
        return index == null ? modId : index.mod(modId).map(PackCatalog.Mod::title).orElse(modId);
    }

    private void applyFilter() {
        String text = this.filter.getText().strip().toLowerCase(Locale.ROOT);
        List<Row> matching = new ArrayList<>();
        for (Row row : this.all) {
            if (text.isEmpty() || row.matched().contains(text)) matching.add(row);
        }
        this.shown.clear();
        this.shown.addAll(matching);
        if (!matching.isEmpty()) {
            ((CardLayout) this.cards.getLayout()).show(this.cards, ROWS_CARD);
            return;
        }
        Listed listed = this.fileList.getSelectedValue();
        boolean report = listed != null && listed.file().kind() == GameLogs.Kind.CRASH_REPORT;
        // The empty states take the place of the rows.
        showMessage(!this.all.isEmpty() ? "Nothing matches the filter."
                : report ? "The crash report names no exception." : "No warnings or errors.");
    }

    private void open(Row row) {
        if (row.target() != null) this.navigator.accept(row.target());
    }

    private JPopupMenu menu(int index) {
        List<Row> selected = this.rows.getSelectedValuesList();
        if (selected.isEmpty()) selected = List.of(this.shown.get(index));
        Row row = selected.getFirst();
        JPopupMenu menu = new JPopupMenu();
        if (row.target() != null) {
            menu.add(ContextMenus.action(row.target() instanceof NavigationTarget.RuntimeClass ? "Open Source" : "Show in File",
                    null, "ENTER", () -> open(row)));
            menu.addSeparator();
        }
        List<String> messages = selected.stream().map(Row::primary).toList();
        menu.add(ContextMenus.defaultCopy(ContextMenus.copyAction(messages.size() == 1 ? "Copy Message"
                : "Copy " + messages.size() + " Messages", String.join("\n", messages))));
        return menu;
    }

    /** A line under the filter for what went wrong after it was asked for, until another file is chosen; empty hides it. */
    private void showNotice(String text) {
        this.notice.setText(text);
        this.notice.setVisible(!text.isEmpty());
    }

    private void showMessage(String text) {
        this.message.setText(text);
        ((CardLayout) this.cards.getLayout()).show(this.cards, MESSAGE_CARD);
    }

    /** How many rows the selected file shows. */
    public int rowCount() {
        return this.shown.size();
    }

    public void dispose() {
        this.disposed = true;
        this.removeCatalogListener.run();
    }
}
