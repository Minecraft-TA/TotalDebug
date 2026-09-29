package com.github.minecraft_ta.totaldebug.protocol.scnet;

import com.github.minecraft_ta.totaldebug.protocol.message.PlayingPayload;
import com.github.tth05.scnet.util.ByteBufferInputStream;
import com.github.tth05.scnet.util.ByteBufferOutputStream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PlayingProtocolCodecTest {
    static List<PlayingPayload> everyKind() {
        return List.of(new PlayingPayload.Menu(),
                new PlayingPayload.Singleplayer("C:/game/saves/New World"),
                new PlayingPayload.Multiplayer("play.example.com:25566", false, true));
    }

    @ParameterizedTest
    @MethodSource("everyKind")
    void whatTheGamePlaysSurvivesTheWire(PlayingPayload playing) {
        PlayingMessage read = new PlayingMessage();
        read.read(new ByteBufferInputStream(written(new PlayingMessage(playing))));

        assertEquals(playing, read.payload());
    }

    @Test
    void anUnknownKindIsRefused() {
        ByteBufferOutputStream output = new ByteBufferOutputStream();
        output.writeByte((byte) 3);
        ByteBuffer buffer = output.getBuffer().duplicate();
        buffer.flip();

        assertThrows(IllegalArgumentException.class, () -> new PlayingMessage().read(new ByteBufferInputStream(buffer)));
    }

    private static ByteBuffer written(PlayingMessage message) {
        ByteBufferOutputStream output = new ByteBufferOutputStream();
        message.write(output);
        ByteBuffer buffer = output.getBuffer().duplicate();
        buffer.flip();
        return buffer;
    }

    @Test
    void theWorldIsNamedByItsFolderOrItsServerAndTheMenuNamesNone() {
        assertEquals("", new PlayingPayload.Menu().identity());
        assertEquals("world C:/saves/New World", new PlayingPayload.Singleplayer("C:/saves/New World").identity());
        assertEquals("server play.example.net", new PlayingPayload.Multiplayer("play.example.net", false, true).identity());
    }
}
