package com.github.minecraft_ta.totalDebugCompanion.ui.speedsearch;

import com.github.minecraft_ta.totalDebugCompanion.Icons;
import com.github.minecraft_ta.totalDebugCompanion.ui.PopupChrome;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.FlatIconTextField;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.ThemeColors;

import javax.swing.JComponent;
import javax.swing.JLayeredPane;
import javax.swing.LookAndFeel;
import javax.swing.JPanel;
import javax.swing.JRootPane;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Rectangle;

final class SpeedSearchPopup extends JPanel {
    private static final int MINIMUM_WIDTH = 180;
    private static final int MAXIMUM_WIDTH = 420;
    private static final int EDGE_GAP = 6;

    private final FlatIconTextField field = new FlatIconTextField(Icons.SEARCH_ICON);
    private JLayeredPane layeredPane;
    private LookAndFeel lookAndFeel;

    SpeedSearchPopup() {
        super(new BorderLayout());
        setBorder(PopupChrome.border());
        this.field.setFocusable(false);
        this.field.setRequestFocusEnabled(false);
        add(this.field);
    }

    void showFor(JComponent target, String query, boolean hasMatches) {
        refreshLookAndFeel();
        this.field.setText(query);
        Color normal = UIManager.getColor("TextField.foreground");
        this.field.setForeground(hasMatches ? normal == null ? ThemeColors.text() : normal : ThemeColors.error());
        this.field.setCaretPosition(query.length());

        JRootPane rootPane = SwingUtilities.getRootPane(target);
        if (rootPane == null || !target.isShowing()) {
            hidePopup();
            return;
        }
        JLayeredPane replacement = rootPane.getLayeredPane();
        if (this.layeredPane != replacement) {
            hidePopup();
            this.layeredPane = replacement;
            this.layeredPane.add(this, JLayeredPane.POPUP_LAYER);
        } else if (getParent() == null) {
            this.layeredPane.add(this, JLayeredPane.POPUP_LAYER);
        }

        Dimension size = preferredSize(query);
        Rectangle anchor = SwingUtilities.convertRectangle(target, target.getVisibleRect(), this.layeredPane);
        int x = Math.max(EDGE_GAP, Math.min(
                anchor.x + EDGE_GAP,
                this.layeredPane.getWidth() - size.width - EDGE_GAP
        ));
        int y = Math.max(EDGE_GAP, Math.min(
                anchor.y + EDGE_GAP,
                this.layeredPane.getHeight() - size.height - EDGE_GAP
        ));
        setBounds(x, y, size.width, size.height);
        setVisible(true);
        this.layeredPane.revalidate();
        this.layeredPane.repaint(getBounds());
    }

    void hidePopup() {
        if (this.layeredPane == null) {
            return;
        }
        Rectangle previousBounds = getBounds();
        this.layeredPane.remove(this);
        this.layeredPane.repaint(previousBounds);
        this.layeredPane = null;
    }

    private void refreshLookAndFeel() {
        LookAndFeel current = UIManager.getLookAndFeel();
        if (this.lookAndFeel == current) {
            return;
        }
        this.lookAndFeel = current;
        SwingUtilities.updateComponentTreeUI(this);
    }

    private Dimension preferredSize(String query) {
        int contentWidth = this.field.getFontMetrics(this.field.getFont()).stringWidth(query) + 54;
        int width = Math.max(MINIMUM_WIDTH, Math.min(MAXIMUM_WIDTH, contentWidth));
        Dimension preferred = super.getPreferredSize();
        return new Dimension(width, preferred.height);
    }
}
