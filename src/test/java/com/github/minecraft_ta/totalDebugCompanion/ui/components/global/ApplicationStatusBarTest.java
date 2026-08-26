package com.github.minecraft_ta.totalDebugCompanion.ui.components.global;

import com.github.minecraft_ta.totalDebugCompanion.model.ServiceStatus;
import com.github.minecraft_ta.totalDebugCompanion.runtime.RuntimeIndexService;
import com.github.minecraft_ta.totalDebugCompanion.ui.UiMetrics;
import org.junit.jupiter.api.Test;

import javax.swing.JProgressBar;
import javax.swing.JButton;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class ApplicationStatusBarTest {

    @Test
    void usesAThinFixedWidthActivityIndicator() throws Exception {
        AtomicReference<ApplicationStatusBar> result = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            ApplicationStatusBar bar = new ApplicationStatusBar();
            bar.setRuntimeStatus(new RuntimeIndexService.Status(
                    RuntimeIndexService.Phase.BUILDING,
                    "Building class index",
                    null
            ));
            result.set(bar);
        });

        ApplicationStatusBar bar = result.get();
        JProgressBar progress = find(bar, JProgressBar.class);

        assertNotNull(progress);
        assertEquals(new Dimension(88, 3), progress.getPreferredSize());
        assertEquals(new Dimension(88, 3), progress.getMaximumSize());
        assertFalse(progress.isStringPainted());
        assertEquals(UiMetrics.STATUS_BAR_HEIGHT, bar.getPreferredSize().height);
    }

    @Test
    void serviceWidgetsRenderOnlyPublishedState() throws Exception {
        AtomicReference<ApplicationStatusBar> result = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> result.set(new ApplicationStatusBar()));
        ApplicationStatusBar bar = result.get();

        assertNotNull(findButton(bar, "Game: Offline"));
        assertNotNull(findButton(bar, "MCP: Stopped"));

        SwingUtilities.invokeAndWait(() -> {
            bar.setGameStatus(new ServiceStatus(
                    ServiceStatus.State.AVAILABLE,
                    "Connected",
                    "Minecraft is connected and authenticated."
            ));
            bar.setMcpStatus(new ServiceStatus(
                    ServiceStatus.State.AVAILABLE,
                    "Listening",
                    "MCP is listening at http://127.0.0.1:32123/mcp"
            ));
        });

        assertNotNull(findButton(bar, "Game: Connected"));
        assertNotNull(findButton(bar, "MCP: Listening"));
    }

    private static <T extends Component> T find(Container parent, Class<T> type) {
        for (Component child : parent.getComponents()) {
            if (type.isInstance(child)) {
                return type.cast(child);
            }
            if (child instanceof Container container) {
                T nested = find(container, type);
                if (nested != null) {
                    return nested;
                }
            }
        }
        return null;
    }

    private static JButton findButton(Container parent, String text) {
        for (Component child : parent.getComponents()) {
            if (child instanceof JButton button && text.equals(button.getText())) {
                return button;
            }
            if (child instanceof Container container) {
                JButton nested = findButton(container, text);
                if (nested != null) {
                    return nested;
                }
            }
        }
        return null;
    }
}
