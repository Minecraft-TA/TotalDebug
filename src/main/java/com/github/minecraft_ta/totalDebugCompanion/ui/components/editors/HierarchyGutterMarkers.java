package com.github.minecraft_ta.totalDebugCompanion.ui.components.editors;

import com.github.minecraft_ta.totalDebugCompanion.Icons;
import com.github.minecraft_ta.totalDebugCompanion.jdt.insight.SourceDeclaration;
import org.fife.ui.rtextarea.Gutter;
import org.fife.ui.rtextarea.GutterIconInfo;
import org.fife.ui.rtextarea.IconRowEvent;
import org.fife.ui.rtextarea.IconRowListener;

import javax.swing.text.BadLocationException;
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
    }

    private final Gutter gutter;
    private final Handler handler;
    private final List<GutterIconInfo> installed = new ArrayList<>();
    private final Map<GutterIconInfo, Marker> markers = new IdentityHashMap<>();

    HierarchyGutterMarkers(Gutter gutter, Handler handler) {
        this.gutter = Objects.requireNonNull(gutter, "gutter");
        this.handler = Objects.requireNonNull(handler, "handler");
        this.gutter.setIconRowHeaderEnabled(true);
        this.gutter.addIconRowListener(this);
    }

    void setEntries(List<CodeVisionEntry> entries) {
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
                        marker.tooltip()
                );
                this.installed.add(info);
                this.markers.put(info, marker);
            } catch (BadLocationException ignored) {
            }
        }
    }

    void dispose() {
        clear();
        this.gutter.removeIconRowListener(this);
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
        Marker marker = this.markers.get(event.getIconInfo());
        if (marker == null) {
            return;
        }
        if (marker.implementations() > 0) {
            this.handler.showImplementations(marker.declaration());
        } else {
            this.handler.showBaseMethods(marker.declaration());
        }
        event.consume();
    }

    private record Marker(SourceDeclaration declaration, int implementations, int bases) {
        private String tooltip() {
            if (this.implementations > 0 && this.bases > 0) {
                return this.implementations + " implementations; overrides " + this.bases
                        + " base methods. Click for implementations, Ctrl+U for bases";
            }
            if (this.implementations > 0) {
                return this.implementations + (this.implementations == 1
                        ? " implementation"
                        : " implementations");
            }
            return "Overrides " + this.bases + (this.bases == 1 ? " base method" : " base methods");
        }
    }

}
