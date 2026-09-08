package com.github.minecraft_ta.totalDebugCompanion.ui.views;

import com.github.minecraft_ta.totalDebugCompanion.CompanionApp;
import com.github.minecraft_ta.totalDebugCompanion.catalog.CatalogSnapshot;
import com.github.minecraft_ta.totalDebugCompanion.catalog.GameCatalogService;
import com.github.minecraft_ta.totalDebugCompanion.jdt.symbol.CodeSymbol;
import com.github.minecraft_ta.totalDebugCompanion.navigation.NavigationTarget;
import com.github.minecraft_ta.totaldebug.storage.AtomicFiles;
import com.github.minecraft_ta.totaldebug.storage.GameCatalog;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;

/** A modeless catalog window. Source/resource links open in the main editor without replacing the overview. */
public final class GameExplorerWindow extends JFrame {
    private final CatalogSnapshot snapshot;
    private final Consumer<NavigationTarget> navigate;
    private final DefaultListModel<GameCatalog.Entry> entries = new DefaultListModel<>();
    private final JList<GameCatalog.Entry> entryList = new JList<>(entries);
    private final JTextField query = new JTextField();
    private final JComboBox<String> mods = new JComboBox<>();
    private final JComboBox<String> kinds = new JComboBox<>(new String[]{"Items and blocks", "Items", "Blocks"});
    private final JLabel title = new JLabel("Select an item or block");
    private final JLabel identity = new JLabel(" ");
    private final JLabel preview = previewLabel();
    private final JTextArea notes = textArea();
    private final DefaultTableModel properties = new DefaultTableModel(new String[]{"Property", "Captured value"}, 0) {
        @Override public boolean isCellEditable(int row, int column) { return false; }
    };
    private final DefaultListModel<String> resourceModel = new DefaultListModel<>();
    private final JList<String> resources = new JList<>(resourceModel);
    private final JTextArea resourceDetails = textArea();
    private final JLabel texture = previewLabel();
    private SwingWorker<?, ?> inspection;
    private SwingWorker<?, ?> resourceJob;
    private int generation;
    private int resourceGeneration;

    public static void open() {
        if (!CompanionApp.hasProfile()) {
            JOptionPane.showMessageDialog(MainWindow.INSTANCE, "Select or connect a game instance first.");
            return;
        }
        var paths = CompanionApp.instancePaths();
        new SwingWorker<CatalogSnapshot, Void>() {
            @Override protected CatalogSnapshot doInBackground() throws Exception {
                return GameCatalogService.INSTANCE.load(paths);
            }
            @Override protected void done() {
                if (!CompanionApp.hasProfile() || !CompanionApp.instancePaths().equals(paths)) return;
                try { open(get(), null); }
                catch (Exception failure) { showError(MainWindow.INSTANCE, failure); }
            }
        }.execute();
    }

    public static void open(CatalogSnapshot snapshot, GameCatalog.Entry selected) {
        var window = new GameExplorerWindow(MainWindow.INSTANCE, snapshot, target -> {
            if (!CompanionApp.hasProfile() || !CompanionApp.instancePaths().gameCatalog().equals(snapshot.archive())) {
                JOptionPane.showMessageDialog(MainWindow.INSTANCE, "Switch back to this capture's game instance before opening source links.");
                return;
            }
            MainWindow.INSTANCE.navigation().navigate(target);
        });
        if (selected != null) window.entryList.setSelectedValue(selected, true);
        window.setVisible(true);
    }

    public GameExplorerWindow(Window owner, CatalogSnapshot snapshot, Consumer<NavigationTarget> navigate) {
        super("Items and blocks");
        this.snapshot = snapshot;
        this.navigate = navigate;
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setSize(1160, 800);
        setMinimumSize(new Dimension(850, 600));
        setLocationRelativeTo(owner);
        var root = new JPanel(new BorderLayout(10, 10));
        root.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        setContentPane(root);

        var modNames = new TreeMap<String, String>();
        snapshot.catalog().entries().forEach(entry -> modNames.put(entry.namespace(), entry.modName()));
        mods.addItem("All mods");
        modNames.forEach((id, name) -> mods.addItem(id));
        mods.setRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected, boolean focus) {
                return super.getListCellRendererComponent(list, modNames.containsKey(value)
                        ? modNames.get(value) + "  ·  " + value : value, index, selected, focus);
            }
        });
        query.putClientProperty("JTextField.placeholderText", "Name, registry ID or mod");
        var filters = new JPanel(new GridLayout(3, 1, 0, 6));
        filters.add(query);
        filters.add(mods);
        filters.add(kinds);
        var browser = new JPanel(new BorderLayout(0, 8));
        browser.add(filters, BorderLayout.NORTH);
        entryList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        entryList.setCellRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected, boolean focus) {
                GameCatalog.Entry entry = (GameCatalog.Entry) value;
                var label = (JLabel) super.getListCellRendererComponent(list,
                        (entry.kind() == GameCatalog.Kind.ITEM ? "Item  " : "Block  ") + entry.name(), index, selected, focus);
                label.setToolTipText(entry.id());
                label.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));
                return label;
            }
        });
        browser.add(new JScrollPane(entryList), BorderLayout.CENTER);
        browser.setMinimumSize(new Dimension(230, 0));

        var inspector = new JPanel(new BorderLayout(0, 8));
        title.setFont(title.getFont().deriveFont(Font.BOLD, 22f));
        var heading = new JPanel(new GridLayout(2, 1));
        heading.add(title);
        heading.add(identity);
        inspector.add(heading, BorderLayout.NORTH);
        var tabs = new JTabbedPane();
        var overview = new JPanel(new BorderLayout(8, 8));
        preview.setPreferredSize(new Dimension(270, 270));
        var top = new JPanel(new BorderLayout(8, 8));
        top.add(preview, BorderLayout.WEST);
        var table = new JTable(properties);
        table.setRowHeight(24);
        top.add(new JScrollPane(table), BorderLayout.CENTER);
        overview.add(top, BorderLayout.CENTER);
        notes.setRows(5);
        overview.add(new JScrollPane(notes), BorderLayout.SOUTH);
        tabs.addTab("Overview", overview);

        var resourcePanel = new JPanel(new BorderLayout(6, 6));
        resources.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        var resourceInfo = new JPanel(new BorderLayout(8, 8));
        resourceInfo.add(new JScrollPane(resourceDetails), BorderLayout.CENTER);
        texture.setPreferredSize(new Dimension(180, 180));
        resourceInfo.add(texture, BorderLayout.EAST);
        var resourceSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, new JScrollPane(resources), resourceInfo);
        resourceSplit.setResizeWeight(0.5);
        resourcePanel.add(resourceSplit, BorderLayout.CENTER);
        var resourceActions = new JPanel(new FlowLayout(FlowLayout.LEFT));
        resourceActions.add(button("Copy pack path", () -> withResource(path -> copy(path))));
        resourceActions.add(button("Open resource", () -> withResource(path -> navigate.accept(
                new NavigationTarget.ArchiveEntry(snapshot.archive(), snapshot.catalog().effectiveEntry(path))))));
        resourceActions.add(button("Export resource…", this::exportResource));
        resourcePanel.add(resourceActions, BorderLayout.SOUTH);
        tabs.addTab("Textures and models", resourcePanel);
        inspector.add(tabs, BorderLayout.CENTER);

        var links = new JPanel(new FlowLayout(FlowLayout.LEFT));
        links.add(button("Copy ID", () -> withEntry(entry -> copy(entry.id()))));
        links.add(button("Open source", () -> withEntry(entry -> navigate.accept(new NavigationTarget.RuntimeClass(entry.className())))));
        links.add(button("Class usages", () -> withEntry(entry -> navigate.accept(
                new NavigationTarget.SymbolUsages(new CodeSymbol.ClassSymbol(entry.className()))))));
        links.add(button("ID in code", () -> withEntry(entry -> navigate.accept(new NavigationTarget.LiteralUsages(entry.id())))));
        links.add(button("Item ↔ block", () -> withEntry(entry -> snapshot.catalog().entries().stream()
                .filter(other -> other.kind() != entry.kind() && other.id().equals(entry.counterpart())).findFirst()
                .ifPresent(other -> { query.setText(""); mods.setSelectedIndex(0); kinds.setSelectedIndex(0); entryList.setSelectedValue(other, true); }))));
        inspector.add(links, BorderLayout.SOUTH);
        var split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, browser, inspector);
        split.setDividerLocation(280);
        root.add(split, BorderLayout.CENTER);
        var footer = new JPanel(new BorderLayout());
        footer.add(new JLabel("Captured " + snapshot.catalog().capturedAt() + "  ·  " + snapshot.catalog().language()
                + "  ·  " + snapshot.catalog().entries().size() + " entries"
                + (snapshot.catalog().warnings().isEmpty() ? "" : "  ·  " + snapshot.catalog().warnings().size() + " capture warnings")), BorderLayout.CENTER);
        footer.add(button("Reload saved capture", () -> { dispose(); open(); }), BorderLayout.EAST);
        root.add(footer, BorderLayout.SOUTH);
        query.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent event) { filter(); }
            public void removeUpdate(DocumentEvent event) { filter(); }
            public void changedUpdate(DocumentEvent event) { filter(); }
        });
        mods.addActionListener(event -> filter());
        kinds.addActionListener(event -> filter());
        entryList.addListSelectionListener(event -> { if (!event.getValueIsAdjusting()) inspect(); });
        resources.addListSelectionListener(event -> { if (!event.getValueIsAdjusting()) inspectResource(); });
        WindowAdapter ownerListener = new WindowAdapter() {
            @Override public void windowClosed(WindowEvent event) { dispose(); }
        };
        if (owner != null) owner.addWindowListener(ownerListener);
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosed(WindowEvent event) {
                if (owner != null) owner.removeWindowListener(ownerListener);
                generation++;
                resourceGeneration++;
                if (inspection != null) inspection.cancel(true);
                if (resourceJob != null) resourceJob.cancel(true);
            }
        });
        filter();
    }

    private void filter() {
        var selected = entryList.getSelectedValue();
        entries.clear();
        GameCatalog.Kind kind = kinds.getSelectedIndex() == 0 ? null
                : kinds.getSelectedIndex() == 1 ? GameCatalog.Kind.ITEM : GameCatalog.Kind.BLOCK;
        entries.addAll(GameCatalogService.search(snapshot.catalog(), query.getText(), kind,
                mods.getSelectedIndex() == 0 ? null : Set.of((String) mods.getSelectedItem()), Integer.MAX_VALUE));
        if (selected != null && entries.contains(selected)) entryList.setSelectedValue(selected, true);
    }

    private void inspect() {
        int requested = ++generation;
        if (inspection != null) inspection.cancel(true);
        resourceModel.clear();
        properties.setRowCount(0);
        preview.setIcon(null);
        preview.setText(" ");
        notes.setText("");
        var entry = entryList.getSelectedValue();
        title.setText(entry == null ? "Select an item or block" : entry.name());
        identity.setText(entry == null ? " " : entry.id() + "  ·  " + entry.modName() + " " + entry.modVersion());
        if (entry == null) return;
        properties.addRow(new Object[]{"Kind", entry.kind()});
        properties.addRow(new Object[]{"Implementation class", entry.className()});
        if (!entry.counterpart().isEmpty()) properties.addRow(new Object[]{"Item/block counterpart", entry.counterpart()});
        new TreeMap<>(entry.properties()).forEach((key, value) -> properties.addRow(new Object[]{key, value}));
        preview.setText("Rendering…");
        inspection = new SwingWorker<CatalogSnapshot.Inspection, Void>() {
            @Override protected CatalogSnapshot.Inspection doInBackground() throws Exception { return snapshot.inspect(entry); }
            @Override protected void done() {
                if (requested != generation || isCancelled()) return;
                try {
                    var result = get();
                    preview.setText(result.preview() == null ? "No offline preview" : "");
                    if (result.preview() != null) preview.setIcon(new ImageIcon(result.preview()));
                    resourceModel.addAll(result.resources());
                    notes.setText(String.join("\n", result.notes()));
                    notes.setCaretPosition(0);
                } catch (Exception failure) {
                    preview.setText("Preview unavailable");
                    notes.setText(message(failure));
                }
            }
        };
        inspection.execute();
    }

    private void inspectResource() {
        int requested = ++resourceGeneration;
        if (resourceJob != null) resourceJob.cancel(true);
        texture.setIcon(null);
        resourceDetails.setText("");
        String path = resources.getSelectedValue();
        if (path == null) return;
        List<String> providers = snapshot.catalog().resources().get(path);
        if (providers == null) { resourceDetails.setText("Resource not captured: " + path); return; }
        String provenance = path + "\nEffective provider: " + providers.getLast()
                + "\nLayers, lowest to highest priority:\n" + String.join("\n", providers)
                + "\nCaptured archive: " + snapshot.archive() + "\n\n";
        resourceDetails.setText(provenance);
        resourceJob = new SwingWorker<ResourcePreview, Void>() {
            @Override protected ResourcePreview doInBackground() throws Exception {
                byte[] bytes = snapshot.read(path);
                if (path.endsWith(".png")) {
                    var image = ImageIO.read(new ByteArrayInputStream(bytes));
                    if (image == null) throw new java.io.IOException("Invalid PNG");
                    int width = image.getWidth(), height = image.getHeight();
                    double scale = Math.min(180.0 / width, 180.0 / height);
                    return new ResourcePreview(width + " × " + height + " pixels\nAlpha channel: " + image.getColorModel().hasAlpha(),
                            new ImageIcon(image.getScaledInstance(Math.max(1, (int) (width * scale)), Math.max(1, (int) (height * scale)), Image.SCALE_FAST)));
                }
                return new ResourcePreview(new String(bytes, 0, Math.min(bytes.length, 16000), StandardCharsets.UTF_8)
                        + (bytes.length > 16000 ? "\n… Open resource to view the full file." : ""), null);
            }
            @Override protected void done() {
                if (requested != resourceGeneration || isCancelled()) return;
                try {
                    var result = get();
                    resourceDetails.setText(provenance + result.text());
                    texture.setIcon(result.icon());
                } catch (Exception failure) { resourceDetails.setText(provenance + message(failure)); }
                resourceDetails.setCaretPosition(0);
            }
        };
        resourceJob.execute();
    }

    private void exportResource() {
        withResource(path -> {
            var chooser = new JFileChooser();
            chooser.setSelectedFile(new java.io.File(path.substring(path.lastIndexOf('/') + 1)));
            if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
            var target = chooser.getSelectedFile().toPath();
            if (Files.exists(target) && JOptionPane.showConfirmDialog(this, "Replace " + target.getFileName() + "?",
                    "Export resource", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;
            new SwingWorker<Void, Void>() {
                @Override protected Void doInBackground() throws Exception {
                    byte[] bytes = snapshot.read(path);
                    AtomicFiles.replace(target, staged -> Files.write(staged, bytes));
                    return null;
                }
                @Override protected void done() {
                    try { get(); }
                    catch (Exception failure) { showError(GameExplorerWindow.this, failure); }
                }
            }.execute();
        });
    }

    private void withEntry(Consumer<GameCatalog.Entry> action) {
        var entry = entryList.getSelectedValue();
        if (entry != null) action.accept(entry);
    }

    private void withResource(Consumer<String> action) {
        String path = resources.getSelectedValue();
        if (path != null && snapshot.catalog().resources().containsKey(path)) action.accept(path);
    }

    private static JButton button(String label, Runnable action) {
        var button = new JButton(label);
        button.addActionListener(event -> action.run());
        return button;
    }

    private static JTextArea textArea() {
        var text = new JTextArea();
        text.setEditable(false);
        text.setLineWrap(true);
        text.setWrapStyleWord(true);
        return text;
    }

    private static JLabel previewLabel() {
        return new JLabel(" ", SwingConstants.CENTER) {
            @Override protected void paintComponent(Graphics graphics) {
                Icon icon = getIcon();
                if (icon != null) {
                    int left = (getWidth() - icon.getIconWidth()) / 2;
                    int top = (getHeight() - icon.getIconHeight()) / 2;
                    Color background = UIManager.getColor("Panel.background");
                    for (int y = 0; y < icon.getIconHeight(); y += 12)
                        for (int x = 0; x < icon.getIconWidth(); x += 12) {
                            graphics.setColor((x / 12 + y / 12) % 2 == 0 ? background : background.brighter());
                            graphics.fillRect(left + x, top + y, Math.min(12, icon.getIconWidth() - x), Math.min(12, icon.getIconHeight() - y));
                        }
                }
                super.paintComponent(graphics);
            }
        };
    }

    private static void copy(String value) {
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(value), null);
    }

    private static String message(Throwable failure) {
        while (failure.getCause() != null) failure = failure.getCause();
        return failure.getMessage() == null ? failure.toString() : failure.getMessage();
    }

    private static void showError(Component owner, Throwable failure) {
        JOptionPane.showMessageDialog(owner, message(failure), "Items and blocks", JOptionPane.WARNING_MESSAGE);
    }

    private record ResourcePreview(String text, ImageIcon icon) { }
}
