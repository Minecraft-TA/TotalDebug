package com.github.minecraft_ta.totalDebugCompanion.pack;

import com.github.minecraft_ta.totalDebugCompanion.change.ChangePipeline;
import com.github.minecraft_ta.totalDebugCompanion.change.ChangeCategory;
import com.github.minecraft_ta.totalDebugCompanion.game.Access;
import com.github.minecraft_ta.totalDebugCompanion.game.GameLocations;
import com.github.minecraft_ta.totalDebugCompanion.game.GameState;
import com.github.minecraft_ta.totalDebugCompanion.storage.ChangeRecord;
import com.github.minecraft_ta.totalDebugCompanion.storage.InstanceState;
import com.github.minecraft_ta.totalDebugCompanion.storage.ResourceOriginals;
import com.github.minecraft_ta.totaldebug.protocol.message.PackStackPayload;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** What Companion wrote and kept stays consistent with what it recorded, so an adopted save and a revert find their contents. */
class ResourceHistoryTest {
    private static final String LANG = "assets/testmod/lang/en_us.json";
    private static final byte[] ORIGINAL = "{\"value\":\"original\"}".getBytes(StandardCharsets.UTF_8);
    private static final byte[] EXTERNAL = "{\"value\":\"external\"}".getBytes(StandardCharsets.UTF_8);
    @TempDir Path directory;

    @Test
    void anIdenticalSaveStillAllowsTheNextExternalEditToBeAdoptedAndReverted() throws Exception {
        ChangeRecord record = ChangeRecord.inMemory();
        ResourceEdits edits = edits(record);
        Path pack = directory.resolve("resourcepacks/TotalDebug");
        Path file = pack.resolve(LANG);
        Files.createDirectories(file.getParent());
        Files.write(file, ORIGINAL);

        edits.save(LANG, ORIGINAL).get(5, TimeUnit.SECONDS);
        assertEquals(0, record.size(), "The identical save made no change");
        Files.write(file, EXTERNAL);

        assertNotNull(edits.adopt(LANG, pack, ORIGINAL, EXTERNAL).get(5, TimeUnit.SECONDS));
        assertEquals(ResourceOriginals.hash(ORIGINAL), record.changes().getFirst().original());
        edits.revert(record.changes().getFirst()).get(5, TimeUnit.SECONDS);
        assertEquals(new String(ORIGINAL, StandardCharsets.UTF_8), Files.readString(file));
    }

    @Test
    void anActualSaveStillAllowsTheNextExternalEditToBeAdoptedAndReverted() throws Exception {
        ChangeRecord record = ChangeRecord.inMemory();
        ResourceEdits edits = edits(record);
        Path pack = directory.resolve("resourcepacks/TotalDebug");
        Path file = pack.resolve(LANG);
        Files.createDirectories(file.getParent());
        Files.write(file, ORIGINAL);
        byte[] intermediate = "{}".getBytes(StandardCharsets.UTF_8);

        edits.save(LANG, intermediate).get(5, TimeUnit.SECONDS);
        Files.write(file, EXTERNAL);

        assertNotNull(edits.adopt(LANG, pack, intermediate, EXTERNAL).get(5, TimeUnit.SECONDS));
        edits.revert(record.changes().getFirst()).get(5, TimeUnit.SECONDS);
        assertEquals(new String(ORIGINAL, StandardCharsets.UTF_8), Files.readString(file));
    }

    @Test
    @SuppressWarnings("unchecked")
    void theRecordedOriginalMustMatchTheBytesKeptWhenAnExternalWriteInterleaves() throws Exception {
        ChangeRecord record = ChangeRecord.inMemory();
        ChangePipeline pipeline = new ChangePipeline(GameLocations.of(directory, false), record, Runnable::run);
        ResourceEdits edits = new ResourceEdits(pipeline,
                new ResourceOriginals(directory.resolve("total-debug/originals")), Runnable::run, InstanceState.inMemory());
        Path pack = directory.resolve("resourcepacks/TotalDebug");
        Path file = pack.resolve(LANG);
        Files.createDirectories(file.getParent());
        Files.write(file, ORIGINAL);
        ChangeRecord.Resource target = new ChangeRecord.Resource(LANG, pack);
        Field field = ResourceEdits.class.getDeclaredField("files");
        field.setAccessible(true);
        ChangeCategory<ChangeRecord.Resource, byte[]> actual =
                (ChangeCategory<ChangeRecord.Resource, byte[]>) field.get(edits);
        // Delegate to the real PackFiles implementation, inserting an external write after its checked read.
        ChangeCategory<ChangeRecord.Resource, byte[]> interleaved = new ChangeCategory<>() {
            @Override public String id() { return actual.id(); }
            @Override public String name(ChangeRecord.Resource resource) { return actual.name(resource); }
            @Override public Access access(GameState game, ChangeRecord.Resource resource) { return actual.access(game, resource); }
            @Override public String text(byte[] value) { return actual.text(value); }

            @Override
            public Map<ChangeRecord.Resource, String> readFile(Collection<ChangeRecord.Resource> targets) throws IOException {
                Map<ChangeRecord.Resource, String> checked = actual.readFile(targets);
                Files.write(file, EXTERNAL);
                return checked;
            }

            @Override
            public void writeFile(List<Write<ChangeRecord.Resource, byte[]>> writes, Consumer<ChangeRecord.Resource> landed)
                    throws IOException {
                actual.writeFile(writes, landed);
            }
        };

        pipeline.write(interleaved, List.of(new ChangePipeline.Edit<>(target, ResourceOriginals.hash(ORIGINAL),
                "{}".getBytes(StandardCharsets.UTF_8))));

        assertEquals(ResourceOriginals.hash(ORIGINAL), record.change(target).original());
        edits.revert(record.change(target)).get(5, TimeUnit.SECONDS);
        assertEquals(new String(ORIGINAL, StandardCharsets.UTF_8), Files.readString(file));
    }

    private ResourceEdits edits(ChangeRecord record) {
        ResourceEdits edits = new ResourceEdits(
                new ChangePipeline(GameLocations.of(directory, false), record, Runnable::run),
                new ResourceOriginals(directory.resolve("total-debug/originals")), Runnable::run, InstanceState.inMemory());
        edits.packStack(new PackStackPayload(34, 48,
                List.of(new PackStackPayload.Pack("vanilla", "Default", ""),
                        new PackStackPayload.Pack(ResourceEdits.PACK_ID, "TotalDebug", "")), List.of()));
        return edits;
    }
}
