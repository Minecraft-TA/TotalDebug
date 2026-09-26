package com.github.minecraft_ta.totaldebug.protocol.scnet;

import com.github.minecraft_ta.totaldebug.protocol.message.ConfigValueResultPayload;
import com.github.minecraft_ta.totaldebug.protocol.message.PackStackPayload;
import com.github.minecraft_ta.totaldebug.protocol.message.ReloadPayload;
import com.github.minecraft_ta.totaldebug.protocol.message.ReloadResultPayload;
import com.github.minecraft_ta.totaldebug.protocol.message.SetConfigValuePayload;
import com.github.minecraft_ta.totaldebug.protocol.message.SetOverlayPayload;
import com.github.tth05.scnet.message.AbstractMessage;
import com.github.tth05.scnet.util.ByteBufferInputStream;
import com.github.tth05.scnet.util.ByteBufferOutputStream;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.EnumSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ResourceProtocolCodecTest {
    @Test
    void thePackStackSurvivesTheWire() {
        PackStackPayload stack = new PackStackPayload(34, 48,
                List.of(new PackStackPayload.Pack("vanilla", "Default", ""),
                        new PackStackPayload.Pack("file/TotalDebug", "TotalDebug", "C:/game/resourcepacks/TotalDebug")),
                List.of(new PackStackPayload.Pack("vanilla", "Default", "")));
        PackStackMessage read = new PackStackMessage();
        read.read(new ByteBufferInputStream(written(new PackStackMessage(stack))));
        assertEquals(stack, read.payload());
    }

    @Test
    void aReloadAndItsAnswerSurviveTheWire() {
        ReloadPayload request = new ReloadPayload(3, EnumSet.of(ReloadPayload.Kind.LANGUAGE, ReloadPayload.Kind.DATA),
                "file/TotalDebug", List.of("assets/testmod/lang/en_us.json"));
        ReloadMessage readRequest = new ReloadMessage();
        readRequest.read(new ByteBufferInputStream(written(new ReloadMessage(request))));
        assertEquals(request, readRequest.payload());

        ReloadResultPayload answer = new ReloadResultPayload(3, 1_250, List.of("Unable to parse testmod:block/slab"), "");
        ReloadResultMessage readAnswer = new ReloadResultMessage();
        readAnswer.read(new ByteBufferInputStream(written(new ReloadResultMessage(answer))));
        assertEquals(answer, readAnswer.payload());
    }

    @Test
    void anOverlayEntryAndItsRemovalSurviveTheWire() {
        SetOverlayPayload put = new SetOverlayPayload("assets/testmod/lang/en_us.json", new byte[]{'{', '}'});
        SetOverlayMessage readPut = new SetOverlayMessage();
        readPut.read(new ByteBufferInputStream(written(new SetOverlayMessage(put))));
        assertEquals(put, readPut.payload());

        SetOverlayPayload remove = new SetOverlayPayload("data/testmod/recipe/gear.json", null);
        SetOverlayMessage readRemove = new SetOverlayMessage();
        readRemove.read(new ByteBufferInputStream(written(new SetOverlayMessage(remove))));
        assertEquals(remove, readRemove.payload());
    }

    @Test
    void aConfigValueAndItsAnswerSurviveTheWire() {
        SetConfigValuePayload request = new SetConfigValuePayload(4, "testmod-common.toml", "widgets.speed", "12");
        SetConfigValueMessage readRequest = new SetConfigValueMessage();
        readRequest.read(new ByteBufferInputStream(written(new SetConfigValueMessage(request))));
        assertEquals(request, readRequest.payload());

        ConfigValueResultPayload answer = new ConfigValueResultPayload(4, "9", "12", "");
        ConfigValueResultMessage readAnswer = new ConfigValueResultMessage();
        readAnswer.read(new ByteBufferInputStream(written(new ConfigValueResultMessage(answer))));
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
