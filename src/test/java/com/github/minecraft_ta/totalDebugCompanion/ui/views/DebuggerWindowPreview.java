package com.github.minecraft_ta.totalDebugCompanion.ui.views;

import com.github.minecraft_ta.totalDebugCompanion.debugger.DebugEngine;
import com.github.minecraft_ta.totalDebugCompanion.debugger.DebugTargetDescriptor;
import com.github.minecraft_ta.totalDebugCompanion.debugger.DebuggerSessionController;

import java.awt.Window;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.net.URI;
import java.util.List;

/** Deterministic visual fixture for the debugger window. */
public final class DebuggerWindowPreview {
    private DebuggerWindowPreview() {
    }

    public static DebuggerWindow open(Window owner) {
        DebuggerSessionController controller = new DebuggerSessionController();
        DebuggerActions actions = new DebuggerActions(controller);
        DebuggerShortcuts shortcuts = new DebuggerShortcuts(actions);
        DebuggerWindow window = new DebuggerWindow(owner, controller, actions, shortcuts, (frame, activateEditor) -> {
        });
        window.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent event) {
                actions.close();
                shortcuts.close();
                controller.close();
            }
        });

        DebugEngine.StackFrame top = frame(
                1,
                "updateOrDestroy",
                "net.minecraft.world.level.block.Block",
                174
        );
        List<DebugEngine.StackFrame> frames = List.of(
                top,
                frame(2, "setBlock", "net.minecraft.world.level.Level", 1041),
                frame(3, "tick", "net.minecraft.server.level.ServerLevel", 782),
                frame(4, "runAllTasks", "net.minecraft.util.thread.BlockableEventLoop", 102),
                frame(5, "managedBlock", "net.minecraft.util.thread.BlockableEventLoop", 132),
                frame(6, "waitUntilNextTick", "net.minecraft.server.MinecraftServer", 915),
                frame(7, "runServer", "net.minecraft.server.MinecraftServer", 718),
                frame(8, "lambda$spin$2", "net.minecraft.server.MinecraftServer", 267),
                frame(9, "run", "java.lang.Thread", 1583)
        );
        List<DebugEngine.Variable> variables = List.of(
                variable("this", "Block@11", "Block", 11, 8),
                variable("oldState", "BlockState@12", "BlockState", 12, 6),
                variable("newState", "BlockState@13", "BlockState", 13, 6),
                variable("level", "ServerLevel@14", "ServerLevel", 14, 42),
                variable("pos", "BlockPos@15", "BlockPos", 15, 3),
                variable("flags", "3", "int", 0, 0),
                variable("depth", "512", "int", 0, 0)
        );
        DebugTargetDescriptor target = new DebugTargetDescriptor("preview", "Minecraft Client", 42);
        DebugEngine.StoppedEvent stopped = new DebugEngine.StoppedEvent("breakpoint", 73, true);
        window.preview(
                new DebuggerSessionController.Status(
                        DebuggerSessionController.Phase.PAUSED,
                        target,
                        "Paused at Block.updateOrDestroy:174",
                        null
                ),
                new DebuggerSessionController.PausedState(stopped, frames, variables)
        );
        window.setBounds(owner.getX() + 80, owner.getY() + 50, 1120, 620);
        window.setVisible(true);
        return window;
    }

    private static DebugEngine.StackFrame frame(int id, String name, String owner, int line) {
        return new DebugEngine.StackFrame(
                id,
                name,
                owner,
                URI.create("file:///preview/" + owner.replace('.', '/') + ".java"),
                line,
                1
        );
    }

    private static DebugEngine.Variable variable(
            String name,
            String value,
            String type,
            int reference,
            int namedVariables
    ) {
        return new DebugEngine.Variable(
                name,
                name,
                name,
                value,
                type,
                "this".equals(name) ? DebugEngine.VariableKind.THIS : DebugEngine.VariableKind.PARAMETER,
                1,
                reference,
                namedVariables,
                0
        );
    }
}
