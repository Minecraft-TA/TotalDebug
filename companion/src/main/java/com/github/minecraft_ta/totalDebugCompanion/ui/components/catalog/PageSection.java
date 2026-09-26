package com.github.minecraft_ta.totalDebugCompanion.ui.components.catalog;

import com.github.minecraft_ta.totalDebugCompanion.ui.UiMetrics;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.SectionHeading;

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
        setBorder(UiMetrics.pagePadding(8, 4));
        this.heading = new SectionHeading(title, null, this::toggle);
        add(this.heading, BorderLayout.NORTH);
        body.setBorder(UiMetrics.sectionBodyPadding());
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
