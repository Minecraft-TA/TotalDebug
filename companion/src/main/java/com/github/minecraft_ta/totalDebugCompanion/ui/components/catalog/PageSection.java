package com.github.minecraft_ta.totalDebugCompanion.ui.components.catalog;

import com.github.minecraft_ta.totalDebugCompanion.ui.UiMetrics;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.SectionHeading;

import com.github.minecraft_ta.totalDebugCompanion.ui.components.subject.LinkLabel;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.ThemeColors;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.List;

/** A titled part of a catalog page, headed like the fact sections above it. */
final class PageSection extends JPanel {
    /** A row of links: the role in secondary text, then each link, such as a mod's configuration files. */
    record LinkRow(String role, List<LinkLabel> links) {
    }

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

    /** Roles in secondary text beside their links, in the grid of the fact sections. */
    static JComponent linkRows(List<LinkRow> rows) {
        JPanel body = new JPanel(new GridBagLayout());
        for (int row = 0; row < rows.size(); row++) {
            JLabel role = new JLabel(rows.get(row).role());
            ThemeColors.keepForeground(role, ThemeColors::secondaryText);
            GridBagConstraints constraints = new GridBagConstraints();
            constraints.gridy = row;
            constraints.anchor = GridBagConstraints.WEST;
            constraints.insets = new Insets(3, 0, 3, 12);
            body.add(role, constraints);
            JPanel links = new JPanel();
            links.setLayout(new BoxLayout(links, BoxLayout.X_AXIS));
            for (LinkLabel link : rows.get(row).links()) {
                if (links.getComponentCount() > 0) links.add(Box.createHorizontalStrut(12));
                links.add(link);
            }
            constraints.gridx = 1;
            constraints.insets = new Insets(3, 0, 3, 0);
            body.add(links, constraints);
        }
        GridBagConstraints filler = new GridBagConstraints();
        filler.gridx = 2;
        filler.weightx = 1;
        body.add(Box.createHorizontalGlue(), filler);
        return body;
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
