package com.github.minecraft_ta.totaldebug.protocol.scnet;

import com.github.minecraft_ta.totaldebug.protocol.message.ClientPacksPayload;
import com.github.minecraft_ta.totaldebug.protocol.message.PackStackPayload;
import com.github.minecraft_ta.totaldebug.protocol.message.ReloadPayload;
import com.github.minecraft_ta.totaldebug.protocol.message.ReloadResultPayload;
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
        PackStackPayload resources = new PackStackPayload(34,
                List.of(new PackStackPayload.Pack("vanilla", "Default", ""),
                        new PackStackPayload.Pack("file/TotalDebug", "TotalDebug", "C:/game/resourcepacks/TotalDebug")),
                List.of(new PackStackPayload.Pack("programmer_art", "Programmer Art", "", PackStackPayload.INCOMPATIBLE)));
        ClientPacksPayload client = new ClientPacksPayload(resources, 48);
        PackStackMessage read = new PackStackMessage();
        read.read(new ByteBufferInputStream(written(new PackStackMessage(client))));
        assertEquals(client, read.payload());

        PackStackPayload datapacks = new PackStackPayload(48, List.of(new PackStackPayload.Pack("vanilla", "Default", "")),
                List.of(new PackStackPayload.Pack("trade_rebalance", "Trade Rebalance", "", PackStackPayload.MISSING_FEATURES)));
        DatapacksMessage readDatapacks = new DatapacksMessage();
        readDatapacks.read(new ByteBufferInputStream(written(new DatapacksMessage("world C:/saves/World", datapacks))));
        assertEquals("world C:/saves/World", readDatapacks.world());
        assertEquals(datapacks, readDatapacks.payload());
    }

    @Test
    void aReloadAndItsAnswerSurviveTheWire() {
        ReloadPayload request = new ReloadPayload(3, EnumSet.of(ReloadPayload.Kind.LANGUAGE, ReloadPayload.Kind.TEXTURES),
                "file/TotalDebug", "", List.of("assets/testmod/lang/en_us.json"));
        ReloadMessage readRequest = new ReloadMessage();
        readRequest.read(new ByteBufferInputStream(written(new ReloadMessage(request))));
        assertEquals(request, readRequest.payload());
        ReloadPayload data = new ReloadPayload(4, EnumSet.of(ReloadPayload.Kind.DATA), "", "file/TotalDebug",
                List.of("data/testmod/recipe/gear.json"));
        readRequest.read(new ByteBufferInputStream(written(new ReloadMessage(data))));
        assertEquals(data, readRequest.payload());

        ReloadResultPayload answer = new ReloadResultPayload(3, 1_250, List.of(new ReloadResultPayload.Problem(
                "assets/testmod/models/block/slab.json", "Unable to parse testmod:block/slab")), "");
        ReloadResultMessage readAnswer = new ReloadResultMessage();
        readAnswer.read(new ByteBufferInputStream(written(new ReloadResultMessage(answer))));
        assertEquals(answer, readAnswer.payload());
    }

    @Test
    void theWorldsDataReloadsApartFromTheClientsResources() {
        assertThrows(IllegalArgumentException.class, () -> new ReloadPayload(1,
                EnumSet.of(ReloadPayload.Kind.DATA, ReloadPayload.Kind.LANGUAGE), "", "", List.of()));
        assertThrows(IllegalArgumentException.class, () -> new ReloadPayload(1, EnumSet.of(ReloadPayload.Kind.DATA),
                "file/TotalDebug", "", List.of()));
        assertThrows(IllegalArgumentException.class, () -> new ReloadPayload(1, EnumSet.of(ReloadPayload.Kind.LANGUAGE),
                "", "file/TotalDebug", List.of()));
    }

    private static ByteBuffer written(AbstractMessage message) {
        ByteBufferOutputStream output = new ByteBufferOutputStream();
        message.write(output);
        ByteBuffer buffer = output.getBuffer().duplicate();
        buffer.flip();
        return buffer;
    }
}
