package com.github.minecraft_ta.totalDebugCompanion.pack;

import com.github.minecraft_ta.totalDebugCompanion.catalog.ConfigChanges;
import com.github.minecraft_ta.totalDebugCompanion.storage.ChangeRecord;
import com.github.minecraft_ta.totalDebugCompanion.storage.ResourceOriginals;
import com.github.minecraft_ta.totaldebug.protocol.message.PackStackPayload;
import com.github.minecraft_ta.totaldebug.protocol.message.ReloadPayload;
import com.github.minecraft_ta.totaldebug.protocol.message.ReloadResultPayload;
import com.github.minecraft_ta.totaldebug.protocol.message.SetOverlayPayload;
import com.github.minecraft_ta.totaldebug.protocol.scnet.ReloadMessage;
import com.github.minecraft_ta.totaldebug.protocol.scnet.SetOverlayMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResourceEditsTest {
    private static final String LANG = "assets/testmod/lang/en_us.json";
    private static final PackStackPayload STACK = new PackStackPayload(34, 48,
            List.of(new PackStackPayload.Pack("vanilla", "Default", ""),
                    new PackStackPayload.Pack(ResourceEdits.PACK_ID, "TotalDebug", "")), List.of());

    @TempDir Path directory;

    @Test
    void writesIntoTheManagedPackAndRevertsToNothing() throws Exception {
        ChangeRecord record = ChangeRecord.inMemory();
        ResourceEdits edits = edits(record);
        edits.packStack(STACK);

        ResourceEdits.Saved saved = edits.save(LANG, bytes("{\"item.testmod.gear\":\"Cog\"}")).get(5, TimeUnit.SECONDS);

        Path pack = this.directory.resolve("resourcepacks/TotalDebug");
        assertEquals(pack, saved.pack());
        assertEquals(ConfigChanges.Effect.GAME_STARTS, saved.effect(), "no game runs");
        assertEquals("{\"item.testmod.gear\":\"Cog\"}", Files.readString(pack.resolve(LANG)));
        assertTrue(Files.readString(pack.resolve("pack.mcmeta")).contains("\"pack_format\":34"));
        ChangeRecord.Change change = record.changes().getFirst();
        assertEquals("", change.original(), "the pack had no copy before");

        edits.revert(change).get(5, TimeUnit.SECONDS);
        assertFalse(Files.exists(pack.resolve(LANG)));
        assertEquals(0, record.size());
    }

    @Test
    void aClosedGameFindsThePackEnabledAtTheTopWhenItStarts() throws Exception {
        Path options = this.directory.resolve("options.txt");
        Files.writeString(options, "version:3955\nresourcePacks:[\"vanilla\",\"file/glow\"]\nlang:en_us\n");
        ResourceEdits edits = edits(ChangeRecord.inMemory());
        edits.packStack(STACK);

        edits.save(LANG, bytes("{}")).get(5, TimeUnit.SECONDS);
        edits.save("assets/testmod/lang/de_de.json", bytes("{}")).get(5, TimeUnit.SECONDS);

        assertEquals("version:3955\nresourcePacks:[\"vanilla\",\"file/glow\",\"file/TotalDebug\"]\nlang:en_us\n",
                Files.readString(options));
    }

    @Test
    void revertingPutsBackWhatThePackHeldBefore() throws Exception {
        ChangeRecord record = ChangeRecord.inMemory();
        ResourceEdits edits = edits(record);
        edits.packStack(STACK);
        Path file = this.directory.resolve("resourcepacks/TotalDebug").resolve(LANG);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{\"a\":\"before\"}");

        edits.save(LANG, bytes("{\"a\":\"first\"}")).get(5, TimeUnit.SECONDS);
        edits.save(LANG, bytes("{\"a\":\"second\"}")).get(5, TimeUnit.SECONDS);
        assertEquals(1, record.size());

        edits.revert(record.changes().getFirst()).get(5, TimeUnit.SECONDS);
        assertEquals("{\"a\":\"before\"}", Files.readString(file));
        assertEquals(0, record.size());
    }

    @Test
    void theFirstWriteNeedsThePackFormatFromTheGame() {
        ResourceEdits edits = edits(ChangeRecord.inMemory());
        CompletableFuture<ResourceEdits.Saved> saved = edits.save(LANG, bytes("{}"));
        Throwable failure = saved.handle((ignored, thrown) -> thrown).join();
        assertTrue(failure.getCause() instanceof IOException, String.valueOf(failure));
        assertTrue(failure.getCause().getMessage().contains("pack format"), failure.getCause().getMessage());
    }

    @Test
    void dataGoesToTheWorldPlayedLastWhileNoneIsOpen() throws Exception {
        Path older = world("Older");
        Path newer = world("Newer");
        Files.setLastModifiedTime(older.resolve("level.dat"), FileTime.from(1_000, TimeUnit.SECONDS));
        ResourceEdits edits = edits(ChangeRecord.inMemory());
        edits.packStack(STACK);

        ResourceEdits.Saved saved = edits.save("data/testmod/recipe/gear.json", bytes("{}")).get(5, TimeUnit.SECONDS);

        assertEquals(newer.resolve("datapacks/TotalDebug"), saved.pack());
        assertEquals(ConfigChanges.Effect.WORLD_OPENS, saved.effect());
        assertTrue(Files.readString(saved.pack().resolve("pack.mcmeta")).contains("\"pack_format\":48"));
    }

    @Test
    void reloadsAskedForDuringAReloadRunTogetherAfterIt() throws Exception {
        ChangeRecord record = ChangeRecord.inMemory();
        ResourceEdits edits = edits(record);
        edits.packStack(STACK);
        List<ReloadPayload> sent = new CopyOnWriteArrayList<>();
        edits.gameConnected(message -> {
            if (message instanceof ReloadMessage reload) sent.add(reload.payload());
            return true;
        });

        CompletableFuture<ResourceEdits.Saved> first = edits.save(LANG, bytes("{}"));
        awaitSent(sent, 1);
        CompletableFuture<ResourceEdits.Saved> second = edits.save("assets/testmod/models/block/gear.json", bytes("{}"));
        CompletableFuture<ResourceEdits.Saved> third = edits.save("assets/testmod/lang/de_de.json", bytes("{}"));
        Thread.sleep(200);
        assertEquals(1, sent.size(), "the second and third wait for the first reload");
        assertEquals(Set.of(ReloadPayload.Kind.LANGUAGE), sent.getFirst().kinds());
        assertEquals(ResourceEdits.PACK_ID, sent.getFirst().managedPack());

        edits.answered(new ReloadResultPayload(sent.getFirst().requestId(), 10, List.of(), ""));
        assertEquals(ConfigChanges.Effect.NOW, first.get(5, TimeUnit.SECONDS).effect());
        awaitSent(sent, 2);
        ReloadPayload merged = sent.get(1);
        assertEquals(Set.of(ReloadPayload.Kind.RESOURCES), merged.kinds(), "a full reload covers the language");
        assertEquals(2, merged.watched().size());

        edits.answered(new ReloadResultPayload(merged.requestId(), 900, List.of("Unable to load model testmod:block/gear"), ""));
        assertEquals(List.of("Unable to load model testmod:block/gear"), second.get(5, TimeUnit.SECONDS).problems());
        assertEquals(ConfigChanges.Effect.NOW, third.get(5, TimeUnit.SECONDS).effect());
    }

    @Test
    void aTryInTheGameIsSentBeforeItsReloadAndEndsWhenTheFileIsSaved() throws Exception {
        ChangeRecord record = ChangeRecord.inMemory();
        ResourceEdits edits = edits(record);
        edits.packStack(STACK);
        List<Object> sent = new CopyOnWriteArrayList<>();
        edits.gameConnected(message -> {
            if (message instanceof ReloadMessage reload) {
                sent.add(reload.payload());
                edits.answered(new ReloadResultPayload(reload.payload().requestId(), 5, List.of(), ""));
            } else if (message instanceof SetOverlayMessage overlay) {
                sent.add(overlay.payload());
            }
            return true;
        });

        ResourceEdits.Saved tried = edits.tryInGame(LANG, bytes("{\"a\":\"tried\"}")).get(5, TimeUnit.SECONDS);

        assertEquals(ConfigChanges.Effect.NOW, tried.effect());
        assertEquals(new SetOverlayPayload(LANG, bytes("{\"a\":\"tried\"}")), sent.get(0));
        assertTrue(sent.get(1) instanceof ReloadPayload);
        assertEquals("{\"a\":\"tried\"}", new String(edits.tried(LANG).orElseThrow(), StandardCharsets.UTF_8));
        assertEquals(ChangeRecord.Level.GAME, record.changes().getFirst().level());
        assertFalse(Files.exists(this.directory.resolve("resourcepacks/TotalDebug").resolve(LANG)), "a try writes no file");

        edits.save(LANG, bytes("{\"a\":\"saved\"}")).get(5, TimeUnit.SECONDS);
        assertTrue(sent.contains(new SetOverlayPayload(LANG, null)), "saving removes the try from the game");
        assertTrue(edits.tried(LANG).isEmpty());
        assertEquals(1, record.size());
        assertEquals(ChangeRecord.Level.PACK, record.changes().getFirst().level());
    }

    @Test
    void aTryNeedsTheGameAndEndsWithIt() throws Exception {
        ChangeRecord record = ChangeRecord.inMemory();
        ResourceEdits edits = edits(record);
        Throwable failure = edits.tryInGame(LANG, bytes("{}")).handle((ignored, thrown) -> thrown).join();
        assertTrue(failure.getMessage().contains("needs the game running"), failure.getMessage());

        edits.gameConnected(message -> {
            if (message instanceof ReloadMessage reload) {
                edits.answered(new ReloadResultPayload(reload.payload().requestId(), 5, List.of(), ""));
            }
            return true;
        });
        edits.tryInGame(LANG, bytes("{}")).get(5, TimeUnit.SECONDS);
        assertEquals(1, record.size());
        edits.gameDisconnected();
        assertEquals(0, record.size());
        assertTrue(edits.tried(LANG).isEmpty());
    }

    @Test
    void anOverridingPackAboveTheManagedOneIsNamed() throws Exception {
        Path above = this.directory.resolve("resourcepacks/Faithful");
        Files.createDirectories(above.resolve("assets/testmod/lang"));
        Files.writeString(above.resolve(LANG), "{}");
        ResourceEdits edits = edits(ChangeRecord.inMemory());
        edits.packStack(new PackStackPayload(34, 48, List.of(
                new PackStackPayload.Pack(ResourceEdits.PACK_ID, "TotalDebug", ""),
                new PackStackPayload.Pack("file/Faithful", "Faithful", above.toString())), List.of()));

        assertEquals("Faithful", edits.overriddenBy(LANG).orElseThrow());
        assertTrue(edits.overriddenBy("assets/testmod/lang/de_de.json").isEmpty());
    }

    private ResourceEdits edits(ChangeRecord record) {
        return new ResourceEdits(this.directory, record, new ResourceOriginals(this.directory.resolve("total-debug/originals")));
    }

    private Path world(String name) throws IOException {
        Path world = this.directory.resolve("saves").resolve(name);
        Files.createDirectories(world);
        Files.writeString(world.resolve("level.dat"), name);
        return world;
    }

    private static void awaitSent(List<ReloadPayload> sent, int count) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (sent.size() < count && System.nanoTime() < deadline) Thread.sleep(10);
        assertEquals(count, sent.size());
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
