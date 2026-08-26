package com.github.minecraft_ta.totalDebugCompanion.ui.components.editors;

import com.github.minecraft_ta.totalDebugCompanion.Icons;
import org.fife.ui.rtextarea.Gutter;
import org.fife.ui.rtextarea.GutterIconInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

final class BreakpointGutterMarkers {
    private final Gutter gutter;
    private final List<GutterIconInfo> installed = new ArrayList<>();

    BreakpointGutterMarkers(Gutter gutter) {
        this.gutter = Objects.requireNonNull(gutter, "gutter");
        this.gutter.setIconRowHeaderEnabled(true);
    }

    void setLines(List<Integer> displayedLines) {
        clear();
        for (int line : displayedLines) {
            try {
                this.installed.add(this.gutter.addLineTrackingIcon(
                        line - 1,
                        Icons.BREAKPOINT,
                        "Breakpoint at line " + line
                ));
            } catch (javax.swing.text.BadLocationException ignored) {
            }
        }
    }

    void dispose() {
        clear();
    }

    private void clear() {
        for (GutterIconInfo marker : this.installed) {
            this.gutter.removeTrackingIcon(marker);
        }
        this.installed.clear();
    }
}
