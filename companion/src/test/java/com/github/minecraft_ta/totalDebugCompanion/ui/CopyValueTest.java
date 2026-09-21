package com.github.minecraft_ta.totalDebugCompanion.ui;

import org.junit.jupiter.api.Test;
import com.github.minecraft_ta.totalDebugCompanion.Icons;

import javax.swing.JButton;
import javax.swing.SwingUtilities;
import java.awt.Dimension;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;

import static org.junit.jupiter.api.Assertions.*;

class CopyValueTest {
    @Test void copyHasFeedbackWithoutResizingAndResetsWhenTheValueDisappears() throws Exception {
        Clipboard clipboard = new Clipboard("copy-value-test");
        SwingUtilities.invokeAndWait(() -> {
            var value = new CopyValue("Copy endpoint", text -> clipboard.setContents(new StringSelection(text), null));
            value.setValue("http://localhost/mcp", "http://localhost/mcp");
            JButton copy = (JButton) value.getComponent(1);
            Dimension before = value.getPreferredSize();
            try {
                copy.doClick(0);
                assertTrue(copy.getText() == null || copy.getText().isEmpty());
                assertSame(Icons.SUCCESS, copy.getIcon());
                assertEquals("Copied", copy.getToolTipText());
                assertEquals(before, value.getPreferredSize());
                assertEquals("http://localhost/mcp", clipboard.getData(DataFlavor.stringFlavor));
                value.setValue("", "");
                assertFalse(copy.isEnabled());
                assertFalse(value.isVisible());
                assertTrue(copy.getText() == null || copy.getText().isEmpty());
                assertSame(Icons.COPY, copy.getIcon());
                assertEquals("Copy endpoint", copy.getToolTipText());
                assertEquals("Copy endpoint", copy.getAccessibleContext().getAccessibleName());
            } catch (Exception failure) {
                throw new AssertionError(failure);
            } finally {
                value.removeNotify();
            }
        });
    }
}
