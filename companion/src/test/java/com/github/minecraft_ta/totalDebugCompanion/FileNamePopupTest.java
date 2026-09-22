package com.github.minecraft_ta.totalDebugCompanion;

import com.github.minecraft_ta.totalDebugCompanion.script.ScriptFiles;
import com.github.minecraft_ta.totalDebugCompanion.ui.views.FileNamePopup;
import org.junit.jupiter.api.Test;
import javax.swing.*;
import java.awt.Component;
import java.awt.Container;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.api.io.TempDir;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class FileNamePopupTest {
    @Test void duplicateNameKeepsTheFriendlyMessageAndPopupWidth(@TempDir Path directory) throws Exception {
        var files = new ScriptFiles(directory.resolve("scripts"));
        Path original = files.create(files.root(), "Test", false, "keep");
        IOException collision = assertThrows(IOException.class, () -> files.create(files.root(), "Test", false, "replace"));
        var work = new CompletableFuture<Void>();
        var dialog = new AtomicReference<FileNamePopup>();
        int[] width = {0};
        SwingUtilities.invokeAndWait(() -> {
            var popup = new FileNamePopup(null, "New Script", "Script", Icons.SCRIPT_FILE, "Test", name -> null, name -> work);
            dialog.set(popup);
            width[0] = popup.getWidth();
            find(popup, JTextField.class).postActionEvent();
        });
        work.completeExceptionally(new CompletionException(new ExecutionException(collision)));
        SwingUtilities.invokeAndWait(() -> {
            var popup = dialog.get();
            try {
                assertTrue(labels(popup).contains("\"Test.tdscript\" already exists."), labels(popup));
                assertFalse(labels(popup).contains(directory.toString()));
                assertEquals(width[0], popup.getWidth());
                var field = find(popup, JTextField.class);
                assertTrue(field.isEnabled());
                assertEquals("Test", field.getText());
                field.setText("DifferentName");
                assertFalse(labels(popup).contains("already exists"));
            } finally { popup.dispose(); }
        });
        assertEquals("keep", files.read(original).text());
    }

    @Test void keyboardOnlyNameEntryKeepsValidationAndReportsAsyncFailure() throws Exception {
        var work = new CompletableFuture<Void>();
        var dialog = new AtomicReference<FileNamePopup>();
        int[] width = {0};
        SwingUtilities.invokeAndWait(() -> {
            var popup = new FileNamePopup(null, "New Script", "Script", Icons.SCRIPT_FILE, "", name -> {
                try { ScriptFiles.validateName(name, true); return null; } catch (IOException failure) { return failure.getMessage(); }
            }, name -> work);
            dialog.set(popup);
            width[0] = popup.getWidth();
            var field = find(popup, JTextField.class);
            assertTrue(popup.isUndecorated());
            assertEquals(JRootPane.NONE, popup.getRootPane().getWindowDecorationStyle());
            String visibleLabels = labels(popup);
            assertTrue(visibleLabels.contains("New Script"));
            assertEquals(visibleLabels.indexOf("New Script"), visibleLabels.lastIndexOf("New Script"));
            assertNull(find(popup, JButton.class));
            field.setText("../invalid"); field.postActionEvent();
            assertFalse(work.isDone()); assertTrue(field.isEnabled());
            field.setText("MyScript"); field.postActionEvent();
            assertFalse(field.isEnabled());
            popup.getRootPane().getActionForKeyStroke(KeyStroke.getKeyStroke("ESCAPE")).actionPerformed(null);
            popup.dispatchEvent(new WindowEvent(popup, WindowEvent.WINDOW_CLOSING));
            assertTrue(popup.isDisplayable(), "Escape must not hide an in-flight operation's eventual error");
        });
        String detail = "Unable to create the file in " + "a long folder name/".repeat(30);
        work.completeExceptionally(new IOException(detail));
        SwingUtilities.invokeAndWait(() -> {
            var popup = dialog.get();
            assertTrue(find(popup, JTextField.class).isEnabled());
            assertTrue(labels(popup).contains(detail));
            assertEquals(width[0], popup.getWidth(), "A long error must not stretch the name popup");
            assertTrue(popup.isDisplayable());
            popup.getRootPane().getActionForKeyStroke(KeyStroke.getKeyStroke("ESCAPE")).actionPerformed(null);
            assertFalse(popup.isDisplayable(), "Escape closes the popup after the failure is shown");
            for (var listener : popup.getWindowFocusListeners()) listener.windowLostFocus(new WindowEvent(popup, WindowEvent.WINDOW_LOST_FOCUS));
            assertFalse(popup.isDisplayable());
            popup.dispose();
        });
    }
    private static String labels(Container parent) {
        StringBuilder result = new StringBuilder();
        for (var child : parent.getComponents()) {
            if (child instanceof JLabel label) result.append(label.getText());
            if (child instanceof Container container) result.append(labels(container));
        }
        return result.toString();
    }
    private static <T extends Component> T find(Container parent, Class<T> type) {
        for (var child : parent.getComponents()) {
            if (type.isInstance(child)) return type.cast(child);
            if (child instanceof Container container) { T found = find(container, type); if (found != null) return found; }
        }
        return null;
    }
}
