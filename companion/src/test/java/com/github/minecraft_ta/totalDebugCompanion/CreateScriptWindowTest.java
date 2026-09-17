package com.github.minecraft_ta.totalDebugCompanion;

import com.github.minecraft_ta.totalDebugCompanion.model.ScriptView;
import com.github.minecraft_ta.totalDebugCompanion.session.CompanionLaunchConfiguration;
import com.github.minecraft_ta.totalDebugCompanion.session.CompanionProfile;
import com.github.minecraft_ta.totalDebugCompanion.ui.views.CreateScriptWindow;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.*;
import java.awt.Component;
import java.awt.Container;
import java.awt.event.ActionEvent;
import java.awt.event.WindowEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

class CreateScriptWindowTest {
    @TempDir Path directory;

    @Test
    void validatesCreatesAndDismissesOnEscapeOrFocusLoss() throws Exception {
        Path home = Files.createDirectories(directory.resolve("app"));
        GlobalConfig.getInstance().loadFrom(home);
        CompanionApp.configureLookAndFeel();
        try (var app = new CompanionApplication(new CompanionLaunchConfiguration(home), "test-token")) {
            app.openProject(CompanionProfile.forGame(Files.createDirectories(directory.resolve("game"))))
                    .get(10, TimeUnit.SECONDS);
            SwingUtilities.invokeAndWait(() -> {
                var window = app.createWindow();
                var created = new AtomicInteger();
                var dialog = new CreateScriptWindow(window.getEditorTabs(), window.editorContext(), created::incrementAndGet);
                var field = find(dialog, JTextField.class, component -> "scriptName".equals(component.getName()));
                var error = find(dialog, JLabel.class, component -> "scriptNameError".equals(component.getName()));
                assertTrue(dialog.isUndecorated());
                assertNull(find(dialog, JButton.class, button -> true));
                field.postActionEvent();
                assertEquals(0, created.get());
                for (String invalid : new String[]{"bad name", "../Escape", "class"}) {
                    field.setText(invalid);
                    field.postActionEvent();
                    assertEquals(0, created.get());
                    assertEquals("Use a valid Java identifier.", error.getText());
                }
                field.setText("MyScript");
                assertFalse(error.isVisible());
                field.postActionEvent();
                assertFalse(dialog.isDisplayable());
                assertEquals(1, created.get());
                var script = (ScriptView) window.getEditorTabs().getSelectedEditor();
                assertTrue(Files.exists(script.getPath()));
                assertEquals("MyScript", script.getScriptName());
                var panel = (Container) script.getComponent();
                var format = find(panel, JButton.class, button -> "Format (Ctrl+Shift+F)".equals(button.getToolTipText()));
                var editor = find(panel, RSyntaxTextArea.class, component -> true);
                assertSame(editor.getActionMap().get("formatFile"), format.getAction());
                assertNotNull(format.getIcon());

                var duplicate = new CreateScriptWindow(window.getEditorTabs(), window.editorContext(), created::incrementAndGet);
                var duplicateName = find(duplicate, JTextField.class, component -> "scriptName".equals(component.getName()));
                duplicateName.setText("MyScript");
                duplicateName.postActionEvent();
                assertTrue(duplicate.isDisplayable());
                assertEquals(1, created.get());
                assertEquals("A script with this name already exists.",
                        find(duplicate, JLabel.class, component -> "scriptNameError".equals(component.getName())).getText());
                invoke(duplicate.getRootPane(), "ESCAPE");
                assertFalse(duplicate.isDisplayable());

                var cancelled = new CreateScriptWindow(window.getEditorTabs(), window.editorContext(), created::incrementAndGet);
                find(cancelled, JTextField.class, component -> "scriptName".equals(component.getName())).setText("Cancelled");
                // Hidden test windows never gain OS focus, so deliver the event to their registered listeners.
                for (var listener : cancelled.getWindowFocusListeners()) {
                    listener.windowLostFocus(new WindowEvent(cancelled, WindowEvent.WINDOW_LOST_FOCUS));
                }
                assertFalse(cancelled.isDisplayable());
                assertEquals(1, created.get());
                assertFalse(Files.exists(script.getPath().resolveSibling("Cancelled" + ScriptView.FILE_EXTENSION)));
                window.dispose();
            });
        }
    }

    private static void invoke(JRootPane root, String stroke) {
        var key = root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).get(KeyStroke.getKeyStroke(stroke));
        var action = root.getActionMap().get(key);
        assertNotNull(action, stroke);
        action.actionPerformed(new ActionEvent(root, ActionEvent.ACTION_PERFORMED, stroke));
    }

    private static <T extends Component> T find(Container parent, Class<T> type, Predicate<T> predicate) {
        for (Component child : parent.getComponents()) {
            if (type.isInstance(child) && predicate.test(type.cast(child))) return type.cast(child);
            if (child instanceof Container container) {
                T found = find(container, type, predicate);
                if (found != null) return found;
            }
        }
        return null;
    }
}
