package com.github.minecraft_ta.totalDebugCompanion.ui.components.global;

import com.github.minecraft_ta.totalDebugCompanion.ui.theme.DynamicMatteBorder;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.ThinSplitPane;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JSplitPane;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;

/** Owns the main workspace geometry and every separator between its regions. */
public final class WorkspacePanel extends JPanel {
    private static final int TREE_WIDTH = 350;
    private JComponent notificationPanel;

    public void setNotificationPanel(JComponent panel) {
        notificationPanel = panel;
        panel.setBorder(BorderFactory.createCompoundBorder(DynamicMatteBorder.separatorRule(0, 1, 0, 0), panel.getBorder()));
        add(panel, BorderLayout.EAST);
    }

    @Override public void doLayout() {
        if (notificationPanel != null)
            notificationPanel.setPreferredSize(new Dimension(Math.min(420, getWidth() / 2), 0));
        super.doLayout();
    }

    public WorkspacePanel(
            JComponent treeHeader,
            Component treeContent,
            Component editorContent,
            JComponent statusBar
    ) {
        super(new BorderLayout());
        setBorder(DynamicMatteBorder.separatorRule(1, 0, 0, 0));

        treeHeader.setBorder(BorderFactory.createCompoundBorder(
                DynamicMatteBorder.separatorRule(0, 0, 1, 0),
                treeHeader.getBorder()
        ));
        statusBar.setBorder(BorderFactory.createCompoundBorder(
                DynamicMatteBorder.separatorRule(1, 0, 0, 0),
                statusBar.getBorder()
        ));

        JPanel tree = new JPanel(new BorderLayout());
        tree.add(treeHeader, BorderLayout.NORTH);
        tree.add(treeContent, BorderLayout.CENTER);

        JSplitPane splitPane = new ThinSplitPane(tree, editorContent);
        splitPane.setDividerLocation(TREE_WIDTH);

        add(splitPane, BorderLayout.CENTER);
        add(statusBar, BorderLayout.SOUTH);
    }

}
