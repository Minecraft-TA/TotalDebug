package com.github.minecraft_ta.totalDebugCompanion.ui.views;

import com.formdev.flatlaf.util.UIScale;

import java.awt.*;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.*;
import javax.swing.text.BadLocationException;
import javax.swing.text.JTextComponent;

public class BaseListPopup<ITEM> extends BasePopup {

    private Consumer<ITEM> enterKeyListener;
    private final Listener listener = new Listener();
    private final JScrollPane scrollPane = new JScrollPane();
    {
        scrollPane.setPreferredSize(new Dimension(450, 200));
        scrollPane.setBorder(BorderFactory.createEmptyBorder());
    }

    private JList<ITEM> list;
    private Component invoker;
    private static final int MAXIMUM_LIST_WIDTH = 600;
    private static final int MINIMUM_LIST_WIDTH = 200;
    private static final int MAXIMUM_LIST_HEIGHT = 200;
    private int boundXPos = -1;

    protected Object selectionKey(ITEM item) { return item; }
    protected boolean acceptsTab() { return false; }

    public BaseListPopup(Window owner) {
        super(owner);
        add(this.scrollPane, BorderLayout.CENTER);
    }

    public void show(JTextComponent invoker) {
        show(invoker, Alignment.BOTTOM_RIGHT);
    }

    public void show(JTextComponent invoker, Alignment alignment) {
        try {
            var cursorRect = invoker.modelToView2D(invoker.getCaretPosition());
            show(invoker, (int) cursorRect.getX(), (int) (cursorRect.getY() + cursorRect.getHeight()), alignment);
        } catch (BadLocationException e) {
            System.getLogger(BaseListPopup.class.getName()).log(
                    System.Logger.Level.WARNING, "Unable to position popup at the caret", e);
        }
    }

    @Override
    public void show(Component invoker, int x, int y, Alignment alignment) {
        if (!(invoker instanceof JTextComponent))
            throw new IllegalArgumentException("invoker must be a JTextComponent");
        if (this.list.getModel().getSize() == 0)
            return;

        super.show(invoker, x, y, alignment);
        this.boundXPos = x;
        bindInvoker((JTextComponent) invoker);
    }

    protected final void bindInvoker(JTextComponent invoker) {
        removeKeyListener();
        this.invoker = invoker;
        invoker.addKeyListener(this.listener);
    }

    @Override
    public void setVisible(boolean b) {
        super.setVisible(b);
        if (!b) {
            removeKeyListener();
            this.boundXPos = -1;
            this.enterKeyListener = null;
        }
    }

    private void removeKeyListener() {
        if (this.invoker != null)
            this.invoker.removeKeyListener(this.listener);
    }

    public void setList(JList<ITEM> list) {
        this.list = list;

        list.setSelectionBackground(UIManager.getColor("List.selectionBackground"));
        list.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ESCAPE) {
                    setVisible(false);
                } else if (e.getKeyCode() == KeyEvent.VK_ENTER && e.getModifiersEx() == 0) {
                    runEnterKeyListener();
                }
            }
        });
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() != 2)
                    return;

                runEnterKeyListener();
            }
        });

        this.scrollPane.setViewportView(list);
        pack();
    }

    public void setItems(List<? extends ITEM> items) {
        var prevWidth = getWidth();
        Object selection = isVisible() && list.getSelectedValue() != null ? selectionKey(list.getSelectedValue()) : null;

        var model = ((DefaultListModel<ITEM>) this.list.getModel());
        model.removeAllElements();
        model.addAll(items);
        this.list.setSelectedIndex(0);
        if (selection != null) {
            for (int i = 0; i < items.size(); i++) {
                if (selection.equals(selectionKey(items.get(i)))) { this.list.setSelectedIndex(i); break; }
            }
        }
        this.scrollPane.getVerticalScrollBar().setValue(0);

        Dimension listSize = this.list.getPreferredSize();
        int viewportWidth = Math.clamp(listSize.width, UIScale.scale(MINIMUM_LIST_WIDTH), UIScale.scale(MAXIMUM_LIST_WIDTH));
        int viewportHeight = Math.min(UIScale.scale(MAXIMUM_LIST_HEIGHT), listSize.height);
        var preferredSize = new Dimension(viewportWidth, viewportHeight);
        if (listSize.width > viewportWidth) preferredSize.height += this.scrollPane.getHorizontalScrollBar().getPreferredSize().height;
        if (listSize.height > viewportHeight) preferredSize.width += this.scrollPane.getVerticalScrollBar().getPreferredSize().width;
        this.scrollPane.setPreferredSize(preferredSize);
        setMinimumSize(UIScale.scale(new Dimension(MINIMUM_LIST_WIDTH, 20)));
        pack();
        if (list.getSelectedIndex() >= 0) list.ensureIndexIsVisible(list.getSelectedIndex());

        if (this.boundXPos != -1) {
            setLocation(getX() - (getWidth() - prevWidth) / 2, getY());
        }
    }

    @Override
    public void setFont(Font f) {
        this.list.setFont(f);
    }

    public void setKeyEnterListener(Consumer<ITEM> listener) {
        this.enterKeyListener = listener;
    }

    public boolean isInvokedBy(Component component) {
        return this.invoker == component;
    }

    private void runEnterKeyListener() {
        if (this.list.getSelectedIndex() == -1 || this.enterKeyListener == null)
            return;

        var val = this.list.getSelectedValue();
        this.enterKeyListener.accept(val);
    }

    private class Listener extends KeyAdapter {

        @Override
        public void keyPressed(KeyEvent e) {
            if (e.getModifiersEx() == 0 && (e.getKeyCode() == KeyEvent.VK_ENTER || acceptsTab() && e.getKeyCode() == KeyEvent.VK_TAB)) {
                runEnterKeyListener();
                e.consume();
            } else if (e.getKeyCode() == KeyEvent.VK_UP || e.getKeyCode() == KeyEvent.VK_DOWN) {
                var selectedIndex = list.getSelectedIndex() + (e.getKeyCode() == KeyEvent.VK_UP ? -1 : 1);
                if (selectedIndex > list.getModel().getSize() - 1)
                    selectedIndex = 0;
                else if (selectedIndex < 0)
                    selectedIndex = list.getModel().getSize() - 1;

                list.setSelectedIndex(selectedIndex);
                list.scrollRectToVisible(list.getCellBounds(selectedIndex, selectedIndex));
                e.consume();
            } else if (e.getKeyCode() == KeyEvent.VK_ESCAPE) {
                setVisible(false);
                e.consume();
            }
        }
    }
}
