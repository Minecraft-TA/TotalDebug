package com.github.minecraft_ta.totalDebugCompanion.ui.views.debugger;

import com.github.minecraft_ta.totalDebugCompanion.Icons;
import com.github.minecraft_ta.totalDebugCompanion.debugger.DebuggerSessionController;

import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.Icon;
import javax.swing.SwingUtilities;
import java.awt.event.ActionEvent;
import java.util.Objects;

/** Shared debugger commands used by every Companion window and debugger control. */
public final class DebuggerActions implements AutoCloseable {
    private final DebuggerSessionController controller;
    private final Action attach;
    private final Action resume;
    private final Action stepOver;
    private final Action stepInto;
    private final Action stepOut;
    private final Action detach;
    private final DebuggerSessionController.Listener listener = new DebuggerSessionController.Listener() {
        @Override
        public void statusChanged(DebuggerSessionController.Status status) {
            applyStatus(status);
        }
    };
    private boolean closed;

    public DebuggerActions(DebuggerSessionController controller) {
        this.controller = Objects.requireNonNull(controller, "controller");
        this.attach = command("Attach", Icons.DEBUG, "Attach debugger", controller::attach);
        this.resume = command("Continue", Icons.DEBUG_RESUME, "Continue (F9)", controller::resume);
        this.stepOver = command("Step Over", Icons.DEBUG_STEP_OVER, "Step Over (F8)", controller::stepOver);
        this.stepInto = command("Step Into", Icons.DEBUG_STEP_INTO, "Step Into (F7)", controller::stepInto);
        this.stepOut = command("Step Out", Icons.DEBUG_STEP_OUT, "Step Out (Shift+F8)", controller::stepOut);
        this.detach = command("Detach", Icons.DEBUG_DETACH, "Detach debugger", controller::detach);
        this.controller.addListener(this.listener);
        applyStatus(this.controller.status());
    }

    public Action attach() {
        return this.attach;
    }

    public Action resume() {
        return this.resume;
    }

    public Action stepOver() {
        return this.stepOver;
    }

    public Action stepInto() {
        return this.stepInto;
    }

    public Action stepOut() {
        return this.stepOut;
    }

    public Action detach() {
        return this.detach;
    }

    void applyStatus(DebuggerSessionController.Status status) {
        Objects.requireNonNull(status, "status");
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> applyStatus(status));
            return;
        }
        if (this.closed) {
            return;
        }

        boolean paused = status.phase() == DebuggerSessionController.Phase.PAUSED;
        this.attach.setEnabled(status.phase() == DebuggerSessionController.Phase.DETACHED
                || status.phase() == DebuggerSessionController.Phase.FAILED);
        this.resume.setEnabled(paused);
        this.stepOver.setEnabled(paused);
        this.stepInto.setEnabled(paused);
        this.stepOut.setEnabled(paused);
        this.detach.setEnabled(switch (status.phase()) {
            case ATTACHING, RUNNING, PAUSED, DETACHING -> true;
            default -> false;
        });
    }

    @Override
    public void close() {
        if (this.closed) {
            return;
        }
        this.closed = true;
        this.controller.removeListener(this.listener);
    }

    private static Action command(String name, Icon icon, String description, Runnable command) {
        AbstractAction action = new AbstractAction(name, icon) {
            @Override
            public void actionPerformed(ActionEvent event) {
                command.run();
            }
        };
        action.putValue(Action.SHORT_DESCRIPTION, description);
        return action;
    }
}
