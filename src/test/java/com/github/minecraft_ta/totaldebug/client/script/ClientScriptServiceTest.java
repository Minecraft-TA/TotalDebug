package com.github.minecraft_ta.totaldebug.client.script;

import com.github.minecraft_ta.totaldebug.client.companion.message.RunScriptMessage;
import com.github.minecraft_ta.totaldebug.network.ForwardedExecutionResult;
import com.github.minecraft_ta.totaldebug.network.RunServerScriptPayload;
import com.github.minecraft_ta.totaldebug.network.StopServerScriptPayload;
import com.github.minecraft_ta.totaldebug.script.ExecutionResult;
import com.github.minecraft_ta.totaldebug.script.ExecutionStatus;
import com.github.minecraft_ta.totaldebug.tick.TickTaskScheduler;
import com.github.tth05.scnet.util.ByteBufferInputStream;
import com.github.tth05.scnet.util.ByteBufferOutputStream;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientScriptServiceTest {
    @Test
    void rejectsServerExecutionLocallyWhenTheCurrentServerHasNoChannels() {
        List<Status> statuses = new ArrayList<>();
        FakeServerTransport transport = new FakeServerTransport(
                ServerScriptTransport.Availability.unsupported("unsupported server")
        );
        ClientScriptService service = service(statuses, transport);

        service.handleRunRequest(serverRun(7));

        assertTrue(transport.runs.isEmpty());
        assertEquals(List.of(new Status(
                7,
                ExecutionResult.failed("", null, "unsupported server")
        )), statuses);
    }

    @Test
    void routesRunStopAndForwardedStatusesThroughTheServerTransport() {
        List<Status> statuses = new ArrayList<>();
        FakeServerTransport transport = new FakeServerTransport(ServerScriptTransport.Availability.supported());
        ClientScriptService service = service(statuses, transport);

        service.handleRunRequest(serverRun(-1));
        int executionId = transport.runs.getFirst().scriptId();
        forward(service, new ForwardedExecutionResult(
                executionId,
                ExecutionResult.progress(ExecutionStatus.COMPILATION_COMPLETED)
        ));
        service.stopScript(-1);
        forward(service, new ForwardedExecutionResult(
                executionId,
                ExecutionResult.failed("partial", null, "Script run cancelled")
        ));
        service.stopScript(-1);

        assertEquals(1, transport.runs.size());
        assertEquals(List.of(new StopServerScriptPayload(executionId)), transport.stops);
        assertEquals(
                List.of(
                        new Status(-1, ExecutionResult.progress(ExecutionStatus.COMPILATION_COMPLETED)),
                        new Status(-1, ExecutionResult.failed("partial", null, "Script run cancelled"))
                ),
                statuses
        );
    }

    @Test
    void ignoresAForwardedStatusForAClientOrUnknownRun() {
        List<Status> statuses = new ArrayList<>();
        ClientScriptService service = service(
                statuses,
                new FakeServerTransport(ServerScriptTransport.Availability.supported())
        );

        forward(service, new ForwardedExecutionResult(
                99,
                ExecutionResult.completed("forged", null)
        ));

        assertTrue(statuses.isEmpty());
    }

    @Test
    void terminatesRemoteRunsWhenTheClientDisconnects() {
        List<Status> statuses = new ArrayList<>();
        FakeServerTransport transport = new FakeServerTransport(ServerScriptTransport.Availability.supported());
        ClientScriptService service = service(statuses, transport);

        service.handleRunRequest(serverRun(7));
        int executionId = transport.runs.getFirst().scriptId();
        service.onServerDisconnect();
        forward(service, new ForwardedExecutionResult(
                executionId,
                ExecutionResult.completed("stale", null)
        ));
        service.stopScript(7);

        assertEquals(
                List.of(new Status(
                        7,
                        ExecutionResult.failed(
                                "",
                                null,
                                "Disconnected from the server while the script was running"
                        )
                )),
                statuses
        );
        assertTrue(transport.stops.isEmpty());
    }

    @Test
    void lateCompletionCannotSettleAReusedCompanionIdAfterSessionClose() {
        List<Status> statuses = new ArrayList<>();
        FakeServerTransport transport = new FakeServerTransport(ServerScriptTransport.Availability.supported());
        try (ClientScriptService service = service(statuses, transport)) {
            service.handleRunRequest(serverRun(41));
            int oldExecution = transport.runs.getLast().scriptId();
            service.close();
            service.handleRunRequest(serverRun(41));
            int newExecution = transport.runs.getLast().scriptId();

            forward(service, new ForwardedExecutionResult(oldExecution, ExecutionResult.completed("old session", null)));
            assertTrue(statuses.isEmpty(), "Old completion reached the replacement run");
            service.stopScript(41);
            assertEquals(new StopServerScriptPayload(newExecution), transport.stops.getLast());
            forward(service, new ForwardedExecutionResult(newExecution, ExecutionResult.completed("new session", null)));
            assertEquals(List.of(new Status(41, ExecutionResult.completed("new session", null))), statuses);
        }
    }

    @Test
    void delayedDuplicateCannotSettleTheNextRunOfTheSameScript() {
        List<Status> statuses = new ArrayList<>();
        FakeServerTransport transport = new FakeServerTransport(ServerScriptTransport.Availability.supported());
        try (ClientScriptService service = service(statuses, transport)) {
            service.handleRunRequest(serverRun(7));
            int first = transport.runs.getLast().scriptId();
            forward(service, new ForwardedExecutionResult(first, ExecutionResult.completed("first", null)));
            service.handleRunRequest(serverRun(7));
            int second = transport.runs.getLast().scriptId();
            forward(service, new ForwardedExecutionResult(first, ExecutionResult.completed("duplicate", null)));
            forward(service, new ForwardedExecutionResult(second, ExecutionResult.completed("second", null)));
            assertEquals(List.of(new Status(7, ExecutionResult.completed("first", null)),
                    new Status(7, ExecutionResult.completed("second", null))), statuses);
        }
    }

    private static ClientScriptService service(List<Status> statuses, ServerScriptTransport transport) {
        return new ClientScriptService(
                (scriptId, status) -> statuses.add(new Status(scriptId, status)),
                new TickTaskScheduler(),
                transport
        );
    }

    private static void forward(ClientScriptService service, ForwardedExecutionResult forwarded) {
        forwarded.toPayloads().forEach(service::handleForwardedPayload);
    }

    private static RunScriptMessage serverRun(int scriptId) {
        ByteBufferOutputStream output = new ByteBufferOutputStream();
        output.writeInt(scriptId);
        output.writeString("public class Test extends com.github.minecraft_ta.totaldebug.script.ScriptProgram "
                + "{ public Object run() { return null; } }");
        output.writeBoolean(true);
        output.writeString("THREAD");
        ByteBuffer bytes = output.getBuffer().duplicate();
        bytes.flip();
        RunScriptMessage message = new RunScriptMessage();
        message.read(new ByteBufferInputStream(bytes));
        return message;
    }

    private record Status(int scriptId, ExecutionResult status) {
    }

    private static final class FakeServerTransport implements ServerScriptTransport {
        private final Availability availability;
        private final List<RunServerScriptPayload> runs = new ArrayList<>();
        private final List<StopServerScriptPayload> stops = new ArrayList<>();

        private FakeServerTransport(Availability availability) {
            this.availability = availability;
        }

        @Override
        public Availability availability() {
            return this.availability;
        }

        @Override
        public void run(RunServerScriptPayload payload) {
            this.runs.add(payload);
        }

        @Override
        public void stop(StopServerScriptPayload payload) {
            this.stops.add(payload);
        }
    }
}
