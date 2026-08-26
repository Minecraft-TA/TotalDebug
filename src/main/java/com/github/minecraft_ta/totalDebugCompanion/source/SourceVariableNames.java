package com.github.minecraft_ta.totalDebugCompanion.source;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Exact runtime and displayed variable names emitted by the decompiler for one source file. */
public final class SourceVariableNames {
    private static final SourceVariableNames EMPTY = new SourceVariableNames(Map.of());

    private final Map<String, String> displayedByRuntimeName;

    private SourceVariableNames(Map<String, String> displayedByRuntimeName) {
        this.displayedByRuntimeName = Map.copyOf(displayedByRuntimeName);
    }

    public static SourceVariableNames empty() {
        return EMPTY;
    }

    public static SourceVariableNames of(Map<String, String> displayedByRuntimeName) {
        Objects.requireNonNull(displayedByRuntimeName, "displayedByRuntimeName");
        if (displayedByRuntimeName.isEmpty()) {
            return EMPTY;
        }
        Map<String, String> checked = new LinkedHashMap<>();
        displayedByRuntimeName.forEach((runtimeName, displayedName) -> {
            String runtime = requireName(runtimeName, "runtime variable name");
            String displayed = requireName(displayedName, "displayed variable name");
            if (!runtime.equals(displayed)) {
                checked.put(runtime, displayed);
            }
        });
        return checked.isEmpty() ? EMPTY : new SourceVariableNames(checked);
    }

    public boolean isEmpty() {
        return this.displayedByRuntimeName.isEmpty();
    }

    public String displayedName(String runtimeName) {
        return this.displayedByRuntimeName.getOrDefault(runtimeName, runtimeName);
    }

    public Map<String, String> mappings() {
        return this.displayedByRuntimeName;
    }

    private static String requireName(String value, String description) {
        Objects.requireNonNull(value, description);
        if (value.isBlank()) {
            throw new IllegalArgumentException(description + " must not be blank");
        }
        return value;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof SourceVariableNames names
                && this.displayedByRuntimeName.equals(names.displayedByRuntimeName);
    }

    @Override
    public int hashCode() {
        return this.displayedByRuntimeName.hashCode();
    }

    @Override
    public String toString() {
        return this.displayedByRuntimeName.toString();
    }
}
