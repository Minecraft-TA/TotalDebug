package com.github.minecraft_ta.totalDebugCompanion.ui.components.global;

import com.github.minecraft_ta.totalDebugCompanion.navigation.BreadcrumbSegment;
import com.github.minecraft_ta.totalDebugCompanion.navigation.NavigationTarget;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.ThemeColors;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Font;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.font.TextAttribute;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

final class BreadcrumbBar extends JPanel {
    private static final String SEPARATOR = "  ›  ";

    private final Consumer<NavigationTarget> navigator;
    private List<BreadcrumbSegment> segments = List.of();

    BreadcrumbBar(Consumer<NavigationTarget> navigator) {
        this.navigator = Objects.requireNonNull(navigator, "navigator");
        setLayout(new BoxLayout(this, BoxLayout.LINE_AXIS));
        setOpaque(false);
        setAlignmentY(Component.CENTER_ALIGNMENT);
    }

    void setSegments(List<BreadcrumbSegment> segments) {
        this.segments = List.copyOf(Objects.requireNonNull(segments, "segments"));
        rebuild();
    }

    void applyTheme() {
        rebuild();
    }

    private void rebuild() {
        removeAll();
        for (int index = 0; index < this.segments.size(); index++) {
            if (index > 0) {
                JLabel separator = new JLabel(SEPARATOR);
                separator.setForeground(ThemeColors.mutedText());
                add(separator);
            }
            add(component(this.segments.get(index)));
        }
        revalidate();
        repaint();
    }

    private Component component(BreadcrumbSegment segment) {
        if (segment.target() == null) {
            JLabel label = new JLabel(segment.label());
            label.setForeground(ThemeColors.mutedText());
            label.setToolTipText(segment.tooltip().isBlank() ? null : segment.tooltip());
            return label;
        }

        JButton button = new JButton(segment.label());
        button.setBorder(BorderFactory.createEmptyBorder());
        button.setContentAreaFilled(false);
        button.setFocusable(false);
        button.setForeground(ThemeColors.mutedText());
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        button.setToolTipText(segment.tooltip().isBlank() ? null : segment.tooltip());
        Font normalFont = button.getFont();
        Font underlinedFont = normalFont.deriveFont(Map.of(TextAttribute.UNDERLINE, TextAttribute.UNDERLINE_ON));
        button.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent event) {
                button.setFont(underlinedFont);
                button.setForeground(ThemeColors.accent());
            }

            @Override
            public void mouseExited(MouseEvent event) {
                button.setFont(normalFont);
                button.setForeground(ThemeColors.mutedText());
            }
        });
        button.addActionListener(event -> this.navigator.accept(segment.target()));
        return button;
    }
}
