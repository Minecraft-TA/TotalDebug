package com.github.minecraft_ta.totaldebug.client.script;

import com.github.minecraft_ta.totaldebug.client.companion.message.RunScriptMessage;
import com.github.minecraft_ta.totaldebug.network.ForwardedExecutionResult;
import com.github.minecraft_ta.totaldebug.network.RunServerScriptPayload;
import com.github.minecraft_ta.totaldebug.network.StopServerScriptPayload;
import com.github.minecraft_ta.totaldebug.script.ExecutionResult;
import com.github.minecraft_ta.totaldebug.tick.TickTaskScheduler;
import com.github.tth05.scnet.util.ByteBufferInputStream;
import com.github.tth05.scnet.util.ByteBufferOutputStream;
import java.util.ArrayList;
import java.util.List;

/** Replays late server completion across a Companion session change, without a game connection. */
public class ScriptSessionIdentityProbe {
    public static void main(String[] args) {
        List<String> received = new ArrayList<>();
        try (ClientScriptService service = new ClientScriptService(
                (id, result) -> received.add(result.logs().text()), new TickTaskScheduler(),
                new ServerScriptTransport() {
                    public Availability availability() { return Availability.supported(); }
                    public void run(RunServerScriptPayload payload) { }
                    public void stop(StopServerScriptPayload payload) { }
                })) {
            service.handleRunRequest(request(41));
            service.close(); // The Companion session-close handler uses this method; Minecraft remains running.
            service.handleRunRequest(request(41));
            new ForwardedExecutionResult(41, ExecutionResult.completed("old session", null))
                    .toPayloads().forEach(service::handleForwardedPayload);
            new ForwardedExecutionResult(41, ExecutionResult.completed("new session", null))
                    .toPayloads().forEach(service::handleForwardedPayload);
            System.out.println("Received completions after session replacement: " + received);
            if (!received.equals(List.of("new session"))) {
                throw new AssertionError("Old-session completion settled the new run and consumed its identity");
            }
        }
    }

    private static RunScriptMessage request(int id) {
        ByteBufferOutputStream output = new ByteBufferOutputStream();
        output.writeInt(id);
        output.writeString("public class Probe extends com.github.minecraft_ta.totaldebug.script.ScriptProgram "
                + "{ public Object run() { return null; } }");
        output.writeBoolean(true);
        output.writeString("THREAD");
        var bytes = output.getBuffer().duplicate().flip();
        RunScriptMessage message = new RunScriptMessage();
        message.read(new ByteBufferInputStream(bytes));
        return message;
    }
}
