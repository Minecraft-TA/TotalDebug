package com.github.minecraft_ta.totaldebug.client.companion.message;

import com.github.minecraft_ta.totaldebug.script.ExecutionResult;
import com.github.minecraft_ta.totaldebug.script.ExecutionStatus;
import com.github.minecraft_ta.totaldebug.script.ExecutionText;
import com.github.minecraft_ta.totaldebug.script.ExecutionValue;
import com.github.tth05.scnet.util.ByteBufferInputStream;
import com.github.tth05.scnet.util.ByteBufferOutputStream;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScriptProtocolCodecTest {
    private static final HexFormat HEX = HexFormat.of();

    @Test
    void runScriptReadsTheSharedGoldenBytes() {
        byte[] golden = HEX.parseHex(
                "00000007000000117075626c696320636c6173732058207b7d0100000009504f53545f5449434b"
        );
        RunScriptMessage message = new RunScriptMessage();

        message.read(new ByteBufferInputStream(ByteBuffer.wrap(golden)));

        assertEquals(7, message.scriptId());
        assertEquals("public class X {}", message.scriptText());
        assertTrue(message.serverSide());
        assertEquals("POST_TICK", message.executionEnvironment());
    }

    @Test
    void executionResultRoundTripsTheCanonicalEnvelope() {
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

        message.read(new ByteBufferInputStream(ByteBuffer.wrap(HEX.parseHex("00000007"))));

        assertEquals(7, message.scriptId());
    }

    private static byte[] writtenBytes(ByteBufferOutputStream output) {
        ByteBuffer buffer = output.getBuffer().duplicate();
        buffer.flip();
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        return bytes;
    }
}
