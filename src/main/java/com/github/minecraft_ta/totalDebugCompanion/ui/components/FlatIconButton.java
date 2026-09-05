package com.github.minecraft_ta.totalDebugCompanion.ui.components;

import com.formdev.flatlaf.extras.FlatSVGIcon;

import javax.swing.*;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.ThemeColors;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public class FlatIconButton extends JButton {

    private final List<Consumer<Boolean>> toggleListeners = new ArrayList<>();
    private boolean state;

    public FlatIconButton(FlatSVGIcon icon, boolean toggleable) {
        super(icon);
        setBorderPainted(false);
        setFocusPainted(false);
        setContentAreaFilled(false);

        addMouseListener(new MouseAdapter() {

            @Override
            public void mouseClicked(MouseEvent e) {
                if (toggleable) {
                    toggle();
                }

                if (state) {
                    setContentAreaFilled(true);
                }

                setBackground(ThemeColors.hoverBackground());
            }

            @Override
            public void mouseEntered(MouseEvent e) {
                setContentAreaFilled(true);
                setBackground(ThemeColors.hoverBackground());
            }

            @Override
            public void mouseExited(MouseEvent e) {
                if (toggleable && state)
                    setBackground(ThemeColors.toggledBackground());
                setContentAreaFilled(false);
            }
        });
    }

    public void toggle() {
        setToggled(!state);
        toggleListeners.forEach(l -> l.accept(state));
    }

    public void setToggled(boolean b) {
        this.state = b;

        if (state) {
            ((FlatSVGIcon) getIcon()).setColorFilter(new FlatSVGIcon.ColorFilter((c) -> ThemeColors.accent()));
        } else {
            ((FlatSVGIcon) getIcon()).setColorFilter(null);
        }
    }

    @Override
    public void setIcon(Icon icon) {
        super.setIcon(new FlatSVGIcon((FlatSVGIcon) icon));
    }

    public void addToggleListener(Consumer<Boolean> listener) {
        this.toggleListeners.add(listener);
    }
}
