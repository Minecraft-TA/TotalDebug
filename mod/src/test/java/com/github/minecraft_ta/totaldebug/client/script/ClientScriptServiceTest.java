package com.github.minecraft_ta.totaldebug.client.script;

import com.github.minecraft_ta.totaldebug.client.inspection.KeptStacks;
import com.github.minecraft_ta.totaldebug.protocol.Side;
import com.github.minecraft_ta.totaldebug.protocol.execution.ExecutionResult;
import com.github.minecraft_ta.totaldebug.protocol.execution.ExecutionStatus;
import com.github.minecraft_ta.totaldebug.protocol.execution.ScriptBytecode;
import com.github.minecraft_ta.totaldebug.protocol.scnet.RunScriptMessage;
import com.github.minecraft_ta.totaldebug.tick.TickTaskScheduler;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The client runs only client scripts; server scripts reach the server through the relay. */
class ClientScriptServiceTest {
    @Test
    void aServerRunSentToTheClientIsRefusedRatherThanRunHere() {
        List<Status> statuses = new ArrayList<>();
        try (ClientScriptService service = service(statuses)) {
            service.handleRunRequest(run(5, Side.SERVER, "THREAD"));
        }

        assertEquals(List.of(new Status(5, ExecutionResult.fromStatus(ExecutionStatus.RUN_EXCEPTION,
                "A server script reaches the server through the relay, not the client"))), statuses);
    }

    @Test
    void anUnknownEnvironmentIsRefusedBeforeTheScriptRuns() {
        List<Status> statuses = new ArrayList<>();
        try (ClientScriptService service = service(statuses)) {
            service.handleRunRequest(run(6, Side.CLIENT, "SOMETIME"));
        }

        assertEquals(1, statuses.size());
        assertEquals(6, statuses.getFirst().scriptId());
        assertEquals(ExecutionStatus.COMPILATION_FAILED, statuses.getFirst().status().status());
    }

    private static ClientScriptService service(List<Status> statuses) {
        return new ClientScriptService((scriptId, result) -> statuses.add(new Status(scriptId, result)),
                new TickTaskScheduler(), () -> "game-session", new KeptStacks());
    }

    private static RunScriptMessage run(int scriptId, Side side, String environment) {
        return new RunScriptMessage(scriptId, new ScriptBytecode("Test", Map.of("Test", new byte[]{1, 2})),
                "inventory", side, environment);
    }

    private record Status(int scriptId, ExecutionResult status) {
    }
}
