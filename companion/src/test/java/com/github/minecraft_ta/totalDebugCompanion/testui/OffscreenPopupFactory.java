package com.github.minecraft_ta.totalDebugCompanion.testui;

import javax.swing.JLayeredPane;
import javax.swing.JPopupMenu;
import javax.swing.Popup;
import javax.swing.PopupFactory;
import javax.swing.RootPaneContainer;
import javax.swing.SwingUtilities;

import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Window;
import java.util.ArrayList;
import java.util.List;

/** Keeps harness popups inside the off-screen owner instead of creating desktop windows. */
public final class OffscreenPopupFactory extends PopupFactory {
    private Placement nextPlacement;
    private final List<ActivePopup> activePopups = new ArrayList<>();

    public static JPopupMenu showingMenuOrNull() {
        if (PopupFactory.getSharedInstance() instanceof OffscreenPopupFactory factory) {
            for (var popup : factory.activePopups.reversed()) {
                if (popup.contents() instanceof JPopupMenu menu) return menu;
            }
        }
        return null;
    }

    public static JPopupMenu showingMenu() {
        var menu = showingMenuOrNull();
        if (menu == null) throw new AssertionError("No offscreen menu is showing");
        return menu;
    }

    public static void paintActivePopups(Graphics2D graphics, Component target) {
        if (!(PopupFactory.getSharedInstance() instanceof OffscreenPopupFactory factory)) {
            return;
        }
        for (ActivePopup popup : List.copyOf(factory.activePopups)) {
            Point location = SwingUtilities.convertPoint(
                    popup.layeredPane(),
                    popup.contents().getLocation(),
                    target
            );
            Graphics2D popupGraphics = (Graphics2D) graphics.create();
            popupGraphics.translate(location.x, location.y);
            popup.contents().paintAll(popupGraphics);
            popupGraphics.dispose();
        }
    }

    public static void expectAt(Component owner, Point location) {
        if (PopupFactory.getSharedInstance() instanceof OffscreenPopupFactory factory) {
            factory.nextPlacement = new Placement(owner, Alignment.AT, new Point(location));
        }
    }

    public static void expectBelowEnd(Component owner) {
        if (PopupFactory.getSharedInstance() instanceof OffscreenPopupFactory factory) {
            factory.nextPlacement = new Placement(owner, Alignment.BELOW_END, null);
        }
    }

    public static void expectAboveEnd(Component owner) {
        if (PopupFactory.getSharedInstance() instanceof OffscreenPopupFactory factory) {
            factory.nextPlacement = new Placement(owner, Alignment.ABOVE_END, null);
        }
    }

    @Override
    public Popup getPopup(Component owner, Component contents, int x, int y) {
        if (owner == null && contents instanceof JPopupMenu menu) owner = menu.getInvoker();
        if (owner == null) throw new IllegalStateException("Offscreen popup requires an owner");
        Window window = owner instanceof Window candidate
                ? candidate
                : SwingUtilities.getWindowAncestor(owner);
        if (!(window instanceof RootPaneContainer rootPaneContainer)) {
            throw new IllegalStateException("UI harness popup has no root-pane owner");
        }
        JLayeredPane layeredPane = rootPaneContainer.getLayeredPane();
        Dimension size = contents.getPreferredSize();
        Placement placement = this.nextPlacement;
        this.nextPlacement = null;
        if (placement != null && placement.owner() != owner) {
            throw new IllegalStateException("The expected popup owner does not match the actual owner");
        }
        Point ownerLocation = placement == null
                ? new Point(x - owner.getLocationOnScreen().x, y - owner.getLocationOnScreen().y)
                : switch (placement.alignment()) {
            case AT -> placement.location();
            case BELOW_END -> new Point(owner.getWidth() - size.width, owner.getHeight());
            case ABOVE_END -> new Point(Math.min(0, owner.getWidth() - size.width), -size.height);
        };
        Point location = SwingUtilities.convertPoint(owner, ownerLocation, layeredPane);
        Point screenLocation = new Point(location);
        SwingUtilities.convertPointToScreen(screenLocation, layeredPane);
        for (var device : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
            if (device.getDefaultConfiguration().getBounds().intersects(new Rectangle(screenLocation, size))) {
                throw new IllegalStateException("Popup placement overlaps the desktop: requested " + x + "," + y
                        + ", owner " + owner.getLocationOnScreen() + ", window " + window.getBounds()
                        + ", resolved " + screenLocation);
            }
        }
        ActivePopup activePopup = new ActivePopup(contents, layeredPane);

        return new Popup() {
            @Override
            public void show() {
                Container parent = contents.getParent();
                if (parent != null) {
                    parent.remove(contents);
                }
                contents.setBounds(location.x, location.y, size.width, size.height);
                layeredPane.add(contents, JLayeredPane.POPUP_LAYER);
                if (!activePopups.contains(activePopup)) {
                    activePopups.add(activePopup);
                }
                layeredPane.revalidate();
                contents.validate();
                layeredPane.repaint(contents.getBounds());
            }

            @Override
            public void hide() {
                if (contents.getParent() == layeredPane) {
                    activePopups.remove(activePopup);
                    layeredPane.remove(contents);
                    layeredPane.revalidate();
                    layeredPane.repaint(contents.getBounds());
                }
            }
        };
    }

    private enum Alignment {
        AT,
        BELOW_END,
        ABOVE_END
    }

    private record Placement(Component owner, Alignment alignment, Point location) {
    }

    private record ActivePopup(Component contents, JLayeredPane layeredPane) {
    }
}
