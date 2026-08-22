package com.github.minecraft_ta.totalDebugCompanion.util;

import com.github.minecraft_ta.totalDebugCompanion.ui.views.MainWindow;
import org.fife.ui.rtextarea.RTextScrollPane;

import javax.swing.*;
import javax.swing.border.Border;
import javax.swing.text.*;
import java.awt.*;

public class UIUtils {

    public static void setTextAndKeepCaret(JTextComponent component, String text) {
        var caretPos = component.getCaretPosition();
        component.setText(text);
        if (caretPos <= text.length())
            component.setCaretPosition(caretPos);
    }

    public static void setIntegerTextFieldEnabled(JTextComponent component) {
        var document = component.getDocument();
        if (!(document instanceof AbstractDocument))
            throw new IllegalArgumentException();

        ((AbstractDocument) document).setDocumentFilter(new DocumentFilter() {
            @Override
            public void replace(FilterBypass fb, int offset, int length, String text, AttributeSet attrs) {
                var newText = new StringBuffer(component.getText()).replace(offset, offset + length, text).toString();
                try {
                    //Allow empty field and starting with a minus
                    if (!newText.isBlank() && !newText.equals("-"))
                        Integer.parseInt(newText);
                    super.replace(fb, offset, length, text, attrs);
                } catch (Throwable ignored) {}
            }
        });
    }

    public static int getFontWidth(JComponent component, String s) {
        return component.getFontMetrics(component.getFont()).stringWidth(s);
    }

    public static <T extends JComponent> T withBorder(T c, Border border) {
        c.setBorder(border);
        return c;
    }

    public static Component topAndBottomStickyLayout(Component top, Component bottom) {
        return verticalLayout(top, Box.createVerticalGlue(), bottom);
    }

    public static JComponent verticalLayout(Component... component) {
        var box = Box.createVerticalBox();
        for (Component c : component) {
            box.add(c);
        }

        return box;
    }

    public static Box horizontalLayout(Component... component) {
        var box = Box.createHorizontalBox();
        for (Component c : component) {
            box.add(c, Box.LEFT_ALIGNMENT);
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
        frame.setLocation(dim.x + (dim.width / 2 - frame.getSize().width / 2), dim.height / 2 - frame.getSize().height / 2);
    }

    public static void centerViewportOnRange(RTextScrollPane scrollPane, int offsetStart, int offsetEnd) {
        try {
            var rect = scrollPane.getTextArea().modelToView2D(offsetStart);
            var viewport = scrollPane.getViewport();

            var viewSize = viewport.getViewSize();
            var extentSize = viewport.getExtentSize();

            int rangeWidth = UIUtils.getFontWidth(scrollPane.getTextArea(), "9".repeat(offsetEnd - offsetStart));
            int x = (int) Math.max(0, rect.getX() - ((extentSize.width - rangeWidth) / 2f));
            x = Math.min(x, viewSize.width - extentSize.width);
            int y = (int) Math.max(0, rect.getY() - ((extentSize.height - rect.getHeight()) / 2f));
            y = Math.min(y, viewSize.height - extentSize.height);

            viewport.setViewPosition(new Point(x, y));
            scrollPane.getTextArea().setCaretPosition(offsetStart);
        } catch (BadLocationException e) {
            e.printStackTrace();
        }
    }
}
