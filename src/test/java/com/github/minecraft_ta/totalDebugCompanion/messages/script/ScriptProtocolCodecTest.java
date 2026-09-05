package com.github.minecraft_ta.totalDebugCompanion.messages.script;

import com.github.tth05.scnet.util.ByteBufferInputStream;
import com.github.tth05.scnet.util.ByteBufferOutputStream;
import com.github.minecraft_ta.totalDebugCompanion.script.ExecutionResult;
import com.github.minecraft_ta.totalDebugCompanion.script.ExecutionText;
import com.github.minecraft_ta.totalDebugCompanion.script.ExecutionValue;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ScriptProtocolCodecTest {
    private static final HexFormat HEX = HexFormat.of();

    @Test
    void cancellationPendingSurvivesTheResultEnvelopeWithoutBecomingTerminal() {
        ExecutionResult result = new ExecutionResult(ExecutionResult.Status.CANCELLATION_PENDING,
                ExecutionText.empty(), null, ExecutionText.complete("Stop requested; script is still running"));
        ExecutionResultMessage written = new ExecutionResultMessage(7, result);
        ByteBufferOutputStream output = new ByteBufferOutputStream();
        written.write(output);
        ExecutionResultMessage read = new ExecutionResultMessage();
        read.read(new ByteBufferInputStream(ByteBuffer.wrap(writtenBytes(output))));
        assertEquals(result, read.getResult());
        assertEquals(false, read.getResult().status().terminal());
    }

    @Test
    void runScriptMatchesTheSharedGoldenBytes() {
        RunScriptMessage message = new RunScriptMessage(
                7,
                "public class X {}",
                true,
                RunScriptMessage.ExecutionEnvironment.POST_TICK
        );
        ByteBufferOutputStream output = new ByteBufferOutputStream();

        message.write(output);

        assertArrayEquals(
                HEX.parseHex(
                        "00000007000000117075626c696320636c6173732058207b7d0100000009504f53545f5449434b"
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
                ExecutionResult.Status.RUN_COMPLETED,
                new ExecutionText("out", 3, false),
                value,
                new ExecutionText("", 0, false)
        );
        ExecutionResultMessage written = new ExecutionResultMessage(7, result);
        ByteBufferOutputStream output = new ByteBufferOutputStream();

        written.write(output);
        ExecutionResultMessage read = new ExecutionResultMessage();
        read.read(new ByteBufferInputStream(ByteBuffer.wrap(writtenBytes(output))));

        assertEquals(7, read.getScriptId());
        assertEquals(result, read.getResult());
    }

    @Test
    void stopScriptMatchesTheSharedGoldenBytes() {
        StopScriptMessage message = new StopScriptMessage(7);
        ByteBufferOutputStream output = new ByteBufferOutputStream();

        message.write(output);

        assertArrayEquals(HEX.parseHex("00000007"), writtenBytes(output));
    }

    private static byte[] writtenBytes(ByteBufferOutputStream output) {
        ByteBuffer buffer = output.getBuffer().duplicate();
        buffer.flip();
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        return bytes;
    }
}
