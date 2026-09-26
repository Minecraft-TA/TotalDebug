package com.github.minecraft_ta.totalDebugCompanion.ui;

import com.github.minecraft_ta.totalDebugCompanion.ui.components.FlatIconButton;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.ThemeColors;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.SwingConstants;
import javax.swing.UIManager;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Color;
import java.awt.Font;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;

/** Shared spacing and controls, without owning popup state or actions. */
public final class PopupElements {
    private PopupElements() {}

    public static JLabel label(String text) {
        JLabel label = new JLabel(text);
        label.putClientProperty("html.disable", true);
        label.setFont(UIManager.getFont("Label.font").deriveFont(Font.PLAIN));
        return label;
    }

    public static void wrappedText(JLabel label, String text, int width) {
        var metrics = label.getFontMetrics(label.getFont());
        if (!text.contains("\n") && metrics.stringWidth(text) <= width) {
            label.putClientProperty("html.disable", true);
            label.setText(text);
            return;
        }
        StringBuilder html = new StringBuilder("<html>");
        for (String line : text.replace("\r\n", "\n").split("\n", -1)) {
            int start = 0;
            while (start < line.length()) {
                int end = start;
                int pixels = 0;
                int space = -1;
                while (end < line.length()) {
                    int codePoint = line.codePointAt(end);
                    int nextWidth = pixels + metrics.charWidth(codePoint);
                    if (nextWidth > width && end > start) break;
                    pixels = nextWidth;
                    if (Character.isWhitespace(codePoint)) space = end;
                    end += Character.charCount(codePoint);
                }
                if (end < line.length() && space > start) end = space + 1;
                html.append(line.substring(start, end).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace(" ", "&nbsp;"));
                if (end < line.length()) html.append("<br>");
                start = end;
            }
            html.append("<br>");
        }
        html.setLength(html.length() - 4);
        html.append("</html>");
        label.putClientProperty("html.disable", false);
        label.setText(html.toString());
    }

    public static JPanel column() {
        JPanel panel = new JPanel();
        panel.setOpaque(false);
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);
        return panel;
    }

    public static JPanel row(Component left, Component right) {
        JPanel panel = new JPanel(new BorderLayout(12, 0));
        panel.setOpaque(false);
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(left, BorderLayout.CENTER);
        if (right != null) panel.add(right, BorderLayout.EAST);
        return panel;
    }

    public static JButton icon(Icon icon, String name, Runnable action) {
        JButton button = new FlatIconButton(icon, false);
        button.setMargin(UiMetrics.compactButtonMargin());
        button.setToolTipText(name);
        button.getAccessibleContext().setAccessibleName(name);
        button.addActionListener(event -> action.run());
        return button;
    }

    public static JButton link(String text, Icon icon, Runnable action) {
        JButton button = icon(icon, text, action);
        button.setText(text);
        button.putClientProperty("html.disable", true);
        button.setFont(UIManager.getFont("Label.font").deriveFont(Font.PLAIN));
        button.setHorizontalTextPosition(SwingConstants.LEFT);
        button.setHorizontalAlignment(SwingConstants.LEFT);
        button.setMargin(UiMetrics.compactButtonMargin());
        button.setForeground(new Color(ThemeColors.link().getRGB()));
        button.addPropertyChangeListener("UI", event -> button.setForeground(new Color(ThemeColors.link().getRGB())));
        return button;
    }

    public static void content(JPopupMenu popup, Component content) {
        popup.setBorder(BorderFactory.createCompoundBorder(PopupChrome.border(), PopupChrome.contentPadding()));
        popup.add(content);
    }

    public static void showAbove(JPopupMenu popup, Component invoker) {
        PopupChrome.showMenu(popup, invoker, true);
    }

    public static void copy(String text) {
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
    }
}
