package com.github.minecraft_ta.totalDebugCompanion.ui.components.global;

import com.github.minecraft_ta.totalDebugCompanion.Icons;
import com.github.minecraft_ta.totalDebugCompanion.model.IEditorPanel;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.CloseButton;
import org.junit.jupiter.api.Test;

import javax.swing.*;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.event.MouseEvent;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EditorTabsTest {


    @Test
    void selectiveClosePreservesOtherEditorsAndSelection() throws Exception {
        EditorTabs tabs = new EditorTabs();
        TestEditor keep = new TestEditor();
        TestEditor first = new TestEditor();
        TestEditor last = new TestEditor();
        SwingUtilities.invokeAndWait(() -> {
            tabs.openEditorTab(first);
            tabs.openEditorTab(keep);
            tabs.openEditorTab(last);
            assertEquals(3, tabs.getTabCount(), "Opening on the EDT must not queue an unguarded second action");
            tabs.closeMatching(editor -> editor != keep);
            assertEquals(1, tabs.getTabCount());
            assertSame(keep, tabs.getSelectedEditor());
            assertFalse(keep.disposed);
            assertTrue(first.disposed);
            assertTrue(last.disposed);
        });
    }

    @Test
    void closingATabDisposesItsEditor() throws Exception {
        EditorTabs tabs = new EditorTabs();
        TestEditor editor = new TestEditor();
        tabs.openEditorTab(editor).get(5, TimeUnit.SECONDS);

        SwingUtilities.invokeAndWait(() -> tabs.removeTabAt(0));

        assertTrue(editor.disposed);
    }

    @Test
    void focusCompletesOnlyWhenTheEditorIsReady() throws Exception {
        EditorTabs tabs = new EditorTabs();
        TestEditor editor = new TestEditor();

        CompletableFuture<TestEditor> focused = tabs.focusOrCreateIfAbsent(
                TestEditor.class,
                candidate -> true,
                () -> editor
        );
        SwingUtilities.invokeAndWait(() -> { });

        assertFalse(focused.isDone());
        editor.ready.complete(null);
        assertSame(editor, focused.get(5, TimeUnit.SECONDS));
    }

    @Test
    void closeSlotKeepsItsWidthWhileTheButtonTracksSelectionAndHover() throws Exception {
        EditorTabs tabs = new EditorTabs();
        tabs.openEditorTab(new TestEditor()).get(5, TimeUnit.SECONDS);
        tabs.openEditorTab(new TestEditor()).get(5, TimeUnit.SECONDS);

        SwingUtilities.invokeAndWait(() -> {
            EditorTabHeader inactiveHeader = (EditorTabHeader) tabs.getTabComponentAt(0);
            EditorTabHeader selectedHeader = (EditorTabHeader) tabs.getTabComponentAt(1);
            CloseButton inactiveClose = find(inactiveHeader, CloseButton.class);
            CloseButton selectedClose = find(selectedHeader, CloseButton.class);
            Dimension reservedWidth = inactiveHeader.getPreferredSize();

            assertFalse(inactiveClose.isVisible());
            assertTrue(selectedClose.isVisible());

            inactiveHeader.dispatchEvent(mouseEvent(inactiveHeader, MouseEvent.MOUSE_ENTERED, 1, 1, MouseEvent.NOBUTTON));
            assertTrue(inactiveHeader.isHovered());
            assertTrue(inactiveClose.isVisible());
            assertEquals(reservedWidth, inactiveHeader.getPreferredSize());

            inactiveHeader.dispatchEvent(mouseEvent(inactiveHeader, MouseEvent.MOUSE_EXITED, -1, -1, MouseEvent.NOBUTTON));
            assertFalse(inactiveHeader.isHovered());
            assertFalse(inactiveClose.isVisible());
            assertEquals(reservedWidth, inactiveHeader.getPreferredSize());
        });
    }

    @Test
    void clickingTheTabTitleSelectsTheTab() throws Exception {
        EditorTabs tabs = new EditorTabs();
        tabs.openEditorTab(new TestEditor()).get(5, TimeUnit.SECONDS);
        tabs.openEditorTab(new TestEditor()).get(5, TimeUnit.SECONDS);

        SwingUtilities.invokeAndWait(() -> {
            EditorTabHeader firstHeader = (EditorTabHeader) tabs.getTabComponentAt(0);
            JLabel title = find(firstHeader, JLabel.class);

            dispatchLeftClick(title, 1, 1);

            assertEquals(0, tabs.getSelectedIndex());
        });
    }

    @Test
    void hoverStaysTransparentAndReachesTheTabbedPaneUi() throws Exception {
        EditorTabs tabs = new EditorTabs();
        tabs.openEditorTab(new TestEditor()).get(5, TimeUnit.SECONDS);
        AtomicInteger forwardedMoves = new AtomicInteger();

        SwingUtilities.invokeAndWait(() -> {
            tabs.addMouseMotionListener(new java.awt.event.MouseMotionAdapter() {
                @Override
                public void mouseMoved(MouseEvent event) {
                    forwardedMoves.incrementAndGet();
                }
            });
            EditorTabHeader header = (EditorTabHeader) tabs.getTabComponentAt(0);
            JLabel title = find(header, JLabel.class);

            title.dispatchEvent(mouseEvent(title, MouseEvent.MOUSE_ENTERED, 1, 1, MouseEvent.NOBUTTON));

            assertFalse(header.isOpaque(), "The inner tab header must not paint a second hover rectangle");
            assertTrue(forwardedMoves.get() > 0, "FlatLaf must receive hover movement for the full tab cell");
        });
    }

    @Test
    void closeButtonUsesTheButtonModelForItsRolloverIcon() {
        CloseButton button = new CloseButton();

        assertSame(Icons.CLOSE_ICON, button.getIcon());
        assertSame(Icons.CLOSE_HOVERED_ICON, button.getRolloverIcon());
    }

    @Test
    void closeButtonAndMiddleClickBothDisposeTheEditor() throws Exception {
        EditorTabs tabs = new EditorTabs();
        TestEditor buttonEditor = new TestEditor();
        TestEditor middleClickEditor = new TestEditor();
        tabs.openEditorTab(buttonEditor).get(5, TimeUnit.SECONDS);
        tabs.openEditorTab(middleClickEditor).get(5, TimeUnit.SECONDS);

        SwingUtilities.invokeAndWait(() -> {
            EditorTabHeader selectedHeader = (EditorTabHeader) tabs.getTabComponentAt(1);
            find(selectedHeader, CloseButton.class).doClick();
        });
        assertTrue(middleClickEditor.disposed);

        SwingUtilities.invokeAndWait(() -> {
            EditorTabHeader remainingHeader = (EditorTabHeader) tabs.getTabComponentAt(0);
            remainingHeader.dispatchEvent(mouseEvent(
                    remainingHeader,
                    MouseEvent.MOUSE_PRESSED,
                    1,
                    1,
                    MouseEvent.BUTTON2
            ));
        });
        assertTrue(buttonEditor.disposed);
        assertEquals(0, tabs.getTabCount());
    }

    private static MouseEvent mouseEvent(Component source, int id, int x, int y, int button) {
        return new MouseEvent(source, id, System.currentTimeMillis(), 0, x, y, 1, false, button);
    }

    private static void dispatchLeftClick(Component source, int x, int y) {
        source.dispatchEvent(mouseEvent(source, MouseEvent.MOUSE_PRESSED, x, y, MouseEvent.BUTTON1));
        source.dispatchEvent(mouseEvent(source, MouseEvent.MOUSE_RELEASED, x, y, MouseEvent.BUTTON1));
        source.dispatchEvent(mouseEvent(source, MouseEvent.MOUSE_CLICKED, x, y, MouseEvent.BUTTON1));
    }

    private static <T extends Component> T find(Container root, Class<T> type) {
        T match = findOrNull(root, type);
        if (match == null) {
            throw new AssertionError("Missing component " + type.getSimpleName());
        }
        return match;
    }

    private static <T extends Component> T findOrNull(Container root, Class<T> type) {
        for (Component component : root.getComponents()) {
            if (type.isInstance(component)) {
                return type.cast(component);
            }
            if (component instanceof Container child) {
                T match = findOrNull(child, type);
                if (match != null) {
                    return match;
                }
            }
        }
        return null;
    }

    private static final class TestEditor implements IEditorPanel {
        private final JPanel panel = new JPanel();
        private final CompletableFuture<Void> ready = new CompletableFuture<>();
        private boolean disposed;

        @Override
        public String getTitle() {
            return "test";
        }

        @Override
        public String getTooltip() {
            return "test";
        }

        @Override
        public Icon getIcon() {
            return null;
        }

        @Override
        public Component getComponent() {
            return this.panel;
        }

        @Override
        public CompletableFuture<Void> ready() {
            return this.ready;
        }

        @Override
        public void dispose() {
            this.disposed = true;
        }
    }
}
