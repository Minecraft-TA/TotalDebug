package com.github.minecraft_ta.totalDebugCompanion.ui.components.editors;

import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import java.awt.Point;
import java.awt.event.MouseEvent;
import java.util.function.Function;

/** Editor overlays take tooltip priority only inside their painted bounds. */
class EditorTextArea extends RSyntaxTextArea {
    private Function<Point, String> overlayTooltip;

    void setOverlayTooltip(Function<Point, String> tooltip) { overlayTooltip = tooltip; }

    @Override protected String getToolTipTextImpl(MouseEvent event) {
        String tooltip = overlayTooltip == null ? null : overlayTooltip.apply(event.getPoint());
        return tooltip != null ? tooltip : super.getToolTipTextImpl(event);
    }
}
