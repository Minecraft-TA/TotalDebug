package com.github.minecraft_ta.totalDebugCompanion.ui.views;

import com.github.minecraft_ta.totalDebugCompanion.CompanionApp;
import com.github.minecraft_ta.totalDebugCompanion.GlobalConfig;
import com.github.minecraft_ta.totalDebugCompanion.debugger.DebugEngine;
import com.github.minecraft_ta.totalDebugCompanion.debugger.DebuggerSessionController;

import javax.swing.JFrame;
import java.awt.Dimension;
import java.awt.Frame;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.Window;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.util.Objects;

/** Floating host for the reusable debugger workspace. */
public final class DebuggerWindow extends JFrame {
    private static final Dimension DEFAULT_SIZE = new Dimension(1120, 620);
    private static final Dimension MINIMUM_SIZE = new Dimension(760, 420);

    private final DebuggerSessionController controller;
    private final DebuggerShortcuts debuggerShortcuts;
    private final DebuggerPanel panel;
    private final DebuggerSessionController.Listener listener = new DebuggerSessionController.Listener() {
        @Override
        public void paused(DebuggerSessionController.PausedState state) {
            javax.swing.SwingUtilities.invokeLater(() -> showWindow());
        }
    };
    private boolean disposed;

    DebuggerWindow(
            Window owner,
            DebuggerSessionController controller,
            DebuggerActions debuggerActions,
            DebuggerShortcuts debuggerShortcuts
    ) {
        this(
                owner,
                controller,
                debuggerActions,
                debuggerShortcuts,
                (frame, activateEditor) -> CompanionApp.openDebugFrame(frame, activateEditor)
        );
    }

    DebuggerWindow(
            Window owner,
            DebuggerSessionController controller,
            DebuggerActions debuggerActions,
            DebuggerShortcuts debuggerShortcuts,
            DebuggerPanel.FrameNavigation frameNavigation
    ) {
        super("Minecraft Debugger");
        this.controller = controller;
        this.debuggerShortcuts = Objects.requireNonNull(debuggerShortcuts, "debuggerShortcuts");
        this.panel = new DebuggerPanel(controller, debuggerActions, frameNavigation);
        this.debuggerShortcuts.install(this);
        if (owner instanceof Frame frame) {
            setIconImages(frame.getIconImages());
        }

        setContentPane(this.panel);
        setDefaultCloseOperation(HIDE_ON_CLOSE);
        setMinimumSize(MINIMUM_SIZE);
        restoreBounds(owner);
        addComponentListener(new ComponentAdapter() {
            @Override
            public void componentMoved(ComponentEvent event) {
                rememberBounds();
            }

            @Override
            public void componentResized(ComponentEvent event) {
                rememberBounds();
            }
        });

        this.controller.addListener(this.listener);
    }

    public void showWindow() {
        if (!isVisible()) {
            setVisible(true);
        }
        if (!isActive()) {
            toFront();
        }
    }

    void showVariable(DebugEngine.StackFrame frame, DebugEngine.Variable variable) {
        showWindow();
        this.panel.focusVariable(frame, variable);
    }

    void preview(
            DebuggerSessionController.Status status,
            DebuggerSessionController.PausedState pausedState,
            boolean showWatches
    ) {
        this.panel.applyStatus(status);
        this.panel.showPausedState(pausedState);
        if (showWatches) {
            this.panel.showWatchesForPreview();
        }
    }

    private void restoreBounds(Window owner) {
        Rectangle saved = GlobalConfig.getInstance().debuggerWindowBounds();
        if (saved != null && isUsable(saved)) {
            setBounds(saved);
            return;
        }
        setPreferredSize(DEFAULT_SIZE);
        pack();
        setLocationRelativeTo(owner);
    }

    private static boolean isUsable(Rectangle bounds) {
        if (bounds.width < MINIMUM_SIZE.width || bounds.height < MINIMUM_SIZE.height) {
            return false;
        }
        for (var device : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
            for (GraphicsConfiguration configuration : device.getConfigurations()) {
                Rectangle visible = configuration.getBounds().intersection(bounds);
                if (visible.width >= 160 && visible.height >= 120) {
                    return true;
                }
            }
        }
        return false;
    }

    private void rememberBounds() {
        if (isDisplayable()) {
            GlobalConfig.getInstance().setDebuggerWindowBounds(getBounds());
        }
    }

    @Override
    public void dispose() {
        if (this.disposed) {
            return;
        }
        this.disposed = true;
        rememberBounds();
        this.controller.removeListener(this.listener);
        this.debuggerShortcuts.uninstall(this);
        this.panel.dispose();
        super.dispose();
    }
}
