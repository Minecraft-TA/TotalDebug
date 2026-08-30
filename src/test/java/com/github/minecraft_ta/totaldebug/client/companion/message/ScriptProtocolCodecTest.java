package com.github.minecraft_ta.totaldebug.client.companion.message;

import com.github.minecraft_ta.totaldebug.script.ScriptStatus;
import com.github.minecraft_ta.totaldebug.script.ScriptStatusType;
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
    void scriptStatusMatchesTheSharedGoldenBytes() {
        ScriptStatusMessage message = new ScriptStatusMessage(
                7,
                new ScriptStatus(ScriptStatusType.RUN_COMPLETED, "out", "{\"ok\":true}", "")
        );
        ByteBufferOutputStream output = new ByteBufferOutputStream();

        message.write(output);

        assertArrayEquals(
                HEX.parseHex(
                        "000000070000000d52554e5f434f4d504c45544544"
                                + "000000036f7574010000000b7b226f6b223a747275657d00000000"
                ),
                writtenBytes(output)
        );
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
