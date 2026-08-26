package com.github.minecraft_ta.totalDebugCompanion.ui.components;

import com.formdev.flatlaf.extras.FlatSVGIcon;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.ThemeColors;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

public class AnimatedFlatSVGIcon implements Icon {

    private Component component;
    private int currentIndex;

    private final FlatSVGIcon[] icons;
    private final Timer timer;

    public AnimatedFlatSVGIcon(String folderName) {
        List<FlatSVGIcon> icons = new ArrayList<>();

        int i = 1;
        while (AnimatedFlatSVGIcon.class.getClassLoader().getResource(folderName + "/step_" + i + ".svg") != null) {
            var icon = new FlatSVGIcon(folderName + "/step_" + i + ".svg");
            icon.setColorFilter(new FlatSVGIcon.ColorFilter(color -> ThemeColors.mutedText()));
            icons.add(icon);
            i++;
        }

        if (icons.size() < 1)
            throw new IllegalArgumentException();

        this.icons = icons.toArray(new FlatSVGIcon[0]);

        this.timer = new Timer(100, null);
        this.timer.addActionListener(e -> {
            Component target = this.component;
            this.component = null;
            if (target == null) {
                this.timer.stop();
                return;
            }
            this.currentIndex = (this.currentIndex + 1) % this.icons.length;
            target.repaint();
        });
    }

    @Override
    public void paintIcon(Component c, Graphics g, int x, int y) {
        this.component = c;
        if (!this.timer.isRunning()) {
            this.timer.start();
        }

        this.icons[this.currentIndex].paintIcon(c, g, x, y);
    }

    public void stop() {
        this.timer.stop();
        this.component = null;
        this.currentIndex = 0;
    }

    boolean isRunning() {
        return this.timer.isRunning();
    }

    @Override
    public int getIconWidth() {
        return icons[0].getIconWidth();
    }

    @Override
    public int getIconHeight() {
        return icons[0].getIconHeight();
    }
}
