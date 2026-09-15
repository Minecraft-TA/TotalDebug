package com.github.minecraft_ta.totalDebugCompanion.ui.components.editors;

import com.github.minecraft_ta.totalDebugCompanion.debugger.DebugEngine;
import com.github.minecraft_ta.totalDebugCompanion.debugger.DebuggerSessionController;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rtextarea.LineNumberList;
import org.fife.ui.rtextarea.RTextScrollPane;
import org.junit.jupiter.api.Test;

import javax.swing.JLayer;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.awt.Point;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BreakpointGutterMarkersTest {
    @Test
    void everyClickInARapidSequenceTogglesExactlyOnce() throws Exception {
        onEdt(() -> {
            try (Fixture fixture = new Fixture()) {
                for (int count = 1; count <= 6; count++) {
                    fixture.click(count, 0);
                    assertEquals(count, fixture.toggles, "Repeated clicks must not be discarded as double/triple clicks");
                    assertEquals(count % 2 == 1, fixture.present);
                }
                assertEquals(0, fixture.editor.getCaretPosition());
            }
        });
    }

    @Test
    void pressingTheGutterAppliesTheToggleWithoutWaitingForRelease() throws Exception {
        onEdt(() -> {
            try (Fixture fixture = new Fixture()) {
                fixture.mouse(MouseEvent.MOUSE_PRESSED, 1, 0, MouseEvent.BUTTON1);
                assertEquals(1, fixture.toggles);
                fixture.mouse(MouseEvent.MOUSE_RELEASED, 1, 0, MouseEvent.BUTTON1);
                fixture.mouse(MouseEvent.MOUSE_CLICKED, 1, 0, MouseEvent.BUTTON1);
                assertEquals(1, fixture.toggles, "Release/click must not toggle again");
            }
        });
    }

    @Test
    void stationaryHoverTracksAddingBindingAndRemovingABreakpoint() throws Exception {
        onEdt(() -> {
            try (Fixture fixture = new Fixture()) {
                fixture.mouse(MouseEvent.MOUSE_MOVED, 0, 0, MouseEvent.NOBUTTON);
                assertNull(fixture.lineNumbers.getToolTipText());
                fixture.show(DebuggerSessionController.BreakpointState.PENDING);
                assertEquals("Breakpoint pending at line 2", fixture.lineNumbers.getToolTipText());
                fixture.show(DebuggerSessionController.BreakpointState.BOUND);
                assertEquals("Breakpoint at line 2", fixture.lineNumbers.getToolTipText());
                fixture.markers.setBreakpoints(List.of(), false);
                assertNull(fixture.lineNumbers.getToolTipText(), "A removed breakpoint must not leave a stale tooltip");
            }
        });
    }

    @Test
    void leavingTheGutterClearsHoverEvenIfBreakpointStateChangesLater() throws Exception {
        onEdt(() -> {
            try (Fixture fixture = new Fixture()) {
                fixture.show(DebuggerSessionController.BreakpointState.PENDING);
                fixture.mouse(MouseEvent.MOUSE_ENTERED, 0, 0, MouseEvent.NOBUTTON);
                assertNotNull(fixture.lineNumbers.getToolTipText());
                fixture.mouse(MouseEvent.MOUSE_EXITED, 0, 0, MouseEvent.NOBUTTON);
                fixture.show(DebuggerSessionController.BreakpointState.BOUND);
                assertNull(fixture.lineNumbers.getToolTipText());
            }
        });
    }

    @Test
    void rapidAltClicksToggleEnabledStateWithoutDeletingTheBreakpoint() throws Exception {
        onEdt(() -> {
            try (Fixture fixture = new Fixture()) {
                fixture.show(DebuggerSessionController.BreakpointState.BOUND);
                for (int count = 1; count <= 4; count++) fixture.click(count, InputEvent.ALT_DOWN_MASK);
                assertEquals(4, fixture.enabledToggles);
                assertEquals(0, fixture.toggles);
            }
        });
    }

    private static final class Fixture implements AutoCloseable, BreakpointGutterMarkers.Handler {
        final RSyntaxTextArea editor = new RSyntaxTextArea("first\nsecond\nthird");
        final RTextScrollPane scroll = new RTextScrollPane(editor);
        final EditorGutter gutter = new EditorGutter(scroll.getGutter());
        final LineNumberList lineNumbers = gutter.lineNumbers();
        final BreakpointGutterMarkers markers = new BreakpointGutterMarkers(gutter, editor, new JLayer<>(scroll), this);
        final int y;
        int toggles;
        int enabledToggles;
        boolean present;

        Fixture() throws Exception {
            editor.setCaretPosition(0);
            scroll.setSize(320, 120);
            layout(scroll);
            y = (int) editor.modelToView2D(editor.getLineStartOffset(1)).getCenterY();
        }

        void show(DebuggerSessionController.BreakpointState state) {
            markers.setBreakpoints(List.of(new DebuggerSessionController.Breakpoint(
                    new DebugEngine.SourceBreakpoint(2), state, "")), false);
        }

        void mouse(int id, int count, int modifiers, int button) {
            lineNumbers.dispatchEvent(new MouseEvent(lineNumbers, id, System.currentTimeMillis(), modifiers,
                    Math.max(0, lineNumbers.getWidth() / 2), y, count, false, button));
        }

        void click(int count, int modifiers) {
            mouse(MouseEvent.MOUSE_PRESSED, count, modifiers, MouseEvent.BUTTON1);
            mouse(MouseEvent.MOUSE_RELEASED, count, modifiers, MouseEvent.BUTTON1);
            mouse(MouseEvent.MOUSE_CLICKED, count, modifiers, MouseEvent.BUTTON1);
        }

        @Override public void toggle(int line) {
            assertEquals(2, line);
            toggles++;
            present = !present;
            if (present) show(DebuggerSessionController.BreakpointState.UNBOUND);
            else markers.setBreakpoints(List.of(), false);
        }

        @Override public void toggleEnabled(int line) { assertEquals(2, line); enabledToggles++; }
        @Override public void configure(int line, Component invoker, Point location) { fail("Unexpected configuration popup"); }
        @Override public void close() { markers.dispose(); }
    }

    private static void layout(Container parent) {
        parent.doLayout();
        for (Component child : parent.getComponents()) if (child instanceof Container container) layout(container);
    }

    private static void onEdt(CheckedRunnable runnable) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            try { runnable.run(); } catch (Exception failure) { throw new RuntimeException(failure); }
        });
    }

    @FunctionalInterface private interface CheckedRunnable { void run() throws Exception; }
}
