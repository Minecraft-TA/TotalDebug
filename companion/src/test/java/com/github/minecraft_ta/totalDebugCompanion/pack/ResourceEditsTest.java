package com.github.minecraft_ta.totalDebugCompanion.pack;

import com.github.minecraft_ta.totalDebugCompanion.change.ChangePipeline;
import com.github.minecraft_ta.totalDebugCompanion.catalog.ConfigChanges;
import com.github.minecraft_ta.totalDebugCompanion.catalog.LevelDatFixture;
import com.github.minecraft_ta.totalDebugCompanion.game.GameLocations;
import com.github.minecraft_ta.totalDebugCompanion.storage.ChangeRecord;
import com.github.minecraft_ta.totalDebugCompanion.storage.ResourceOriginals;
import com.github.minecraft_ta.totaldebug.protocol.message.PackStackPayload;
import com.github.minecraft_ta.totaldebug.protocol.message.PlayingPayload;
import com.github.minecraft_ta.totaldebug.protocol.message.ReloadPayload;
import com.github.minecraft_ta.totaldebug.protocol.message.ReloadResultPayload;
import com.github.minecraft_ta.totaldebug.protocol.scnet.ReloadMessage;
import com.github.minecraft_ta.totalDebugCompanion.storage.InstanceState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.nio.channels.FileLock;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

        // A player who moved it down finds it on top again, as a connected game puts it.
        Files.writeString(options, "resourcePacks:[\"vanilla\",\"file/TotalDebug\",\"file/glow\"]\n");
        edits.save(LANG, bytes("{\"a\":\"b\"}")).get(5, TimeUnit.SECONDS);
        assertEquals("resourcePacks:[\"vanilla\",\"file/glow\",\"file/TotalDebug\"]\n", Files.readString(options));
    }

    @Test
    void aRevertLeavesAFileChangedOutsideCompanionAlone() throws Exception {
        ChangeRecord record = ChangeRecord.inMemory();
        ResourceEdits edits = edits(record);
        edits.packStack(STACK);
        Path file = this.directory.resolve("resourcepacks/TotalDebug").resolve(LANG);
        edits.save(LANG, bytes("{\"a\":\"saved\"}")).get(5, TimeUnit.SECONDS);
        Files.writeString(file, "{\"a\":\"by hand\"}");

        Throwable failure = edits.revert(record.changes().getFirst()).handle((ignored, thrown) -> thrown).join();
        assertTrue(failure.getMessage().contains("en_us.json changed in the TotalDebug resource pack since Companion read it"), failure.getMessage());
        assertEquals("{\"a\":\"by hand\"}", Files.readString(file));
        assertEquals(1, record.size());

        // Once the file holds its original again, here none, the revert only ends the entry.
        Files.delete(file);
        edits.revert(record.changes().getFirst()).get(5, TimeUnit.SECONDS);
        assertEquals(0, record.size());
    }

    @Test
    void editListenersHearOfASaveOnceTheOptionsEnableThePack() throws Exception {
        Path options = Files.writeString(this.directory.resolve("options.txt"), "resourcePacks:[\"vanilla\"]\n");
        ResourceEdits edits = edits(ChangeRecord.inMemory());
        edits.packStack(STACK);
        List<String> seen = new CopyOnWriteArrayList<>();
        edits.addEditListener(() -> {
            try {
                seen.add(Files.readString(options));
            } catch (IOException unreadable) {
                seen.add(unreadable.toString());
            }
        });

        edits.save(LANG, bytes("{}")).get(5, TimeUnit.SECONDS);
        assertEquals(List.of("resourcePacks:[\"vanilla\",\"file/TotalDebug\"]\n"), seen,
                "a listener reading the pack stack then finds the pack enabled");
    }

    @Test
    void revertingTwiceRevertsOnce() throws Exception {
        ChangeRecord record = ChangeRecord.inMemory();
        ResourceEdits edits = edits(record);
        edits.packStack(STACK);
        edits.save(LANG, bytes("{\"a\":\"saved\"}")).get(5, TimeUnit.SECONDS);
        ChangeRecord.Change change = record.changes().getFirst();

        edits.revert(change).get(5, TimeUnit.SECONDS);
        edits.revert(change).get(5, TimeUnit.SECONDS);
        assertEquals(0, record.size(), "the second revert finds nothing left to put back");
    }

    @Test
    void aFileRestoredOutsideCompanionEndsItsChange() throws Exception {
        ChangeRecord record = ChangeRecord.inMemory();
        ResourceEdits edits = edits(record);
        edits.packStack(STACK);
        edits.save(LANG, bytes("{\"a\":\"saved\"}")).get(5, TimeUnit.SECONDS);
        ChangeRecord.Change change = record.changes().getFirst();

        Files.delete(this.directory.resolve("resourcepacks/TotalDebug").resolve(LANG));
        assertFalse(edits.holds(change));
        assertEquals(0, record.size(), "the pack holds its original again, none");
    }

    @Test
    void aRunningGameNotConnectedKeepsItsOptionsAndIsAskedToConnect() throws Exception {
        Path options = this.directory.resolve("options.txt");
        Files.writeString(options, "resourcePacks:[\"vanilla\"]\n");
        ResourceEdits edits = new ResourceEdits(new ChangePipeline(GameLocations.of(this.directory, true), ChangeRecord.inMemory(), Runnable::run),
                new ResourceOriginals(this.directory.resolve("total-debug/originals")), Runnable::run,
                InstanceState.inMemory());
        edits.packStack(STACK);

        ResourceEdits.Saved saved = edits.save(LANG, bytes("{}")).get(5, TimeUnit.SECONDS);
        assertTrue(Files.isRegularFile(saved.pack().resolve(LANG)), "the file is saved");
        assertEquals("resourcePacks:[\"vanilla\"]\n", Files.readString(options), "the running game writes options.txt itself");
        assertTrue(saved.reloadFailure().contains("not connected"), saved.reloadFailure());
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
    void aDataReloadNamesTheWorldItsDataWasWrittenFor() throws Exception {
        Path world = this.directory.resolve("saves/World");
        LevelDatFixture.write(world, LevelDatFixture.world("World"));
        ResourceEdits edits = edits(ChangeRecord.inMemory());
        List<ReloadPayload> sent = new CopyOnWriteArrayList<>();
        edits.location().connected(message -> {
            if (message instanceof ReloadMessage reload) sent.add(reload.payload());
            return true;
        });
        edits.location().playing(new PlayingPayload.Singleplayer(world.toString()));
        edits.packStack(STACK);

        edits.save("data/testmod/recipe/gear.json", bytes("{}"));
        awaitSent(sent, 1);

        assertEquals(Set.of(ReloadPayload.Kind.DATA), sent.getFirst().kinds());
        assertEquals(world.toAbsolutePath().normalize().toString(), sent.getFirst().dataWorld(),
                "the game refuses to reload it once it plays another world");
    }

    @Test
    void aDatapackOfAWorldTheUnconnectedGameHasOpenIsWrittenForItsNextLoad() throws Exception {
        Path world = this.directory.resolve("saves/World");
        LevelDatFixture.write(world, LevelDatFixture.world("World"));
        Path pack = Files.createDirectories(world.resolve("datapacks/TotalDebug"));
        Files.writeString(pack.resolve("pack.mcmeta"), "{\"pack\":{\"pack_format\":48,\"description\":\"\"}}");
        ResourceEdits edits = new ResourceEdits(new ChangePipeline(GameLocations.of(this.directory, true), ChangeRecord.inMemory(), Runnable::run),
                new ResourceOriginals(this.directory.resolve("total-debug/originals")), Runnable::run, InstanceState.inMemory());

        try (FileChannel channel = FileChannel.open(world.resolve("session.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock ignored = channel.lock()) {
            ResourceEdits.Saved saved = edits.save("data/testmod/recipe/gear.json", bytes("{}")).get(5, TimeUnit.SECONDS);

            assertEquals("{}", Files.readString(pack.resolve("data/testmod/recipe/gear.json")), "the game only reads a datapack");
            assertEquals(ConfigChanges.Effect.WORLD_OPENS, saved.effect());
            assertEquals("The world World is open in a game that is not connected to Companion; it uses the change when the world is loaded again",
                    saved.reloadFailure());
        }
    }

    @Test
    void aWorldsDataReloadsInItsOwnQueueWhichLeavingTheWorldDrops() throws Exception {
        Path first = this.directory.resolve("saves/First");
        Path second = this.directory.resolve("saves/Second");
        LevelDatFixture.write(first, LevelDatFixture.world("First"));
        LevelDatFixture.write(second, LevelDatFixture.world("Second"));
        ResourceEdits edits = edits(ChangeRecord.inMemory());
        List<ReloadPayload> sent = new CopyOnWriteArrayList<>();
        edits.location().connected(message -> {
            if (message instanceof ReloadMessage reload) sent.add(reload.payload());
            return true;
        });
        edits.location().playing(new PlayingPayload.Singleplayer(first.toString()));
        edits.packStack(STACK);

        CompletableFuture<ResourceEdits.Saved> running = edits.save("data/testmod/recipe/gear.json", bytes("{}"));
        awaitSent(sent, 1);
        CompletableFuture<ResourceEdits.Saved> waiting = edits.save("data/testmod/recipe/wheel.json", bytes("{}"));
        CompletableFuture<ResourceEdits.Saved> language = edits.save(LANG, bytes("{}"));
        awaitSent(sent, 2);
        assertEquals(Set.of(ReloadPayload.Kind.LANGUAGE), sent.get(1).kinds(), "the client's resources do not wait for the data");
        assertEquals("", sent.get(1).dataWorld());

        edits.location().playing(new PlayingPayload.Singleplayer(second.toString()));
        edits.packStack(STACK);
        assertTrue(waiting.get(5, TimeUnit.SECONDS).reloadFailure().contains("another world"),
                "the first world's waiting data is not reloaded in the second");
        edits.pipeline().reloads().answered(new ReloadResultPayload(sent.getFirst().requestId(), 10, List.of(), ""));
        running.get(5, TimeUnit.SECONDS);
        Thread.sleep(100);
        assertEquals(2, sent.size(), "nothing is left to ask for the first world");
        edits.pipeline().reloads().answered(new ReloadResultPayload(sent.get(1).requestId(), 10, List.of(), ""));
        assertEquals(ConfigChanges.Effect.NOW, language.get(5, TimeUnit.SECONDS).effect());

        CompletableFuture<ResourceEdits.Saved> later = edits.save("data/testmod/recipe/axle.json", bytes("{}"));
        awaitSent(sent, 3);
        assertEquals(second.toAbsolutePath().normalize().toString(), sent.get(2).dataWorld());
        edits.pipeline().reloads().answered(new ReloadResultPayload(sent.get(2).requestId(), 10, List.of(), ""));
        assertEquals(ConfigChanges.Effect.NOW, later.get(5, TimeUnit.SECONDS).effect(), "later reloads are not held up");
    }

    @Test
    void packsNamedBeforeTheConnectionWasEstablishedStayForTheWorldTheyBelongTo() {
        Path world = this.directory.resolve("saves/World");
        ResourceEdits edits = edits(ChangeRecord.inMemory());
        edits.location().playing(new PlayingPayload.Singleplayer(world.toString()));
        edits.packStack(STACK);

        edits.location().connected(message -> true);

        assertEquals(STACK, edits.packStack(), "they were named for the world the connection now plays");
    }

    @Test
    void reloadsAskedForDuringAReloadRunTogetherAfterIt() throws Exception {
        ChangeRecord record = ChangeRecord.inMemory();
        ResourceEdits edits = edits(record);
        edits.packStack(STACK);
        List<ReloadPayload> sent = new CopyOnWriteArrayList<>();
        edits.location().connected(message -> {
            if (message instanceof ReloadMessage reload) sent.add(reload.payload());
            return true;
        });

        CompletableFuture<ResourceEdits.Saved> first = edits.save(LANG, bytes("{}"));
        awaitSent(sent, 1);
        CompletableFuture<ResourceEdits.Saved> second = edits.save("assets/testmod/models/block/gear.json", bytes("{}"));
        CompletableFuture<ResourceEdits.Saved> third = edits.save("assets/testmod/lang/de_de.json", bytes("{}"));
        CompletableFuture<ResourceEdits.Saved> fourth = edits.save("assets/testmod/textures/block/gear.png", bytes("png"));
        Thread.sleep(200);
        assertEquals(1, sent.size(), "the second and third wait for the first reload");
        assertEquals(Set.of(ReloadPayload.Kind.LANGUAGE), sent.getFirst().kinds());
        assertEquals(ResourceEdits.PACK_ID, sent.getFirst().managedResourcePack());
        assertEquals("", sent.getFirst().managedDataPack(), "no data edit asks for the managed datapack");

        edits.pipeline().reloads().answered(new ReloadResultPayload(sent.getFirst().requestId(), 10, List.of(), ""));
        assertEquals(ConfigChanges.Effect.NOW, first.get(5, TimeUnit.SECONDS).effect());
        awaitSent(sent, 2);
        ReloadPayload merged = sent.get(1);
        assertEquals(Set.of(ReloadPayload.Kind.RESOURCES), merged.kinds(), "a full reload covers the language and textures");
        assertEquals(3, merged.watched().size());

        edits.pipeline().reloads().answered(new ReloadResultPayload(merged.requestId(), 900, List.of(new ReloadResultPayload.Problem(
                "assets/testmod/models/block/gear.json", "Unable to load model testmod:block/gear")), ""));
        assertEquals(List.of("Unable to load model testmod:block/gear"), second.get(5, TimeUnit.SECONDS).problems());
        assertEquals(ConfigChanges.Effect.NOW, third.get(5, TimeUnit.SECONDS).effect());
        assertEquals(List.of(), third.get(5, TimeUnit.SECONDS).problems(), "the model's problem is not the language file's");
    }

    @Test
    void revertingSeveralChangesTakesOneReload() throws Exception {
        ChangeRecord record = ChangeRecord.inMemory();
        ExecutorService queue = Executors.newSingleThreadExecutor();
        try {
            ResourceEdits edits = new ResourceEdits(new ChangePipeline(GameLocations.of(this.directory, false), record, queue),
                    new ResourceOriginals(this.directory.resolve("total-debug/originals")), queue, InstanceState.inMemory());
            edits.packStack(STACK);
            List<ReloadPayload> sent = new CopyOnWriteArrayList<>();
            edits.location().connected(message -> {
                if (message instanceof ReloadMessage reload) {
                    sent.add(reload.payload());
                    edits.pipeline().reloads().answered(new ReloadResultPayload(reload.payload().requestId(), 10, List.of(), ""));
                }
                return true;
            });
            edits.save(LANG, bytes("{}")).get(5, TimeUnit.SECONDS);
            edits.save("assets/testmod/textures/item/gear.png", bytes("png")).get(5, TimeUnit.SECONDS);
            sent.clear();

            // As Revert All does, however soon the first revert is written.
            for (CompletableFuture<ResourceEdits.Saved> revert : edits.revert(record.changes())) revert.get(5, TimeUnit.SECONDS);

            assertEquals(1, sent.size(), "one reload for both files");
            assertEquals(2, sent.getFirst().watched().size());
            assertEquals(Set.of(ReloadPayload.Kind.LANGUAGE, ReloadPayload.Kind.TEXTURES), sent.getFirst().kinds(),
                    "the language and the texture's pixels are shown the quick way");
        } finally {
            queue.shutdownNow();
        }
    }

    @Test
    void aCopyTooLargeToOpenIsRefusedBeforeItIsRead() throws Exception {
        ResourceEdits edits = edits(ChangeRecord.inMemory());
        Path pack = edits.pack(LANG);
        Path file = Files.createDirectories(pack.resolve(LANG).getParent()).resolve("en_us.json");
        try (var output = Files.newOutputStream(file)) {
            output.write(new byte[16 * 1024 * 1024 + 1]);
        }
        IOException refused = assertThrows(IOException.class, () -> edits.managed(pack, LANG));
        assertTrue(refused.getMessage().contains("more than the 16 MiB Companion opens"), refused.getMessage());
    }

    @Test
    void aSaveOverACopyWrittenSinceIsRefusedAndWritesNothing() throws Exception {
        ChangeRecord record = ChangeRecord.inMemory();
        ResourceEdits edits = edits(record);
        edits.packStack(STACK);
        String texture = "assets/testmod/textures/block/gear.png";
        Path pack = edits.save(texture, null, bytes("first")).get(5, TimeUnit.SECONDS).pack();
        String seen = ResourceOriginals.hash(bytes("first"));
        // Another tab saves in between.
        edits.save(texture, null, bytes("other tab")).get(5, TimeUnit.SECONDS);

        ExecutionException refused = assertThrows(ExecutionException.class, () -> edits.save(texture, null, bytes("this tab"),
                Map.of(texture + ".mcmeta", bytes("{}")), seen).get(5, TimeUnit.SECONDS));
        assertTrue(refused.getCause() instanceof ChangePipeline.Stale, refused.getCause().toString());
        assertEquals("other tab", Files.readString(pack.resolve(texture)));
        assertFalse(Files.exists(pack.resolve(texture + ".mcmeta")), "nothing beside it is written either");

        edits.save(texture, null, bytes("this tab"), Map.of(), ResourceOriginals.hash(bytes("other tab"))).get(5, TimeUnit.SECONDS);
        assertEquals("this tab", Files.readString(pack.resolve(texture)), "a save over the copy it read goes through");
    }

    @Test
    void aTexturesAnimationComesFromTheHighestPackThatSuppliesIt() throws Exception {
        ResourceEdits edits = edits(ChangeRecord.inMemory());
        String texture = "assets/testmod/textures/block/gear.png";
        Path managed = edits.pack(texture);
        Files.createDirectories(managed.resolve(texture).getParent());
        Files.writeString(managed.resolve(texture + ".mcmeta"), "{\"animation\":{\"frametime\":1}}");
        Path top = Files.createDirectories(this.directory.resolve("resourcepacks/Top"));
        Files.createDirectories(top.resolve(texture).getParent());
        Files.writeString(top.resolve(texture + ".mcmeta"), "{\"animation\":{\"frametime\":9}}");

        assertEquals("{\"animation\":{\"frametime\":1}}", new String(edits.metadata(texture, managed, 1024).orElseThrow(),
                StandardCharsets.UTF_8), "without the game's stack, the pack's own");
        edits.packStack(new PackStackPayload(34, 48, List.of(new PackStackPayload.Pack(ResourceEdits.PACK_ID, "TotalDebug", ""),
                new PackStackPayload.Pack("file/Top", "Top", top.toString())), List.of()));
        assertEquals("{\"animation\":{\"frametime\":9}}", new String(edits.metadata(texture, managed, 1024).orElseThrow(),
                StandardCharsets.UTF_8), "a pack above that supplies it wins, as in the game");
        assertThrows(IOException.class, () -> edits.metadata(texture, managed, 8), "larger than an animation needs");
    }

    @Test
    void aRevertIntoATotalDebugPackWithoutMetadataSaysSo() throws Exception {
        ChangeRecord record = ChangeRecord.inMemory();
        ResourceEdits edits = edits(record);
        edits.packStack(STACK);
        Path pack = edits.save(LANG, bytes("{}")).get(5, TimeUnit.SECONDS).pack();
        Files.delete(pack.resolve("pack.mcmeta"));

        ResourceEdits.Saved reverted = edits.revert(record.changes().getFirst()).get(5, TimeUnit.SECONDS);
        assertTrue(reverted.unused().startsWith("The TotalDebug resource pack is gone or has no readable pack.mcmeta"), reverted.unused());
    }

    @Test
    void aRevertIntoAPackTheGameSkipsSaysSo() throws Exception {
        ChangeRecord record = ChangeRecord.inMemory();
        ResourceEdits edits = edits(record);
        Path mine = Files.createDirectories(this.directory.resolve("resourcepacks/MyPack"));
        Files.writeString(mine.resolve("pack.mcmeta"), "{\"pack\":{\"pack_format\":34,\"description\":\"\"}}");
        edits.save(LANG, mine, bytes("{}")).get(5, TimeUnit.SECONDS);
        Files.delete(mine.resolve("pack.mcmeta"));

        ResourceEdits.Saved reverted = edits.revert(record.changes().getFirst()).get(5, TimeUnit.SECONDS);
        assertFalse(Files.exists(mine.resolve(LANG)), "the file is put back all the same, so the change can end");
        assertEquals("The MyPack resource pack is gone or has no readable pack.mcmeta, so the game does not load it", reverted.unused());
    }

    @Test
    void anAnimationWrittenBesideATextureThatAPackAboveSuppliesIsNamed() throws Exception {
        ResourceEdits edits = edits(ChangeRecord.inMemory());
        String texture = "assets/testmod/textures/block/gear.png";
        Path top = Files.createDirectories(this.directory.resolve("resourcepacks/Top"));
        Files.createDirectories(top.resolve(texture).getParent());
        Files.writeString(top.resolve(texture + ".mcmeta"), "{\"animation\":{}}");
        edits.packStack(new PackStackPayload(34, 48, List.of(new PackStackPayload.Pack("vanilla", "Default", ""),
                new PackStackPayload.Pack(ResourceEdits.PACK_ID, "TotalDebug", ""),
                new PackStackPayload.Pack("file/Top", "Top", top.toString())), List.of()));

        ResourceEdits.Saved saved = edits.save(texture, null, bytes("png"), Map.of(texture + ".mcmeta", bytes("{}")))
                .get(5, TimeUnit.SECONDS);
        assertEquals("Top is above the TotalDebug resource pack and supplies gear.png.mcmeta too, so the game uses its copy",
                saved.unused(), "the game reads the animation from the highest pack at or above the texture's");
    }

    @Test
    void aTexturesAnimationIsWrittenBesideItWhereThePackHasNone() throws Exception {
        ChangeRecord record = ChangeRecord.inMemory();
        ResourceEdits edits = edits(record);
        edits.packStack(STACK);
        String texture = "assets/testmod/textures/block/gear.png";
        byte[] animation = bytes("{\"animation\":{\"frametime\":2}}");

        Path pack = edits.save(texture, null, bytes("png"), Map.of(texture + ".mcmeta", animation)).get(5, TimeUnit.SECONDS).pack();
        assertEquals("{\"animation\":{\"frametime\":2}}", Files.readString(pack.resolve(texture + ".mcmeta")),
                "the game reads a texture's animation only from its own pack or one above it");
        assertEquals(2, record.changes().size(), "the animation is a change of its own, reverted by deleting it");

        Files.writeString(pack.resolve(texture + ".mcmeta"), "{\"animation\":{\"frametime\":5}}");
        edits.save(texture, null, bytes("png2"), Map.of(texture + ".mcmeta", animation)).get(5, TimeUnit.SECONDS);
        assertEquals("{\"animation\":{\"frametime\":5}}", Files.readString(pack.resolve(texture + ".mcmeta")),
                "the pack's own animation stays");

        Files.delete(pack.resolve(texture + ".mcmeta"));
        edits.save(texture, null, bytes("png3"), Map.of(texture + ".mcmeta", animation)).get(5, TimeUnit.SECONDS);
        assertFalse(Files.exists(pack.resolve(texture + ".mcmeta")),
                "a copy the pack had already, static here, is not given the opened file's animation");
    }

    @Test
    void anAnimationWrittenBesideATextureIsReloadedWithIt() throws Exception {
        ResourceEdits edits = edits(ChangeRecord.inMemory());
        edits.packStack(STACK);
        List<ReloadPayload> sent = new CopyOnWriteArrayList<>();
        edits.location().connected(message -> {
            if (message instanceof ReloadMessage reload) {
                sent.add(reload.payload());
                edits.pipeline().reloads().answered(new ReloadResultPayload(reload.payload().requestId(), 10, List.of(new ReloadResultPayload.Problem(
                        "assets/testmod/textures/block/gear.png.mcmeta", "Bad section")), ""));
            }
            return true;
        });
        String texture = "assets/testmod/textures/block/gear.png";

        ResourceEdits.Saved saved = edits.save(texture, null, bytes("png"), Map.of(texture + ".mcmeta", bytes("{}")))
                .get(5, TimeUnit.SECONDS);
        assertEquals(1, sent.size());
        assertEquals(Set.of(texture, texture + ".mcmeta"), Set.copyOf(sent.getFirst().watched()),
                "the game takes the new animation into account, not only the pixels");
        assertTrue(saved.problems().contains("Bad section"), "a problem with the animation is the save's own");
    }

    @Test
    void aFileOfAnotherWorldsPackIsSavedIntoThatPack() throws Exception {
        Path older = world("Older");
        world("Newer");
        Files.setLastModifiedTime(older.resolve("level.dat"), FileTime.fromMillis(1_000));
        ResourceEdits edits = edits(ChangeRecord.inMemory());
        edits.packStack(STACK);
        Path olderPack = older.resolve("datapacks/TotalDebug");
        String recipe = "data/testmod/recipe/gear.json";

        assertEquals(olderPack, edits.packOf(olderPack.resolve(recipe)).orElseThrow());
        assertEquals(this.directory.resolve("resourcepacks/TotalDebug"),
                edits.packOf(this.directory.resolve("resourcepacks/TotalDebug").resolve(LANG)).orElseThrow());
        assertTrue(edits.packOf(older.resolve("datapacks/Other").resolve(recipe)).isEmpty());
        assertTrue(edits.packOf(this.directory.resolve("config/testmod.toml")).isEmpty());

        ResourceEdits.Saved saved = edits.save(recipe, olderPack, bytes("{}")).get(5, TimeUnit.SECONDS);
        assertEquals(olderPack, saved.pack(), "not the current world's pack");
        assertTrue(Files.isRegularFile(olderPack.resolve(recipe)));
    }

    @Test
    void aMalformedOptionsFileStillAcknowledgesTheSave() throws Exception {
        Files.writeString(this.directory.resolve("options.txt"), "resourcePacks:{\"not\":\"a list\"}\n");
        ResourceEdits edits = edits(ChangeRecord.inMemory());
        edits.packStack(STACK);

        ResourceEdits.Saved saved = edits.save(LANG, bytes("{}")).get(5, TimeUnit.SECONDS);
        assertTrue(Files.isRegularFile(saved.pack().resolve(LANG)));
        assertTrue(saved.reloadFailure().contains("could not be enabled"), saved.reloadFailure());
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

        Path managed = this.directory.resolve("resourcepacks/TotalDebug");
        assertEquals("Faithful is above the TotalDebug resource pack and supplies this file too, so the game shows its copy",
                edits.unusedBecause(LANG, managed).orElseThrow());
        assertTrue(edits.unusedBecause("assets/testmod/lang/de_de.json", managed).isEmpty());
        Path mine = Files.createDirectories(this.directory.resolve("resourcepacks/MyPack"));
        assertEquals("The MyPack resource pack is not enabled, so the game does not use this file",
                edits.unusedBecause(LANG, mine).orElseThrow(), "only the managed pack is enabled by a save");
    }

    @Test
    void withoutAGameADisabledPackOfTheirsIsNamedFromTheFilesThatEnableIt() throws Exception {
        Path mine = Files.createDirectories(this.directory.resolve("resourcepacks/MyPack"));
        Files.writeString(mine.resolve("pack.mcmeta"), "{\"pack\":{\"pack_format\":34,\"description\":\"\"}}");
        ResourceEdits edits = edits(ChangeRecord.inMemory());
        Files.writeString(this.directory.resolve("options.txt"), "resourcePacks:[\"vanilla\",\"mod_resources\"]\n");
        assertEquals("The MyPack resource pack is not enabled, so the game does not use this file",
                edits.unusedBecause(LANG, mine).orElseThrow(), "options.txt does not enable it");
        Files.writeString(this.directory.resolve("options.txt"), "resourcePacks:[\"vanilla\",\"mod_resources\",\"file/MyPack\"]\n");
        assertTrue(edits.unusedBecause(LANG, mine).isEmpty());
        assertTrue(edits.unusedBecause(LANG, this.directory.resolve("resourcepacks/TotalDebug")).isEmpty(),
                "the managed pack is enabled when it is saved to");

        Path world = this.directory.resolve("saves/World");
        Map<String, Object> data = LevelDatFixture.world("World");
        data.put("DataPacks", Map.of("Enabled", List.of("vanilla"), "Disabled", List.of("file/Old")));
        LevelDatFixture.write(world, data);
        Path old = LevelDatFixture.datapack(world, "Old");
        Path added = LevelDatFixture.datapack(world, "Added");
        String recipe = "data/testmod/recipe/gear.json";
        assertEquals("The Old datapack of World is not enabled, so the game does not use this file",
                edits.unusedBecause(recipe, old).orElseThrow());
        assertTrue(edits.unusedBecause(recipe, added).isEmpty(), "a new pack of the world's folder is enabled when the world loads");
    }

    @Test
    void aDatapackAddedSinceTheWorldLoadedCountsAsUsedAsAReloadEnablesIt() throws Exception {
        Path world = this.directory.resolve("saves/World");
        Map<String, Object> data = LevelDatFixture.world("World");
        data.put("DataPacks", Map.of("Enabled", List.of("vanilla", "file/Live"), "Disabled", List.of("file/Off")));
        LevelDatFixture.write(world, data);
        Path added = LevelDatFixture.datapack(world, "Added");
        Path off = LevelDatFixture.datapack(world, "Off");
        Path live = LevelDatFixture.datapack(world, "Live");
        ResourceEdits edits = edits(ChangeRecord.inMemory());
        edits.location().connected(message -> true);
        edits.location().playing(new PlayingPayload.Singleplayer(world.toString()));
        edits.packStack(STACK);
        String recipe = "data/tweaks/recipe/gear.json";

        assertTrue(edits.unusedBecause(recipe, added).isEmpty(), "a reload enables it, as /reload does");
        assertEquals("The Off datapack of World is not enabled, so the game does not use this file",
                edits.unusedBecause(recipe, off).orElseThrow(), "but not one the world disabled");
        assertEquals("The Live datapack of World is not enabled, so the game does not use this file",
                edits.unusedBecause(recipe, live).orElseThrow(), "nor one disabled in the open world since level.dat was saved");
    }

    @Test
    void withoutAGameAnUnreadableLevelDatSaysWhetherTheDatapackIsUsedIsUnknown() throws Exception {
        Path world = Files.createDirectories(this.directory.resolve("saves/World"));
        Files.writeString(world.resolve("level.dat"), "not nbt");
        Path tweaks = LevelDatFixture.datapack(world, "Tweaks");

        String reason = edits(ChangeRecord.inMemory()).unusedBecause("data/tweaks/recipe/gear.json", tweaks).orElseThrow();
        assertTrue(reason.startsWith("Whether the game enables the Tweaks datapack of World could not be read: "), reason);
    }

    @Test
    void aPackOfTheirsThatIsGoneIsNotSavedInto() throws Exception {
        Path mine = Files.createDirectories(this.directory.resolve("resourcepacks/MyPack"));
        CompletableFuture<ResourceEdits.Saved> save = edits(ChangeRecord.inMemory()).save(LANG, mine, bytes("{}"));

        Throwable failure = assertThrows(ExecutionException.class, () -> save.get(5, TimeUnit.SECONDS));
        while (failure.getCause() != null) failure = failure.getCause();
        assertEquals("The MyPack resource pack is gone or has no readable pack.mcmeta, so the game does not load it", failure.getMessage());
        assertFalse(Files.exists(mine.resolve(LANG)));
    }

    @Test
    void aSaveIntoADisabledPackOfTheirsSaysTheGameDoesNotUseIt() throws Exception {
        Path mine = Files.createDirectories(this.directory.resolve("resourcepacks/MyPack"));
        Files.writeString(mine.resolve("pack.mcmeta"), "{\"pack\":{\"pack_format\":34,\"description\":\"\"}}");
        Files.writeString(this.directory.resolve("options.txt"), "resourcePacks:[\"vanilla\",\"mod_resources\"]\n");
        ResourceEdits.Saved saved = edits(ChangeRecord.inMemory()).save(LANG, mine, bytes("{}")).get(5, TimeUnit.SECONDS);

        assertEquals("The MyPack resource pack is not enabled, so the game does not use this file", saved.unused(),
                "saving writes the file but does not enable the player's pack");
        ResourceEdits named = edits(ChangeRecord.inMemory());
        named.packStack(STACK);
        assertTrue(named.save(LANG, bytes("{}")).get(5, TimeUnit.SECONDS).unused().isEmpty(),
                "the managed pack is enabled when it is saved to");
    }

    @Test
    void aFileOnTheSideAPackIsNotReadForIsNotSavedInThatPack() throws Exception {
        Path mine = Files.createDirectories(this.directory.resolve("resourcepacks/MyPack"));
        Files.writeString(mine.resolve("pack.mcmeta"), "{\"pack\":{\"pack_format\":34,\"description\":\"\"}}");
        Path world = Files.createDirectories(this.directory.resolve("saves/World"));
        Path datapack = LevelDatFixture.datapack(world, "Tweaks");
        ResourceEdits edits = edits(ChangeRecord.inMemory());

        assertTrue(edits.packOf(mine.resolve("data/mypack/recipe/gear.json")).isEmpty(), "the game reads no data from a resource pack");
        assertTrue(edits.packOf(datapack.resolve("assets/mypack/lang/en_us.json")).isEmpty(), "nor assets from a datapack");
        assertEquals(mine, edits.packOf(mine.resolve(LANG)).orElseThrow());
        assertEquals(datapack, edits.packOf(datapack.resolve("data/tweaks/recipe/gear.json")).orElseThrow());
    }

    @Test
    void aFileOfTheirOwnPackIsSavedInPlaceWithoutMovingThatPack() throws Exception {
        Path world = world("World");
        Path mine = Files.createDirectories(world.resolve("datapacks/MyPack"));
        Files.writeString(mine.resolve("pack.mcmeta"), "{\"pack\":{\"pack_format\":48,\"description\":\"\"}}");
        Path recipe = Files.createDirectories(mine.resolve("data/mypack/recipe")).resolve("gear.json");
        Files.writeString(recipe, "{}");
        Files.createDirectories(world.resolve("datapacks/notapack/data"));
        ChangeRecord record = ChangeRecord.inMemory();
        ResourceEdits edits = edits(record);
        assertEquals(mine, edits.packOf(recipe).orElseThrow());
        assertTrue(edits.packOf(world.resolve("datapacks/notapack/data/x.json")).isEmpty(), "a folder without pack.mcmeta is no pack");

        edits.save("data/mypack/recipe/gear.json", mine, bytes("{\"type\":\"x\"}")).get(5, TimeUnit.SECONDS);
        assertEquals("{\"type\":\"x\"}", Files.readString(recipe));
        assertTrue(record.change(new ChangeRecord.Resource("data/mypack/recipe/gear.json", mine)) != null,
                "an edit of their own pack can be reverted from Changes like any other");
        assertTrue(Files.notExists(world.resolve("datapacks/TotalDebug")), "the managed pack is not created for it");
    }

    @Test
    void theWorkingPackTakesFilesOfModsUntilItIsGone() throws Exception {
        Path mine = Files.createDirectories(this.directory.resolve("resourcepacks/MyPack"));
        Files.writeString(mine.resolve("pack.mcmeta"), "{\"pack\":{\"pack_format\":34,\"description\":\"\"}}");
        Files.writeString(this.directory.resolve("resourcepacks/Faithful.zip"), "");
        InstanceState state = InstanceState.inMemory();
        ResourceEdits edits = edits(ChangeRecord.inMemory(), state);
        Path managed = this.directory.resolve("resourcepacks/TotalDebug");
        assertEquals(List.of(managed, mine), edits.packs(LANG), "the managed pack first, then folder packs; a zip cannot be written");
        assertEquals(managed, edits.pack(LANG));

        edits.setWorkingPack(LANG, mine);
        assertEquals(mine, edits.pack(LANG));
        assertEquals("MyPack", state.workingPack("assets"));
        assertEquals("", state.workingPack("data"), "each side has its own working pack");

        List<ReloadPayload> sent = new CopyOnWriteArrayList<>();
        edits.location().connected(message -> {
            if (message instanceof ReloadMessage reload) sent.add(reload.payload());
            return true;
        });
        CompletableFuture<ResourceEdits.Saved> saved = edits.save(LANG, bytes("{}"));
        awaitSent(sent, 1);
        assertEquals("", sent.getFirst().managedResourcePack(), "the game reloads without enabling or moving any pack");
        assertEquals("", sent.getFirst().managedDataPack());
        edits.pipeline().reloads().answered(new ReloadResultPayload(sent.getFirst().requestId(), 10, List.of(), ""));
        assertEquals(mine, saved.get(5, TimeUnit.SECONDS).pack());
        assertTrue(Files.isRegularFile(mine.resolve(LANG)));
        assertTrue(Files.notExists(managed), "saving into their own pack does not create the managed one");

        try (Stream<Path> files = Files.walk(mine)) {
            for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(file);
        }
        assertEquals(managed, edits.pack(LANG), "a working pack that is gone falls back to the managed pack");
        Files.createDirectories(mine);
        assertEquals(managed, edits.pack(LANG), "so does a folder the game does not take as a pack, without pack.mcmeta");
        edits.setWorkingPack(LANG, managed);
        assertEquals("", state.workingPack("assets"));
    }

    private ResourceEdits edits(ChangeRecord record) {
        return edits(record, InstanceState.inMemory());
    }

    private ResourceEdits edits(ChangeRecord record, InstanceState state) {
        return new ResourceEdits(new ChangePipeline(GameLocations.of(this.directory, false), record, Runnable::run), new ResourceOriginals(this.directory.resolve("total-debug/originals")),
                Runnable::run, state);
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
