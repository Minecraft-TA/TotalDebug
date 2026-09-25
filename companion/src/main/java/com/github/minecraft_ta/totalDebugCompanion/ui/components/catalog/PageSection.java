package com.github.minecraft_ta.totalDebugCompanion.ui.components.catalog;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSeparator;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;

/** A titled part of a catalog page, headed like the fact sections above it. */
final class PageSection extends JPanel {
    PageSection(String title, JComponent body) {
        super(new BorderLayout());
        setAlignmentX(Component.LEFT_ALIGNMENT);
        setBorder(BorderFactory.createEmptyBorder(8, 12, 4, 12));
        JLabel heading = new JLabel(title);
        heading.setFont(heading.getFont().deriveFont(Font.BOLD));
        JPanel header = new JPanel(new BorderLayout(8, 0));
        header.add(heading, BorderLayout.WEST);
        JPanel rule = new JPanel(new GridBagLayout());
        GridBagConstraints fill = new GridBagConstraints();
        fill.weightx = 1;
        fill.fill = GridBagConstraints.HORIZONTAL;
        rule.add(new JSeparator(), fill);
        header.add(rule, BorderLayout.CENTER);
        add(header, BorderLayout.NORTH);
        body.setBorder(BorderFactory.createEmptyBorder(6, 18, 0, 0));
        add(body, BorderLayout.CENTER);
    }

    @Override
    public Dimension getMaximumSize() {
        return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
    }
}
