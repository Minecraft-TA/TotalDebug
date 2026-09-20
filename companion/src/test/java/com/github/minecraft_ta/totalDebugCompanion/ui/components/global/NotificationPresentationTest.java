package com.github.minecraft_ta.totalDebugCompanion.ui.components.global;

import com.github.minecraft_ta.totalDebugCompanion.notification.NotificationCenter.Severity;
import com.github.minecraft_ta.totalDebugCompanion.notification.NotificationCenter.Source;
import com.github.minecraft_ta.totalDebugCompanion.model.ServiceStatus;
import com.github.minecraft_ta.totalDebugCompanion.navigation.NavigationTarget;
import com.github.minecraft_ta.totalDebugCompanion.runtime.RuntimeIndexService;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.CompanionTheme;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.ThemeManager;
import org.junit.jupiter.api.Test;
import javax.swing.JButton;
import javax.swing.JList;
import javax.swing.JPopupMenu;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.imageio.ImageIO;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
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
        JButton open = field(widget.get(), "openSource", JButton.class);
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
    @Test void detailsStayLiteralSelectableAndKeepSelectionForAnUnchangedUpdate() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var panel = new StatusDetailsPanel("<html>Run failed</html>\nSecond line");
            JTextArea text = find(panel, JTextArea.class);
            assertFalse(text.isEditable());
            assertEquals("<html>Run failed</html>\nSecond line", text.getText());
            text.select(7, 10);
            panel.setDetails(text.getText());
            assertEquals(7, text.getSelectionStart());
            assertEquals(10, text.getSelectionEnd());
            assertTrue(find(panel, JButton.class).isEnabled());
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
                bar.setGameStatus(new ServiceStatus(ServiceStatus.State.AVAILABLE, "Connected", "Minecraft is connected and authenticated."));
                bar.setMcpStatus(new ServiceStatus(ServiceStatus.State.AVAILABLE, "Listening", "http://127.0.0.1:32123/mcp"));
                for (RuntimeIndexService.Phase phase : RuntimeIndexService.Phase.values()) {
                    bar.setRuntimeStatus(new RuntimeIndexService.Status(phase, "Runtime index " + phase.name().toLowerCase(), null));
                    JButton index = button(bar, "Runtime index " + phase.name().toLowerCase());
                    assertNotNull(index);
                    assertTrue(index.isEnabled());
                    assertTrue(index.isVisible());
                    assertTrue(index.isFocusable());
                }
                bar.setRuntimeStatus(new RuntimeIndexService.Status(RuntimeIndexService.Phase.BUILDING, "Building class index", null));
                capture(bar, new Dimension(1050, 24), output.resolve("status-" + theme.id() + ".png"));
                var widget = find(bar, NotificationWidget.class);
                try {
                    var field = NotificationWidget.class.getDeclaredField("popup");
                    field.setAccessible(true);
                    var popup = (JPopupMenu) field.get(widget);
                    capture(popup, popup.getPreferredSize(), output.resolve("history-" + theme.id() + ".png"));
                    assertTrue(popup.getWidth() <= 520);
                    assertTrue(popup.getHeight() <= 450);
                    assertNotNull(find(popup, JList.class));
                } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
            });
        }
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
