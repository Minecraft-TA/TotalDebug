package com.github.minecraft_ta.totalDebugCompanion.debugger;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Pure breakpoint validation and adapter-application state transitions. */
final class DebuggerBreakpointState {
    private DebuggerBreakpointState() {
    }

    static DebuggerSessionController.Breakpoint created(
            DebugEngine.Source source,
            DebugEngine.SourceBreakpoint request,
            boolean attached,
            boolean muted
    ) {
        if (source != null && staticallyInvalid(source, request)) {
            return invalid(request);
        }
        return new DebuggerSessionController.Breakpoint(
                request,
                !attached || muted
                        ? DebuggerSessionController.BreakpointState.UNBOUND
                        : DebuggerSessionController.BreakpointState.PENDING,
                ""
        );
    }

    static DebuggerSessionController.Breakpoint revalidated(
            DebugEngine.Source source,
            DebuggerSessionController.Breakpoint current,
            boolean attached,
            boolean muted
    ) {
        if (current.state() == DebuggerSessionController.BreakpointState.DISABLED) {
            return current;
        }
        if (staticallyInvalid(source, current.request())) {
            return invalid(current.request());
        }
        if (current.state() == DebuggerSessionController.BreakpointState.INVALID
                && current.detail().equals(staticInvalidDetail(current.request()))) {
            return created(source, current.request(), attached, muted);
        }
        return current;
    }

    static Application application(
            Collection<DebuggerSessionController.Breakpoint> breakpoints,
            boolean muted
    ) {
        List<DebuggerSessionController.Breakpoint> requested = muted
                ? List.of()
                : breakpoints.stream()
                        .filter(breakpoint -> breakpoint.state()
                                != DebuggerSessionController.BreakpointState.DISABLED)
                        .filter(breakpoint -> breakpoint.state()
                                != DebuggerSessionController.BreakpointState.INVALID)
                        .toList();
        Set<DebuggerSessionController.Breakpoint> requestedSet = new HashSet<>(requested);
        List<DebuggerSessionController.Breakpoint> pending = new ArrayList<>(breakpoints.size());
        for (DebuggerSessionController.Breakpoint breakpoint : breakpoints) {
            pending.add(requestedSet.contains(breakpoint)
                    ? new DebuggerSessionController.Breakpoint(
                            breakpoint.request(),
                            DebuggerSessionController.BreakpointState.PENDING,
                            0,
                            ""
                    )
                    : breakpoint);
        }
        return new Application(requested, List.copyOf(pending));
    }

    static DebuggerSessionController.Breakpoint adapterResponse(
            DebuggerSessionController.Breakpoint current,
            DebugEngine.Breakpoint response
    ) {
        DebuggerSessionController.BreakpointState state = response.verified()
                ? DebuggerSessionController.BreakpointState.BOUND
                : response.message().isBlank()
                        ? DebuggerSessionController.BreakpointState.PENDING
                        : DebuggerSessionController.BreakpointState.INVALID;
        return new DebuggerSessionController.Breakpoint(
                current.request(),
                state,
                Math.max(0, response.line()),
                response.message()
        );
    }

    static DebuggerSessionController.Breakpoint unbound(DebuggerSessionController.Breakpoint current) {
        return current.state() == DebuggerSessionController.BreakpointState.BOUND
                || current.state() == DebuggerSessionController.BreakpointState.PENDING
                ? new DebuggerSessionController.Breakpoint(
                        current.request(), DebuggerSessionController.BreakpointState.UNBOUND, "")
                : current;
    }

    private static boolean staticallyInvalid(DebugEngine.Source source, DebugEngine.SourceBreakpoint request) {
        if (request.isMethodEntry() && request.debuggerLine() == 0) {
            return true;
        }
        return !source.lineMap().isEmpty() && !source.lineMap().containsDisplayedLine(request.debuggerLine());
    }

    private static DebuggerSessionController.Breakpoint invalid(DebugEngine.SourceBreakpoint request) {
        return new DebuggerSessionController.Breakpoint(
                request,
                DebuggerSessionController.BreakpointState.INVALID,
                staticInvalidDetail(request)
        );
    }

    private static String staticInvalidDetail(DebugEngine.SourceBreakpoint request) {
        return request.isMethodEntry()
                ? "Method has no executable bytecode"
                : "No executable bytecode is mapped to line " + request.line();
    }

    record Application(
            List<DebuggerSessionController.Breakpoint> requested,
            List<DebuggerSessionController.Breakpoint> pending
    ) {
    }
}
