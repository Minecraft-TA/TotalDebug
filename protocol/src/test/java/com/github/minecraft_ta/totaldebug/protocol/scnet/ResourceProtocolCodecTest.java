package com.github.minecraft_ta.totaldebug.protocol.scnet;

import com.github.minecraft_ta.totaldebug.protocol.message.PackStackPayload;
import com.github.minecraft_ta.totaldebug.protocol.message.ReloadPayload;
import com.github.minecraft_ta.totaldebug.protocol.message.ReloadResultPayload;
import com.github.minecraft_ta.totaldebug.protocol.message.SetPacksPayload;
import com.github.tth05.scnet.message.AbstractMessage;
import com.github.tth05.scnet.util.ByteBufferInputStream;
import com.github.tth05.scnet.util.ByteBufferOutputStream;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.EnumSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ResourceProtocolCodecTest {
    @Test
    void thePackStackSurvivesTheWire() {
        PackStackPayload stack = new PackStackPayload(34, 48,
                List.of(new PackStackPayload.Pack("vanilla", "Default", ""),
                        new PackStackPayload.Pack("file/TotalDebug", "TotalDebug", "C:/game/resourcepacks/TotalDebug")),
                List.of(new PackStackPayload.Pack("vanilla", "Default", "")),
                List.of(new PackStackPayload.Pack("programmer_art", "Programmer Art", "", PackStackPayload.INCOMPATIBLE)),
                List.of(new PackStackPayload.Pack("trade_rebalance", "Trade Rebalance", "", PackStackPayload.MISSING_FEATURES)));
        PackStackMessage read = new PackStackMessage();
        read.read(new ByteBufferInputStream(written(new PackStackMessage(stack))));
        assertEquals(stack, read.payload());
    }

    @Test
    void aReloadAndItsAnswerSurviveTheWire() {
        ReloadPayload request = new ReloadPayload(3, EnumSet.of(ReloadPayload.Kind.LANGUAGE, ReloadPayload.Kind.DATA, ReloadPayload.Kind.TEXTURES),
                "file/TotalDebug", "", List.of("assets/testmod/lang/en_us.json"));
        ReloadMessage readRequest = new ReloadMessage();
        readRequest.read(new ByteBufferInputStream(written(new ReloadMessage(request))));
        assertEquals(request, readRequest.payload());

        ReloadResultPayload answer = new ReloadResultPayload(3, 1_250, List.of(new ReloadResultPayload.Problem(
                "assets/testmod/models/block/slab.json", "Unable to parse testmod:block/slab")), "");
        ReloadResultMessage readAnswer = new ReloadResultMessage();
        readAnswer.read(new ByteBufferInputStream(written(new ReloadResultMessage(answer))));
        assertEquals(answer, readAnswer.payload());
    }

    @Test
    void aPackSelectionSurvivesTheWire() {
        SetPacksPayload request = new SetPacksPayload(4, SetPacksPayload.Side.DATA, "C:/game/saves/World", List.of("vanilla", "mod_data", "file/Tweaks"));
        SetPacksMessage read = new SetPacksMessage();
        read.read(new ByteBufferInputStream(written(new SetPacksMessage(request))));
        assertEquals(request, read.payload());
    }

    @Test
    void aDatapackSelectionNamesItsWorldAndAResourcePackSelectionNone() {
        assertThrows(IllegalArgumentException.class, () -> new SetPacksPayload(1, SetPacksPayload.Side.DATA, "", List.of()));
        assertThrows(IllegalArgumentException.class, () -> new SetPacksPayload(1, SetPacksPayload.Side.RESOURCES, "C:/game/saves/World", List.of()));
    }

    private static ByteBuffer written(AbstractMessage message) {
        ByteBufferOutputStream output = new ByteBufferOutputStream();
        message.write(output);
        ByteBuffer buffer = output.getBuffer().duplicate();
        buffer.flip();
        return buffer;
    }
}
