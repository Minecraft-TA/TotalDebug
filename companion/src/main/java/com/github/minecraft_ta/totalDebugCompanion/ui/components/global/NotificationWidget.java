package com.github.minecraft_ta.totalDebugCompanion.ui.components.global;

import com.github.minecraft_ta.totalDebugCompanion.ui.components.FlatIconButton;
import java.awt.Insets;
import com.github.minecraft_ta.totalDebugCompanion.Icons;
import com.github.minecraft_ta.totalDebugCompanion.navigation.NavigationTarget;
import com.github.minecraft_ta.totalDebugCompanion.notification.NotificationCenter;
import com.github.minecraft_ta.totalDebugCompanion.notification.NotificationCenter.Entry;
import com.github.minecraft_ta.totalDebugCompanion.notification.NotificationCenter.Source;
import com.github.minecraft_ta.totalDebugCompanion.util.UIUtils;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Point;
import java.awt.Rectangle;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.nio.file.Files;
import java.util.concurrent.CompletableFuture;

/** A view of application history; never owns events or editor lifetimes. */
final class NotificationWidget extends JButton implements AutoCloseable {
    private final NotificationCenter center;
    private final Function<Source, String> unavailable;
    private final Consumer<Source> open;
    private final Runnable unsubscribe;
    private final JPopupMenu popup = new JPopupMenu();
    private final DefaultListModel<Entry> model = new DefaultListModel<>();
    private final JList<Entry> list = new JList<>(model);
    private final JScrollPane scroll = new JScrollPane(list);
    private final StatusDetailsPanel details = new StatusDetailsPanel("");
    private final JButton openSource = new JButton("Open source");
    private final JButton dismiss = new JButton("Dismiss");
    private long revision = -1;
    private boolean closed;
    private boolean updating;
    private long sourceRequest;

    NotificationWidget(NotificationCenter center, Function<Source, String> unavailable, Consumer<Source> open) {
        this.center = center;
        FlatIconButton.configure(this);
        setMargin(new Insets(0, 6, 0, 6));
        this.unavailable = unavailable;
        this.open = open;
        setIcon(Icons.INFORMATION);
        setToolTipText("Notifications");
        getAccessibleContext().setAccessibleName("Notifications");
        setMaximumSize(new Dimension(280, 22));
        putClientProperty("html.disable", true);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected, boolean focus) {
                super.getListCellRendererComponent(list, value, index, selected, focus);
                putClientProperty("html.disable", true);
                Entry entry = (Entry) value;
                setText(DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault()).format(entry.time())
                        + "  " + entry.source().label() + "  " + entry.message());
                setIcon(switch (entry.severity()) { case ERROR -> Icons.ERROR; case WARNING -> Icons.WARNING;
                    case SUCCESS -> Icons.SUCCESS; case INFORMATION -> Icons.INFORMATION; });
                return this;
            }
        });
        list.addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting() && !updating) {
                selectionChanged();
                Entry entry = list.getSelectedValue();
                if (entry != null && !entry.read()) center.acknowledge(Set.of(entry.id()));
            }
        });
        scroll.setPreferredSize(new Dimension(480, 180));
        scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.getViewport().addChangeListener(event -> acknowledgeVisible());
        JPanel panel = new JPanel(new BorderLayout(0, 6));
        panel.add(scroll, BorderLayout.CENTER);
        JPanel bottom = new JPanel(new BorderLayout(0, 6));
        bottom.add(details, BorderLayout.CENTER);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEADING, 4, 0));
        openSource.addActionListener(event -> {
            Entry entry = list.getSelectedValue();
            if (entry == null) return;
            checkSource(entry, true);
        });
        dismiss.addActionListener(event -> { Entry entry = list.getSelectedValue(); if (entry != null) center.dismiss(entry.id()); });
        JButton clear = new JButton("Clear all");
        clear.addActionListener(event -> center.clear());
        actions.add(openSource); actions.add(dismiss); actions.add(clear);
        bottom.add(actions, BorderLayout.SOUTH);
        panel.add(bottom, BorderLayout.SOUTH);
        popup.add(panel);
        addActionListener(event -> {
            selectionChanged();
            popup.show(this, 0, -popup.getPreferredSize().height);
            list.requestFocusInWindow();
            SwingUtilities.invokeLater(this::acknowledgeVisible);
        });
        unsubscribe = center.subscribe(snapshot -> UIUtils.onEdt(() -> render(snapshot)));
    }

    private void render(NotificationCenter.Snapshot snapshot) {
        if (closed || snapshot.revision() <= revision) return;
        revision = snapshot.revision();
        Entry preview = snapshot.preview();
        setText(preview == null ? "" : snapshot.unread() + "  " + preview.message());
        setIcon(preview == null ? Icons.INFORMATION : switch (preview.severity()) {
            case ERROR -> Icons.ERROR;
            case WARNING -> Icons.WARNING;
            case SUCCESS -> Icons.SUCCESS;
            case INFORMATION -> Icons.INFORMATION;
        });
        Entry previous = list.getSelectedValue();
        Long selected = previous == null ? null : previous.id();
        int first = list.getFirstVisibleIndex();
        Long anchor = first > 0 && first < model.size() ? model.get(first).id() : null;
        Rectangle anchorBounds = anchor == null ? null : list.getCellBounds(first, first);
        int inset = anchorBounds == null ? 0 : scroll.getViewport().getViewPosition().y - anchorBounds.y;
        updating = true;
        boolean sameRows = model.size() == snapshot.entries().size();
        for (int i = 0; sameRows && i < model.size(); i++) sameRows = model.get(i).id() == snapshot.entries().get(i).id();
        if (sameRows) {
            for (int i = 0; i < model.size(); i++) if (!model.get(i).equals(snapshot.entries().get(i))) model.set(i, snapshot.entries().get(i));
        } else {
            model.clear();
            model.addAll(snapshot.entries());
        }
        for (int i = 0; i < model.size(); i++) if (selected != null && model.get(i).id() == selected) list.setSelectedIndex(i);
        if (list.getSelectedIndex() < 0 && !model.isEmpty()) list.setSelectedIndex(0);
        if (!sameRows && anchor != null) {
            for (int i = 0; i < model.size(); i++) if (model.get(i).id() == anchor) {
                Rectangle bounds = list.getCellBounds(i, i);
                if (bounds != null) scroll.getViewport().setViewPosition(new Point(0, bounds.y + inset));
                break;
            }
        }
        updating = false;
        Entry current = list.getSelectedValue();
        if (previous == null || current == null || !previous.copyText().equals(current.copyText()) || previous.id() != current.id()) selectionChanged();
        SwingUtilities.invokeLater(this::acknowledgeVisible);
    }

    void selectionChanged() {
        Entry entry = list.getSelectedValue();
        details.setDetails(entry == null ? "No notifications" : entry.copyText());
        if (entry == null) { sourceRequest++; openSource.setEnabled(false); openSource.setToolTipText("Select a notification"); }
        else checkSource(entry, false);
        dismiss.setEnabled(entry != null);
    }

    private void checkSource(Entry entry, boolean activate) {
        long request = ++sourceRequest;
        String reason = unavailable.apply(entry.source());
        openSource.setEnabled(false);
        openSource.setToolTipText(reason);
        if (reason != null) return;
        if (!(entry.source().target() instanceof NavigationTarget.LocalFile file)) {
            openSource.setEnabled(true);
            if (activate) { popup.setVisible(false); open.accept(entry.source()); }
            return;
        }
        openSource.setToolTipText("Checking source file");
        CompletableFuture.supplyAsync(() -> Files.isRegularFile(file.path())).whenComplete((exists, failure) -> UIUtils.onEdt(() -> {
            if (closed || request != sourceRequest) return;
            String currentReason = unavailable.apply(entry.source());
            if (currentReason == null && (failure != null || !Boolean.TRUE.equals(exists))) currentReason = "The source file is no longer available";
            openSource.setEnabled(currentReason == null);
            openSource.setToolTipText(currentReason);
            if (activate && currentReason == null) { popup.setVisible(false); open.accept(entry.source()); }
        }));
    }

    private void acknowledgeVisible() {
        if (closed || updating || !popup.isVisible()) return;
        Set<Long> ids = new HashSet<>();
        int first = list.getFirstVisibleIndex();
        int last = list.getLastVisibleIndex();
        for (int i = Math.max(0, first); i <= last && i < model.size(); i++) if (!model.get(i).read()) ids.add(model.get(i).id());
        if (!ids.isEmpty()) center.acknowledge(ids);
    }

    void applyTheme() { SwingUtilities.updateComponentTreeUI(popup); }

    @Override public void close() { closed = true; unsubscribe.run(); popup.setVisible(false); }
}
