package com.github.minecraft_ta.totalDebugCompanion.ui.components.global;

import com.github.minecraft_ta.totalDebugCompanion.Icons;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.AnimatedFlatSVGIcon;

import javax.swing.*;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.ThemeColors;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.DynamicMatteBorder;
import java.awt.*;

public class BottomInformationBar extends JPanel {

    private final JLabel infoLabel = new JLabel();

    public BottomInformationBar() {
        super();
        setLayout(new BoxLayout(this, BoxLayout.LINE_AXIS));

        // Font size lives in File > Settings; it is a global setting and every editor tab used to
        // render its own copy of the slider.
        add(this.infoLabel);
        add(Box.createHorizontalGlue());

        setBorder(DynamicMatteBorder.rule(1, 0, 0, 0));
    }

    public void setDefaultInfoText(String text, Color color) {
        this.infoLabel.setText(text);
        this.infoLabel.setForeground(color);
    }

    public void setDefaultInfoText(String text) {
        this.infoLabel.setIcon(Icons.INFORMATION);
        this.infoLabel.setText(text);
    }

    public void setProcessInfoText(String text) {
        this.infoLabel.setIcon(new AnimatedFlatSVGIcon("icons/process"));
        this.infoLabel.setText(text);
    }

    public void setSuccessInfoText(String text) {
        this.infoLabel.setIcon(Icons.SUCCESS);
        this.infoLabel.setText(text);
    }

    public void setFailureInfoText(String text) {
        this.infoLabel.setIcon(Icons.ERROR);
        this.infoLabel.setText(text);
    }

    public void clearInfoText() {
        this.infoLabel.setIcon(null);
        setDefaultInfoText("", ThemeColors.mutedText());
    }
}
