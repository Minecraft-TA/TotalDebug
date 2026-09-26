package com.github.minecraft_ta.totalDebugCompanion.ui.components.subject;

import com.github.minecraft_ta.totalDebugCompanion.ui.UiMetrics;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.DynamicMatteBorder;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.ThemeColors;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.border.CompoundBorder;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.util.List;

/**
 * The top of every subject page: its icon and name, a line of identifying text and links, and the page's controls
 * on the right.
 */
public final class SubjectHeader extends JPanel {
    public static final int ICON_SIZE = UiMetrics.previewPixels(UiMetrics.HEADER_ICON_SIZE);

    private final JLabel icon = new JLabel();
    private final JLabel title = new JLabel();
    private final JPanel subtitle = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
    private final JPanel controls = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));

    public SubjectHeader() {
        super(new BorderLayout(12, 0));
        this.title.putClientProperty("FlatLaf.styleClass", "h3");
        this.icon.setPreferredSize(new Dimension(ICON_SIZE, ICON_SIZE));
        this.icon.setHorizontalAlignment(SwingConstants.CENTER);
        this.icon.setVerticalAlignment(SwingConstants.CENTER);

        JPanel text = new JPanel();
        text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));
        this.title.setAlignmentX(Component.LEFT_ALIGNMENT);
        this.subtitle.setAlignmentX(Component.LEFT_ALIGNMENT);
        this.subtitle.setVisible(false);
        text.add(this.title);
        text.add(this.subtitle);

        JPanel identity = new JPanel(new BorderLayout(10, 0));
        identity.add(this.icon, BorderLayout.WEST);
        identity.add(text, BorderLayout.CENTER);
        add(identity, BorderLayout.WEST);
        add(this.controls, BorderLayout.EAST);
        setBorder(new CompoundBorder(
                DynamicMatteBorder.separatorRule(0, 0, 1, 0),
                UiMetrics.pagePadding(8, 8)
        ));
    }

    public void setTitle(String text) {
        this.title.setText(text);
    }

    public String title() {
        return this.title.getText();
    }

    public void setIcon(Icon value) {
        this.icon.setIcon(value);
        this.icon.setPreferredSize(new Dimension(value == null ? ICON_SIZE : Math.max(ICON_SIZE, value.getIconWidth()),
                value == null ? ICON_SIZE : Math.max(ICON_SIZE, value.getIconHeight())));
        revalidate();
    }

    /** Replaces the identifying line; text parts are drawn muted and separated from their neighbors. */
    public void setSubtitle(List<JComponent> parts) {
        this.subtitle.removeAll();
        for (JComponent part : parts) {
            if (this.subtitle.getComponentCount() > 0) this.subtitle.add(Box.createHorizontalStrut(12));
            if (part instanceof JLabel label && !(part instanceof LinkLabel)) {
                ThemeColors.keepForeground(label, ThemeColors::secondaryText);
            }
            this.subtitle.add(part);
        }
        this.subtitle.setVisible(!parts.isEmpty());
        this.subtitle.revalidate();
        this.subtitle.repaint();
    }

    /** A text part of the identifying line. */
    public static JLabel text(String value) {
        return new JLabel(value);
    }

    public void addControl(JComponent control) {
        this.controls.add(control);
    }
}
