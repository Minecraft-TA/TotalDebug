package com.github.minecraft_ta.totalDebugCompanion.project;

import com.github.minecraft_ta.totalDebugCompanion.debugger.DebugEngine;
import com.github.minecraft_ta.totalDebugCompanion.debugger.DebuggerSessionController;
import com.github.minecraft_ta.totalDebugCompanion.navigation.NavigationService;
import com.github.minecraft_ta.totalDebugCompanion.navigation.NavigationState;
import com.github.minecraft_ta.totalDebugCompanion.navigation.NavigationTarget;
import com.github.minecraft_ta.totalDebugCompanion.runtime.RuntimeBinding;
import com.github.minecraft_ta.totalDebugCompanion.session.CompanionProfile;
import com.github.minecraft_ta.totalDebugCompanion.storage.InstanceState;
import com.github.minecraft_ta.totaldebug.storage.InstancePaths;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/** Resources and request admission for one opened project. */
public final class ProjectScope implements AutoCloseable {
    public enum Phase { ACTIVE, SWITCHING, RETIRED }
    public record PendingNavigation(NavigationTarget target, NavigationService.Activation activation) { }

    private final NavigationState navigation = new NavigationState();
    public NavigationState navigation() { return navigation; }

    private final Object lock;
    private final CompanionProfile profile;
    private final InstanceState state;
    private final List<PendingNavigation> pending = new ArrayList<>();
    private volatile Phase phase = Phase.ACTIVE;
    private volatile RuntimeBinding runtime;
    private boolean closed;

    public ProjectScope(Object lock, CompanionProfile profile, InstanceState state) {
        this.lock = Objects.requireNonNull(lock);
        this.profile = Objects.requireNonNull(profile);
        this.state = Objects.requireNonNull(state);
    }

    public static ProjectScope open(Object lock, CompanionProfile profile) throws IOException {
        return new ProjectScope(lock, profile, InstanceState.open(new InstancePaths(profile.dataDirectory())));
    }

    public CompanionProfile profile() { return profile; }
    public InstanceState state() { return state; }
    public InstancePaths paths() { return new InstancePaths(profile.dataDirectory()); }
    public Phase phase() { return phase; }
    public boolean isActive() { return phase == Phase.ACTIVE; }
    public void requireActive() {
        if (!isActive()) throw new IllegalStateException("Project changed during the request");
    }

    /** Check and submit under the shared lifecycle lock; actions must never wait. */
    public <T> T admit(Supplier<T> action) {
        synchronized (lock) { requireActive(); return action.get(); }
    }
    public void beginSwitch() {
        synchronized (lock) { requireActive(); phase = Phase.SWITCHING; }
    }
    public void cancelSwitch() {
        synchronized (lock) {
            if (phase == Phase.SWITCHING) phase = Phase.ACTIVE;
        }
    }
    public void retire() { synchronized (lock) { phase = Phase.RETIRED; } }
    public RuntimeBinding runtime() { return runtime; }
    public String runtimeSignature() { var value = runtime; return value == null ? null : value.snapshot().signature(); }

    /** Called by the loader under the shared lifecycle lock, before its ownership handoff. */
    public void bindRuntime(RuntimeBinding value) { requireActive(); runtime = value; }
    public void closeRuntime() {
        RuntimeBinding previous = runtime;
        runtime = null;
        if (previous != null) previous.close();
    }
    public void queueNavigation(NavigationTarget target, NavigationService.Activation activation) {
        synchronized (lock) { requireActive(); pending.add(new PendingNavigation(target, activation)); }
    }
    public List<PendingNavigation> drainNavigations() {
        synchronized (lock) {
            requireActive();
            var result = List.copyOf(pending);
            pending.clear();
            return result;
        }
    }
    @Override public void close() throws IOException {
        synchronized (lock) {
            if (phase != Phase.RETIRED) throw new IllegalStateException("Retire the project before closing it");
            if (closed) return;
            closed = true;
            pending.clear();
        }
        try { closeRuntime(); } finally { state.close(); }
    }

    public String loadBreakpointScript(String name) {
        Path relative = Path.of(name);
        Path root = paths().scripts().toAbsolutePath().normalize();
        Path file = root.resolve(relative).normalize();
        if (relative.isAbsolute() || !file.startsWith(root) || file.equals(root)) {
            throw new IllegalArgumentException("Breakpoint script must be relative to the scripts directory");
        }
        try { return Files.readString(file); }
        catch (IOException failure) { throw new IllegalStateException("Unable to read breakpoint script " + name, failure); }
    }

    public List<DebuggerSessionController.BreakpointDefinition> restoreBreakpoints(String runtimeSignature) {
        return state.debuggerBreakpoints(runtimeSignature).stream()
                .map(persisted -> {
                    DebugEngine.MethodTarget method = persisted.methodOwner() == null
                            ? null
                            : new DebugEngine.MethodTarget(
                                    persisted.methodOwner(),
                                    persisted.methodName(),
                                    persisted.methodDescriptor()
                            );
                    DebugEngine.SourceBreakpoint request = new DebugEngine.SourceBreakpoint(
                            persisted.line(),
                            persisted.debuggerLine(),
                            method,
                            persisted.condition(),
                            persisted.hitCondition(), persisted.action()
                    );
                    return new DebuggerSessionController.BreakpointDefinition(
                            URI.create(persisted.sourceUri()),
                            persisted.binaryName(),
                            request,
                            persisted.enabled()
                    );
                })
                .toList();
    }

    public void persistBreakpoints(DebuggerSessionController controller) {
        if (!isActive()) return;
        String runtimeSignature = runtimeSignature();
        if (runtimeSignature == null || runtimeSignature.isBlank()) {
            return;
        }
        List<InstanceState.PersistedBreakpoint> persisted = controller.breakpointDefinitions().stream()
                .map(definition -> {
                    DebugEngine.SourceBreakpoint request = definition.request();
                    DebugEngine.MethodTarget method = request.method();
                    return new InstanceState.PersistedBreakpoint(
                            definition.sourceUri().toString(),
                            definition.binaryName(),
                            request.line(),
                            request.debuggerLine(),
                            method == null ? null : method.ownerClassName(),
                            method == null ? null : method.name(),
                            method == null ? null : method.descriptor(),
                            request.condition(),
                            request.hitCondition(),
                            definition.enabled(), request.action()
                    );
                })
                .toList();
        state.setDebuggerBreakpoints(runtimeSignature, persisted);
    }

}
