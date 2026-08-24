package com.github.minecraft_ta.totalDebugCompanion.ui.components.global;

import com.github.minecraft_ta.totalDebugCompanion.model.IEditorPanel;
import org.junit.jupiter.api.Test;

import javax.swing.*;
import java.awt.Component;
import java.util.concurrent.TimeUnit;

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

    private static final class TestEditor implements IEditorPanel {
        private final JPanel panel = new JPanel();
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
        public void dispose() {
            this.disposed = true;
        }
    }
}
