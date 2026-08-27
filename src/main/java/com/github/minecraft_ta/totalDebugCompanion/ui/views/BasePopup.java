package com.github.minecraft_ta.totalDebugCompanion.ui.views;

import com.github.minecraft_ta.totalDebugCompanion.ui.PopupChrome;

import javax.swing.*;
import java.awt.*;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.HierarchyBoundsAdapter;
import java.awt.event.HierarchyEvent;

public class BasePopup extends JWindow {

    private Component activeInvoker;
    private final FocusAdapter focusListener = new FocusAdapter() {
        @Override
        public void focusLost(FocusEvent event) {
            setVisible(false);
        }
    };
    private final HierarchyBoundsAdapter hierarchyBoundsListener = new HierarchyBoundsAdapter() {
        @Override
        public void ancestorMoved(HierarchyEvent event) {
            setVisible(false);
        }

        @Override
        public void ancestorResized(HierarchyEvent event) {
            setVisible(false);
        }
    };

    public BasePopup(Window owner) {
        super(owner);
        setLayout(new BorderLayout());
        setAlwaysOnTop(true);
        setFocusableWindowState(false);
    }

    public void show(Component invoker, int x, int y) {
        show(invoker, x, y, Alignment.BOTTOM_RIGHT);
    }

    public void show(Component invoker, int x, int y, Alignment alignment) {
        var base = invoker.getLocationOnScreen();
        x = base.x + x;
        y = base.y + y;
        switch (alignment) {
            case BOTTOM_RIGHT -> {}
            case BOTTOM_CENTER -> x -= getWidth() / 2;
            case TOP_CENTER -> {
                x -= getWidth() / 2;
                y -= getHeight();
            }
        }

        showAt(invoker, PopupChrome.clampToScreen(invoker, new Point(x, y), getSize()));
    }

    protected final void showAdjacent(Component invoker, Rectangle sourceLine) {
        detachInvoker();
        PopupChrome.placeAdjacent(this, invoker, sourceLine);
        super.setVisible(true);
        attachInvoker(invoker);
    }

    @Override
    public void setVisible(boolean visible) {
        if (!visible) {
            detachInvoker();
        }
        super.setVisible(visible);
    }

    @Override
    public void dispose() {
        detachInvoker();
        super.dispose();
    }

    private void showAt(Component invoker, Point location) {
        detachInvoker();
        setLocation(location);
        super.setVisible(true);
        attachInvoker(invoker);
    }

    private void attachInvoker(Component invoker) {
        this.activeInvoker = invoker;
        invoker.addFocusListener(this.focusListener);
        invoker.addHierarchyBoundsListener(this.hierarchyBoundsListener);
    }

    private void detachInvoker() {
        if (this.activeInvoker == null) {
            return;
        }
        this.activeInvoker.removeFocusListener(this.focusListener);
        this.activeInvoker.removeHierarchyBoundsListener(this.hierarchyBoundsListener);
        this.activeInvoker = null;
    }

    public enum Alignment {
        TOP_CENTER,
        BOTTOM_RIGHT,
        BOTTOM_CENTER
    }
}
