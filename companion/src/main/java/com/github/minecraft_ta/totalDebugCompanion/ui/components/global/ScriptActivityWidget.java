package com.github.minecraft_ta.totalDebugCompanion.ui.components.global;

import com.github.minecraft_ta.totalDebugCompanion.Icons;
import com.github.minecraft_ta.totalDebugCompanion.script.EditorScriptRunService;
import com.github.minecraft_ta.totalDebugCompanion.ui.PopupElements;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.FlatIconButton;
import com.github.minecraft_ta.totalDebugCompanion.util.UIUtils;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.Scrollable;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Insets;
import java.awt.Rectangle;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

final class ScriptActivityWidget extends JButton implements AutoCloseable {
    private final EditorScriptRunService runs;
    private final Runnable unsubscribe;
    private final JPopupMenu popup = new JPopupMenu();
    private final JPanel rows = new ActivityRows();
    private final JScrollPane scroll = new JScrollPane(rows);
    private final Map<Integer, RunRow> runRows = new LinkedHashMap<>();
    private boolean closed;

    ScriptActivityWidget(EditorScriptRunService runs) {
        this.runs = runs;
        FlatIconButton.configure(this);
        setMargin(new Insets(0, 6, 0, 6));
        putClientProperty("html.disable", true);
        setMaximumSize(new Dimension(220, 22));
        setToolTipText("Script activity");
        rows.setLayout(new BoxLayout(rows, BoxLayout.Y_AXIS));
        scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        PopupElements.content(popup, scroll);
        addActionListener(event -> { refreshPopup(); PopupElements.showAbove(popup, this); });
        unsubscribe = runs.subscribe(() -> UIUtils.onEdt(this::refresh));
    }
    private void refresh() {
        if (closed) return;
        var active = runs.activeRuns();
        setVisible(!active.isEmpty());
        setText(active.size() == 1 ? active.getFirst().source().label() + ": " + active.getFirst().state().phase().label()
                : active.size() + " scripts active");
        if (active.isEmpty()) popup.setVisible(false);
        refreshPopup();
    }
    private void refreshPopup() {
        var active = runs.activeRuns();
        Set<Integer> ids = active.stream().map(EditorScriptRunService.Run::id).collect(Collectors.toSet());
        runRows.entrySet().removeIf(entry -> {
            if (ids.contains(entry.getKey())) return false;
            rows.remove(entry.getValue());
            return true;
        });
        for (var run : active) {
            RunRow row = runRows.computeIfAbsent(run.id(), id -> {
                var created = new RunRow(run);
                rows.add(created);
                return created;
            });
            row.label.setText(run.source().label() + ": " + run.state().phase().label());
            row.stop.setEnabled(run.state().phase() != EditorScriptRunService.Phase.STOPPING);
        }
        scroll.setPreferredSize(new Dimension(380, Math.min(180, Math.max(30, rows.getPreferredSize().height))));
        if (popup.isVisible()) PopupElements.showAbove(popup, this);
    }

    private static final class ActivityRows extends JPanel implements Scrollable {
        @Override public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        @Override public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) { return 24; }
        @Override public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) { return Math.max(24, visible.height - 24); }
        @Override public boolean getScrollableTracksViewportWidth() { return true; }
        @Override public boolean getScrollableTracksViewportHeight() { return false; }
    }

    private static final class RunRow extends JPanel {
        final JLabel label = new JLabel();
        final JButton stop;
        RunRow(EditorScriptRunService.Run run) {
            super(new BorderLayout(8, 0));
            label.putClientProperty("html.disable", true);
            label.setMinimumSize(new Dimension(0, label.getPreferredSize().height));
            add(label, BorderLayout.CENTER);
            stop = PopupElements.icon(Icons.STOP, "Stop script", run::stop);
            add(stop, BorderLayout.EAST);
            setMaximumSize(new Dimension(Integer.MAX_VALUE, stop.getPreferredSize().height));
        }
    }
    void applyTheme() { SwingUtilities.updateComponentTreeUI(popup); }

    @Override public void close() { closed = true; unsubscribe.run(); popup.setVisible(false); rows.removeAll(); runRows.clear(); }
}
