package com.github.minecraft_ta.totalDebugCompanion.ui.components.global;

import com.github.minecraft_ta.totalDebugCompanion.model.IEditorPanel;
import org.junit.jupiter.api.Test;

import javax.swing.*;
import java.awt.Component;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

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
