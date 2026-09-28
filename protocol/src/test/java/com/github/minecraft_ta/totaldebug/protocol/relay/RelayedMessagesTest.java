package com.github.minecraft_ta.totaldebug.protocol.relay;

import com.github.minecraft_ta.totaldebug.protocol.CompanionProtocol;
import com.github.minecraft_ta.totaldebug.protocol.Side;
import com.github.minecraft_ta.totaldebug.protocol.execution.ExecutionResult;
import com.github.minecraft_ta.totaldebug.protocol.execution.ExecutionResultCodec;
import com.github.minecraft_ta.totaldebug.protocol.execution.ScriptBytecode;
import com.github.minecraft_ta.totaldebug.protocol.execution.ScriptExecutionEnvironment;
import com.github.minecraft_ta.totaldebug.protocol.scnet.CompanionLeftMessage;
import com.github.minecraft_ta.totaldebug.protocol.scnet.ExecutionResultMessage;
import com.github.minecraft_ta.totaldebug.protocol.scnet.FromServerMessage;
import com.github.minecraft_ta.totaldebug.protocol.scnet.ServerScriptsMessage;
import com.github.minecraft_ta.totaldebug.protocol.scnet.ServerScriptsRequestMessage;
import com.github.minecraft_ta.totaldebug.protocol.scnet.PlayingMessage;
import com.github.minecraft_ta.totaldebug.protocol.scnet.RunScriptMessage;
import com.github.minecraft_ta.totaldebug.protocol.scnet.ToServerMessage;
import com.github.minecraft_ta.totaldebug.protocol.message.PlayingPayload;
import com.github.tth05.scnet.message.impl.DefaultMessageProcessor;
import com.github.tth05.scnet.util.ByteBufferInputStream;
import com.github.tth05.scnet.util.ByteBufferOutputStream;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RelayedMessagesTest {
    @Test
    void aServerRunTravelsInItsEnvelopeAndIsReadBackByTheServer() {
        byte[] large = new byte[40_000];
        RunScriptMessage run = new RunScriptMessage(7, new ScriptBytecode("Probe", Map.of("Probe", large)), "inventory",
                Side.SERVER, ScriptExecutionEnvironment.POST_TICK.name(),
                "block minecraft:overworld 1 64 -2", "game-session", "minecraft:furnace");

        RelayedMessage relayed = RelayedMessages.toServer(run, 7, "game-session");
        ToServerMessage envelope = new ToServerMessage(relayed);
        ToServerMessage read = new ToServerMessage();
        read.read(new ByteBufferInputStream(written(envelope)));

        assertEquals(7, read.payload().correlation());
        assertEquals("game-session", read.payload().world());
        RunScriptMessage decoded = assertInstanceOf(RunScriptMessage.class, RelayedMessages.decodeToServer(read.payload()));
        assertEquals(7, decoded.scriptId());
        assertEquals(Side.SERVER, decoded.side());
        assertEquals("block minecraft:overworld 1 64 -2", decoded.subject());
        assertArrayEquals(large, decoded.bytecode().classes().get("Probe"), "no 30,000-byte cap on the way to the server");
    }

    @Test
    void theServersAnswerIsReadBackByCompanion() {
        ExecutionResultMessage result = new ExecutionResultMessage(7, ExecutionResult.completed("done", null));

        ExecutionResultMessage decoded = assertInstanceOf(ExecutionResultMessage.class,
                RelayedMessages.decodeFromServer(RelayedMessages.fromServer(result)));

        assertEquals(7, decoded.scriptId());
        assertEquals(ExecutionResult.completed("done", null), decoded.result());
    }

    @Test
    void theServersRefusalOfScriptsIsReadBackByCompanion() {
        ServerScriptsMessage decoded = assertInstanceOf(ServerScriptsMessage.class, RelayedMessages.decodeFromServer(
                RelayedMessages.fromServer(new ServerScriptsMessage(-3, "Server-side scripts are disabled by the server configuration"))));

        assertEquals("Server-side scripts are disabled by the server configuration", decoded.refusal());
        assertEquals(-3, decoded.request(), "the answer names the question it answers");
        assertTrue(RelayedMessages.decodeFromServer(RelayedMessages.fromServer(ServerScriptsMessage.allowed(-4))) instanceof ServerScriptsMessage allowed
                && allowed.isAllowed());
    }

    @Test
    void anAccessRequestCarriesItsIdAndCompanionLeavingNothing() {
        RelayedMessage relayed = RelayedMessages.toServer(new ServerScriptsRequestMessage(-3), -3, "");
        RelayedMessage left = RelayedMessages.toServer(new CompanionLeftMessage(), 0, "");

        assertEquals(-3, assertInstanceOf(ServerScriptsRequestMessage.class, RelayedMessages.decodeToServer(relayed)).request());
        assertEquals(0, left.body().length);
        assertInstanceOf(CompanionLeftMessage.class, RelayedMessages.decodeToServer(left));
    }

    @Test
    void theLargestBodyWithTheLongestSessionFitsOneFrame() {
        RelayedMessage largest = new RelayedMessage(Integer.MAX_VALUE, "\u20ac".repeat(RelayedMessage.MAX_WORLD_LENGTH),
                CompanionProtocol.EXECUTION_RESULT, new byte[RelayedMessage.MAX_BODY_BYTES]);
        ByteBufferOutputStream output = new ByteBufferOutputStream();

        new FromServerMessage(largest).write(output);

        assertTrue(output.getBuffer().position() <= DefaultMessageProcessor.DEFAULT_MAX_FRAME_SIZE);
        assertTrue(ExecutionResultCodec.MAX_WIRE_BYTES + 2 * Integer.BYTES <= RelayedMessage.MAX_BODY_BYTES,
                "a server's largest result fits the relay's body");
    }

    @Test
    void onlyTheServersMessagesTravelEachWay() {
        assertThrows(IllegalArgumentException.class,
                () -> RelayedMessages.toServer(new PlayingMessage(new PlayingPayload.Menu()), 0, ""), "the game's own message");
        RelayedMessage toServer = RelayedMessages.toServer(new ServerScriptsRequestMessage(-3), 0, "");
        assertThrows(IllegalArgumentException.class, () -> RelayedMessages.decodeFromServer(toServer),
                "a request is not an answer");
    }

    @Test
    void bytesAfterAMessagesEndAreRefused() {
        RelayedMessage padded = new RelayedMessage(0, "", RelayedMessages.toServer(new CompanionLeftMessage(), 0, "").messageId(),
                new byte[]{1});

        assertThrows(IllegalArgumentException.class, () -> RelayedMessages.decodeToServer(padded));
    }

    private static ByteBuffer written(ToServerMessage message) {
        ByteBufferOutputStream output = new ByteBufferOutputStream();
        message.write(output);
        ByteBuffer buffer = output.getBuffer().duplicate();
        buffer.flip();
        return buffer;
    }
}
