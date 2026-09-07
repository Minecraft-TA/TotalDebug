package com.github.minecraft_ta.totalDebugCompanion.ui.components.editors;

import com.github.minecraft_ta.totalDebugCompanion.bytecode.insight.HierarchyRelation;
import com.github.minecraft_ta.totalDebugCompanion.jdt.insight.SourceDeclaration;
import com.github.minecraft_ta.totalDebugCompanion.ui.HierarchyPresentation;
import org.fife.ui.rtextarea.Gutter;
import org.fife.ui.rtextarea.GutterIconInfo;
import org.fife.ui.rtextarea.IconRowHeader;
import org.fife.ui.rtextarea.IconRowEvent;
import org.fife.ui.rtextarea.IconRowListener;

import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.text.BadLocationException;
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
        void navigate(SourceDeclaration declaration, HierarchyRelation relation, int count);

        void preview(
                SourceDeclaration declaration,
                HierarchyRelation relation,
                int count,
                boolean mixedBaseRelations
        );

        void hidePreview();
    }

    private final Gutter gutter;
    private final Handler handler;
    private final IconRowHeader iconRowHeader;
    private final Timer previewTimer;
    private final List<GutterIconInfo> installed = new ArrayList<>();
    private final Map<GutterIconInfo, Marker> markers = new IdentityHashMap<>();
    private Marker hovered;

    private final MouseMotionAdapter hoverMotion = new MouseMotionAdapter() {
        @Override
        public void mouseMoved(MouseEvent event) {
            updateHovered(markerAt(event.getPoint()));
        }
    };
    private final MouseAdapter hoverExit = new MouseAdapter() {
        @Override
        public void mouseExited(MouseEvent event) {
            updateHovered(null);
        }
    };

    HierarchyGutterMarkers(EditorGutter editorGutter, Handler handler) {
        Objects.requireNonNull(editorGutter, "editorGutter");
        this.gutter = editorGutter.component();
        this.handler = Objects.requireNonNull(handler, "handler");
        this.iconRowHeader = editorGutter.hierarchyIcons();
        this.previewTimer = new Timer(350, event -> showHoveredPreview());
        this.previewTimer.setRepeats(false);
        this.gutter.addIconRowListener(this);
        this.iconRowHeader.addMouseMotionListener(this.hoverMotion);
        this.iconRowHeader.addMouseListener(this.hoverExit);
    }

    void setEntries(List<CodeVisionEntry> entries) {
        updateHovered(null);
        clear();
        for (CodeVisionEntry entry : entries) {
            HierarchyRelation relation = entry.insight().primaryGutterRelation().orElse(null);
            if (relation == null) {
                continue;
            }
            int count = entry.insight().count(relation.direction());
            Marker marker = new Marker(
                    entry.declaration(),
                    relation,
                    count,
                    entry.insight().count(HierarchyRelation.IMPLEMENTS) > 0
                            && entry.insight().count(HierarchyRelation.OVERRIDES) > 0
            );
            try {
                GutterIconInfo info = this.gutter.addOffsetTrackingIcon(
                        entry.declaration().markerOffset(),
                        HierarchyPresentation.gutterIcon(relation),
                        null
                );
                this.installed.add(info);
                this.markers.put(info, marker);
            } catch (BadLocationException ignored) {
            }
        }
    }

    void dispose() {
        updateHovered(null);
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
        updateHovered(null);
        this.handler.navigate(marker.declaration(), marker.relation(), marker.count());
        event.consume();
    }

    private void updateHovered(Marker marker) {
        if (Objects.equals(this.hovered, marker)) {
            return;
        }
        this.previewTimer.stop();
        this.handler.hidePreview();
        this.hovered = marker;
        this.iconRowHeader.setCursor(marker == null
                ? Cursor.getDefaultCursor()
                : Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        if (marker != null) {
            this.previewTimer.restart();
        }
    }

    private void showHoveredPreview() {
        Marker marker = this.hovered;
        if (marker == null) {
            return;
        }
        this.handler.preview(
                marker.declaration(),
                marker.relation(),
                marker.count(),
                marker.mixedBaseRelations()
        );
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

    private record Marker(
            SourceDeclaration declaration,
            HierarchyRelation relation,
            int count,
            boolean mixedBaseRelations
    ) {
    }

}
