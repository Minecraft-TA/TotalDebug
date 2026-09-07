package com.github.minecraft_ta.totalDebugCompanion.ui.components.editors;

import org.fife.ui.rtextarea.Gutter;
import org.fife.ui.rtextarea.IconRowHeader;
import org.fife.ui.rtextarea.LineNumberList;

import java.awt.BorderLayout;
import java.awt.Component;
import java.util.Objects;

/** Owns the column layout shared by line numbers, breakpoints and hierarchy markers. */
final class EditorGutter {
    private final Gutter gutter;
    private final LineNumberList lineNumbers;
    private final IconRowHeader hierarchyIcons;

    EditorGutter(Gutter gutter) {
        this.gutter = Objects.requireNonNull(gutter, "gutter");
        this.gutter.setIconRowHeaderEnabled(true);
        this.lineNumbers = find(this.gutter, LineNumberList.class, "line numbers");
        this.hierarchyIcons = find(this.gutter, IconRowHeader.class, "icon row header");

        this.gutter.remove(this.lineNumbers);
        this.gutter.remove(this.hierarchyIcons);
        this.gutter.add(this.lineNumbers, BorderLayout.LINE_START);
        this.gutter.add(this.hierarchyIcons, BorderLayout.CENTER);
        this.gutter.revalidate();
    }

    Gutter component() {
        return this.gutter;
    }

    LineNumberList lineNumbers() {
        return this.lineNumbers;
    }

    IconRowHeader hierarchyIcons() {
        return this.hierarchyIcons;
    }

    private static <T extends Component> T find(
            Gutter gutter,
            Class<T> type,
            String description
    ) {
        for (Component child : gutter.getComponents()) {
            if (type.isInstance(child)) {
                return type.cast(child);
            }
        }
        throw new IllegalStateException("RSyntaxTextArea gutter has no " + description);
    }
}
