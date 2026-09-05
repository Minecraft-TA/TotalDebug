package com.github.minecraft_ta.totalDebugCompanion.util;

import com.github.minecraft_ta.totalDebugCompanion.ui.views.MainWindow;
import com.github.minecraft_ta.totalDebugCompanion.ui.PopupChrome;
import org.fife.ui.rtextarea.RTextScrollPane;

import javax.swing.*;
import javax.swing.text.*;
import java.awt.*;

public class UIUtils {
    private static final double NAVIGATION_TARGET_VERTICAL_POSITION = 1.0 / 3.0;

    public static int getFontWidth(JComponent component, String s) {
        return component.getFontMetrics(component.getFont()).stringWidth(s);
    }

    public static JComponent verticalLayout(Component... component) {
        var box = Box.createVerticalBox();
        for (Component c : component) {
            box.add(c);
        }

        return box;
    }

    public static String getText(JTextComponent c) {
        try {
            return c.getDocument().getText(0, c.getDocument().getLength());
        } catch (BadLocationException e) {
            throw new RuntimeException(e);
        }
    }

    public static void focusWindow(JFrame frame) {
        WindowsWindowActivator.activate(frame);
    }

    public static void centerJFrame(JFrame frame) {
        var gc = MainWindow.INSTANCE.getGraphicsConfiguration();
        var dim = gc.getBounds();
        frame.setLocation(PopupChrome.centeredLocation(dim, frame.getSize()));
    }

    public static void positionViewportOnRange(RTextScrollPane scrollPane, int offsetStart, int offsetEnd) {
        try {
            var rect = scrollPane.getTextArea().modelToView2D(offsetStart);
            var viewport = scrollPane.getViewport();

            var viewSize = viewport.getViewSize();
            var extentSize = viewport.getExtentSize();

            int rangeWidth = UIUtils.getFontWidth(scrollPane.getTextArea(), "9".repeat(offsetEnd - offsetStart));
            int x = (int) Math.max(0, rect.getX() - ((extentSize.width - rangeWidth) / 2f));
            x = Math.min(x, Math.max(0, viewSize.width - extentSize.width));
            int targetContext = (int) Math.round(
                    (extentSize.height - rect.getHeight()) * NAVIGATION_TARGET_VERTICAL_POSITION
            );
            int y = (int) Math.max(0, rect.getY() - targetContext);
            y = Math.min(y, Math.max(0, viewSize.height - extentSize.height));

            viewport.setViewPosition(new Point(x, y));
            scrollPane.getTextArea().setCaretPosition(offsetStart);
        } catch (BadLocationException e) {
            e.printStackTrace();
        }
    }
}
