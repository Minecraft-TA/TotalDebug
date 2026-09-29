package com.github.minecraft_ta.totalDebugCompanion.pack;

import com.github.minecraft_ta.totalDebugCompanion.change.ChangePipeline;
import com.github.minecraft_ta.totalDebugCompanion.game.GameLocations;
import com.github.minecraft_ta.totalDebugCompanion.storage.ChangeRecord;
import com.github.minecraft_ta.totalDebugCompanion.storage.InstanceState;
import com.github.minecraft_ta.totalDebugCompanion.storage.ResourceOriginals;
import com.github.minecraft_ta.totaldebug.protocol.message.ClientPacksPayload;
import com.github.minecraft_ta.totaldebug.protocol.message.PackStackPayload;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExternalEditsTest {
    private static final String LANG = "assets/testmod/lang/en_us.json";
    private static final String TEXTURE = "assets/testmod/textures/item/gear.png";

    @TempDir Path directory;

    @Test
    void aProgramsSaveIsRecordedAndRevertsToWhatCompanionLeft() throws Exception {
        ChangeRecord record = ChangeRecord.inMemory();
        ResourceEdits edits = edits(record);
        Path pack = edits.save(LANG, bytes("{\"a\":\"companion\"}")).get(5, TimeUnit.SECONDS).pack();
        Path file = pack.resolve(LANG);

        assertNull(edits.adopt(LANG, pack, bytes("{\"a\":\"companion\"}"), Files.readAllBytes(file)).get(5, TimeUnit.SECONDS),
                "Companion's own save is nothing new");
        Files.writeString(file, "{\"a\":\"program\"}");
        assertNull(edits.adopt(LANG, pack, bytes("{\"a\":\"companion\"}"), bytes("{\"a\":\"half\"}")).get(5, TimeUnit.SECONDS),
                "a file that changed again since it was found whole waits for the next save");
        assertNotNull(edits.adopt(LANG, pack, bytes("{\"a\":\"companion\"}"), Files.readAllBytes(file)).get(5, TimeUnit.SECONDS));
        ChangeRecord.Change change = record.changes().getFirst();
        assertEquals("", change.original(), "the pack had no copy before Companion's save");
        assertEquals(ResourceOriginals.hash(bytes("{\"a\":\"program\"}")), change.current());

        edits.revert(change).get(5, TimeUnit.SECONDS);
        assertFalse(Files.exists(file));
        assertNull(edits.adopt(LANG, pack, bytes("{\"a\":\"companion\"}"), null).get(5, TimeUnit.SECONDS),
                "the revert is Companion's, not the program's");
    }

    @Test
    void aProgramsSaveToAFileCompanionNeverChangedKeepsTheFileBefore() throws Exception {
        ChangeRecord record = ChangeRecord.inMemory();
        ResourceEdits edits = edits(record);
        Path pack = Files.createDirectories(this.directory.resolve("resourcepacks/MyPack"));
        Files.writeString(pack.resolve("pack.mcmeta"), "{\"pack\":{\"pack_format\":34,\"description\":\"\"}}");
        Path file = pack.resolve(LANG);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{\"a\":\"mine\"}");

        Files.writeString(file, "{\"a\":\"program\"}");
        assertNotNull(edits.adopt(LANG, pack, bytes("{\"a\":\"mine\"}"), Files.readAllBytes(file)).get(5, TimeUnit.SECONDS));
        edits.revert(record.changes().getFirst()).get(5, TimeUnit.SECONDS);
        assertEquals("{\"a\":\"mine\"}", Files.readString(file), "Revert puts back what the file held before the program");
    }

    @Test
    void aProgramsSaveAfterTheFileWasPutBackOutsideCompanionStartsFromThatOriginal() throws Exception {
        ChangeRecord record = ChangeRecord.inMemory();
        ResourceEdits edits = edits(record);
        Path pack = edits.save(LANG, bytes("{\"a\":\"companion\"}")).get(5, TimeUnit.SECONDS).pack();
        Path file = pack.resolve(LANG);
        // Put back by hand: the pack had no copy before Companion's save.
        Files.delete(file);
        edits.holds(record.changes().getFirst());
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (record.size() > 0 && System.nanoTime() < deadline) Thread.sleep(10);
        assertEquals(0, record.size());

        Files.writeString(file, "{\"a\":\"program\"}");
        assertNotNull(edits.adopt(LANG, pack, null, Files.readAllBytes(file)).get(5, TimeUnit.SECONDS));
        assertEquals("", record.changes().getFirst().original(), "what the file held before the program: nothing");
    }

    @Test
    void aFollowedTextureIsTakenOnceItIsWhole() throws Exception {
        ChangeRecord record = ChangeRecord.inMemory();
        ResourceEdits edits = edits(record);
        byte[] first = png(0xFF112233);
        Path pack = edits.save(TEXTURE, first).get(5, TimeUnit.SECONDS).pack();
        BlockingQueue<Object> taken = new LinkedBlockingQueue<>();
        try {
            edits.external().follow(TEXTURE, pack);
            edits.external().addListener(TEXTURE, pack, (saved, failure) -> taken.add(failure != null ? failure : saved));

            // A program's half-written file is not taken.
            Files.write(pack.resolve(TEXTURE), new byte[]{(byte) 0x89, 'P', 'N', 'G'});
            assertNull(taken.poll(1500, TimeUnit.MILLISECONDS));
            byte[] drawn = png(0xFF445566);
            Files.write(pack.resolve(TEXTURE), drawn);
            Object result = taken.poll(10, TimeUnit.SECONDS);
            assertNotNull(result, "the program's save is taken");
            assertEquals(ResourceEdits.Saved.class, result.getClass(), String.valueOf(result));
            assertEquals(ResourceOriginals.hash(drawn), record.change(new ChangeRecord.Resource(TEXTURE, pack)).current());
            assertArrayEquals(drawn, Files.readAllBytes(pack.resolve(TEXTURE)));
        } finally {
            edits.close();
        }
    }

    @Test
    void aTextureIsWholeOnceItEndsWithItsClosingChunk() throws Exception {
        byte[] whole = png(0xFF112233);
        assertTrue(ExternalEdits.readable(TEXTURE, whole));
        assertFalse(ExternalEdits.readable(TEXTURE, Arrays.copyOf(whole, whole.length - 4)), "cut off before its end");
        assertTrue(ExternalEdits.readable(LANG, bytes("{")), "other files are taken as they are");
    }

    @Test
    void aProgramThatDoesNotStartLeavesTheFileUnfollowed() throws Exception {
        ResourceEdits edits = edits(ChangeRecord.inMemory());
        Path pack = edits.save(LANG, bytes("{}")).get(5, TimeUnit.SECONDS).pack();
        try {
            assertThrows(IOException.class, () -> edits.external().open(LANG, pack, this.directory.resolve("missing.exe").toString()));
            assertFalse(edits.external().follows(LANG, pack), "nothing opened it, so its saves are not the program's");
        } finally {
            edits.close();
        }
    }

    private ResourceEdits edits(ChangeRecord record) {
        ResourceEdits edits = ResourceEditsFixture.edits(GameLocations.of(this.directory, false), record,
                new ResourceOriginals(this.directory.resolve("total-debug/originals")), Runnable::run,
                InstanceState.inMemory());
        edits.packs().named(new ClientPacksPayload(new PackStackPayload(34, List.of(new PackStackPayload.Pack(ResourceEdits.PACK_ID, "TotalDebug", ""))), 48));
        return edits;
    }

    private static byte[] png(int argb) throws Exception {
        BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, argb);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
