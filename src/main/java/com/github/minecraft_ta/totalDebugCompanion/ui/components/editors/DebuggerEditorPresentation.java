package com.github.minecraft_ta.totalDebugCompanion.ui.components.editors;

import com.github.minecraft_ta.totalDebugCompanion.debugger.DebugEngine;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/** Current selected debugger frame and its values, independent of any open editor tab. */
public final class DebuggerEditorPresentation {
    public record PresentedVariable(DebugEngine.Variable variable, DebugEngine.ValuePreview preview) {
        public PresentedVariable {
            Objects.requireNonNull(variable, "variable");
            Objects.requireNonNull(preview, "preview");
        }
    }

    public record Snapshot(DebugEngine.StackFrame frame, List<PresentedVariable> variables) {
        public Snapshot {
            Objects.requireNonNull(frame, "frame");
            variables = List.copyOf(variables);
        }
    }

    private static final CopyOnWriteArrayList<Consumer<Snapshot>> LISTENERS = new CopyOnWriteArrayList<>();
    private static volatile Snapshot current;

    private DebuggerEditorPresentation() {
    }

    public static Runnable addListener(Consumer<Snapshot> listener) {
        Consumer<Snapshot> checked = Objects.requireNonNull(listener, "listener");
        LISTENERS.add(checked);
        checked.accept(current);
        return () -> LISTENERS.remove(checked);
    }

    public static void select(DebugEngine.StackFrame frame, List<DebugEngine.Variable> variables) {
        List<PresentedVariable> presented = variables.stream()
                .map(variable -> new PresentedVariable(variable, DebugEngine.ValuePreview.NONE))
                .toList();
        publish(new Snapshot(frame, presented));
    }

    public static void updatePreview(
            DebugEngine.StackFrame frame,
            int variablesReference,
            DebugEngine.ValuePreview preview
    ) {
        Snapshot snapshot = current;
        if (snapshot == null || !snapshot.frame().equals(frame)) {
            return;
        }
        List<PresentedVariable> replacement = new ArrayList<>(snapshot.variables().size());
        boolean changed = false;
        for (PresentedVariable value : snapshot.variables()) {
            if (value.variable().variablesReference() == variablesReference) {
                replacement.add(new PresentedVariable(value.variable(), preview));
                changed = true;
            } else {
                replacement.add(value);
            }
        }
        if (changed) {
            publish(new Snapshot(frame, replacement));
        }
    }

    public static void clear() {
        publish(null);
    }

    private static void publish(Snapshot replacement) {
        current = replacement;
        for (Consumer<Snapshot> listener : LISTENERS) {
            listener.accept(replacement);
        }
    }
}
