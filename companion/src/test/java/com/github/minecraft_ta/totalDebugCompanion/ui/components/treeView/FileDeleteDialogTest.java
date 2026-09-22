package com.github.minecraft_ta.totalDebugCompanion.ui.components.treeView;

import com.github.minecraft_ta.totalDebugCompanion.ui.components.treeView.ScriptFileActions.FileSelection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import java.nio.file.Path;
import java.awt.event.ActionEvent;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FileDeleteDialogTest {
    @TempDir Path directory;

    @Test void fileConfirmationDefaultsToDeleteAndCancelDoesNothing() throws Exception {
        var file = new FileSelection(directory.resolve("Test.tdscript"), false);
        SwingUtilities.invokeAndWait(() -> {
            int[] deletes = {0};
            JDialog dialog = ScriptFileActions.deleteDialog(null, List.of(file), true, () -> deletes[0]++);
            try {
                var pane = (JOptionPane) dialog.getContentPane().getComponent(0);
                assertEquals("Delete file \"Test.tdscript\"?", pane.getMessage());
                assertEquals("Delete", dialog.getRootPane().getDefaultButton().getText());
                assertSame(pane.getInitialValue(), dialog.getRootPane().getDefaultButton());
                ((JButton) pane.getOptions()[1]).doClick(0);
                assertEquals(0, deletes[0]);
                assertFalse(dialog.isDisplayable());
            } finally { dialog.dispose(); }
            dialog = ScriptFileActions.deleteDialog(null, List.of(file), true, () -> deletes[0]++);
            try {
                showOffscreen(dialog);
                dialog.getRootPane().getActionMap().get("press").actionPerformed(new ActionEvent(dialog.getRootPane(), 0, "press"));
                assertEquals(1, deletes[0]);
                assertFalse(dialog.isDisplayable());
            } finally { dialog.dispose(); }
            dialog = ScriptFileActions.deleteDialog(null, List.of(file), true, () -> deletes[0]++);
            try {
                showOffscreen(dialog);
                var pane = (JOptionPane) dialog.getContentPane().getComponent(0);
                pane.getActionMap().get("close").actionPerformed(new ActionEvent(pane, 0, "close"));
                assertFalse(dialog.isVisible());
                assertEquals(1, deletes[0], "Escape must not delete");
            } finally { dialog.dispose(); }
        });
    }

    private static void showOffscreen(JDialog dialog) {
        dialog.setModal(false);
        dialog.setLocation(-20000, -20000);
        dialog.setFocusableWindowState(false);
        dialog.setVisible(true);
    }

    @Test void foldersAndPermanentDeletionUseSpecificQuestions() throws Exception {
        var folder = new FileSelection(directory.resolve("Utilities"), true);
        var file = new FileSelection(directory.resolve("Test.tdscript"), false);
        SwingUtilities.invokeAndWait(() -> {
            JDialog dialog = ScriptFileActions.deleteDialog(null, List.of(folder), false, () -> fail("Deleted without confirmation"));
            try {
                assertEquals("Permanently delete folder \"Utilities\" and its contents?", ((JOptionPane) dialog.getContentPane().getComponent(0)).getMessage());
            } finally { dialog.dispose(); }
            dialog = ScriptFileActions.deleteDialog(null, List.of(file, folder), true, () -> fail("Deleted without confirmation"));
            try {
                assertEquals("Delete these 2 items?\n\nTest.tdscript\nUtilities (including contents)", ((JOptionPane) dialog.getContentPane().getComponent(0)).getMessage());
            } finally { dialog.dispose(); }
        });
    }
}
