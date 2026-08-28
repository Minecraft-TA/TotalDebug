package com.github.minecraft_ta.totalDebugCompanion.source;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Exact runtime and displayed variable names emitted by the decompiler, scoped to their declaring method. */
public final class SourceVariableNames {
    private static final SourceVariableNames EMPTY = new SourceVariableNames(Map.of());

    private final Map<MethodKey, Map<String, String>> displayedByMethod;

    private SourceVariableNames(Map<MethodKey, Map<String, String>> displayedByMethod) {
        this.displayedByMethod = Map.copyOf(displayedByMethod);
    }

    public static SourceVariableNames empty() {
        return EMPTY;
    }

    public static SourceVariableNames of(Map<MethodKey, ? extends Map<String, String>> displayedByMethod) {
        Objects.requireNonNull(displayedByMethod, "displayedByMethod");
        if (displayedByMethod.isEmpty()) {
            return EMPTY;
        }
        Map<MethodKey, Map<String, String>> checkedMethods = new LinkedHashMap<>();
        displayedByMethod.forEach((method, mappings) -> {
            Objects.requireNonNull(method, "method");
            Objects.requireNonNull(mappings, "variable mappings");
            Map<String, String> checkedMappings = new LinkedHashMap<>();
            mappings.forEach((runtimeName, displayedName) -> {
                String runtime = requireText(runtimeName, "runtime variable name");
                String displayed = requireText(displayedName, "displayed variable name");
                if (!runtime.equals(displayed)) {
                    checkedMappings.put(runtime, displayed);
                }
            });
            if (!checkedMappings.isEmpty()) {
                checkedMethods.put(method, Map.copyOf(checkedMappings));
            }
        });
        return checkedMethods.isEmpty() ? EMPTY : new SourceVariableNames(checkedMethods);
    }

    public static SourceVariableNames forMethod(
            String methodName,
            String methodDescriptor,
            Map<String, String> mappings
    ) {
        return of(Map.of(new MethodKey(methodName, methodDescriptor), mappings));
    }

    public boolean isEmpty() {
        return this.displayedByMethod.isEmpty();
    }

    public String displayedName(String methodName, String methodDescriptor, String runtimeName) {
        Objects.requireNonNull(runtimeName, "runtimeName");
        return namesForMethod(methodName, methodDescriptor).getOrDefault(runtimeName, runtimeName);
    }

    public Map<String, String> namesForMethod(String methodName, String methodDescriptor) {
        return this.displayedByMethod.getOrDefault(new MethodKey(methodName, methodDescriptor), Map.of());
    }

    public Map<MethodKey, Map<String, String>> mappings() {
        return this.displayedByMethod;
    }

    private static String requireText(String value, String description) {
        Objects.requireNonNull(value, description);
        if (value.isBlank()) {
            throw new IllegalArgumentException(description + " must not be blank");
        }
        return value;
    }

    public record MethodKey(String name, String descriptor) {
        public MethodKey {
            name = requireText(name, "method name");
            descriptor = requireText(descriptor, "method descriptor");
        }
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof SourceVariableNames names
                && this.displayedByMethod.equals(names.displayedByMethod);
    }

    @Override
    public int hashCode() {
        return this.displayedByMethod.hashCode();
    }

    @Override
    public String toString() {
        return this.displayedByMethod.toString();
    }
}
