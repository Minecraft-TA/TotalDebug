package com.github.minecraft_ta.totalDebugCompanion.ui.components.editors;

import com.github.minecraft_ta.totalDebugCompanion.Icons;
import com.github.minecraft_ta.totalDebugCompanion.jdt.insight.SourceDeclaration;
import org.fife.ui.rtextarea.Gutter;
import org.fife.ui.rtextarea.GutterIconInfo;
import org.fife.ui.rtextarea.IconRowHeader;
import org.fife.ui.rtextarea.IconRowEvent;
import org.fife.ui.rtextarea.IconRowListener;

import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.text.BadLocationException;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Point;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Owns the implementation and override markers installed in one editor gutter. */
final class HierarchyGutterMarkers implements IconRowListener {
    interface Handler {
        void showImplementations(SourceDeclaration declaration);

        void showBaseMethods(SourceDeclaration declaration);

        void previewImplementations(SourceDeclaration declaration, Component invoker, Point point);

        void previewBaseMethods(SourceDeclaration declaration, Component invoker, Point point);

        void hidePreview();
    }

    private final Gutter gutter;
    private final Handler handler;
    private final IconRowHeader iconRowHeader;
    private final Timer previewTimer;
    private final List<GutterIconInfo> installed = new ArrayList<>();
    private final Map<GutterIconInfo, Marker> markers = new IdentityHashMap<>();
    private Marker hovered;
    private Point hoverPoint;

    private final MouseMotionAdapter hoverMotion = new MouseMotionAdapter() {
        @Override
        public void mouseMoved(MouseEvent event) {
            updateHovered(markerAt(event.getPoint()), event.getPoint());
        }
    };
    private final MouseAdapter hoverExit = new MouseAdapter() {
        @Override
        public void mouseExited(MouseEvent event) {
            updateHovered(null, null);
        }
    };

    HierarchyGutterMarkers(Gutter gutter, Handler handler) {
        this.gutter = Objects.requireNonNull(gutter, "gutter");
        this.handler = Objects.requireNonNull(handler, "handler");
        this.gutter.setIconRowHeaderEnabled(true);
        this.iconRowHeader = findIconRowHeader(this.gutter);
        this.previewTimer = new Timer(350, event -> showHoveredPreview());
        this.previewTimer.setRepeats(false);
        this.gutter.addIconRowListener(this);
        this.iconRowHeader.addMouseMotionListener(this.hoverMotion);
        this.iconRowHeader.addMouseListener(this.hoverExit);
    }

    void setEntries(List<CodeVisionEntry> entries) {
        updateHovered(null, null);
        clear();
        for (CodeVisionEntry entry : entries) {
            int implementations = entry.insight().implementationCount();
            int bases = entry.insight().baseCount();
            if (implementations == 0 && bases == 0) {
                continue;
            }
            Marker marker = new Marker(entry.declaration(), implementations, bases);
            try {
                GutterIconInfo info = this.gutter.addOffsetTrackingIcon(
                        entry.declaration().markerOffset(),
                        implementations > 0 ? Icons.IMPLEMENTED_METHOD : Icons.IMPLEMENTING_METHOD,
                        null
                );
                this.installed.add(info);
                this.markers.put(info, marker);
            } catch (BadLocationException ignored) {
            }
        }
    }

    void dispose() {
        updateHovered(null, null);
        clear();
        this.gutter.removeIconRowListener(this);
        this.iconRowHeader.removeMouseMotionListener(this.hoverMotion);
        this.iconRowHeader.removeMouseListener(this.hoverExit);
    }

    private void clear() {
        for (GutterIconInfo info : this.installed) {
            this.gutter.removeTrackingIcon(info);
        }
        this.installed.clear();
        this.markers.clear();
    }

    @Override
    public void bookmarkAdded(IconRowEvent event) {
    }

    @Override
    public void bookmarkRemoved(IconRowEvent event) {
    }

    @Override
    public void mouseClicked(IconRowEvent event, java.awt.event.MouseEvent mouseEvent) {
        Marker marker = markerFor(event.getIconsAtLine());
        if (marker == null) {
            return;
        }
        updateHovered(null, null);
        if (marker.implementations() > 0) {
            this.handler.showImplementations(marker.declaration());
        } else {
            this.handler.showBaseMethods(marker.declaration());
        }
        event.consume();
    }

    private void updateHovered(Marker marker, Point point) {
        if (Objects.equals(this.hovered, marker)) {
            this.hoverPoint = point == null ? null : new Point(point);
            return;
        }
        this.previewTimer.stop();
        this.handler.hidePreview();
        this.hovered = marker;
        this.hoverPoint = point == null ? null : new Point(point);
        this.iconRowHeader.setCursor(marker == null
                ? Cursor.getDefaultCursor()
                : Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        if (marker != null) {
            this.previewTimer.restart();
        }
    }

    private void showHoveredPreview() {
        Marker marker = this.hovered;
        Point point = this.hoverPoint;
        if (marker == null || point == null) {
            return;
        }
        Point popupPoint = new Point(this.iconRowHeader.getWidth() + 8, point.y + 4);
        if (marker.implementations() > 0) {
            this.handler.previewImplementations(marker.declaration(), this.iconRowHeader, popupPoint);
        } else {
            this.handler.previewBaseMethods(marker.declaration(), this.iconRowHeader, popupPoint);
        }
    }

    private Marker markerAt(Point iconPoint) {
        try {
            Point gutterPoint = SwingUtilities.convertPoint(this.iconRowHeader, iconPoint, this.gutter);
            return markerFor(this.gutter.getTrackingIcons(gutterPoint));
        } catch (BadLocationException ignored) {
            return null;
        }
    }

    private Marker markerFor(GutterIconInfo[] infos) {
        for (int index = infos.length - 1; index >= 0; index--) {
            Marker marker = this.markers.get(infos[index]);
            if (marker != null) {
                return marker;
            }
        }
        return null;
    }

    private static IconRowHeader findIconRowHeader(Gutter gutter) {
        for (Component child : gutter.getComponents()) {
            if (child instanceof IconRowHeader iconRowHeader) {
                return iconRowHeader;
            }
        }
        throw new IllegalStateException("RSyntaxTextArea gutter has no icon row header");
    }

    private record Marker(SourceDeclaration declaration, int implementations, int bases) {
    }

}
