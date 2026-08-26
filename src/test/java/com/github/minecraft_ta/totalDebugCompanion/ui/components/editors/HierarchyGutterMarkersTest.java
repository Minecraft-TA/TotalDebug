package com.github.minecraft_ta.totalDebugCompanion.ui.components.editors;

import com.github.minecraft_ta.totalDebugCompanion.bytecode.insight.HierarchyRelation;
import com.github.minecraft_ta.totalDebugCompanion.debugger.DebugEngine;
import com.github.minecraft_ta.totalDebugCompanion.jdt.insight.SourceDeclaration;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rtextarea.IconRowHeader;
import org.fife.ui.rtextarea.LineNumberList;
import org.fife.ui.rtextarea.RTextScrollPane;
import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.image.BufferedImage;
import java.util.List;
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

    @Test
    void paintsLineNumbersWhenHierarchyAndBreakpointMarkersShareTheGutter() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var editor = new RSyntaxTextArea("first\nsecond\nthird");
            editor.setSize(320, 120);
            var scrollPane = new RTextScrollPane(editor);
            scrollPane.setLineNumbersEnabled(true);
            var gutter = scrollPane.getGutter();
            gutter.setForeground(Color.WHITE);
            gutter.setBackground(new Color(0, 0, 0, 0));
            gutter.setOpaque(false);

            var hierarchyMarkers = new HierarchyGutterMarkers(gutter, new EmptyHandler());
            var breakpointMarkers = new BreakpointGutterMarkers(gutter);
            breakpointMarkers.setBreakpoints(List.of(new DebugEngine.SourceBreakpoint(2)));

            LineNumberList lineNumbers = null;
            for (Component component : gutter.getComponents()) {
                if (component instanceof LineNumberList candidate) {
                    lineNumbers = candidate;
                    break;
                }
            }
            assertInstanceOf(LineNumberList.class, lineNumbers);
            lineNumbers.setSize(Math.max(1, lineNumbers.getPreferredSize().width), editor.getHeight());

            BufferedImage image = new BufferedImage(
                    lineNumbers.getWidth(),
                    lineNumbers.getHeight(),
                    BufferedImage.TYPE_INT_ARGB
            );
            Graphics2D graphics = image.createGraphics();
            lineNumbers.paint(graphics);
            graphics.dispose();

            int[] pixels = image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth());
            long visiblePixels = java.util.Arrays.stream(pixels)
                    .filter(pixel -> (pixel >>> 24) != 0)
                    .count();
            assertTrue(visiblePixels > 0, "The line-number component painted no visible pixels");

            breakpointMarkers.dispose();
            hierarchyMarkers.dispose();
        });
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
