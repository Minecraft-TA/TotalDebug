package com.github.minecraft_ta.totalDebugCompanion.ui.components.treeView;

import com.github.minecraft_ta.totalDebugCompanion.ui.theme.DynamicMatteBorder;

import javax.swing.*;
import javax.swing.border.CompoundBorder;
import java.awt.*;

import static com.formdev.flatlaf.util.UIScale.scale;

public class FileTreeViewHeader extends JPanel {

    public FileTreeViewHeader() {
        super();
        setLayout(new BoxLayout(this, BoxLayout.X_AXIS));
        Dimension size = new Dimension(10000, scale(29));
        setMinimumSize(new Dimension(0, size.height));
        setPreferredSize(size);
        setMaximumSize(size);
        setBorder(new CompoundBorder(
                DynamicMatteBorder.separatorRule(0, 0, 1, 0),
                BorderFactory.createEmptyBorder(0, scale(10), 0, scale(8))
        ));
        JLabel files = new JLabel("Files");
        files.setFont(files.getFont().deriveFont(Font.BOLD));
        add(files);
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
