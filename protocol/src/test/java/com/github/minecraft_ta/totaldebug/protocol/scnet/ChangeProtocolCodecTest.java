package com.github.minecraft_ta.totaldebug.protocol.scnet;

import com.github.minecraft_ta.totaldebug.protocol.message.ChangePayload;
import com.github.minecraft_ta.totaldebug.protocol.message.ChangeResultPayload;
import com.github.tth05.scnet.message.AbstractMessage;
import com.github.tth05.scnet.util.ByteBufferInputStream;
import com.github.tth05.scnet.util.ByteBufferOutputStream;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ChangeProtocolCodecTest {
    @Test
    void aChangeAndItsAnswerSurviveTheWire() {
        ChangePayload change = new ChangePayload(7, List.of(
                new ChangePayload.Edit("keyBinding", "key.jump", "key.keyboard.space", "key.keyboard.g:CONTROL"),
                new ChangePayload.Edit("keyBinding", "key.sneak", "key.keyboard.g:CONTROL", "key.keyboard.unknown")));
        ChangeMessage readChange = new ChangeMessage();
        readChange.read(new ByteBufferInputStream(written(new ChangeMessage(change))));
        assertEquals(change, readChange.payload());

        ChangeResultPayload answer = new ChangeResultPayload(7, List.of(
                new ChangeResultPayload.Applied("key.keyboard.space", "key.keyboard.g:CONTROL"),
                new ChangeResultPayload.Applied("key.keyboard.g:CONTROL", "key.keyboard.unknown")), "");
        ChangeResultMessage readAnswer = new ChangeResultMessage();
        readAnswer.read(new ByteBufferInputStream(written(new ChangeResultMessage(answer))));
        assertEquals(answer, readAnswer.payload());

        ChangeResultPayload refused = ChangeResultPayload.refused(8, "key.jump changed since it was shown");
        readAnswer.read(new ByteBufferInputStream(written(new ChangeResultMessage(refused))));
        assertEquals(refused, readAnswer.payload());
    }

    @Test
    void aChangeHoldsAtLeastOneEditAndAnAnswerEitherValuesOrAnError() {
        assertThrows(IllegalArgumentException.class, () -> new ChangePayload(1, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new ChangePayload(1,
                Collections.nCopies(ChangePayload.MAX_EDITS + 1, new ChangePayload.Edit("keyBinding", "key.jump", "", ""))));
        assertThrows(IllegalArgumentException.class, () -> new ChangeResultPayload(1, List.of(), ""));
        assertThrows(IllegalArgumentException.class, () -> new ChangeResultPayload(1,
                List.of(new ChangeResultPayload.Applied("a", "b")), "refused"));
    }

    private static ByteBuffer written(AbstractMessage message) {
        ByteBufferOutputStream output = new ByteBufferOutputStream();
        message.write(output);
        ByteBuffer buffer = output.getBuffer().duplicate();
        buffer.flip();
        return buffer;
    }
}
