package com.github.minecraft_ta.totalDebugCompanion;

import com.github.minecraft_ta.totalDebugCompanion.script.ScriptFiles;
import com.github.minecraft_ta.totalDebugCompanion.ui.views.FileNamePopup;
import org.junit.jupiter.api.Test;
import javax.swing.*;
import java.awt.Component;
import java.awt.Container;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class FileNamePopupTest {
    @Test void keyboardOnlyNameEntryKeepsValidationAndReportsAsyncFailure() throws Exception {
        var work = new CompletableFuture<Void>();
        var dialog = new AtomicReference<FileNamePopup>();
        SwingUtilities.invokeAndWait(() -> {
            var popup = new FileNamePopup(null, "New Script", "Script", Icons.JAVA_FILE, "", name -> {
                try { ScriptFiles.validateName(name, true); return null; } catch (IOException failure) { return failure.getMessage(); }
            }, name -> work);
            dialog.set(popup);
            var field = find(popup, JTextField.class);
            assertTrue(popup.isUndecorated());
            assertNull(find(popup, JButton.class));
            field.setText("../invalid"); field.postActionEvent();
            assertFalse(work.isDone()); assertTrue(field.isEnabled());
            field.setText("MyScript"); field.postActionEvent();
            assertFalse(field.isEnabled());
        });
        work.completeExceptionally(new IOException("Already exists"));
        SwingUtilities.invokeAndWait(() -> {
            var popup = dialog.get();
            assertTrue(find(popup, JTextField.class).isEnabled());
            assertTrue(labels(popup).contains("Already exists"));
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
