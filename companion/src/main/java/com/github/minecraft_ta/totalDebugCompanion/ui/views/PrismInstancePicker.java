package com.github.minecraft_ta.totalDebugCompanion.ui.views;

import com.formdev.flatlaf.util.UIScale;
import com.github.minecraft_ta.totalDebugCompanion.Icons;
import com.github.minecraft_ta.totalDebugCompanion.session.CompanionProfile;
import com.github.minecraft_ta.totalDebugCompanion.session.PrismInstances;
import com.github.minecraft_ta.totalDebugCompanion.session.ProjectRegistry;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.DynamicMatteBorder;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.ThemeColors;
import com.github.minecraft_ta.totalDebugCompanion.util.UIUtils;
import javax.imageio.ImageIO;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListModel;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.JTextArea;
import javax.swing.KeyStroke;
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;

/** Browses local Prism instances without launching or changing them. */
public final class PrismInstancePicker extends JDialog {
    private record Entry(CompanionProfile profile, String name, String description, Icon icon, boolean current) { }
    private final JTextField search = new JTextField();
    private final DefaultListModel<Entry> model = new DefaultListModel<>();
    private final JList<Entry> list = new JList<>(model);
    private final JLabel count = new JLabel("Loading instances…");
    private final JLabel message = new JLabel("Reading your Prism library…", SwingConstants.CENTER);
    private final JButton open = new JButton("Open instance");
    private final JPanel content = new JPanel(new BorderLayout());
    private final JScrollPane scroll = new JScrollPane(list);
    private final Consumer<CompanionProfile> onOpen;
    private List<Entry> entries = List.of();
    private volatile boolean loading = true;
    private boolean disposed;

    public PrismInstancePicker(Window owner, Path prismHome, CompanionProfile current, Consumer<CompanionProfile> onOpen) {
        super(owner, "Prism instances", ModalityType.MODELESS);
        this.onOpen = onOpen;
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setLayout(new BorderLayout());
        var filterBar = new JPanel(new BorderLayout());
        filterBar.setBorder(BorderFactory.createEmptyBorder(16, 20, 14, 20));
        search.putClientProperty("JTextField.leadingIcon", Icons.SEARCH_ICON);
        search.putClientProperty("JTextField.placeholderText", "Search…");
        search.putClientProperty("JTextField.showClearButton", true);
        search.setPreferredSize(new Dimension(UIScale.scale(600), UIScale.scale(36)));
        filterBar.add(search, BorderLayout.CENTER);
        add(filterBar, BorderLayout.NORTH);

        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setFixedCellHeight(UIScale.scale(76));
        list.setCellRenderer(new InstanceRenderer());
        list.setBorder(BorderFactory.createEmptyBorder(2, 8, 2, 8));
        list.addListSelectionListener(event -> open.setEnabled(list.getSelectedValue() != null));
        list.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent event) {
                int index = list.locationToIndex(event.getPoint());
                if (event.getClickCount() == 2 && index >= 0 && list.getCellBounds(index, index).contains(event.getPoint())) openSelected();
            }
        });
        list.getInputMap().put(KeyStroke.getKeyStroke("ENTER"), "open-instance");
        list.getActionMap().put("open-instance", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent event) { openSelected(); }
        });
        scroll.setBorder(BorderFactory.createEmptyBorder());
        content.setBorder(DynamicMatteBorder.rule(1, 0, 1, 0));
        content.add(message, BorderLayout.CENTER);
        add(content, BorderLayout.CENTER);

        var footer = new JPanel(new BorderLayout(12, 0));
        footer.setBorder(BorderFactory.createEmptyBorder(14, 24, 14, 24));
        count.setForeground(ThemeColors.secondaryText());
        footer.add(count, BorderLayout.WEST);
        var actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        var cancel = new JButton("Cancel");
        cancel.setPreferredSize(new Dimension(UIScale.scale(88), UIScale.scale(32)));
        open.setPreferredSize(new Dimension(UIScale.scale(136), UIScale.scale(32)));
        cancel.addActionListener(event -> dispose());
        open.setEnabled(false);
        open.addActionListener(event -> openSelected());
        actions.add(cancel);
        actions.add(open);
        footer.add(actions, BorderLayout.EAST);
        add(footer, BorderLayout.SOUTH);
        getRootPane().setDefaultButton(open);
        getRootPane().registerKeyboardAction(event -> dispose(), KeyStroke.getKeyStroke("ESCAPE"), JComponent.WHEN_IN_FOCUSED_WINDOW);
        search.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent event) { filter(); }
            public void removeUpdate(DocumentEvent event) { filter(); }
            public void changedUpdate(DocumentEvent event) { filter(); }
        });
        search.getInputMap().put(KeyStroke.getKeyStroke("DOWN"), "focus-instances");
        search.getActionMap().put("focus-instances", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent event) { list.requestFocusInWindow(); }
        });
        setSize(UIScale.scale(700), UIScale.scale(620));
        setMinimumSize(new Dimension(UIScale.scale(580), UIScale.scale(420)));
        setLocation(owner.getX() + (owner.getWidth() - getWidth()) / 2, owner.getY() + (owner.getHeight() - getHeight()) / 2);
        CompletableFuture.supplyAsync(() -> {
            try {
                return PrismInstances.discover(prismHome).stream().map(profile -> {
                    var details = PrismInstances.details(profile);
                    String description = details.minecraftVersion().isBlank() ? "Version information unavailable" : "Minecraft " + details.minecraftVersion();
                    if (!details.loader().isBlank()) description += ", " + details.loader();
                    boolean selected = current != null && current.workspaceDirectory().equals(profile.workspaceDirectory());
                    return new Entry(profile, ProjectRegistry.defaultName(profile), description, artwork(details.icon()), selected);
                }).sorted(Comparator.comparing(Entry::current).reversed().thenComparing(Entry::name, String.CASE_INSENSITIVE_ORDER)).toList();
            } catch (IOException failure) { throw new CompletionException(failure); }
        }).whenComplete((loaded, failure) -> UIUtils.onEdt(() -> {
            if (disposed) return;
            loading = false;
            if (failure != null) {
                search.setEnabled(false);
                Throwable cause = failure;
                while (cause.getCause() != null) cause = cause.getCause();
                var detail = new JTextArea("Could not read " + prismHome.resolve("instances") + "\n\n" + cause);
                detail.setEditable(false);
                detail.setLineWrap(true);
                detail.setWrapStyleWord(true);
                detail.setFont(message.getFont());
                detail.setOpaque(false);
                detail.setBorder(BorderFactory.createEmptyBorder(20, 24, 20, 24));
                content.removeAll();
                content.add(new JScrollPane(detail), BorderLayout.CENTER);
                content.revalidate();
                content.repaint();
                count.setText("Library unavailable");
                return;
            }
            entries = loaded;
            filter();
            search.requestFocusInWindow();
        }));
    }

    public boolean isLoading() { return loading; }

    private void filter() {
        if (loading || disposed || !search.isEnabled()) return;
        String query = search.getText().strip().toLowerCase(Locale.ROOT);
        Entry previous = list.getSelectedValue();
        model.clear();
        for (Entry entry : entries) if ((entry.name() + " " + entry.description()).toLowerCase(Locale.ROOT).contains(query)) model.addElement(entry);
        if (previous != null && model.contains(previous)) list.setSelectedValue(previous, true);
        else if (!model.isEmpty()) list.setSelectedIndex(0);
        content.removeAll();
        if (model.isEmpty()) {
            message.setText(entries.isEmpty() ? "No Prism instances found. Use Open to choose an instance folder." : "No instances match “" + search.getText() + "”");
            content.add(message, BorderLayout.CENTER);
        } else content.add(scroll, BorderLayout.CENTER);
        count.setText(query.isEmpty() ? entries.size() + " instances" : model.size() + " of " + entries.size() + " instances");
        content.revalidate();
        content.repaint();
    }

    private void openSelected() {
        Entry selected = list.getSelectedValue();
        if (selected == null) return;
        dispose();
        onOpen.accept(selected.profile());
    }

    @Override public void dispose() { disposed = true; super.dispose(); }

    private static Icon artwork(Path file) {
        try {
            BufferedImage source = file == null ? null : ImageIO.read(file.toFile());
            if (source != null) {
                int size = UIScale.scale(44);
                var scaled = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
                Graphics2D graphics = scaled.createGraphics();
                graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                double ratio = (double) size / Math.max(source.getWidth(), source.getHeight());
                int width = Math.max(1, (int) (source.getWidth() * ratio));
                int height = Math.max(1, (int) (source.getHeight() * ratio));
                graphics.drawImage(source, (size - width) / 2, (size - height) / 2, width, height, null);
                graphics.dispose();
                return new ImageIcon(scaled);
            }
        } catch (IOException | RuntimeException ignored) { }
        return Icons.PRISM_INSTANCE.derive(44, 44);
    }

    private static final class InstanceRenderer extends JPanel implements ListCellRenderer<Entry> {
        private final JLabel icon = new JLabel();
        private final JLabel name = new JLabel();
        private final JLabel detail = new JLabel();
        private final JLabel current = new JLabel("Current project");

        InstanceRenderer() {
            super(new BorderLayout(14, 0));
            setBorder(BorderFactory.createEmptyBorder(10, 14, 10, 14));
            add(icon, BorderLayout.WEST);
            var text = new JPanel();
            text.setOpaque(false);
            text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));
            name.setFont(name.getFont().deriveFont(Font.BOLD, name.getFont().getSize2D() + 1));
            text.add(Box.createVerticalGlue());
            text.add(name);
            text.add(Box.createVerticalStrut(6));
            text.add(detail);
            text.add(Box.createVerticalGlue());
            add(text, BorderLayout.CENTER);
            add(current, BorderLayout.EAST);
        }

        @Override public Component getListCellRendererComponent(JList<? extends Entry> list, Entry entry, int index, boolean selected, boolean focus) {
            setBackground(selected ? list.getSelectionBackground() : list.getBackground());
            Color foreground = selected ? list.getSelectionForeground() : ThemeColors.text();
            name.setForeground(foreground);
            detail.setForeground(selected ? foreground : ThemeColors.secondaryText());
            current.setForeground(selected ? foreground : ThemeColors.secondaryText());
            name.setText(entry.name());
            detail.setText(entry.description());
            icon.setIcon(entry.icon());
            current.setVisible(entry.current());
            return this;
        }
    }
}
