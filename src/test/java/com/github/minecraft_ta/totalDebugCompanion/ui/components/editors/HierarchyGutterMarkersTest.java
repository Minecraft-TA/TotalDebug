package com.github.minecraft_ta.totalDebugCompanion.ui.components.editors;

import com.github.minecraft_ta.totalDebugCompanion.bytecode.insight.HierarchyRelation;
import com.github.minecraft_ta.totalDebugCompanion.jdt.insight.SourceDeclaration;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rtextarea.IconRowHeader;
import org.fife.ui.rtextarea.LineNumberList;
import org.fife.ui.rtextarea.RTextScrollPane;
import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Point;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HierarchyGutterMarkersTest {

    @Test
    void placesLineNumbersOutsideHierarchyIcons() throws Exception {
        AtomicReference<Component> lineNumbers = new AtomicReference<>();
        AtomicReference<Component> hierarchyIcons = new AtomicReference<>();

        SwingUtilities.invokeAndWait(() -> {
            RTextScrollPane scrollPane = new RTextScrollPane(new RSyntaxTextArea("class Sample {}"));
            var gutter = scrollPane.getGutter();
            var markers = new HierarchyGutterMarkers(gutter, new EmptyHandler());
            gutter.setSize(gutter.getPreferredSize());
            gutter.doLayout();
            for (Component component : gutter.getComponents()) {
                if (component instanceof LineNumberList) {
                    lineNumbers.set(component);
                } else if (component instanceof IconRowHeader) {
                    hierarchyIcons.set(component);
                }
            }
            markers.dispose();
        });

        assertInstanceOf(LineNumberList.class, lineNumbers.get());
        assertInstanceOf(IconRowHeader.class, hierarchyIcons.get());
        assertTrue(lineNumbers.get().getX() < hierarchyIcons.get().getX());
    }

    private static final class EmptyHandler implements HierarchyGutterMarkers.Handler {
        @Override
        public void navigate(SourceDeclaration declaration, HierarchyRelation relation, int count) {
        }

        @Override
        public void preview(
                SourceDeclaration declaration,
                HierarchyRelation relation,
                int count,
                boolean mixedBaseRelations,
                Component invoker,
                Point point
        ) {
        }

        @Override
        public void hidePreview() {
        }
    }
}
