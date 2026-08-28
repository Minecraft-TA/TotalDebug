package com.github.minecraft_ta.totalDebugCompanion.ui.components.editors;

import com.github.minecraft_ta.totalDebugCompanion.Icons;
import com.github.minecraft_ta.totalDebugCompanion.bytecode.insight.HierarchyRelation;
import com.github.minecraft_ta.totalDebugCompanion.debugger.DebugEngine;
import com.github.minecraft_ta.totalDebugCompanion.debugger.DebuggerSessionController;
import com.github.minecraft_ta.totalDebugCompanion.jdt.insight.SourceDeclaration;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rtextarea.IconRowHeader;
import org.fife.ui.rtextarea.LineNumberList;
import org.fife.ui.rtextarea.RTextScrollPane;
import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import javax.swing.JLayer;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HierarchyGutterMarkersTest {

    @Test
    void paintsEachBreakpointSnapshotWithoutWaitingForTheNextUpdate() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var editor = new RSyntaxTextArea("first\nsecond\nthird");
            editor.setSize(320, 120);
            var scrollPane = new RTextScrollPane(editor);
            scrollPane.setLineNumbersEnabled(true);
            var paintLayer = new JLayer<>(scrollPane);
            var editorGutter = new EditorGutter(scrollPane.getGutter());
            var breakpointMarkers = new BreakpointGutterMarkers(
                    editorGutter,
                    editor,
                    paintLayer,
                    new EmptyBreakpointHandler()
            );
            paintLayer.setSize(320, 120);
            layoutRecursively(paintLayer);

            BufferedImage empty = paint(breakpointMarkers, paintLayer);
            breakpointMarkers.setBreakpoints(List.of(managed(
                    new DebugEngine.SourceBreakpoint(1),
                    DebuggerSessionController.BreakpointState.UNBOUND
            )));
            BufferedImage first = paint(breakpointMarkers, paintLayer);
            breakpointMarkers.setBreakpoints(List.of(
                    managed(new DebugEngine.SourceBreakpoint(1), DebuggerSessionController.BreakpointState.UNBOUND),
                    managed(new DebugEngine.SourceBreakpoint(2), DebuggerSessionController.BreakpointState.UNBOUND)
            ));
            BufferedImage second = paint(breakpointMarkers, paintLayer);

            assertNotEquals(rowPixels(empty, editor, paintLayer, 1), rowPixels(first, editor, paintLayer, 1));
            assertEquals(rowPixels(first, editor, paintLayer, 1), rowPixels(second, editor, paintLayer, 1));
            assertNotEquals(rowPixels(first, editor, paintLayer, 2), rowPixels(second, editor, paintLayer, 2));

            breakpointMarkers.dispose();
        });
    }

    @Test
    void placesLineNumbersOutsideHierarchyIcons() throws Exception {
        AtomicReference<Component> lineNumbers = new AtomicReference<>();
        AtomicReference<Component> hierarchyIcons = new AtomicReference<>();

        SwingUtilities.invokeAndWait(() -> {
            RTextScrollPane scrollPane = new RTextScrollPane(new RSyntaxTextArea("class Sample {}"));
            var gutter = scrollPane.getGutter();
            var editorGutter = new EditorGutter(gutter);
            var markers = new HierarchyGutterMarkers(editorGutter, new EmptyHandler());
            gutter.setSize(gutter.getPreferredSize());
            gutter.doLayout();
            lineNumbers.set(editorGutter.lineNumbers());
            hierarchyIcons.set(editorGutter.hierarchyIcons());
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

            var editorGutter = new EditorGutter(gutter);
            var hierarchyMarkers = new HierarchyGutterMarkers(editorGutter, new EmptyHandler());
            var paintLayer = new JLayer<>(scrollPane);
            var breakpointMarkers = new BreakpointGutterMarkers(
                    editorGutter,
                    editor,
                    paintLayer,
                    new EmptyBreakpointHandler()
            );
            breakpointMarkers.setBreakpoints(List.of(new DebuggerSessionController.Breakpoint(
                    new DebugEngine.SourceBreakpoint(2),
                    DebuggerSessionController.BreakpointState.UNBOUND,
                    ""
            )));
            scrollPane.setSize(320, 120);
            layoutRecursively(scrollPane);

            try {
                int y = (int) editor.modelToView2D(editor.getLineStartOffset(1)).getCenterY();
                assertEquals(0, gutter.getTrackingIcons(new Point(1, y)).length,
                        "Breakpoint icons must not occupy the hierarchy icon column");
            } catch (javax.swing.text.BadLocationException exception) {
                throw new AssertionError(exception);
            }

            LineNumberList lineNumbers = editorGutter.lineNumbers();
            assertInstanceOf(LineNumberList.class, lineNumbers);
            assertTrue(lineNumbers.getWidth() > 0, "The laid-out line-number column has no width");
            assertTrue(lineNumbers.getHeight() > 0, "The laid-out line-number column has no height");

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

    @Test
    void clickingALineNumberRequestsABreakpointForThatDisplayedLine() throws Exception {
        AtomicInteger requestedLine = new AtomicInteger(-1);
        AtomicInteger caretPosition = new AtomicInteger(-1);

        SwingUtilities.invokeAndWait(() -> {
            var editor = new RSyntaxTextArea("first\nsecond\nthird");
            var scrollPane = new RTextScrollPane(editor);
            var gutter = scrollPane.getGutter();
            var editorGutter = new EditorGutter(gutter);
            var hierarchyMarkers = new HierarchyGutterMarkers(editorGutter, new EmptyHandler());
            var paintLayer = new JLayer<>(scrollPane);
            var breakpointMarkers = new BreakpointGutterMarkers(
                    editorGutter,
                    editor,
                    paintLayer,
                    new BreakpointGutterMarkers.Handler() {
                        @Override
                        public void toggle(int displayedLine) {
                            requestedLine.set(displayedLine);
                        }

                        @Override
                        public void toggleEnabled(int displayedLine) {
                        }

                        @Override
                        public void configure(int displayedLine, Component invoker, Point location) {
                        }
                    }
            );
            scrollPane.setSize(320, 120);
            layoutRecursively(scrollPane);

            LineNumberList lineNumbers = editorGutter.lineNumbers();
            assertInstanceOf(LineNumberList.class, lineNumbers);
            try {
                editor.setCaretPosition(editor.getLineStartOffset(0));
                int secondLineOffset = editor.getLineStartOffset(1);
                int y = (int) editor.modelToView2D(secondLineOffset).getCenterY();
                int x = Math.max(0, lineNumbers.getWidth() / 2);
                dispatchLeftClick(lineNumbers, x, y);
                caretPosition.set(editor.getCaretPosition());
            } catch (javax.swing.text.BadLocationException exception) {
                throw new AssertionError(exception);
            }

            breakpointMarkers.dispose();
            hierarchyMarkers.dispose();
        });

        assertEquals(2, requestedLine.get());
        assertEquals(0, caretPosition.get(), "A breakpoint gutter click must not move the editor caret");
    }

    @Test
    void altClickingABreakpointTogglesItsEnabledStateWithoutRemovingIt() throws Exception {
        AtomicInteger toggledLine = new AtomicInteger(-1);
        AtomicInteger enabledLine = new AtomicInteger(-1);

        SwingUtilities.invokeAndWait(() -> {
            var editor = new RSyntaxTextArea("first\nsecond\nthird");
            var scrollPane = new RTextScrollPane(editor);
            var editorGutter = new EditorGutter(scrollPane.getGutter());
            var paintLayer = new JLayer<>(scrollPane);
            var breakpointMarkers = new BreakpointGutterMarkers(
                    editorGutter,
                    editor,
                    paintLayer,
                    new BreakpointGutterMarkers.Handler() {
                        @Override
                        public void toggle(int displayedLine) {
                            toggledLine.set(displayedLine);
                        }

                        @Override
                        public void toggleEnabled(int displayedLine) {
                            enabledLine.set(displayedLine);
                        }

                        @Override
                        public void configure(int displayedLine, Component invoker, Point location) {
                        }
                    }
            );
            breakpointMarkers.setBreakpoints(List.of(managed(
                    new DebugEngine.SourceBreakpoint(2),
                    DebuggerSessionController.BreakpointState.BOUND
            )));
            scrollPane.setSize(320, 120);
            layoutRecursively(scrollPane);

            try {
                int y = (int) editor.modelToView2D(editor.getLineStartOffset(1)).getCenterY();
                LineNumberList lineNumbers = editorGutter.lineNumbers();
                dispatchLeftClick(
                        lineNumbers,
                        Math.max(0, lineNumbers.getWidth() / 2),
                        y,
                        InputEvent.ALT_DOWN_MASK
                );
            } catch (javax.swing.text.BadLocationException exception) {
                throw new AssertionError(exception);
            }

            breakpointMarkers.dispose();
        });

        assertEquals(-1, toggledLine.get());
        assertEquals(2, enabledLine.get());
    }

    @Test
    void restoresNativeLineNumberInteractionWhenBreakpointMarkersAreDisposed() throws Exception {
        AtomicInteger caretPosition = new AtomicInteger(-1);

        SwingUtilities.invokeAndWait(() -> {
            var editor = new RSyntaxTextArea("first\nsecond\nthird");
            var scrollPane = new RTextScrollPane(editor);
            var editorGutter = new EditorGutter(scrollPane.getGutter());
            var paintLayer = new JLayer<>(scrollPane);
            var breakpointMarkers = new BreakpointGutterMarkers(
                    editorGutter,
                    editor,
                    paintLayer,
                    new EmptyBreakpointHandler()
            );
            scrollPane.setSize(320, 120);
            layoutRecursively(scrollPane);
            breakpointMarkers.dispose();

            try {
                int y = (int) editor.modelToView2D(editor.getLineStartOffset(1)).getCenterY();
                LineNumberList lineNumbers = editorGutter.lineNumbers();
                lineNumbers.dispatchEvent(new MouseEvent(
                        lineNumbers,
                        MouseEvent.MOUSE_PRESSED,
                        System.currentTimeMillis(),
                        0,
                        Math.max(0, lineNumbers.getWidth() / 2),
                        y,
                        1,
                        false,
                        MouseEvent.BUTTON1
                ));
                caretPosition.set(editor.getCaretPosition());
            } catch (javax.swing.text.BadLocationException exception) {
                throw new AssertionError(exception);
            }
        });

        assertTrue(caretPosition.get() > 0, "Disposal must restore RSyntax's native line-number listener");
    }

    @Test
    void rightClickingALineNumberRequestsBreakpointConfigurationForThatLine() throws Exception {
        AtomicInteger requestedLine = new AtomicInteger(-1);

        SwingUtilities.invokeAndWait(() -> {
            var editor = new RSyntaxTextArea("first\nsecond\nthird");
            var scrollPane = new RTextScrollPane(editor);
            var editorGutter = new EditorGutter(scrollPane.getGutter());
            var paintLayer = new JLayer<>(scrollPane);
            var breakpointMarkers = new BreakpointGutterMarkers(
                    editorGutter,
                    editor,
                    paintLayer,
                    new BreakpointGutterMarkers.Handler() {
                        @Override
                        public void toggle(int displayedLine) {
                        }

                        @Override
                        public void toggleEnabled(int displayedLine) {
                        }

                        @Override
                        public void configure(int displayedLine, Component invoker, Point location) {
                            requestedLine.set(displayedLine);
                        }
                    }
            );
            scrollPane.setSize(320, 120);
            layoutRecursively(scrollPane);

            try {
                int y = (int) editor.modelToView2D(editor.getLineStartOffset(1)).getCenterY();
                editorGutter.lineNumbers().dispatchEvent(new MouseEvent(
                        editorGutter.lineNumbers(),
                        MouseEvent.MOUSE_RELEASED,
                        System.currentTimeMillis(),
                        0,
                        Math.max(0, editorGutter.lineNumbers().getWidth() / 2),
                        y,
                        1,
                        true,
                        MouseEvent.BUTTON3
                ));
            } catch (javax.swing.text.BadLocationException exception) {
                throw new AssertionError(exception);
            }

            breakpointMarkers.dispose();
        });

        assertEquals(2, requestedLine.get());
    }

    @Test
    void selectsBreakpointIconsFromBindingStateAndCondition() {
        var plain = new DebugEngine.SourceBreakpoint(7);
        var conditional = new DebugEngine.SourceBreakpoint(8, "value > 2", null, null);
        var method = DebugEngine.SourceBreakpoint.methodEntry(
                9,
                10,
                new DebugEngine.MethodTarget("sample.Target", "run", "()V"),
                null,
                null
        );

        assertSame(Icons.BREAKPOINT, BreakpointGutterMarkers.iconFor(managed(
                plain,
                DebuggerSessionController.BreakpointState.UNBOUND
        )));
        assertSame(Icons.BREAKPOINT, BreakpointGutterMarkers.iconFor(managed(
                plain,
                DebuggerSessionController.BreakpointState.PENDING
        )));
        assertSame(Icons.BREAKPOINT_VALID, BreakpointGutterMarkers.iconFor(managed(
                plain,
                DebuggerSessionController.BreakpointState.BOUND
        )));
        assertSame(Icons.BREAKPOINT_INVALID, BreakpointGutterMarkers.iconFor(managed(
                plain,
                DebuggerSessionController.BreakpointState.INVALID
        )));
        assertSame(Icons.BREAKPOINT_DISABLED, BreakpointGutterMarkers.iconFor(managed(
                plain,
                DebuggerSessionController.BreakpointState.DISABLED
        )));
        assertEquals(14, BreakpointGutterMarkers.iconFor(managed(
                conditional,
                DebuggerSessionController.BreakpointState.UNBOUND
        )).getIconWidth());
        assertEquals(14, BreakpointGutterMarkers.iconFor(managed(
                conditional,
                DebuggerSessionController.BreakpointState.BOUND
        )).getIconWidth());
        assertSame(Icons.BREAKPOINT_INVALID, BreakpointGutterMarkers.iconFor(managed(
                conditional,
                DebuggerSessionController.BreakpointState.INVALID
        )));
        assertSame(Icons.BREAKPOINT_METHOD, BreakpointGutterMarkers.iconFor(managed(
                method,
                DebuggerSessionController.BreakpointState.UNBOUND
        )));
        assertSame(Icons.BREAKPOINT_METHOD_VALID, BreakpointGutterMarkers.iconFor(managed(
                method,
                DebuggerSessionController.BreakpointState.BOUND
        )));
        assertSame(Icons.BREAKPOINT_INVALID, BreakpointGutterMarkers.iconFor(managed(
                method,
                DebuggerSessionController.BreakpointState.INVALID
        )));
    }

    private static DebuggerSessionController.Breakpoint managed(
            DebugEngine.SourceBreakpoint request,
            DebuggerSessionController.BreakpointState state
    ) {
        return new DebuggerSessionController.Breakpoint(request, state, "");
    }

    private static BufferedImage paint(BreakpointGutterMarkers markers, Component component) {
        BufferedImage image = new BufferedImage(
                component.getWidth(),
                component.getHeight(),
                BufferedImage.TYPE_INT_ARGB
        );
        Graphics2D graphics = image.createGraphics();
        markers.paint(graphics, component, Color.BLACK, Color.DARK_GRAY);
        graphics.dispose();
        return image;
    }

    private static void dispatchLeftClick(Component target, int x, int y) {
        dispatchLeftClick(target, x, y, 0);
    }

    private static void dispatchLeftClick(Component target, int x, int y, int modifiers) {
        long when = System.currentTimeMillis();
        target.dispatchEvent(new MouseEvent(
                target, MouseEvent.MOUSE_PRESSED, when, modifiers, x, y, 1, false, MouseEvent.BUTTON1
        ));
        target.dispatchEvent(new MouseEvent(
                target, MouseEvent.MOUSE_RELEASED, when, modifiers, x, y, 1, false, MouseEvent.BUTTON1
        ));
        target.dispatchEvent(new MouseEvent(
                target, MouseEvent.MOUSE_CLICKED, when, modifiers, x, y, 1, false, MouseEvent.BUTTON1
        ));
    }

    private static int rowPixels(
            BufferedImage image,
            RSyntaxTextArea editor,
            Component ancestor,
            int displayedLine
    ) {
        try {
            int editorY = (int) editor.modelToView2D(editor.getLineStartOffset(displayedLine - 1)).getY();
            int y = SwingUtilities.convertPoint(editor, 0, editorY, ancestor).y;
            int height = Math.min(editor.getLineHeight(), image.getHeight() - y);
            return java.util.Arrays.hashCode(image.getRGB(
                    0,
                    y,
                    image.getWidth(),
                    height,
                    null,
                    0,
                    image.getWidth()
            ));
        } catch (javax.swing.text.BadLocationException exception) {
            throw new AssertionError(exception);
        }
    }

    private static void layoutRecursively(Container container) {
        container.doLayout();
        for (Component child : container.getComponents()) {
            if (child instanceof Container nested) {
                layoutRecursively(nested);
            }
        }
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
                boolean mixedBaseRelations
        ) {
        }

        @Override
        public void hidePreview() {
        }
    }

    private static final class EmptyBreakpointHandler implements BreakpointGutterMarkers.Handler {
        @Override
        public void toggle(int displayedLine) {
        }

        @Override
        public void toggleEnabled(int displayedLine) {
        }

        @Override
        public void configure(int displayedLine, Component invoker, Point location) {
        }
    }
}
