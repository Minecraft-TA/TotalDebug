package com.github.minecraft_ta.totaldebug.client.script;

import com.github.minecraft_ta.totaldebug.client.companion.message.RunScriptMessage;
import com.github.minecraft_ta.totaldebug.network.ForwardedScriptStatus;
import com.github.minecraft_ta.totaldebug.network.RunServerScriptPayload;
import com.github.minecraft_ta.totaldebug.network.StopServerScriptPayload;
import com.github.minecraft_ta.totaldebug.script.ScriptStatus;
import com.github.minecraft_ta.totaldebug.script.ScriptStatusType;
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
                ScriptStatus.failed("", null, "unsupported server")
        )), statuses);
    }

    @Test
    void routesRunStopAndForwardedStatusesThroughTheServerTransport() {
        List<Status> statuses = new ArrayList<>();
        FakeServerTransport transport = new FakeServerTransport(ServerScriptTransport.Availability.supported());
        ClientScriptService service = service(statuses, transport);

        service.handleRunRequest(serverRun(7));
        service.handleForwardedPayload(new ForwardedScriptStatus(
                7,
                ScriptStatus.progress(ScriptStatusType.COMPILATION_COMPLETED)
        ).toPayload());
        service.stopScript(7);
        service.handleForwardedPayload(new ForwardedScriptStatus(
                7,
                ScriptStatus.failed("partial", null, "Script run cancelled")
        ).toPayload());
        service.stopScript(7);

        assertEquals(1, transport.runs.size());
        assertEquals(7, transport.runs.getFirst().scriptId());
        assertEquals(List.of(new StopServerScriptPayload(7)), transport.stops);
        assertEquals(
                List.of(
                        new Status(7, ScriptStatus.progress(ScriptStatusType.COMPILATION_COMPLETED)),
                        new Status(7, ScriptStatus.failed("partial", null, "Script run cancelled"))
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

        service.handleForwardedPayload(new ForwardedScriptStatus(
                99,
                ScriptStatus.completed("forged", null)
        ).toPayload());

        assertTrue(statuses.isEmpty());
    }

    @Test
    void terminatesRemoteRunsWhenTheClientDisconnects() {
        List<Status> statuses = new ArrayList<>();
        FakeServerTransport transport = new FakeServerTransport(ServerScriptTransport.Availability.supported());
        ClientScriptService service = service(statuses, transport);

        service.handleRunRequest(serverRun(7));
        service.onServerDisconnect();
        service.handleForwardedPayload(new ForwardedScriptStatus(
                7,
                ScriptStatus.completed("stale", null)
        ).toPayload());
        service.stopScript(7);

        assertEquals(
                List.of(new Status(
                        7,
                        ScriptStatus.failed(
                                "",
                                null,
                                "Disconnected from the server while the script was running"
                        )
                )),
                statuses
        );
        assertTrue(transport.stops.isEmpty());
    }

    private static ClientScriptService service(List<Status> statuses, ServerScriptTransport transport) {
        return new ClientScriptService(
                (scriptId, status) -> statuses.add(new Status(scriptId, status)),
                new TickTaskScheduler(),
                transport
        );
    }

    private static RunScriptMessage serverRun(int scriptId) {
        ByteBufferOutputStream output = new ByteBufferOutputStream();
        output.writeInt(scriptId);
        output.writeString("public class Test extends BaseScript { public void run() {} }");
        output.writeBoolean(true);
        output.writeString("THREAD");
        ByteBuffer bytes = output.getBuffer().duplicate();
        bytes.flip();
        RunScriptMessage message = new RunScriptMessage();
        message.read(new ByteBufferInputStream(bytes));
        return message;
    }

    private record Status(int scriptId, ScriptStatus status) {
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
