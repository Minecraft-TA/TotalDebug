package com.github.minecraft_ta.totalDebugCompanion.messages.script;

import com.github.tth05.scnet.util.ByteBufferInputStream;
import com.github.tth05.scnet.util.ByteBufferOutputStream;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ScriptProtocolCodecTest {
    private static final HexFormat HEX = HexFormat.of();

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
    void scriptStatusReadsTheSharedGoldenBytes() {
        ScriptStatusMessage message = new ScriptStatusMessage();

        message.read(new ByteBufferInputStream(ByteBuffer.wrap(
                HEX.parseHex("000000070000000d52554e5f434f4d504c45544544000000026f6b")
        )));

        assertEquals(7, message.getScriptId());
        assertEquals(ScriptStatusMessage.Type.RUN_COMPLETED, message.getType());
        assertEquals("ok", message.getMessage());
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
