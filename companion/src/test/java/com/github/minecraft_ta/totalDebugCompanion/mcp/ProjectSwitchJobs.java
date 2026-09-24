package com.github.minecraft_ta.totalDebugCompanion.mcp;

import com.github.minecraft_ta.totaldebug.protocol.execution.ExecutionResult;
import com.github.minecraft_ta.totaldebug.protocol.execution.ExecutionStatus;
import com.github.minecraft_ta.totaldebug.protocol.execution.ExecutionText;
import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import com.github.minecraft_ta.totalDebugCompanion.script.ExecutionRuns;
import java.util.function.IntConsumer;

/** Holds compilation until application teardown cancels it. */
public final class ProjectSwitchJobs {
    private ProjectSwitchJobs() { }
    public static CodeModeJobService create() {
        return create(ignored -> {});
    }

    public static CodeModeJobService create(IntConsumer cancelled) {
        return new CodeModeJobService(() -> true, new CodeModeJobService.Transport() {
            private final Map<Integer, ExecutionRuns.Observer> compiling = new HashMap<>();
            private int lastId;
            @Override public int open(ExecutionRuns.Observer observer) {
                compiling.put(++lastId, observer);
                return lastId;
            }
            @Override public void execute(int id, String source, CodeModeJobService.ExecutionSide side,
                                          CodeModeJobService.ExecutionEnvironment environment) {
            }
            @Override public void discard(int id) {
                compiling.remove(id);
            }
            @Override public void cancel(int id) {
                cancelled.accept(id);
                var observer = compiling.remove(id);
                if (observer != null) observer.result(id, new ExecutionResult(ExecutionStatus.COMPILATION_FAILED,
                        ExecutionText.empty(), null, ExecutionText.complete("Compilation cancelled")));
            }
        }, Clock.systemUTC());
    }
}
