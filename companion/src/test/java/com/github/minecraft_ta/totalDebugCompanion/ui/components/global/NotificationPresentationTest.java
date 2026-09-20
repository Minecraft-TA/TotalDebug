package com.github.minecraft_ta.totalDebugCompanion.ui.components.global;

import com.github.minecraft_ta.totalDebugCompanion.notification.NotificationCenter.Severity;
import com.github.minecraft_ta.totalDebugCompanion.notification.NotificationCenter.Source;
import com.github.minecraft_ta.totalDebugCompanion.model.ServiceStatus;
import com.github.minecraft_ta.totalDebugCompanion.navigation.NavigationTarget;
import com.github.minecraft_ta.totalDebugCompanion.runtime.RuntimeIndexService;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.CompanionTheme;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.ThemeManager;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.ThemeColors;
import org.junit.jupiter.api.Test;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JCheckBox;
import javax.swing.JTextField;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JTextArea;
import com.github.minecraft_ta.totalDebugCompanion.ui.PopupElements;
import com.github.minecraft_ta.totalDebugCompanion.runtime.IndexIdentity;
import javax.swing.SwingUtilities;
import javax.imageio.ImageIO;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.BorderLayout;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class NotificationPresentationTest extends StatusBarTestFixture {
    @TempDir Path directory;

    @Test void unrelatedPublicationDoesNotCancelSourceActivation() throws Exception {
        Path file = Files.writeString(directory.resolve("Test.tdscript"), "return 1;");
        var source = new Source("Test", "project", directory.toString(), new NavigationTarget.LocalFile(file), null);
        var opened = new CompletableFuture<Source>();
        var widget = new AtomicReference<NotificationWidget>();
        notifications.publish(Severity.ERROR, "Run failed", "", source);
        SwingUtilities.invokeAndWait(() -> widget.set(new NotificationWidget(notifications, ignored -> null, opened::complete)));
        JPopupMenu popup = field(widget.get(), "popup", JPopupMenu.class);
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
            JPopupMenu popup = field(find(bar, NotificationWidget.class), "popup", JPopupMenu.class);
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
            assertTrue(widget.getText().contains("Unable to save"));
            notifications.acknowledge(Set.of(event));
            assertEquals("", widget.getText());
            notifications.publish(Severity.SUCCESS, "Formatted", "", Source.application("Test"));
            assertTrue(widget.getText().contains("Formatted"));
            bar.get().dispose();
            notifications.publish(Severity.ERROR, "Late", "", Source.application("Test"));
            assertTrue(widget.getText().contains("Formatted"));
        });
    }

    @Test void captureBoundedHistoryAndAlwaysInteractiveIndexInBothThemes() throws Exception {
        Path output = Files.createDirectories(Path.of("build/notification-preview"));
        for (CompanionTheme theme : CompanionTheme.available()) {
            SwingUtilities.invokeAndWait(() -> {
                ThemeManager.installTheme(theme);
                notifications.clear();
                notifications.publish(Severity.INFORMATION, "Run cancelled", "Cancelled before execution", Source.application("CameraDrag"));
                notifications.publish(Severity.SUCCESS, "Applied 3 formatting edits", "", Source.application("Test"));
                notifications.publish(Severity.ERROR, "Compilation failed", "EventListener cannot be resolved to a type\nTest.tdscript:18", Source.application("Test"));
                ApplicationStatusBar bar = statusBar(target -> {});
                bar.setMcpToggle(ignored -> {});
                bar.setGameStatus(new ServiceStatus(ServiceStatus.State.AVAILABLE, "Connected", "Minecraft is connected and authenticated."));
                bar.setMcpStatus(new ServiceStatus(ServiceStatus.State.AVAILABLE, "Listening", "http://127.0.0.1:32123/mcp"));
                assertEquals(ThemeColors.link().getRGB(), field(bar, "mcpEndpoint", JButton.class).getForeground().getRGB());
                for (RuntimeIndexService.Phase phase : RuntimeIndexService.Phase.values()) {
                    bar.setRuntimeStatus(new RuntimeIndexService.Status(phase, "Runtime index " + phase.name().toLowerCase(), null));
                    JButton index = field(bar, "taskState", JButton.class);
                    assertNotNull(index);
                    assertTrue(index.isEnabled());
                    assertTrue(index.isVisible());
                    assertTrue(index.isFocusable());
                }
                bar.setRuntimeStatus(new RuntimeIndexService.Status(RuntimeIndexService.Phase.READY, "Runtime index ready", null,
                        IndexIdentity.Kind.RUNTIME, new RuntimeIndexService.Metrics(82314, 10_500_000_000L, true)));
                bar.setGameIdentity("All the Mods 10 - To the Sky", Path.of("C:/Games/ATM10SKY/minecraft"), 24064);
                capture(bar, new Dimension(1050, 24), output.resolve("status-" + theme.id() + ".png"));
                var widget = find(bar, NotificationWidget.class);
                try {
                    var field = NotificationWidget.class.getDeclaredField("popup");
                    field.setAccessible(true);
                    var popup = (JPopupMenu) field.get(widget);
                    button(popup, "Compilation failed").doClick(0);
                    capture(popup, popup.getPreferredSize(), output.resolve("history-" + theme.id() + ".png"));
                    assertTrue(popup.getWidth() <= 520);
                    assertTrue(popup.getHeight() <= 450);
                    assertNull(find(popup, JTextArea.class));
                    for (String name : new String[]{"mcpStatus", "gameStatus"}) {
                        var service = field(bar, name, ServiceStatusWidget.class);
                        var servicePopup = field(service, "popup", JPopupMenu.class);
                        capture(servicePopup, servicePopup.getPreferredSize(), output.resolve(name + "-" + theme.id() + ".png"));
                    }
                    var indexPopup = field(bar, "taskPopup", JPopupMenu.class);
                    capture(indexPopup, indexPopup.getPreferredSize(), output.resolve("index-" + theme.id() + ".png"));
                } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
            });
        }
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
            assertFalse(field(bar, "mcpEndpoint", JButton.class).isVisible());
            assertFalse(field(bar, "mcpEndpoint", JButton.class).isEnabled());
            bar.setGameIdentity("Pack", directory, 1);
            JButton copyDirectory = field(bar, "gameDirectory", JButton.class);
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
                frame.setContentPane(body);
                frame.setSize(700, 400);
                frame.setVisible(true);
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
                var popup = field(widget, "popup", JPopupMenu.class);
                notifications.publish(Severity.INFORMATION, "New while open", "", Source.application("Test"));
                assertTrue(popup.isVisible());
                assertEquals(0, balloon.entryId());
            } finally { widget.close(); frame.dispose(); }
        });
    }

    private static void capture(Component component, Dimension size, Path path) {
        component.setSize(size);
        layout(component);
        var image = new BufferedImage(size.width, size.height, BufferedImage.TYPE_INT_ARGB);
        var graphics = image.createGraphics();
        component.printAll(graphics);
        graphics.dispose();
        try { ImageIO.write(image, "png", path.toFile()); } catch (Exception failure) { throw new AssertionError(failure); }
    }
    private static <T> T field(Object owner, String name, Class<T> type) {
        try { var field = owner.getClass().getDeclaredField(name); field.setAccessible(true); return type.cast(field.get(owner)); }
        catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
    private static void layout(Component component) {
        if (component instanceof Container container) { container.doLayout(); for (Component child : container.getComponents()) layout(child); }
    }
    private static JButton button(Container container, String text) {
        for (Component child : container.getComponents()) {
            if (child instanceof JButton button && text.equals(button.getText())) return button;
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
