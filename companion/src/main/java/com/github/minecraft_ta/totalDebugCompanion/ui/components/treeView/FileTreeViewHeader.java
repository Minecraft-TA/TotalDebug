package com.github.minecraft_ta.totalDebugCompanion.ui.components.treeView;

import com.github.minecraft_ta.totalDebugCompanion.ui.UiMetrics;
import javax.swing.*;
import java.awt.*;

import static com.formdev.flatlaf.util.UIScale.scale;

public class FileTreeViewHeader extends JPanel {

    public FileTreeViewHeader() {
        super();
        setLayout(new BoxLayout(this, BoxLayout.X_AXIS));
        Dimension size = new Dimension(10000, scale(UiMetrics.TAB_HEIGHT) + scale(UiMetrics.TAB_SEPARATOR_HEIGHT));
        setMinimumSize(new Dimension(0, size.height));
        setPreferredSize(size);
        setMaximumSize(size);
        setBorder(BorderFactory.createEmptyBorder(0, scale(10), 0, scale(8)));
        add(new JLabel("Project"));
    }

    @Override
    public void updateUI() {
        super.updateUI();
        Color background = UIManager.getColor("ToolWindow.background");
        if (background != null) {
            setBackground(background);
        }
    }
}
