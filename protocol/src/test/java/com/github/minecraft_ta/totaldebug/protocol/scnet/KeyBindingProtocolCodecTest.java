package com.github.minecraft_ta.totaldebug.protocol.scnet;

import com.github.minecraft_ta.totaldebug.protocol.message.KeyBindingResultPayload;
import com.github.minecraft_ta.totaldebug.protocol.message.SetKeyBindingPayload;
import com.github.tth05.scnet.message.AbstractMessage;
import com.github.tth05.scnet.util.ByteBufferInputStream;
import com.github.tth05.scnet.util.ByteBufferOutputStream;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;

class KeyBindingProtocolCodecTest {
    @Test
    void aKeyBindingRequestAndItsAnswerSurviveTheWire() {
        SetKeyBindingMessage request = new SetKeyBindingMessage(7, "key.jump", "key.keyboard.g", "CONTROL");
        SetKeyBindingMessage readRequest = new SetKeyBindingMessage();
        readRequest.read(new ByteBufferInputStream(written(request)));
        assertEquals(new SetKeyBindingPayload(7, "key.jump", "key.keyboard.g", "CONTROL"), readRequest.payload());

        KeyBindingResultPayload answer = new KeyBindingResultPayload(7, "key.jump", "key.keyboard.space", "NONE",
                "key.keyboard.g", "CONTROL", "");
        KeyBindingResultMessage readAnswer = new KeyBindingResultMessage();
        readAnswer.read(new ByteBufferInputStream(written(new KeyBindingResultMessage(answer))));
        assertEquals(answer, readAnswer.payload());
    }

    private static ByteBuffer written(AbstractMessage message) {
        ByteBufferOutputStream output = new ByteBufferOutputStream();
        message.write(output);
        ByteBuffer buffer = output.getBuffer().duplicate();
        buffer.flip();
        return buffer;
    }
}
