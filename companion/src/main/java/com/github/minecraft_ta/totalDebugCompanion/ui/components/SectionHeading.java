package com.github.minecraft_ta.totalDebugCompanion.ui.components;

import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSeparator;
import javax.swing.UIManager;
import java.awt.BorderLayout;
import java.awt.Cursor;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/**
 * The heading of a page section: its title in regular text with a rule to its right, as in IntelliJ's settings.
 * A section that collapses shows the tree's chevron and toggles when the heading is clicked; one that does not, such
 * as a group of settings, has neither.
 */
public final class SectionHeading extends JPanel {
    private final JLabel title = new JLabel();
    private boolean collapsible;
    private boolean collapsed;

    /**
     * {@code trailing} sits right of the rule, such as a count of omitted entries; it may be null. {@code toggle}
     * collapses or expands the section, or is null for a heading that does not collapse.
     */
    public SectionHeading(String title, JComponent trailing, Runnable toggle) {
        super(new BorderLayout(8, 0));
        this.title.setText(title);
        this.title.setIconTextGap(4);
        add(this.title, BorderLayout.WEST);
        JPanel rule = new JPanel(new GridBagLayout());
        GridBagConstraints fill = new GridBagConstraints();
        fill.weightx = 1;
        fill.fill = GridBagConstraints.HORIZONTAL;
        rule.add(new JSeparator(), fill);
        add(rule, BorderLayout.CENTER);
        if (trailing != null) add(trailing, BorderLayout.EAST);
        this.collapsible = toggle != null;
        if (this.collapsible) {
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent event) {
                    toggle.run();
                }
            });
        }
        showChevron();
    }

    public void setCollapsed(boolean collapsed) {
        this.collapsed = collapsed;
        showChevron();
    }

    /** The chevron comes from the look and feel, so it follows theme changes. */
    @Override
    public void updateUI() {
        super.updateUI();
        if (this.title != null) showChevron();
    }

    private void showChevron() {
        this.title.setIcon(!this.collapsible ? null : UIManager.getIcon(this.collapsed ? "Tree.collapsedIcon" : "Tree.expandedIcon"));
    }
}
