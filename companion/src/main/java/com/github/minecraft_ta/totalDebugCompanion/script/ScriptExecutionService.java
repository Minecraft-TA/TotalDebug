package com.github.minecraft_ta.totalDebugCompanion.script;

import com.github.minecraft_ta.totalDebugCompanion.project.ProjectScope;
import com.github.minecraft_ta.totalDebugCompanion.project.ProjectScope.InactiveProjectException;
import com.github.minecraft_ta.totalDebugCompanion.session.CompanionSession;
import com.github.minecraft_ta.totaldebug.protocol.execution.ScriptExecutionEnvironment;
import com.github.minecraft_ta.totaldebug.protocol.scnet.StopScriptMessage;
import java.util.function.Consumer;
import java.util.function.BooleanSupplier;

/** Authenticated execution and cancellation for {@link ExecutionRuns}. */
public final class ScriptExecutionService {
    private final CompanionSession session;
    private final BooleanSupplier connected;
    private final ScriptCompilationService compiler;

    public ScriptExecutionService(CompanionSession session, ScriptCompilationService compiler, BooleanSupplier connected) {
        this.connected = connected;
        this.session = session;
        this.compiler = compiler;
    }

    public boolean isConnected() { return connected.getAsBoolean(); }
    public boolean isReady() { return isConnected() && compiler.hasRuntime(); }

    public boolean run(ProjectScope project, int id, String source, boolean serverSide,
                       ScriptExecutionEnvironment environment, Consumer<ScriptCompilationService.Failure> failureHandler) {
        return run(project, id, source, serverSide, environment, null, failureHandler);
    }

    public boolean run(ProjectScope project, int id, String source, boolean serverSide,
                       ScriptExecutionEnvironment environment, ScriptSubject subject,
                       Consumer<ScriptCompilationService.Failure> failureHandler) {
        if (project == null || !project.isActive() || !isConnected()) return false;
        try {
            return project.admit(() -> {
                if (!isConnected()) return false;
                compiler.submit(id, source, serverSide, environment, subject, failureHandler);
                return true;
            });
        } catch (InactiveProjectException ignored) {
            return false;
        }
    }

    /** Whether a run on one side would be accepted now; disconnected, it waits for the next runtime change. */
    public ScriptCompilationService.Readiness readiness(boolean serverSide) {
        ScriptCompilationService.Readiness compilation = compiler.readiness(serverSide);
        return isConnected() ? compilation
                : new ScriptCompilationService.Readiness(false, "Minecraft is not connected", compilation.changed());
    }

    public boolean stop(int id) { return compiler.cancel(id) || session.send(new StopScriptMessage(id)); }
}
