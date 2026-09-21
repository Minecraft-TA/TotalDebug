package com.github.minecraft_ta.totalDebugCompanion.ui;

import com.github.minecraft_ta.totalDebugCompanion.ui.theme.DynamicMatteBorder;

import javax.swing.BorderFactory;
import javax.swing.SwingUtilities;
import javax.swing.JPopupMenu;
import javax.swing.JRootPane;
import javax.swing.border.Border;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.GraphicsConfiguration;
import java.awt.Insets;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.geom.Rectangle2D;

/** Small shared pieces of popup chrome and placement; popup ownership stays with each surface. */
public final class PopupChrome {
    public static final int CONTENT_PADDING = 8;

    private PopupChrome() {
    }

    public static Border border() {
        return DynamicMatteBorder.rule(1, 1, 1, 1);
    }

    public static Border contentPadding() {
        return BorderFactory.createEmptyBorder(
                CONTENT_PADDING,
                CONTENT_PADDING,
                CONTENT_PADDING,
                CONTENT_PADDING
        );
    }

    /** Places a popup below its complete source-line band, or above it when there is no room below. */
    public static void placeAdjacent(Window popup, Component invoker, Rectangle2D sourceLine) {
        Point invokerLocation = invoker.getLocationOnScreen();
        Rectangle anchor = new Rectangle(
                invokerLocation.x + (int) Math.floor(sourceLine.getX()),
                invokerLocation.y + (int) Math.floor(sourceLine.getY()),
                Math.max(1, (int) Math.ceil(sourceLine.getWidth())),
                Math.max(1, (int) Math.ceil(sourceLine.getHeight()))
        );
        Rectangle screen = usableScreenBounds(invoker);
        Point location = isOnConfiguredScreen(invoker, screen)
                ? adjacentLocation(anchor, popup.getSize(), screen)
                : new Point(anchor.x, anchor.y + anchor.height);
        popup.setLocation(location);
    }

    public static Point clampToScreen(Component invoker, Point desired, Dimension size) {
        Rectangle screen = usableScreenBounds(invoker);
        return isOnConfiguredScreen(invoker, screen) ? clamp(desired, size, screen) : desired;
    }

    /** Status popups stay within their owning application, even on a split desktop. */
    public static void showMenu(JPopupMenu popup, Component invoker, boolean above) {
        JRootPane root = SwingUtilities.getRootPane(invoker);
        Point origin = invoker.getLocationOnScreen();
        Rectangle bounds = new Rectangle(root.getLocationOnScreen(), root.getSize());
        Rectangle screen = usableScreenBounds(invoker);
        if (bounds.intersects(screen)) bounds = bounds.intersection(screen);
        // State may have changed earlier in this EDT event, before deferred revalidation.
        invalidateLayout(popup);
        popup.setPreferredSize(null);
        Dimension preferred = popup.getPreferredSize();
        Dimension size = new Dimension(Math.min(preferred.width, bounds.width), Math.min(preferred.height, bounds.height));
        popup.setPopupSize(size);
        Point desired = new Point(origin.x + invoker.getWidth() - size.width,
                above ? origin.y - size.height : origin.y + invoker.getHeight());
        Point location = clamp(desired, size, bounds);
        popup.show(invoker, location.x - origin.x, location.y - origin.y);
    }

    private static void invalidateLayout(Container container) {
        for (Component child : container.getComponents())
            if (child instanceof Container nested) invalidateLayout(nested);
        container.invalidate();
    }

    public static Point centeredLocation(Rectangle bounds, Dimension size) {
        return new Point(
                bounds.x + (bounds.width - size.width) / 2,
                bounds.y + (bounds.height - size.height) / 2
        );
    }

    static Point adjacentLocation(Rectangle anchor, Dimension size, Rectangle screen) {
        int below = anchor.y + anchor.height;
        int above = anchor.y - size.height;
        int y = below + size.height <= screen.y + screen.height ? below : above;
        return clamp(new Point(anchor.x, y), size, screen);
    }

    static Point clamp(Point desired, Dimension size, Rectangle screen) {
        int maximumX = Math.max(screen.x, screen.x + screen.width - size.width);
        int maximumY = Math.max(screen.y, screen.y + screen.height - size.height);
        return new Point(
                Math.clamp(desired.x, screen.x, maximumX),
                Math.clamp(desired.y, screen.y, maximumY)
        );
    }

    private static Rectangle usableScreenBounds(Component invoker) {
        GraphicsConfiguration configuration = invoker.getGraphicsConfiguration();
        Rectangle bounds = new Rectangle(configuration.getBounds());
        Insets insets = Toolkit.getDefaultToolkit().getScreenInsets(configuration);
        bounds.x += insets.left;
        bounds.y += insets.top;
        bounds.width -= insets.left + insets.right;
        bounds.height -= insets.top + insets.bottom;
        return bounds;
    }

    private static boolean isOnConfiguredScreen(Component invoker, Rectangle screen) {
        Window window = SwingUtilities.getWindowAncestor(invoker);
        return window == null || screen.intersects(window.getBounds());
    }
}
