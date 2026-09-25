package com.github.minecraft_ta.totalDebugCompanion.script;

import com.github.minecraft_ta.totalDebugCompanion.project.ProjectScope;
import com.github.minecraft_ta.totalDebugCompanion.session.CompanionProfile;
import com.github.minecraft_ta.totalDebugCompanion.session.CompanionSession;
import com.github.minecraft_ta.totalDebugCompanion.storage.ChangeRecord;
import com.github.minecraft_ta.totalDebugCompanion.storage.InstanceState;
import com.github.minecraft_ta.totaldebug.protocol.execution.ExecutionResult;
import com.github.minecraft_ta.totaldebug.protocol.execution.ExecutionStatus;
import com.github.minecraft_ta.totaldebug.protocol.execution.ScriptExecutionEnvironment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ScriptExecutionServiceTest {
    @TempDir Path directory;

    @Test
    void connectedWithoutRuntimeReportsCompilationReadinessThroughTheFailureHandler() throws Exception {
        var project = new ProjectScope(new Object(), new CompanionProfile("test", directory, directory), InstanceState.inMemory(), ChangeRecord.inMemory());
        try (var session = new CompanionSession("test-token-1234567890abcdef");
             var compiler = new ScriptCompilationService(message -> fail("Must not send local code"), message -> false)) {
            var scripts = new ScriptExecutionService(session, compiler, () -> true);
            var result = new AtomicReference<ExecutionResult>();
            assertFalse(scripts.isReady());
            assertTrue(scripts.run(project, 1, "source", false, ScriptExecutionEnvironment.THREAD, failure -> result.set(failure.result())),
                    "False is reserved for disconnected or inactive projects");
            assertNotNull(result.get());
            assertEquals(ExecutionStatus.COMPILATION_FAILED, result.get().status());
            assertTrue(result.get().error().text().contains("runtime class index is not ready"));
        } finally { project.retire(); project.close(); }
    }
}
