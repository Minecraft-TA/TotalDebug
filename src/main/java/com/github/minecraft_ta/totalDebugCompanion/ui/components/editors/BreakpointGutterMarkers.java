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

    void setBreakpoints(List<com.github.minecraft_ta.totalDebugCompanion.debugger.DebugEngine.SourceBreakpoint> breakpoints) {
        clear();
        for (var breakpoint : breakpoints) {
            try {
                boolean dependent = breakpoint.condition() != null && !breakpoint.condition().isBlank()
                        || breakpoint.hitCondition() != null && !breakpoint.hitCondition().isBlank();
                this.installed.add(this.gutter.addLineTrackingIcon(
                        breakpoint.line() - 1,
                        dependent ? Icons.BREAKPOINT_DEPENDENT : Icons.BREAKPOINT,
                        dependent
                                ? "Conditional breakpoint at line " + breakpoint.line()
                                : "Breakpoint at line " + breakpoint.line()
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
