package com.github.minecraft_ta.totalDebugCompanion.ui.views.debugger;

import javax.swing.Action;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import java.awt.KeyboardFocusManager;
import java.awt.KeyEventDispatcher;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Dispatches debugger shortcuts before focused Swing components can claim the same keys. */
public final class DebuggerShortcuts implements KeyEventDispatcher, AutoCloseable {
    static final String RESUME = "debugger.resume";
    static final String STEP_OVER = "debugger.stepOver";
    static final String STEP_INTO = "debugger.stepInto";
    static final String STEP_OUT = "debugger.stepOut";

    private final List<Binding> bindings;
    private final Set<Window> windows = Collections.newSetFromMap(new IdentityHashMap<>());
    private boolean closed;

    public DebuggerShortcuts(DebuggerActions actions) {
        Objects.requireNonNull(actions, "actions");
        this.bindings = List.of(
                binding(KeyEvent.VK_F9, 0, RESUME, actions.resume()),
                binding(KeyEvent.VK_F8, 0, STEP_OVER, actions.stepOver()),
                binding(KeyEvent.VK_F7, 0, STEP_INTO, actions.stepInto()),
                binding(KeyEvent.VK_F8, InputEvent.SHIFT_DOWN_MASK, STEP_OUT, actions.stepOut())
        );
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(this);
    }

    public void install(Window window) {
        if (this.closed) {
            throw new IllegalStateException("Debugger shortcuts are closed");
        }
        this.windows.add(Objects.requireNonNull(window, "window"));
    }

    public void uninstall(Window window) {
        this.windows.remove(window);
    }

    Action actionFor(KeyStroke keyStroke) {
        Binding binding = bindingFor(keyStroke);
        return binding == null ? null : binding.action();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getID() != KeyEvent.KEY_PRESSED) {
            return false;
        }
        Window window = event.getComponent() instanceof Window sourceWindow
                ? sourceWindow
                : SwingUtilities.getWindowAncestor(event.getComponent());
        if (!this.windows.contains(window)) {
            return false;
        }

        Binding binding = bindingFor(KeyStroke.getKeyStrokeForEvent(event));
        if (binding == null) {
            return false;
        }
        if (binding.action().isEnabled()) {
            binding.action().actionPerformed(new ActionEvent(
                    event.getComponent(),
                    ActionEvent.ACTION_PERFORMED,
                    binding.id(),
                    event.getWhen(),
                    event.getModifiersEx()
            ));
        }
        event.consume();
        return true;
    }

    @Override
    public void close() {
        if (this.closed) {
            return;
        }
        this.closed = true;
        this.windows.clear();
        KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(this);
    }

    private Binding bindingFor(KeyStroke keyStroke) {
        for (Binding binding : this.bindings) {
            if (binding.keyStroke().equals(keyStroke)) {
                return binding;
            }
        }
        return null;
    }

    private static Binding binding(int keyCode, int modifiers, String id, Action action) {
        return new Binding(KeyStroke.getKeyStroke(keyCode, modifiers), id, action);
    }

    private record Binding(KeyStroke keyStroke, String id, Action action) {
    }
}
