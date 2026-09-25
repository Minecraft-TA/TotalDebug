package com.github.minecraft_ta.totalDebugCompanion.ui.components.catalog;

import com.github.minecraft_ta.totalDebugCompanion.ui.components.SectionHeading;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;

/** A titled part of a catalog page, headed like the fact sections above it. */
final class PageSection extends JPanel {
    private final JComponent body;
    private final SectionHeading heading;

    PageSection(String title, JComponent body) {
        super(new BorderLayout());
        this.body = body;
        setAlignmentX(Component.LEFT_ALIGNMENT);
        setBorder(BorderFactory.createEmptyBorder(8, 12, 4, 12));
        this.heading = new SectionHeading(title, null, this::toggle);
        add(this.heading, BorderLayout.NORTH);
        body.setBorder(BorderFactory.createEmptyBorder(6, 18, 0, 0));
        add(body, BorderLayout.CENTER);
    }

    private void toggle() {
        this.body.setVisible(!this.body.isVisible());
        this.heading.setCollapsed(!this.body.isVisible());
        revalidate();
    }

    @Override
    public Dimension getMaximumSize() {
        return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
    }
}
