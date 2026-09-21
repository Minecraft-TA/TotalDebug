package com.github.minecraft_ta.totalDebugCompanion.ui.components.global;

import com.github.minecraft_ta.totalDebugCompanion.Icons;
import com.github.minecraft_ta.totalDebugCompanion.navigation.NavigationTarget;
import com.github.minecraft_ta.totalDebugCompanion.notification.NotificationCenter;
import com.github.minecraft_ta.totalDebugCompanion.notification.NotificationCenter.Entry;
import com.github.minecraft_ta.totalDebugCompanion.notification.NotificationCenter.Source;
import com.github.minecraft_ta.totalDebugCompanion.ui.PopupElements;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.FlatIconButton;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.ThemeColors;
import com.github.minecraft_ta.totalDebugCompanion.util.UIUtils;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.Scrollable;
import javax.swing.Box;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JComponent;
import javax.swing.KeyStroke;
import javax.swing.AbstractAction;
import javax.swing.JScrollPane;
import javax.swing.JSeparator;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Insets;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.KeyboardFocusManager;
import java.awt.Window;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.event.ActionEvent;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

/** Session history with stable, expandable rows. Events remain owned by NotificationCenter. */
final class NotificationWidget extends JButton implements AutoCloseable {
    private final NotificationCenter center;
    private final Function<Source, String> unavailable;
    private final Consumer<Source> open;
    private final Runnable unsubscribe;
    private final JPanel history = new JPanel(new BorderLayout(0, 6));
    private final JButton message = new JButton();
    private Component previousFocus;
    private Window owner;
    private final WindowAdapter activation = new WindowAdapter() {
        @Override public void windowActivated(WindowEvent event) { SwingUtilities.invokeLater(NotificationWidget.this::acknowledgeVisible); }
    };
    private Long previewId;
    private boolean unread;
    private final JPanel rows = new HistoryRows();
    private final Map<Long, NotificationRow> entries = new LinkedHashMap<>();
    private final JScrollPane scroll = new JScrollPane(rows);
    private final JLabel empty = PopupElements.label("No notifications");
    private final NotificationBalloon balloon = new NotificationBalloon();
    private final JButton clear;
    private final JButton closeHistory;
    private Predicate<Source> sourceVisible = source -> false;
    private long revision = -1;
    private long lastId;
    private boolean closed;
    private boolean updating;

    NotificationWidget(NotificationCenter center, Function<Source, String> unavailable, Consumer<Source> open) {
        this.center = center;
        this.unavailable = unavailable;
        this.open = open;
        FlatIconButton.configure(this);
        setMargin(new Insets(0, 6, 0, 6));
        setToolTipText("Notifications");
        getAccessibleContext().setAccessibleName("Notifications");
        setIcon(Icons.NOTIFICATIONS);
        setPreferredSize(new Dimension(32, 22));
        setMinimumSize(getPreferredSize());
        setMaximumSize(getPreferredSize());
        FlatIconButton.configure(message);
        message.setMargin(new Insets(0, 6, 0, 6));
        message.setMinimumSize(new Dimension(0, 22));
        message.setMaximumSize(new Dimension(280, 22));
        message.putClientProperty("html.disable", true);
        message.addActionListener(event -> showHistory(previewId));
        putClientProperty("html.disable", true);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.getVerticalScrollBar().setUnitIncrement(24);
        scroll.getViewport().addChangeListener(event -> acknowledgeVisible());
        clear = PopupElements.icon(Icons.DELETE, "Clear history", center::clear);
        JPanel controls = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 0));
        controls.setOpaque(false);
        controls.add(clear);
        closeHistory = PopupElements.icon(Icons.CLOSE_ICON, "Close notifications", this::hideHistory);
        controls.add(closeHistory);
        history.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
        history.add(PopupElements.row(PopupElements.label("Notifications"), controls), BorderLayout.NORTH);
        history.add(scroll, BorderLayout.CENTER);
        history.setVisible(false);
        history.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke("ESCAPE"), "closeHistory");
        history.getActionMap().put("closeHistory", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent event) { hideHistory(); }
        });
        scroll.getViewport().addComponentListener(new ComponentAdapter() {
            @Override public void componentResized(ComponentEvent event) {
                entries.values().forEach(NotificationRow::wrapDetails);
                rows.revalidate();
            }
        });
        addActionListener(event -> { if (history.isVisible()) hideHistory(); else showHistory(null); });
        unsubscribe = center.subscribe(snapshot -> UIUtils.onEdt(() -> render(snapshot)));
    }

    void setSourceVisible(Predicate<Source> predicate) { sourceVisible = predicate; }

    JPanel historyPanel() { return history; }
    JButton messageButton() { return message; }

    private void hideHistory() {
        history.setVisible(false);
        setSelected(false);
        Component restoreFocus = previousFocus;
        previousFocus = null;
        if (restoreFocus != null && restoreFocus.isShowing()) restoreFocus.requestFocusInWindow();
        else requestFocusInWindow();
    }

    @Override public void addNotify() {
        super.addNotify();
        owner = SwingUtilities.getWindowAncestor(this);
        if (owner != null) owner.addWindowListener(activation);
    }

    @Override public void removeNotify() {
        if (owner != null) owner.removeWindowListener(activation);
        owner = null;
        previousFocus = null;
        super.removeNotify();
    }

    private void showHistory(Long id) {
        balloon.close();
        selectionChanged();
        NotificationRow row = id == null ? null : entries.get(id);
        if (row != null) row.expand(true);
        if (!history.isVisible()) previousFocus = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
        history.setVisible(true);
        setSelected(true);
        SwingUtilities.invokeLater(() -> {
            if (closed || !history.isShowing()) return;
            if (row != null) { rows.scrollRectToVisible(row.getBounds()); row.toggle.requestFocusInWindow(); }
            else if (rows.getComponentCount() > 0 && rows.getComponent(0) instanceof NotificationRow first) first.toggle.requestFocusInWindow();
            else closeHistory.requestFocusInWindow();
            acknowledgeVisible();
        });
    }

    private void render(NotificationCenter.Snapshot snapshot) {
        if (closed || snapshot.revision() <= revision) return;
        boolean initial = revision < 0;
        revision = snapshot.revision();
        updating = true;
        try {
            Entry preview = snapshot.preview();
            previewId = preview == null ? null : preview.id();
            message.setText(preview == null ? "" : preview.message());
            message.setIcon(preview == null ? null : icon(preview));
            message.setToolTipText(preview == null ? null : "Show notification: " + preview.message());
            message.setVisible(preview != null);
            unread = snapshot.unread() > 0;
            repaint();
            getAccessibleContext().setAccessibleDescription(snapshot.unread() + " unread notifications");
            Point position = scroll.getViewport().getViewPosition();
            NotificationRow anchor = entries.values().stream().filter(row -> row.getY() <= position.y && row.getY() + row.getHeight() > position.y).findFirst().orElse(null);
            int inset = anchor == null ? 0 : position.y - anchor.getY();
            Set<Long> ids = new HashSet<>();
            snapshot.entries().forEach(entry -> ids.add(entry.id()));
            entries.entrySet().removeIf(item -> {
                if (ids.contains(item.getKey())) return false;
                item.getValue().sourceRequest++;
                rows.remove(item.getValue());
                return true;
            });
            rows.remove(empty);
            int index = 0;
            for (Entry entry : snapshot.entries()) {
                NotificationRow row = entries.computeIfAbsent(entry.id(), ignored -> new NotificationRow(entry));
                row.update(entry);
                if (row.getParent() != rows) rows.add(row, index);
                else if (rows.getComponentZOrder(row) != index) rows.setComponentZOrder(row, index);
                index++;
            }
            if (entries.isEmpty()) rows.add(empty);
            clear.setEnabled(!entries.isEmpty());
            resizeHistory();
            if (position.y > 0 && anchor != null && entries.containsKey(anchor.entry.id())) {
                rows.doLayout();
                scroll.getViewport().setViewPosition(new Point(0, Math.max(0, anchor.getY() + inset)));
            }
            if (!ids.contains(balloon.entryId())) balloon.close();
            Entry attention = snapshot.entries().stream().filter(entry -> entry.id() > lastId && !entry.read())
                    .filter(entry -> entry.severity() == NotificationCenter.Severity.ERROR || entry.severity() == NotificationCenter.Severity.WARNING)
                    .filter(entry -> !entry.shownAtSource() || !sourceVisible.test(entry.source())).findFirst().orElse(null);
            for (Entry entry : snapshot.entries()) lastId = Math.max(lastId, entry.id());
            if (!initial && attention != null && !history.isVisible() && isShowing())
                balloon.show(this, attention, () -> showHistory(attention.id()));
        } finally { updating = false; }
        SwingUtilities.invokeLater(this::acknowledgeVisible);
    }

    private void resizeHistory() {
        rows.revalidate();
        rows.repaint();
    }

    void selectionChanged() { entries.values().stream().filter(row -> row.expanded).forEach(row -> row.checkSource(false)); }

    private void acknowledgeVisible() {
        if (closed || updating || !history.isShowing() || owner == null || !owner.isActive()) return;
        Set<Long> ids = new HashSet<>();
        var visible = scroll.getViewport().getViewRect();
        for (NotificationRow row : entries.values()) if (!row.entry.read() && visible.intersects(row.getBounds())) ids.add(row.entry.id());
        if (!ids.isEmpty()) center.acknowledge(ids);
    }

    static Icon icon(Entry entry) {
        return switch (entry.severity()) { case ERROR -> Icons.ERROR; case WARNING -> Icons.WARNING;
            case SUCCESS -> Icons.SUCCESS; case INFORMATION -> Icons.INFORMATION; };
    }

    private final class NotificationRow extends JPanel {
        private Entry entry;
        private boolean expanded;
        private long sourceRequest;
        private final JButton toggle;
        private final JButton chevron;
        private final JButton dismiss;
        private final JButton openSource;
        private final JLabel context = PopupElements.label("");
        private final JLabel time = PopupElements.label("");
        private final JLabel detail = PopupElements.label("");
        private final JPanel body = PopupElements.column();
        private final Component sourceGap = Box.createHorizontalStrut(14);

        NotificationRow(Entry entry) {
            super(new BorderLayout());
            this.entry = entry;
            setOpaque(false);
            JPanel content = PopupElements.column();
            content.setBorder(BorderFactory.createEmptyBorder(8, 0, 8, 0));
            toggle = PopupElements.link(entry.message(), icon(entry), () -> expand(!expanded));
            toggle.setHorizontalTextPosition(SwingConstants.RIGHT);
            toggle.setForeground(ThemeColors.text());
            toggle.setIconTextGap(8);
            toggle.setMinimumSize(new Dimension(0, toggle.getPreferredSize().height));
            dismiss = PopupElements.icon(Icons.CLOSE_ICON, "Dismiss notification", () -> center.dismiss(this.entry.id()));
            chevron = PopupElements.icon(Icons.RIGHT_ARROW, "Expand notification", () -> expand(!expanded));
            JPanel controls = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 0));
            controls.setOpaque(false);
            controls.add(dismiss);
            controls.add(chevron);
            content.add(PopupElements.row(toggle, controls));
            int textIndent = toggle.getInsets().left + toggle.getIcon().getIconWidth() + toggle.getIconTextGap();
            JPanel metadata = PopupElements.row(context, time);
            context.setMinimumSize(new Dimension(0, context.getPreferredSize().height));
            metadata.setBorder(BorderFactory.createEmptyBorder(3, textIndent, 0, 24));
            content.add(metadata);
            body.setBorder(BorderFactory.createEmptyBorder(10, textIndent, 0, 0));
            body.add(detail);
            JPanel links = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
            links.setOpaque(false);
            links.setAlignmentX(LEFT_ALIGNMENT);
            boolean script = entry.source().target() instanceof NavigationTarget.LocalFile file && file.path().toString().endsWith(".tdscript");
            openSource = PopupElements.link(script ? "Open script" : "Open source", Icons.JUMP_TO_SOURCE, () -> checkSource(true));
            links.add(openSource);
            links.add(sourceGap);
            links.add(PopupElements.link("Copy details", Icons.COPY, () -> PopupElements.copy(this.entry.copyText())));
            body.add(Box.createVerticalStrut(7));
            body.add(links);
            content.add(body);
            add(content, BorderLayout.CENTER);
            add(new JSeparator(), BorderLayout.SOUTH);
            body.setVisible(false);
            dismiss.setVisible(false);
            update(entry);
        }

        void update(Entry replacement) {
            boolean changed = !entry.copyText().equals(replacement.copyText());
            entry = replacement;
            toggle.setText(entry.message());
            toggle.setToolTipText(entry.message());
            context.setText(entry.source().label());
            context.setForeground(ThemeColors.secondaryText());
            time.setText(DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault()).format(entry.time()));
            time.setForeground(ThemeColors.secondaryText());
            if (changed || detail.getText().isEmpty()) wrapDetails();
            detail.setVisible(!entry.details().isEmpty());
            openSource.setVisible(entry.source().target() != null);
            sourceGap.setVisible(openSource.isVisible());
            if (expanded && changed) checkSource(false);
        }

        void wrapDetails() {
            PopupElements.wrappedText(detail, entry.details(), Math.max(100, scroll.getViewport().getWidth() - body.getInsets().left - 4));
        }

        void expand(boolean value) {
            expanded = value;
            body.setVisible(value);
            dismiss.setVisible(value);
            chevron.setIcon(value ? Icons.DOWN_ARROW : Icons.RIGHT_ARROW);
            chevron.setToolTipText(value ? "Collapse notification" : "Expand notification");
            if (value) checkSource(false); else sourceRequest++;
            resizeHistory();
            if (value) SwingUtilities.invokeLater(NotificationWidget.this::acknowledgeVisible);
        }

        private void checkSource(boolean activate) {
            long request = ++sourceRequest;
            String reason = unavailable.apply(entry.source());
            openSource.setEnabled(false);
            openSource.setToolTipText(reason);
            if (reason != null || entry.source().target() == null) return;
            Path path;
            boolean directory;
            if (entry.source().target() instanceof NavigationTarget.LocalFile file) {
                path = file.path();
                directory = false;
            } else if (entry.source().target() instanceof NavigationTarget.LocalDirectory folder) {
                path = folder.path();
                directory = true;
            } else {
                openSource.setEnabled(true);
                if (activate) open.accept(entry.source());
                return;
            }
            openSource.setToolTipText("Checking source");
            CompletableFuture.supplyAsync(() -> directory ? Files.isDirectory(path) : Files.isRegularFile(path)).whenComplete((exists, failure) -> UIUtils.onEdt(() -> {
                if (closed || request != sourceRequest || !expanded) return;
                String currentReason = unavailable.apply(entry.source());
                if (currentReason == null && (failure != null || !Boolean.TRUE.equals(exists))) currentReason = "The source is no longer available";
                openSource.setEnabled(currentReason == null);
                openSource.setToolTipText(currentReason);
                if (activate && currentReason == null) open.accept(entry.source());
            }));
        }
    }

    void applyTheme() {
        SwingUtilities.updateComponentTreeUI(history);
        scroll.getViewport().setBackground(history.getBackground());
        entries.values().forEach(row -> { row.toggle.setForeground(ThemeColors.text()); row.update(row.entry); });
        balloon.close();
    }

    @Override protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        if (unread) {
            Graphics2D badge = (Graphics2D) graphics.create();
            badge.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            badge.setColor(ThemeColors.link());
            badge.fill(new Ellipse2D.Double(getWidth() / 2.0 + 4, 2, 5, 5));
            badge.dispose();
        }
    }

    private static final class HistoryRows extends JPanel implements Scrollable {
        HistoryRows() { setOpaque(false); setLayout(new BoxLayout(this, BoxLayout.Y_AXIS)); }
        @Override public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        @Override public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) { return 24; }
        @Override public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) { return Math.max(24, visible.height - 24); }
        @Override public boolean getScrollableTracksViewportWidth() { return true; }
        @Override public boolean getScrollableTracksViewportHeight() { return false; }
    }

    @Override public void close() {
        closed = true;
        unsubscribe.run();
        balloon.close();
        history.setVisible(false);
        previousFocus = null;
        if (owner != null) owner.removeWindowListener(activation);
        owner = null;
        rows.removeAll();
        entries.clear();
    }
}
