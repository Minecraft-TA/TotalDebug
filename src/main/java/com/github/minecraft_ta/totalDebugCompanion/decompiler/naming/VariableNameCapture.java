package com.github.minecraft_ta.totalDebugCompanion.decompiler.naming;

import com.github.minecraft_ta.totalDebugCompanion.source.SourceLineMap;
import com.github.minecraft_ta.totalDebugCompanion.source.SourceVariableNames;
import org.jetbrains.java.decompiler.main.extern.TextTokenVisitor;
import org.jetbrains.java.decompiler.struct.gen.MethodDescriptor;
import org.jetbrains.java.decompiler.util.token.TextRange;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Collects the final variable names written by Vineflower while one class is being decompiled. */
public final class VariableNameCapture implements AutoCloseable {
    private static final Map<String, List<VariableNameCapture>> ACTIVE = new LinkedHashMap<>();

    private final String internalClassName;
    private final Map<SourceVariableNames.MethodKey, Map<String, Set<String>>> candidates = new LinkedHashMap<>();
    private final Map<OriginalLocation, Set<String>> runtimeNamesByLocation = new LinkedHashMap<>();
    private final Map<DisplayedLocation, Set<String>> displayedNamesByLocation = new LinkedHashMap<>();
    private boolean closed;

    private VariableNameCapture(String internalClassName) {
        this.internalClassName = internalClassName;
    }

    public static VariableNameCapture open(String internalClassName) {
        VariableNameCapture capture = new VariableNameCapture(internalClassName);
        synchronized (ACTIVE) {
            ACTIVE.computeIfAbsent(internalClassName, ignored -> new ArrayList<>()).add(capture);
        }
        return capture;
    }

    static void recordRuntimeName(
            String owner,
            String methodName,
            String methodDescriptor,
            int originalLine,
            String runtimeName
    ) {
        if (originalLine < 1 || runtimeName == null || runtimeName.isBlank()) {
            return;
        }
        forCapture(owner, capture -> capture.addRuntimeName(
                new OriginalLocation(new SourceVariableNames.MethodKey(methodName, methodDescriptor), originalLine),
                runtimeName
        ));
    }

    static void recordRename(
            String owner,
            String methodName,
            String methodDescriptor,
            String runtimeName,
            String displayedName
    ) {
        if (runtimeName == null || runtimeName.isBlank()
                || displayedName == null || displayedName.isBlank()
                || runtimeName.equals(displayedName)) {
            return;
        }
        SourceVariableNames.MethodKey method = new SourceVariableNames.MethodKey(methodName, methodDescriptor);
        forCapture(owner, capture -> capture.addCandidate(method, runtimeName, displayedName));
    }

    static TextTokenVisitor textTokenVisitor(TextTokenVisitor next) {
        return new TextTokenVisitor(next) {
            private int[] lineStarts = new int[]{0};

            @Override
            public void start(String content) {
                super.start(content);
                int[] starts = new int[(int) content.chars().filter(character -> character == '\n').count() + 1];
                starts[0] = 0;
                int line = 1;
                for (int offset = 0; offset < content.length(); offset++) {
                    if (content.charAt(offset) == '\n') {
                        starts[line++] = offset + 1;
                    }
                }
                this.lineStarts = starts;
            }

            @Override
            public void visitParameter(
                    TextRange range,
                    boolean declaration,
                    String className,
                    String methodName,
                    MethodDescriptor methodDescriptor,
                    int index,
                    String name
            ) {
                super.visitParameter(range, declaration, className, methodName, methodDescriptor, index, name);
                if (declaration) {
                    recordDisplayedName(className, methodName, methodDescriptor.toString(), lineAt(range.start), name);
                }
            }

            @Override
            public void visitLocal(
                    TextRange range,
                    boolean declaration,
                    String className,
                    String methodName,
                    MethodDescriptor methodDescriptor,
                    int index,
                    String name
            ) {
                super.visitLocal(range, declaration, className, methodName, methodDescriptor, index, name);
                if (declaration) {
                    recordDisplayedName(className, methodName, methodDescriptor.toString(), lineAt(range.start), name);
                }
            }

            private int lineAt(int offset) {
                int index = Arrays.binarySearch(this.lineStarts, offset);
                return index >= 0 ? index + 1 : -index - 1;
            }
        };
    }

    public synchronized SourceVariableNames result(SourceLineMap lineMap) {
        reconcileLocations(lineMap);
        Map<SourceVariableNames.MethodKey, Map<String, String>> unambiguous = new LinkedHashMap<>();
        this.candidates.forEach((method, methodCandidates) -> {
            Map<String, String> methodNames = new LinkedHashMap<>();
            methodCandidates.forEach((runtimeName, displayedNames) -> {
                if (displayedNames.size() == 1) {
                    methodNames.put(runtimeName, displayedNames.iterator().next());
                }
            });
            if (!methodNames.isEmpty()) {
                unambiguous.put(method, methodNames);
            }
        });
        return SourceVariableNames.of(unambiguous);
    }

    private static void recordDisplayedName(
            String owner,
            String methodName,
            String methodDescriptor,
            int displayedLine,
            String displayedName
    ) {
        if (displayedLine < 1 || displayedName == null || displayedName.isBlank()) {
            return;
        }
        forCapture(owner, capture -> capture.addDisplayedName(
                new DisplayedLocation(new SourceVariableNames.MethodKey(methodName, methodDescriptor), displayedLine),
                displayedName
        ));
    }

    private static void forCapture(String owner, java.util.function.Consumer<VariableNameCapture> action) {
        List<VariableNameCapture> captures;
        synchronized (ACTIVE) {
            captures = List.copyOf(ACTIVE.getOrDefault(owner, List.of()));
        }
        captures.forEach(action);
    }

    private synchronized void addRuntimeName(OriginalLocation location, String runtimeName) {
        if (!this.closed) {
            this.runtimeNamesByLocation.computeIfAbsent(location, ignored -> new LinkedHashSet<>()).add(runtimeName);
        }
    }

    private synchronized void addDisplayedName(DisplayedLocation location, String displayedName) {
        if (!this.closed) {
            this.displayedNamesByLocation.computeIfAbsent(location, ignored -> new LinkedHashSet<>()).add(displayedName);
        }
    }

    private synchronized void addCandidate(
            SourceVariableNames.MethodKey method,
            String runtimeName,
            String displayedName
    ) {
        if (!this.closed) {
            this.candidates.computeIfAbsent(method, ignored -> new LinkedHashMap<>())
                    .computeIfAbsent(runtimeName, ignored -> new LinkedHashSet<>())
                    .add(displayedName);
        }
    }

    private void reconcileLocations(SourceLineMap lineMap) {
        int[] mappings = lineMap.originalToDisplayed();
        this.runtimeNamesByLocation.forEach((location, runtimeNames) -> {
            for (int index = 0; index < mappings.length; index += 2) {
                if (mappings[index] != location.originalLine()) {
                    continue;
                }
                Set<String> displayedNames = this.displayedNamesByLocation.get(
                        new DisplayedLocation(location.method(), mappings[index + 1])
                );
                reconcileNames(location.method(), runtimeNames, displayedNames);
            }
        });
    }

    private void reconcileNames(
            SourceVariableNames.MethodKey method,
            Set<String> runtimeNames,
            Set<String> displayedNames
    ) {
        if (displayedNames == null) {
            return;
        }
        Set<String> unmatchedRuntime = new LinkedHashSet<>(runtimeNames);
        Set<String> unmatchedDisplayed = new LinkedHashSet<>(displayedNames);
        Set<String> unchanged = new LinkedHashSet<>(unmatchedRuntime);
        unchanged.retainAll(unmatchedDisplayed);
        unmatchedRuntime.removeAll(unchanged);
        unmatchedDisplayed.removeAll(unchanged);
        if (unmatchedRuntime.size() == 1 && unmatchedDisplayed.size() == 1) {
            addCandidate(method, unmatchedRuntime.iterator().next(), unmatchedDisplayed.iterator().next());
        }
    }

    @Override
    public void close() {
        synchronized (ACTIVE) {
            if (this.closed) {
                return;
            }
            this.closed = true;
            List<VariableNameCapture> captures = ACTIVE.get(this.internalClassName);
            if (captures != null) {
                captures.remove(this);
                if (captures.isEmpty()) {
                    ACTIVE.remove(this.internalClassName);
                }
            }
        }
    }

    private record OriginalLocation(SourceVariableNames.MethodKey method, int originalLine) {
    }

    private record DisplayedLocation(SourceVariableNames.MethodKey method, int displayedLine) {
    }
}
