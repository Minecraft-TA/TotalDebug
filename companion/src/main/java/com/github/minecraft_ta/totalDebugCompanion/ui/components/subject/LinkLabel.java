package com.github.minecraft_ta.totalDebugCompanion.ui.components.subject;

import com.github.minecraft_ta.totalDebugCompanion.ui.theme.ThemeColors;

import javax.swing.JLabel;
import javax.swing.Icon;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.Cursor;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.font.TextAttribute;
import java.util.Map;
import java.util.Objects;

/** Text that opens its target when clicked, drawn in the link color and underlined while hovered. */
public final class LinkLabel extends JLabel {
    public LinkLabel(String text, Icon icon, String tooltip, Runnable open) {
        super(text, icon, LEADING);
        Objects.requireNonNull(open, "open");
        setToolTipText(tooltip);
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        style(this, true, false);
        addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                if (SwingUtilities.isLeftMouseButton(event)) open.run();
            }

            @Override
            public void mouseEntered(MouseEvent event) {
                style(LinkLabel.this, true, true);
            }

            @Override
            public void mouseExited(MouseEvent event) {
                style(LinkLabel.this, true, false);
            }
        });
    }

    @Override
    public void updateUI() {
        super.updateUI();
        style(this, true, false);
    }

    /** Applies the link appearance to any label; unlinked labels keep the regular font. */
    public static void style(JLabel label, boolean linked, boolean hovered) {
        if (linked) label.setForeground(ThemeColors.link());
        var font = UIManager.getFont("Label.font");
        if (font == null) return;
        label.setFont(font.deriveFont(Map.of(TextAttribute.UNDERLINE, linked && hovered ? TextAttribute.UNDERLINE_ON : -1)));
    }
}
