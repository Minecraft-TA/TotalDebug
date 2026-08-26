package com.github.minecraft_ta.totalDebugCompanion.ui.components.global;

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EditorTabsTest {

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
