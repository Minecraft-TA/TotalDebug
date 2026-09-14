package com.github.minecraft_ta.totalDebugCompanion.debugger;

import java.util.Objects;
import java.util.Optional;

/** Breakpoint policy over the same source scopes used by navigation and MCP. */
public final class DebuggerBreakpointResolver {
    private DebuggerBreakpointResolver() {}
    public static Optional<DebugEngine.SourceBreakpoint> resolve(
            DebugEngine.Source source, int line, String condition, String hitCondition) {
        Objects.requireNonNull(source);
        if (line < 1 || line > source.contents().lines().count()) {
            throw new IllegalArgumentException("Source has no displayed line " + line);
        }
        if (source.lineMap().isEmpty()) return Optional.of(new DebugEngine.SourceBreakpoint(line, condition, hitCondition));
        var scope = source.document().methodAtLine(line);
        if (scope.isEmpty()) {
            return source.lineMap().containsDisplayedLine(line)
                    ? Optional.of(new DebugEngine.SourceBreakpoint(line, condition, hitCondition)) : Optional.empty();
        }
        var debuggerLine = source.lineMap().firstMappedDisplayedLine(line, scope.get().lastLine());
        if (debuggerLine.isEmpty()) return Optional.empty();
        var method = scope.get().method();
        return Optional.of(DebugEngine.SourceBreakpoint.methodEntry(line, debuggerLine.getAsInt(),
                new DebugEngine.MethodTarget(method.ownerClassName(), method.name(), method.descriptor()), condition, hitCondition));
    }
}
