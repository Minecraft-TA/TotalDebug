package com.github.minecraft_ta.totalDebugCompanion.script;

import com.github.minecraft_ta.totalDebugCompanion.project.ProjectScope;
import com.github.minecraft_ta.totalDebugCompanion.session.CompanionSession;
import com.github.minecraft_ta.totaldebug.protocol.execution.ExecutionResult;
import com.github.minecraft_ta.totaldebug.protocol.execution.ScriptExecutionEnvironment;
import com.github.minecraft_ta.totaldebug.protocol.scnet.StopScriptMessage;
import java.util.function.Consumer;

/** Authenticated execution and cancellation, shared by editors and MCP jobs. */
public final class ScriptExecutionService {
    private final CompanionSession session;
    private final ScriptCompilationService compiler;

    public ScriptExecutionService(CompanionSession session, ScriptCompilationService compiler) {
        this.session = session;
        this.compiler = compiler;
    }

    public boolean isConnected() { return session.isConnected(); }

    public boolean run(ProjectScope project, int id, String source, boolean serverSide,
                       ScriptExecutionEnvironment environment, Consumer<ExecutionResult> failureHandler) {
        if (project == null || !project.isActive() || !isConnected()) return false;
        return project.admit(() -> {
            if (!isConnected()) return false;
            compiler.submit(id, source, serverSide, environment, failureHandler);
            return true;
        });
    }

    public boolean stop(int id) { return compiler.cancel(id) || session.send(new StopScriptMessage(id)); }
}
