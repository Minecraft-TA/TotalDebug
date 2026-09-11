package com.github.minecraft_ta.totalDebugCompanion.mcp;

import com.github.minecraft_ta.totaldebug.protocol.execution.ExecutionResult;
import com.github.minecraft_ta.totaldebug.protocol.execution.ExecutionStatus;
import com.github.minecraft_ta.totaldebug.protocol.execution.ExecutionText;
import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/** Holds compilation until application teardown cancels it. */
public final class ProjectSwitchJobs {
    private ProjectSwitchJobs() { }
    public static CodeModeJobService create() {
        return new CodeModeJobService(() -> true, new CodeModeJobService.Transport() {
            private final Map<Integer, Consumer<ExecutionResult>> compiling = new HashMap<>();
            @Override public void execute(int id, String source, CodeModeJobService.ExecutionSide side,
                                          CodeModeJobService.ExecutionEnvironment environment, Consumer<ExecutionResult> failure) {
                compiling.put(id, failure);
            }
            @Override public void cancel(int id) {
                var failure = compiling.remove(id);
                if (failure != null) failure.accept(new ExecutionResult(ExecutionStatus.COMPILATION_FAILED,
                        ExecutionText.empty(), null, ExecutionText.complete("Compilation cancelled")));
            }
        }, Clock.systemUTC());
    }
}
