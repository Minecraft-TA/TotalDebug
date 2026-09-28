package com.github.minecraft_ta.totalDebugCompanion.script;

import com.github.minecraft_ta.totalDebugCompanion.project.ProjectScope;
import com.github.minecraft_ta.totalDebugCompanion.project.ProjectScope.InactiveProjectException;
import com.github.minecraft_ta.totalDebugCompanion.session.CompanionSession;
import com.github.minecraft_ta.totaldebug.protocol.Side;
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

    public boolean run(ProjectScope project, int id, String source, Side side,
                       ScriptExecutionEnvironment environment, Consumer<ScriptCompilationService.Failure> failureHandler) {
        return run(project, id, source, side, environment, null, failureHandler);
    }

    public boolean run(ProjectScope project, int id, String source, Side side,
                       ScriptExecutionEnvironment environment, ScriptSubject subject,
                       Consumer<ScriptCompilationService.Failure> failureHandler) {
        if (project == null || !project.isActive() || !isConnected()) return false;
        try {
            return project.admit(() -> {
                if (!isConnected()) return false;
                compiler.submit(id, source, side, environment, subject, failureHandler);
                return true;
            });
        } catch (InactiveProjectException ignored) {
            return false;
        }
    }

    /** Whether a run on one side would be accepted now; disconnected, it waits for the next runtime change. */
    public ScriptCompilationService.Readiness readiness(Side side) {
        ScriptCompilationService.Readiness compilation = compiler.readiness(side);
        return isConnected() ? compilation
                : new ScriptCompilationService.Readiness(false, "Minecraft is not connected", compilation.changed());
    }

    /** Stops run {@code id} where it runs: still compiling, in the client, or on the server through the relay. */
    public boolean stop(int id, Side side) {
        if (compiler.cancel(id)) return true;
        return side == Side.SERVER ? session.sendToServer(new StopScriptMessage(id), id, "") : session.send(new StopScriptMessage(id));
    }
}
