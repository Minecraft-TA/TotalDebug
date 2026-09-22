package com.github.minecraft_ta.totalDebugCompanion.ui.components.global;

import com.github.minecraft_ta.totalDebugCompanion.Icons;
import com.github.minecraft_ta.totalDebugCompanion.notification.NotificationCenter.Entry;
import com.github.minecraft_ta.totalDebugCompanion.ui.PopupElements;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.JLayeredPane;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.UIManager;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/** One transient alert above the status bar, without a window or focus change. */
final class NotificationBalloon implements AutoCloseable {
    private final Timer timeout = new Timer(10_000, event -> close());
    private JLayeredPane layer;
    private JPanel panel;
    private long entryId;
    private final ComponentAdapter resize = new ComponentAdapter() {
        @Override public void componentResized(ComponentEvent event) { close(); }
        @Override public void componentHidden(ComponentEvent event) { close(); }
    };

    NotificationBalloon() { timeout.setRepeats(false); }
    long entryId() { return entryId; }

    void show(Component anchor, Entry entry, Runnable details) {
        close();
        var root = SwingUtilities.getRootPane(anchor);
        if (root == null || !anchor.isShowing()) return;
        layer = root.getLayeredPane();
        entryId = entry.id();
        panel = new JPanel(new BorderLayout(12, 0)) {
            @Override protected void paintComponent(Graphics graphics) {
                Graphics2D copy = (Graphics2D) graphics.create();
                copy.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                copy.setColor(UIManager.getColor("PopupMenu.background"));
                copy.fillRoundRect(0, 0, getWidth(), getHeight(), 10, 10);
                copy.dispose();
            }
        };
        panel.setOpaque(false);
        panel.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 10));
        JLabel severity = new JLabel(NotificationWidget.icon(entry));
        severity.setVerticalAlignment(SwingConstants.TOP);
        panel.add(severity, BorderLayout.WEST);
        JPanel lines = PopupElements.column();
        JLabel message = PopupElements.label("");
        String summary = entry.message().replaceAll("\\s+", " ");
        if (summary.length() > 160) summary = summary.substring(0, 157) + "…";
        PopupElements.wrappedText(message, summary, 270);
        lines.add(message);
        lines.add(Box.createVerticalStrut(4));
        lines.add(PopupElements.row(PopupElements.link("Show details", null, details), null));
        panel.add(lines, BorderLayout.CENTER);
        JPanel dismiss = new JPanel(new BorderLayout());
        dismiss.setOpaque(false);
        dismiss.add(PopupElements.icon(Icons.CLOSE_ICON, "Dismiss alert", this::close), BorderLayout.NORTH);
        panel.add(dismiss, BorderLayout.EAST);
        // Moving between child controls must not expire an alert underneath a click.
        var hover = new MouseAdapter() {
            @Override public void mouseEntered(MouseEvent event) { timeout.stop(); }
            @Override public void mouseExited(MouseEvent event) { timeout.restart(); }
        };
        installHover(panel, hover);
        var size = panel.getPreferredSize();
        Point bar = SwingUtilities.convertPoint(anchor, 0, 0, layer);
        panel.setBounds(Math.max(8, layer.getWidth() - size.width - 12), Math.max(8, bar.y - size.height - 8), size.width, size.height);
        layer.add(panel, JLayeredPane.PALETTE_LAYER);
        layer.addComponentListener(resize);
        layer.revalidate();
        layer.repaint();
        timeout.start();
    }

    private static void installHover(Component component, MouseAdapter hover) {
        component.addMouseListener(hover);
        if (component instanceof Container container)
            for (Component child : container.getComponents()) installHover(child, hover);
    }

    @Override public void close() {
        timeout.stop();
        entryId = 0;
        if (layer == null) return;
        layer.removeComponentListener(resize);
        layer.remove(panel);
        layer.repaint();
        layer = null;
        panel = null;
    }
}
