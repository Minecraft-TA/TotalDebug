package com.github.minecraft_ta.totalDebugCompanion.ui.components.global;

import com.formdev.flatlaf.ui.FlatMenuBarBorder;

import javax.swing.*;
import java.awt.*;

import static com.formdev.flatlaf.util.UIScale.scale;

public class SimpleMenuBarBorder extends FlatMenuBarBorder {

    @Override
    public Insets getBorderInsets(Component c, Insets insets) {
        super.getBorderInsets(c, insets);
        Insets margin = (c instanceof JMenuBar) ? ((JMenuBar) c).getMargin() : new Insets(0, 0, 0, 0);
        insets.bottom = scale(margin.bottom + 1);
        return insets;
    }

    @Override
    protected boolean showBottomSeparator(Component c) {
        return true;
    }
}
