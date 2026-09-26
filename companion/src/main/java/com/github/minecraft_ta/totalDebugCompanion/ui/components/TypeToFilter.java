package com.github.minecraft_ta.totalDebugCompanion.ui.components;

import javax.swing.AbstractAction;
import javax.swing.JComponent;
import javax.swing.JList;
import javax.swing.JTable;
import javax.swing.KeyStroke;
import javax.swing.text.JTextComponent;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.util.function.Supplier;

/**
 * Lets a list or table be filtered by typing while it has focus: typed text goes into its filter field, Backspace
 * edits it and Escape clears it. Down in the filter returns to the list, selecting its first row.
 */
public final class TypeToFilter {
    private TypeToFilter() {
    }

    public static void install(JComponent view, JTextComponent filter) {
        forwardTyping(view, () -> filter);
        filter.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0), "focusFilteredView");
        filter.getActionMap().put("focusFilteredView", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent event) {
                if (view instanceof JList<?> list && list.getSelectedIndex() < 0 && list.getModel().getSize() > 0) {
                    list.setSelectedIndex(0);
                } else if (view instanceof JTable table && table.getSelectedRow() < 0 && table.getRowCount() > 0) {
                    table.setRowSelectionInterval(0, 0);
                }
                view.requestFocusInWindow();
            }
        });
    }

    /**
     * Sends typing in {@code source}, such as a tab strip, to the filter {@code target} names at that moment; nothing
     * happens while it names none.
     */
    public static void forwardTyping(JComponent source, Supplier<JTextComponent> target) {
        source.addKeyListener(new KeyAdapter() {
            @Override
            public void keyTyped(KeyEvent event) {
                JTextComponent filter = target.get();
                char typed = event.getKeyChar();
                if (filter == null || typed == KeyEvent.CHAR_UNDEFINED || Character.isISOControl(typed)
                        || (event.getModifiersEx() & (InputEvent.CTRL_DOWN_MASK | InputEvent.ALT_DOWN_MASK
                        | InputEvent.META_DOWN_MASK)) != 0) {
                    return;
                }
                filter.setCaretPosition(filter.getDocument().getLength());
                filter.replaceSelection(String.valueOf(typed));
                filter.requestFocusInWindow();
                event.consume();
            }

            @Override
            public void keyPressed(KeyEvent event) {
                JTextComponent filter = target.get();
                if (filter == null) return;
                String text = filter.getText();
                if (event.getKeyCode() == KeyEvent.VK_BACK_SPACE && !text.isEmpty()) {
                    filter.setText(text.substring(0, text.length() - 1));
                    filter.requestFocusInWindow();
                    event.consume();
                } else if (event.getKeyCode() == KeyEvent.VK_ESCAPE && !text.isEmpty()) {
                    filter.setText("");
                    event.consume();
                }
            }
        });
    }
}
