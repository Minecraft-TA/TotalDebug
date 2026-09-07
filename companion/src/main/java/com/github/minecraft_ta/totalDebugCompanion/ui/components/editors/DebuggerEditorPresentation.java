package com.github.minecraft_ta.totalDebugCompanion.ui.components.editors;

import com.github.minecraft_ta.totalDebugCompanion.debugger.DebugEngine;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/** Current selected debugger frame and its values, independent of any open editor tab. */
public final class DebuggerEditorPresentation {
    public record PresentedVariable(
            DebugEngine.Variable variable,
            DebugEngine.ValuePreview preview,
            boolean previewResolved
    ) {
        public PresentedVariable {
            Objects.requireNonNull(variable, "variable");
            Objects.requireNonNull(preview, "preview");
        }

        public PresentedVariable(DebugEngine.Variable variable, DebugEngine.ValuePreview preview) {
            this(variable, preview, true);
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
        select(frame, variables, Map.of());
    }

    public static void select(
            DebugEngine.StackFrame frame,
            List<DebugEngine.Variable> variables,
            Map<Integer, DebugEngine.ValuePreview> previews
    ) {
        List<PresentedVariable> presented = variables.stream()
                .map(variable -> new PresentedVariable(
                        variable,
                        previews.getOrDefault(
                                variable.variablesReference(),
                                DebugEngine.ValuePreview.NONE
                        ),
                        true
                ))
                .toList();
        publish(new Snapshot(frame, presented));
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
