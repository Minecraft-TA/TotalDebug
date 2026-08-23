package com.github.minecraft_ta.totalDebugCompanion.ui.components;

import javax.swing.*;
import java.awt.*;

public class LabelWithButtonTabComponent extends JPanel {

    private final JTabbedPane pane;

    public LabelWithButtonTabComponent(JTabbedPane pane, Icon icon) {
        super(new FlowLayout(FlowLayout.LEFT, 0, 0));
        this.pane = pane;
        setOpaque(false);

        JLabel label = new JLabel() {
            public String getText() {
                int i = pane.indexOfTabComponent(LabelWithButtonTabComponent.this);
                if (i != -1) {
                    return pane.getTitleAt(i);
                }
                return null;
            }
        };

        label.setIcon(icon);
        label.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 6));
        add(label);

        JButton button = new CloseButton();
        button.addActionListener((e) -> {
            int i = this.pane.indexOfTabComponent(this);
            if (i != -1) {
                this.pane.removeTabAt(i);
            }
        });
        add(button);
        setBorder(BorderFactory.createEmptyBorder());
    }

}
