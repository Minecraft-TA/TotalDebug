package com.github.minecraft_ta.totalDebugCompanion.ui.components.global;

import com.github.minecraft_ta.totalDebugCompanion.testui.UiTest;
import com.github.minecraft_ta.totalDebugCompanion.testui.UiTestScope;
import com.github.minecraft_ta.totalDebugCompanion.notification.NotificationCenter.Severity;
import com.github.minecraft_ta.totalDebugCompanion.notification.NotificationCenter.Source;
import com.github.minecraft_ta.totalDebugCompanion.model.ServiceStatus;
import com.github.minecraft_ta.totalDebugCompanion.navigation.NavigationTarget;
import com.github.minecraft_ta.totalDebugCompanion.runtime.RuntimeIndexService;
import org.junit.jupiter.api.Test;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JCheckBox;
import javax.swing.JTextField;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import com.github.minecraft_ta.totalDebugCompanion.ui.PopupElements;
import com.github.minecraft_ta.totalDebugCompanion.ui.CopyValue;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.awt.BorderLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

@UiTest
class NotificationPresentationTest extends StatusBarTestFixture {
    @TempDir Path directory;

    @Test void unrelatedPublicationDoesNotCancelSourceActivation() throws Exception {
        Path file = Files.writeString(directory.resolve("Test.tdscript"), "return 1;");
        var source = new Source("Test", "project", directory.toString(), new NavigationTarget.LocalFile(file), null);
        var opened = new CompletableFuture<Source>();
        var widget = new AtomicReference<NotificationWidget>();
        notifications.publish(Severity.ERROR, "Run failed", "", source);
        SwingUtilities.invokeAndWait(() -> widget.set(new NotificationWidget(notifications, ignored -> null, opened::complete)));
        JPanel popup = widget.get().historyPanel();
        SwingUtilities.invokeAndWait(() -> button(popup, "Run failed").doClick(0));
        JButton open = button(popup, "Open script");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        var enabled = new AtomicReference<>(false);
        while (!enabled.get() && System.nanoTime() < deadline) {
            SwingUtilities.invokeAndWait(() -> enabled.set(open.isEnabled()));
            if (!enabled.get()) Thread.sleep(5);
        }
        assertTrue(enabled.get());
        SwingUtilities.invokeAndWait(() -> {
            open.doClick(0);
            notifications.publish(Severity.SUCCESS, "Unrelated operation", "", Source.application("Index"));
        });
        assertEquals(source, opened.get(5, TimeUnit.SECONDS));
        SwingUtilities.invokeAndWait(() -> widget.get().close());
    }
    @Test void detailsAreLiteralLabelsAndActionsBelongToExpandedRows() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JLabel text = PopupElements.label("");
            PopupElements.wrappedText(text, "<html>Run failed</html>\nSecond line", 300);
            assertTrue(text.getText().contains("&lt;html&gt;Run&nbsp;failed&lt;/html&gt;<br>Second&nbsp;line"));
            PopupElements.wrappedText(text, "C:/" + "long-path-segment/".repeat(100), 320);
            assertTrue(text.getPreferredSize().width <= 325, text.getPreferredSize().toString());
            notifications.publish(Severity.ERROR, "Run failed", "Details", Source.application("Test"));
            ApplicationStatusBar bar = statusBar(target -> {});
            JPanel popup = find(bar, NotificationWidget.class).historyPanel();
            assertNull(find(popup, JTextArea.class));
            assertNull(find(popup, JTextField.class));
            JButton copy = button(popup, "Copy details");
            assertFalse(copy.getParent().getParent().isVisible());
            button(popup, "Run failed").doClick(0);
            assertTrue(copy.getParent().getParent().isVisible());
            notifications.publish(Severity.SUCCESS, "Another result", "", Source.application("Index"));
            assertTrue(SwingUtilities.isDescendingFrom(copy, popup));
            assertTrue(copy.getParent().getParent().isVisible());
        });
    }

    @Test void historySurvivesEditorSelectionAndDisposalRejectsQueuedPublication() throws Exception {
        var bar = new AtomicReference<ApplicationStatusBar>();
        long event = notifications.publish(Severity.ERROR, "Unable to save", "Disk full", Source.application("Test"));
        SwingUtilities.invokeAndWait(() -> {
            bar.set(statusBar(target -> {}));
            bar.get().setEditor(null);
            assertEquals(1, notifications.snapshot().entries().size());
            var widget = find(bar.get(), NotificationWidget.class);
            assertTrue(widget.messageButton().getText().contains("Unable to save"));
            notifications.acknowledge(Set.of(event));
            assertEquals("", widget.messageButton().getText());
            notifications.publish(Severity.SUCCESS, "Formatted", "", Source.application("Test"));
            assertTrue(widget.messageButton().getText().contains("Formatted"));
            bar.get().dispose();
            notifications.publish(Severity.ERROR, "Late", "", Source.application("Test"));
            assertTrue(widget.messageButton().getText().contains("Formatted"));
        });
    }

    @Test void mcpToggleAndIndexRefreshFollowPublishedState() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var bar = statusBar(target -> {});
            var enabled = new AtomicReference<Boolean>();
            bar.setMcpToggle(enabled::set);
            bar.setMcpStatus(new ServiceStatus(ServiceStatus.State.AVAILABLE, "Listening", "http://127.0.0.1:32123/mcp"));
            JCheckBox checkbox = field(bar, "mcpEnabled", JCheckBox.class);
            assertTrue(checkbox.isSelected());
            checkbox.doClick(0);
            assertEquals(false, enabled.get());
            assertFalse(checkbox.isEnabled());
            bar.setMcpStatus(new ServiceStatus(ServiceStatus.State.INACTIVE, "Stopped", "Stopped"));
            assertTrue(checkbox.isEnabled());
            assertFalse(field(bar, "mcpEndpoint", CopyValue.class).isVisible());
            assertFalse(button(field(bar, "mcpEndpoint", CopyValue.class), "Copy MCP endpoint").isEnabled());
            bar.setGameIdentity("Pack", directory);
            JButton copyDirectory = button(field(bar, "gameDirectory", CopyValue.class), "Copy game directory");
            copyDirectory.getModel().setArmed(true);
            copyDirectory.getModel().setPressed(true);
            bar.setGameStatus(new ServiceStatus(ServiceStatus.State.INACTIVE, "Offline", "Disconnected"));
            copyDirectory.getModel().setPressed(false);
            assertFalse(copyDirectory.isEnabled());
            bar.setRuntimeStatus(new RuntimeIndexService.Status(RuntimeIndexService.Phase.BUILDING, "Building class index", null));
            assertFalse(field(bar, "retry", JButton.class).isEnabled());
            bar.setRuntimeStatus(new RuntimeIndexService.Status(RuntimeIndexService.Phase.READY, "Ready", null));
            assertTrue(field(bar, "retry", JButton.class).isEnabled());
            bar.setRuntimeStatus(new RuntimeIndexService.Status(RuntimeIndexService.Phase.EMPTY, "No mod archives found", null));
            assertTrue(field(bar, "retry", JButton.class).isEnabled());
        });
    }

    @Test void balloonsOnlyInterruptForNewUnseenProblemsAndNeverTakeFocus() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var widget = new NotificationWidget(notifications, source -> null, source -> {});
            JFrame frame = new JFrame();
            try {
                JPanel body = new JPanel(new BorderLayout());
                body.add(widget, BorderLayout.SOUTH);
                body.add(widget.historyPanel(), BorderLayout.EAST);
                frame.setContentPane(body);
                frame.setSize(700, 400);
                UiTestScope.show(frame);
                var balloon = field(widget, "balloon", NotificationBalloon.class);
                notifications.publish(Severity.SUCCESS, "Formatted", "", Source.application("Test"));
                assertEquals(0, balloon.entryId());
                widget.setSourceVisible(source -> true);
                notifications.publish(Severity.ERROR, "Visible compiler result", "", Source.application("Test"), true);
                assertEquals(0, balloon.entryId());
                long id = notifications.publish(Severity.ERROR, "Unable to save", "Disk full", Source.application("Test"));
                assertEquals(id, balloon.entryId());
                JPanel panel = field(balloon, "panel", JPanel.class);
                assertSame(frame.getLayeredPane(), panel.getParent());
                notifications.update(id, "Still full");
                assertSame(panel, field(balloon, "panel", JPanel.class));
                notifications.dismiss(id);
                assertEquals(0, balloon.entryId());
                notifications.publish(Severity.ERROR, "Long error path ".repeat(1000), "", Source.application("Test"));
                panel = field(balloon, "panel", JPanel.class);
                assertTrue(panel.getHeight() < 200);
                assertTrue(panel.getY() >= 0);
                assertTrue(panel.getY() + panel.getHeight() < frame.getLayeredPane().getHeight());
                widget.doClick(0);
                var popup = widget.historyPanel();
                notifications.publish(Severity.INFORMATION, "New while open", "", Source.application("Test"));
                assertTrue(popup.isVisible());
                assertEquals(0, balloon.entryId());
            } finally { widget.close(); frame.dispose(); }
        });
    }

    private static <T> T field(Object owner, String name, Class<T> type) {
        try { var field = owner.getClass().getDeclaredField(name); field.setAccessible(true); return type.cast(field.get(owner)); }
        catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
    private static JButton button(Container container, String text) {
        for (Component child : container.getComponents()) {
            if (child instanceof JButton button && text.equals(button.getAccessibleContext().getAccessibleName())) return button;
            if (child instanceof Container nested) { var result = button(nested, text); if (result != null) return result; }
        }
        return null;
    }
    private static <T> T find(Container container, Class<T> type) {
        for (Component child : container.getComponents()) {
            if (type.isInstance(child)) return type.cast(child);
            if (child instanceof Container nested) { T result = find(nested, type); if (result != null) return result; }
        }
        return null;
    }
}
