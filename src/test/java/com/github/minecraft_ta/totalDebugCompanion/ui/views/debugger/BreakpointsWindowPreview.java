package com.github.minecraft_ta.totalDebugCompanion.ui.views.debugger;

import com.github.minecraft_ta.totalDebugCompanion.debugger.DebugEngine;
import com.github.minecraft_ta.totalDebugCompanion.debugger.DebuggerSessionController;

import java.awt.Window;
import java.net.URI;

/** Deterministic visual fixture for the persisted-breakpoint manager. */
public final class BreakpointsWindowPreview {
    private BreakpointsWindowPreview() {
    }

    public static BreakpointsWindow open(Window owner) {
        DebuggerSessionController controller = new DebuggerSessionController(ignored -> null);
        DebugEngine.Source source = new DebugEngine.Source(
                URI.create("file:///preview/net/minecraft/world/level/block/Block.java"),
                "net.minecraft.world.level.block.Block",
                "class Block {}"
        );
        controller.configureBreakpoint(source, 174, "newState != oldState", "3").join();
        controller.toggleBreakpoint(source, 181).join();
        controller.toggleBreakpointEnabled(source, 181).join();
        controller.toggleBreakpoint(source, DebugEngine.SourceBreakpoint.methodEntry(
                201,
                203,
                new DebugEngine.MethodTarget(
                        "net.minecraft.world.level.block.Block",
                        "updateOrDestroy",
                        "()V"
                ),
                null,
                null
        )).join();

        BreakpointsWindow window = new BreakpointsWindow(owner, controller, target -> {
        });
        window.setBounds(owner.getX() + 150, owner.getY() + 85, 880, 520);
        window.showWindow();
        return window;
    }
}
