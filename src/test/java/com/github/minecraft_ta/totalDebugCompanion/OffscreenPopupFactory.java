package com.github.minecraft_ta.totalDebugCompanion;

import javax.swing.JLayeredPane;
import javax.swing.Popup;
import javax.swing.PopupFactory;
import javax.swing.RootPaneContainer;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Point;
import java.util.ArrayList;
import java.util.List;

/** Keeps harness popups inside the off-screen owner instead of creating desktop windows. */
final class OffscreenPopupFactory extends PopupFactory {
    private Placement nextPlacement;
    private final List<ActivePopup> activePopups = new ArrayList<>();

    static void paintActivePopups(Graphics2D graphics, Component target) {
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

    static void expectAt(Component owner, Point location) {
        if (PopupFactory.getSharedInstance() instanceof OffscreenPopupFactory factory) {
            factory.nextPlacement = new Placement(owner, Alignment.AT, new Point(location));
        }
    }

    static void expectBelowEnd(Component owner) {
        if (PopupFactory.getSharedInstance() instanceof OffscreenPopupFactory factory) {
            factory.nextPlacement = new Placement(owner, Alignment.BELOW_END, null);
        }
    }

    static void expectAboveEnd(Component owner) {
        if (PopupFactory.getSharedInstance() instanceof OffscreenPopupFactory factory) {
            factory.nextPlacement = new Placement(owner, Alignment.ABOVE_END, null);
        }
    }

    @Override
    public Popup getPopup(Component owner, Component contents, int x, int y) {
        java.awt.Window window = owner instanceof java.awt.Window candidate
                ? candidate
                : SwingUtilities.getWindowAncestor(owner);
        if (!(window instanceof RootPaneContainer rootPaneContainer)) {
            throw new IllegalStateException("UI harness popup has no root-pane owner");
        }
        JLayeredPane layeredPane = rootPaneContainer.getLayeredPane();
        Dimension size = contents.getPreferredSize();
        Placement placement = this.nextPlacement;
        this.nextPlacement = null;
        if (placement == null || placement.owner() != owner) {
            throw new IllegalStateException("UI harness popup was opened without an explicit off-screen placement");
        }
        Point ownerLocation = switch (placement.alignment()) {
            case AT -> placement.location();
            case BELOW_END -> new Point(owner.getWidth() - size.width, owner.getHeight());
            case ABOVE_END -> new Point(Math.min(0, owner.getWidth() - size.width), -size.height);
        };
        Point location = SwingUtilities.convertPoint(owner, ownerLocation, layeredPane);
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
