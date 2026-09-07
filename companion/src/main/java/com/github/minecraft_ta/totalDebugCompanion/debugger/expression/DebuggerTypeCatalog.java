package com.github.minecraft_ta.totalDebugCompanion.debugger.expression;

import com.sun.jdi.ReferenceType;
import com.sun.jdi.VirtualMachine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.TreeMap;

/** Immutable per-VM lookup index for loaded debugger types. */
final class DebuggerTypeCatalog {
    private final VirtualMachine vm;
    private final Map<String, ReferenceType> byBinaryName;
    private final NavigableMap<String, List<ReferenceType>> byFoldedSimpleName;

    private DebuggerTypeCatalog(
            VirtualMachine vm,
            Map<String, ReferenceType> byBinaryName,
            NavigableMap<String, List<ReferenceType>> byFoldedSimpleName
    ) {
        this.vm = vm;
        this.byBinaryName = Map.copyOf(byBinaryName);
        this.byFoldedSimpleName = new TreeMap<>(byFoldedSimpleName);
    }

    static DebuggerTypeCatalog capture(VirtualMachine vm) {
        Objects.requireNonNull(vm, "vm");
        Map<String, ReferenceType> byBinaryName = new LinkedHashMap<>();
        NavigableMap<String, List<ReferenceType>> mutableBySimpleName = new TreeMap<>();
        for (ReferenceType type : vm.allClasses()) {
            String binaryName = type.name();
            byBinaryName.putIfAbsent(binaryName, type);
            if (binaryName.startsWith("[") || binaryName.endsWith("package-info")
                    || binaryName.endsWith("module-info")) {
                continue;
            }
            String simpleName = simpleName(binaryName);
            if (simpleName.isBlank() || Character.isDigit(simpleName.charAt(0))) {
                continue;
            }
            mutableBySimpleName.computeIfAbsent(simpleName.toLowerCase(Locale.ROOT), ignored -> new ArrayList<>())
                    .add(type);
        }
        NavigableMap<String, List<ReferenceType>> bySimpleName = new TreeMap<>();
        mutableBySimpleName.forEach((name, types) -> bySimpleName.put(name, List.copyOf(types)));
        return new DebuggerTypeCatalog(vm, byBinaryName, bySimpleName);
    }

    boolean belongsTo(VirtualMachine candidate) {
        return this.vm == candidate;
    }

    Map<String, ReferenceType> byBinaryName() {
        return this.byBinaryName;
    }

    List<ReferenceType> withSimpleNamePrefix(String prefix) {
        String folded = Objects.requireNonNull(prefix, "prefix").toLowerCase(Locale.ROOT);
        NavigableMap<String, List<ReferenceType>> matches = folded.isEmpty()
                ? this.byFoldedSimpleName
                : this.byFoldedSimpleName.subMap(folded, true, folded + Character.MAX_VALUE, true);
        return matches.values().stream().flatMap(List::stream).toList();
    }

    private static String simpleName(String binaryName) {
        return binaryName.substring(Math.max(binaryName.lastIndexOf('.'), binaryName.lastIndexOf('$')) + 1);
    }
}
