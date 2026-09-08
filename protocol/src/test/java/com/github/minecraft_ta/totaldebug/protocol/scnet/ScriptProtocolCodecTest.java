package com.github.minecraft_ta.totaldebug.protocol.scnet;

import com.github.minecraft_ta.totaldebug.protocol.GoldenMessages;
import com.github.minecraft_ta.totaldebug.protocol.execution.ScriptBytecode;
import java.util.Map;
import com.github.minecraft_ta.totaldebug.protocol.execution.ExecutionResult;
import com.github.minecraft_ta.totaldebug.protocol.execution.ExecutionStatus;
import com.github.minecraft_ta.totaldebug.protocol.execution.ExecutionText;
import com.github.minecraft_ta.totaldebug.protocol.execution.ExecutionValue;
import com.github.minecraft_ta.totaldebug.protocol.execution.ScriptExecutionEnvironment;
import com.github.tth05.scnet.util.ByteBufferInputStream;
import com.github.tth05.scnet.util.ByteBufferOutputStream;
import java.nio.ByteBuffer;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScriptProtocolCodecTest {
    private static final HexFormat HEX = HexFormat.of();

    @Test
    void runScriptReadsTheSharedGoldenBytes() {
        byte[] golden = HEX.parseHex(
                GoldenMessages.RUN_SCRIPT
        );
        RunScriptMessage message = new RunScriptMessage();

        message.read(new ByteBufferInputStream(ByteBuffer.wrap(golden)));

        assertEquals(7, message.scriptId());
        assertEquals("X", message.bytecode().primaryClass());
        assertArrayEquals(new byte[]{1, 2, 3}, message.bytecode().classes().get("X"));
        assertEquals("inventory", message.inventoryId());
        assertEquals("s", message.serverSessionId());
        assertTrue(message.serverSide());
        assertEquals("POST_TICK", message.executionEnvironment());
    }

    @Test
    void executionResultWritesTheCanonicalEnvelope() {
        ExecutionValue value = new ExecutionValue(
                ExecutionText.complete("java.lang.Boolean"),
                ExecutionText.complete("true"),
                ExecutionText.empty(),
                ExecutionValue.Kind.BOOLEAN,
                0,
                0,
                false,
                java.util.List.of()
        );
        ExecutionResult result = new ExecutionResult(
                ExecutionStatus.RUN_COMPLETED,
                ExecutionText.complete("out"),
                value,
                ExecutionText.empty()
        );
        ExecutionResultMessage message = new ExecutionResultMessage(
                7,
                result
        );
        ByteBufferOutputStream output = new ByteBufferOutputStream();

        message.write(output);
        ByteBufferInputStream input = new ByteBufferInputStream(ByteBuffer.wrap(writtenBytes(output)));

        assertEquals(7, input.readInt());
        assertEquals("""
                {"status":"RUN_COMPLETED","logs":{"text":"out","totalCharacters":3,"truncated":false},"value":{"type":{"text":"java.lang.Boolean","totalCharacters":17,"truncated":false},"value":{"text":"true","totalCharacters":4,"truncated":false},"preview":{"text":"","totalCharacters":0,"truncated":false},"kind":"BOOLEAN","identity":0,"totalChildren":0,"truncated":false,"children":[]},"error":{"text":"","totalCharacters":0,"truncated":false}}""", input.readString());
    }

    @Test
    void stopScriptReadsTheSharedGoldenBytes() {
        StopScriptMessage message = new StopScriptMessage();

        message.read(new ByteBufferInputStream(ByteBuffer.wrap(HEX.parseHex(GoldenMessages.STOP_SCRIPT))));

        assertEquals(7, message.scriptId());
    }


    @Test
    void cancellationPendingSurvivesTheResultEnvelopeWithoutBecomingTerminal() {
        ExecutionResult result = new ExecutionResult(ExecutionStatus.CANCELLATION_PENDING,
                ExecutionText.empty(), null, ExecutionText.complete("Stop requested; script is still running"));
        ExecutionResultMessage written = new ExecutionResultMessage(7, result);
        ByteBufferOutputStream output = new ByteBufferOutputStream();
        written.write(output);
        ExecutionResultMessage read = new ExecutionResultMessage();
        read.read(new ByteBufferInputStream(ByteBuffer.wrap(writtenBytes(output))));
        assertEquals(result, read.result());
        assertEquals(false, read.result().status().terminal());
    }

    @Test
    void runScriptMatchesTheSharedGoldenBytes() {
        RunScriptMessage message = new RunScriptMessage(
                7,
                new ScriptBytecode("X", Map.of("X", new byte[]{1, 2, 3})),
                "inventory",
                true,
                ScriptExecutionEnvironment.POST_TICK.name(), "s"
        );
        ByteBufferOutputStream output = new ByteBufferOutputStream();

        message.write(output);

        assertArrayEquals(
                HEX.parseHex(
                        GoldenMessages.RUN_SCRIPT
                ),
                writtenBytes(output)
        );
    }

    @Test
    void executionResultRoundTripsTheCanonicalEnvelope() {
        ExecutionValue value = new ExecutionValue(
                new ExecutionText("java.lang.Boolean", 17, false),
                new ExecutionText("true", 4, false),
                new ExecutionText("", 0, false),
                ExecutionValue.Kind.BOOLEAN,
                0,
                0,
                false,
                java.util.List.of()
        );
        ExecutionResult result = new ExecutionResult(
                ExecutionStatus.RUN_COMPLETED,
                new ExecutionText("out", 3, false),
                value,
                new ExecutionText("", 0, false)
        );
        ExecutionResultMessage written = new ExecutionResultMessage(7, result);
        ByteBufferOutputStream output = new ByteBufferOutputStream();

        written.write(output);
        ExecutionResultMessage read = new ExecutionResultMessage();
        read.read(new ByteBufferInputStream(ByteBuffer.wrap(writtenBytes(output))));

        assertEquals(7, read.scriptId());
        assertEquals(result, read.result());
    }

    @Test
    void stopScriptMatchesTheSharedGoldenBytes() {
        StopScriptMessage message = new StopScriptMessage(7);
        ByteBufferOutputStream output = new ByteBufferOutputStream();

        message.write(output);

        assertArrayEquals(HEX.parseHex(GoldenMessages.STOP_SCRIPT), writtenBytes(output));
    }

    private static byte[] writtenBytes(ByteBufferOutputStream output) {
        ByteBuffer buffer = output.getBuffer().duplicate();
        buffer.flip();
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        return bytes;
    }
}
