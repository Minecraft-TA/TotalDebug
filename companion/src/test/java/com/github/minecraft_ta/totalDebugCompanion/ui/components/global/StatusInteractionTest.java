package com.github.minecraft_ta.totalDebugCompanion.ui.components.global;

import com.github.minecraft_ta.totalDebugCompanion.testui.UiTest;
import com.github.minecraft_ta.totalDebugCompanion.testui.UiTestScope;
import com.github.minecraft_ta.totalDebugCompanion.model.ServiceStatus;
import com.github.minecraft_ta.totalDebugCompanion.notification.NotificationCenter.Severity;
import com.github.minecraft_ta.totalDebugCompanion.notification.NotificationCenter.Source;
import com.github.minecraft_ta.totalDebugCompanion.project.ProjectScope;
import com.github.minecraft_ta.totalDebugCompanion.runtime.IndexIdentity;
import com.github.minecraft_ta.totalDebugCompanion.runtime.RuntimeIndexService;
import com.github.minecraft_ta.totalDebugCompanion.script.EditorScriptRunService;
import com.github.minecraft_ta.totalDebugCompanion.session.CompanionProfile;
import com.github.minecraft_ta.totalDebugCompanion.storage.ChangeRecord;
import com.github.minecraft_ta.totalDebugCompanion.storage.InstanceState;
import com.github.minecraft_ta.totalDebugCompanion.ui.CopyValue;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.CompanionTheme;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.ThemeManager;
import com.github.minecraft_ta.totaldebug.protocol.execution.ScriptExecutionEnvironment;
import org.junit.jupiter.api.Test;
import com.github.minecraft_ta.totalDebugCompanion.testui.RequiresDesktop;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.imageio.ImageIO;
import javax.swing.JFrame;
import javax.swing.JButton;
import javax.swing.JScrollPane;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Rectangle;
import java.awt.event.ActionEvent;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@UiTest
class StatusInteractionTest extends StatusBarTestFixture {
    @Test void anInactiveHistoryPanelDoesNotReadNewEvents() throws Exception {
        var active = new AtomicBoolean();
        var frame = new JFrame() { @Override public boolean isActive() { return active.get(); } };
        var widget = new AtomicReference<NotificationWidget>();
        try {
            SwingUtilities.invokeAndWait(() -> {
                widget.set(new NotificationWidget(notifications, source -> null, source -> {}));

                frame.add(widget.get(), BorderLayout.SOUTH);
                frame.add(widget.get().historyPanel(), BorderLayout.CENTER);
                frame.setSize(420, 400);
                UiTestScope.show(frame);
                widget.get().doClick(0);
                notifications.publish(Severity.INFORMATION, "Arrived in the background", "", Source.application("Index"));
                frame.validate();
            });
            SwingUtilities.invokeAndWait(() -> assertEquals(1, notifications.snapshot().unread()));
            SwingUtilities.invokeAndWait(() -> {
                active.set(true);
                for (var listener : frame.getWindowListeners()) listener.windowActivated(new WindowEvent(frame, WindowEvent.WINDOW_ACTIVATED));
            });
            SwingUtilities.invokeAndWait(() -> assertEquals(0, notifications.snapshot().unread()));
        } finally {
            SwingUtilities.invokeAndWait(() -> { widget.get().close(); frame.dispose(); });
        }
    }

    @Test void historyIsContainedAndBellStaysPutInBothThemes() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            for (CompanionTheme theme : CompanionTheme.available()) {
                ThemeManager.installTheme(theme);
                var bar = statusBar(target -> {});
                var widget = field(bar, "notifications", NotificationWidget.class);
                var workspace = new WorkspacePanel(new JLabel("Files"), new JPanel(), new JTextArea(), bar);
                workspace.setNotificationPanel(bar.notificationPanel());
                JFrame frame = new JFrame();

                frame.setContentPane(workspace);
                try {
                    frame.setSize(984, 620);
                    UiTestScope.show(frame);
                    bar.setRuntimeStatus(new RuntimeIndexService.Status(RuntimeIndexService.Phase.READY, "Runtime index ready", null,
                            IndexIdentity.Kind.RUNTIME, new RuntimeIndexService.Metrics(82314, 10_500_000_000L, true)));
                    frame.validate();
                    Rectangle bell = SwingUtilities.convertRectangle(widget.getParent(), widget.getBounds(), bar);
                    assertEquals(bar.getWidth() - bar.getInsets().right, bell.x + bell.width);
                    notifications.publish(Severity.SUCCESS, "A long status message ".repeat(30), "", Source.application("Script"));
                    frame.validate();
                    assertEquals(bell, SwingUtilities.convertRectangle(widget.getParent(), widget.getBounds(), bar));
                    for (int i = 0; i < 30; i++) notifications.publish(Severity.INFORMATION, "Run " + i, "Long/path/".repeat(100), Source.application("Test"));
                    widget.doClick(0);
                    frame.validate();
                    assertTrue(widget.historyPanel().isShowing());
                    assertSame(workspace, widget.historyPanel().getParent());
                    assertTrue(new Rectangle(0, 0, workspace.getWidth(), workspace.getHeight()).contains(widget.historyPanel().getBounds()));
                    assertEquals(bell, SwingUtilities.convertRectangle(widget.getParent(), widget.getBounds(), bar));
                    assertTrue(widget.historyPanel().getWidth() <= frame.getWidth() / 2);
                    capture(frame, "history-" + theme.id());
                    frame.setSize(720, 500);
                    frame.validate();
                    assertTrue(new Rectangle(0, 0, workspace.getWidth(), workspace.getHeight()).contains(widget.historyPanel().getBounds()));
                    assertTrue(widget.historyPanel().getWidth() <= 360);
                    widget.historyPanel().getActionMap().get("closeHistory").actionPerformed(new ActionEvent(widget, 0, ""));
                    frame.validate();
                    assertFalse(widget.historyPanel().isVisible());
                    assertFalse(widget.isSelected());
                } finally { frame.dispose(); bar.dispose(); notifications.clear(); }
            }
        });
    }

    @RequiresDesktop
    @Test void realServiceMenusFitTheOwnerAtEitherSideOfTheScreen() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var bar = statusBar(target -> {});
            JFrame frame = new JFrame();

            frame.add(bar, BorderLayout.SOUTH);
            try {
                Rectangle screen = frame.getGraphicsConfiguration().getBounds();
                for (int x : new int[]{screen.x + 30, screen.x + Math.max(30, screen.width - 1010)}) {
                    frame.setBounds(x, screen.y + 100, 984, 550);
                    UiTestScope.show(frame);
                    bar.setMcpToggle(ignored -> {});
                    bar.setMcpStatus(new ServiceStatus(ServiceStatus.State.AVAILABLE, "Listening", "http://127.0.0.1:32123/mcp"));
                    bar.setGameStatus(new ServiceStatus(ServiceStatus.State.AVAILABLE, "Connected", "Connected"));
                    bar.setGameIdentity("All the Mods 10", Path.of("C:/Games/ATM10SKY/minecraft"));
                    frame.validate();
                    for (String name : new String[]{"mcpStatus", "gameStatus"}) {
                        var service = field(bar, name, ServiceStatusWidget.class);
                        service.doClick(0);
                        var popup = field(service, "popup", JPopupMenu.class);
                        assertTrue(popup.isShowing());
                        Rectangle owner = new Rectangle(frame.getRootPane().getLocationOnScreen(), frame.getRootPane().getSize());
                        assertTrue(owner.contains(new Rectangle(popup.getLocationOnScreen(), popup.getSize())), name);
                        capture(frame, name + "-" + x);
                        popup.setVisible(false);
                    }
                    var mcp = field(bar, "mcpStatus", ServiceStatusWidget.class);
                    bar.setMcpStatus(new ServiceStatus(ServiceStatus.State.INACTIVE, "Stopped", "Stopped"));
                    mcp.doClick(0);
                    var mcpPopup = field(mcp, "popup", JPopupMenu.class);
                    int stoppedWidth = mcpPopup.getWidth();
                    bar.setMcpStatus(new ServiceStatus(ServiceStatus.State.AVAILABLE, "Listening", "http://127.0.0.1:32123/mcp"));
                    assertTrue(mcpPopup.isVisible());
                    assertTrue(mcpPopup.getWidth() > stoppedWidth, stoppedWidth + " -> " + mcpPopup.getWidth() + " preferred " + mcpPopup.getPreferredSize());
                    int readyWidth = mcpPopup.getWidth();
                    mcpPopup.setVisible(false);
                    bar.setMcpStatus(new ServiceStatus(ServiceStatus.State.INACTIVE, "Stopped", "Stopped"));
                    mcp.doClick(0);
                    assertTrue(mcpPopup.getWidth() < readyWidth);
                    mcpPopup.setVisible(false);
                    var game = field(bar, "gameStatus", ServiceStatusWidget.class);
                    game.doClick(0);
                    bar.setGameStatus(new ServiceStatus(ServiceStatus.State.INACTIVE, "Offline", "Disconnected"));
                    assertFalse(game.isEnabled());
                    assertFalse(field(game, "popup", JPopupMenu.class).isVisible());
                    assertEquals("Disconnected", game.getToolTipText());
                }
            } finally { frame.dispose(); bar.dispose(); }
        });
    }

    @Test void reconnectShowsRealStateAndKeepsThePopupUsableInBothThemes() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            for (CompanionTheme theme : CompanionTheme.available()) {
                ThemeManager.installTheme(theme);
                var bar = statusBar(target -> {});
                var game = field(bar, "gameStatus", ServiceStatusWidget.class);
                var retry = field(bar, "gameReconnect", JButton.class);
                var popup = field(game, "popup", JPopupMenu.class);
                var directoryRow = field(bar, "gameDirectoryRow", JPanel.class);
                var calls = new AtomicInteger();
                var attempt = new CompletableFuture<Void>();
                var frame = new JFrame();

                frame.add(bar, BorderLayout.SOUTH);
                frame.setSize(720, 400);
                try {
                    assertFalse(game.isEnabled());
                    assertFalse(retry.isVisible());
                    bar.setGameReconnect(() -> { calls.incrementAndGet(); return attempt; });
                    assertTrue(game.isEnabled());
                    assertTrue(retry.getText() == null || retry.getText().isEmpty());
                    assertEquals("Reconnect to the selected Minecraft instance", retry.getToolTipText());
                    UiTestScope.show(frame);
                    game.doClick(0);
                    assertTrue(popup.isShowing());
                    retry.doClick(0);
                    retry.doClick(0);
                    assertEquals(1, calls.get());
                    assertFalse(retry.isEnabled());
                    bar.setGameStatus(new ServiceStatus(ServiceStatus.State.PENDING, "Reconnecting", "Waiting for Minecraft to reconnect."));
                    attempt.complete(null);
                    assertFalse(retry.isEnabled(), "Only backend connection state can enable the pending action");
                    assertEquals("Game: Reconnecting", game.getText());
                    assertTrue(popup.isShowing());
                    bar.setGameStatus(new ServiceStatus(ServiceStatus.State.AVAILABLE, "Connected", "Minecraft is connected."));
                    bar.setGameIdentity("All the Mods 10", Path.of("C:/Games/ATM10/minecraft"));
                    assertTrue(retry.getText() == null || retry.getText().isEmpty());
                    assertEquals("Reconnect to the selected Minecraft instance", retry.getToolTipText());
                    assertTrue(retry.isEnabled());
                    assertTrue(directoryRow.isVisible());
                    capture(frame, "game-connected-" + theme.id());
                    bar.setGameStatus(new ServiceStatus(ServiceStatus.State.FAILED, "Connection failed", "No matching Minecraft connected within 30 seconds."));
                    assertTrue(popup.isShowing());
                    assertTrue(retry.isEnabled());
                    assertTrue(retry.getText() == null || retry.getText().isEmpty());
                    assertEquals("Reconnect to the selected Minecraft instance", retry.getToolTipText());
                    assertFalse(directoryRow.isVisible());
                    Rectangle owner = new Rectangle(frame.getRootPane().getLocationOnScreen(), frame.getRootPane().getSize());
                    assertTrue(owner.contains(new Rectangle(popup.getLocationOnScreen(), popup.getSize())));
                    capture(frame, "game-retry-" + theme.id());
                    assertTrue(notifications.snapshot().entries().isEmpty(), "UI must not duplicate backend notifications");
                    bar.setGameReconnect(null);
                    assertFalse(popup.isVisible());
                    assertFalse(game.isEnabled());
                } finally {
                    frame.dispose();
                    bar.dispose();
                }
            }
        });
    }

    @Test void staleReconnectCompletionCannotEnableAnotherAttemptOrADisposedControl() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var bar = statusBar(target -> {});
            var retry = field(bar, "gameReconnect", JButton.class);
            var oldAttempt = new CompletableFuture<Void>();
            var newAttempt = new CompletableFuture<Void>();
            bar.setGameReconnect(() -> oldAttempt);
            retry.doClick(0);
            bar.setGameReconnect(null);
            bar.setGameReconnect(() -> newAttempt);
            retry.doClick(0);
            oldAttempt.completeExceptionally(new IllegalStateException("Project changed"));
            assertFalse(retry.isEnabled());
            newAttempt.completeExceptionally(new IllegalStateException("Connection failed"));
            assertTrue(retry.isEnabled());
            var closingAttempt = new CompletableFuture<Void>();
            bar.setGameReconnect(() -> closingAttempt);
            retry.doClick(0);
            bar.dispose();
            closingAttempt.complete(null);
            assertFalse(retry.isEnabled());
        });
    }

    @Test void gameDirectoryLabelsHandleRootsAndRootChildren() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var bar = statusBar(target -> {});
            CopyValue directory = field(bar, "gameDirectory", CopyValue.class);
            JLabel label = field(directory, "label", JLabel.class);
            Path root = Path.of("").toAbsolutePath().getRoot();
            for (Path path : new Path[]{root, root.resolve("Minecraft"), Path.of("Minecraft")}) {
                bar.setGameIdentity("Test", path);
                assertEquals(path.toString(), label.getText());
                assertEquals(path.toString(), label.getToolTipText());
            }
            Path nested = root.resolve("Games").resolve("Minecraft");
            bar.setGameIdentity("Test", nested);
            String separator = nested.getFileSystem().getSeparator();
            assertEquals("\u2026" + separator + "Games" + separator + "Minecraft", label.getText());
            assertEquals(nested.toString(), label.getToolTipText());
        });
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void activityPopupAndStopControlsStayInsideTheirViewports(boolean longName, @TempDir Path directory) throws Exception {
        var project = new ProjectScope(new Object(), new CompanionProfile("activity-test", directory, directory), InstanceState.inMemory(), ChangeRecord.inMemory());
        try {
            SwingUtilities.invokeAndWait(() -> {
                var bar = statusBar(target -> {});
                var activity = field(bar, "scriptActivity", ScriptActivityWidget.class);
                var runs = field(activity, "runs", EditorScriptRunService.class);
                var frame = new JFrame();
                // Put the actual activity control at the right edge of a small owner.
                frame.add(activity, BorderLayout.SOUTH);
                frame.setSize(340, 400);
                UiTestScope.show(frame);
                var inspected = new AtomicBoolean();
                // Inspect the real initial run event before the disconnected fixture rejects submission.
                Runnable unsubscribe = runs.subscribe(() -> {
                    if (runs.activeRuns().isEmpty() || !inspected.compareAndSet(false, true)) return;
                    frame.validate();
                    activity.doClick(0);
                    var popup = field(activity, "popup", JPopupMenu.class);
                    var scroll = field(activity, "scroll", JScrollPane.class);
                    var rows = field(activity, "rows", JPanel.class);
                    JPanel row = (JPanel) rows.getComponent(0);
                    JButton stop = field(row, "stop", JButton.class);
                    Rectangle stopBounds = SwingUtilities.convertRectangle(row, stop.getBounds(), scroll.getViewport());
                    assertTrue(new Rectangle(scroll.getViewport().getSize()).contains(stopBounds), "Stop must remain visible");
                    Rectangle owner = new Rectangle(frame.getRootPane().getLocationOnScreen(), frame.getRootPane().getSize());
                    assertTrue(owner.contains(new Rectangle(popup.getLocationOnScreen(), popup.getSize())), "Activity popup must stay in its owner");
                    stop.doClick(0);
                    assertEquals(EditorScriptRunService.Phase.STOPPING, runs.activeRuns().getFirst().state().phase());
                    assertFalse(stop.isEnabled());
                    assertTrue(owner.contains(new Rectangle(popup.getLocationOnScreen(), popup.getSize())), "Refresh must preserve containment");
                });
                try {
                    String label = longName ? "Long script name ".repeat(30) : "Test";
                    runs.start(project, Source.capture(project, label, null), "", false, ScriptExecutionEnvironment.THREAD);
                    assertTrue(inspected.get());
                    assertFalse(field(activity, "popup", JPopupMenu.class).isVisible());
                } finally {
                    unsubscribe.run();
                    frame.dispose();
                    bar.dispose();
                }
            });
        } finally {
            project.retire();
            project.close();
        }
    }

    private static <T> T field(Object object, String name, Class<T> type) {
        try { var field = object.getClass().getDeclaredField(name); field.setAccessible(true); return type.cast(field.get(object)); }
        catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }

    /** Prints the displayed root, including popups positioned by their real action listeners. */
    private static void capture(JFrame frame, String name) {
        try {
            Path output = Files.createDirectories(Path.of("build/status-interaction-preview"));
            var root = frame.getRootPane();
            var image = new BufferedImage(root.getWidth(), root.getHeight(), BufferedImage.TYPE_INT_RGB);
            var graphics = image.createGraphics();
            root.printAll(graphics);
            graphics.dispose();
            ImageIO.write(image, "png", output.resolve(name + ".png").toFile());
        } catch (Exception failure) { throw new AssertionError(failure); }
    }
}
