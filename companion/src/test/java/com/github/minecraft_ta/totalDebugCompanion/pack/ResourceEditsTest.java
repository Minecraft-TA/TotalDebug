package com.github.minecraft_ta.totalDebugCompanion.pack;

import com.github.minecraft_ta.totalDebugCompanion.catalog.ConfigChanges;
import com.github.minecraft_ta.totalDebugCompanion.catalog.LevelDatFixture;
import com.github.minecraft_ta.totalDebugCompanion.storage.ChangeRecord;
import com.github.minecraft_ta.totalDebugCompanion.storage.ResourceOriginals;
import com.github.minecraft_ta.totaldebug.protocol.message.PackStackPayload;
import com.github.minecraft_ta.totaldebug.protocol.message.ReloadPayload;
import com.github.minecraft_ta.totaldebug.protocol.message.ReloadResultPayload;
import com.github.minecraft_ta.totaldebug.protocol.scnet.ReloadMessage;
import com.github.minecraft_ta.totalDebugCompanion.storage.InstanceState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

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
        assertTrue(failure.getMessage().contains("changed outside Companion"), failure.getMessage());
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
        ResourceEdits edits = new ResourceEdits(this.directory, ChangeRecord.inMemory(),
                new ResourceOriginals(this.directory.resolve("total-debug/originals")), Runnable::run, () -> true,
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

        edits.answered(new ReloadResultPayload(merged.requestId(), 900, List.of(new ReloadResultPayload.Problem(
                "assets/testmod/models/block/gear.json", "Unable to load model testmod:block/gear")), ""));
        assertEquals(List.of("Unable to load model testmod:block/gear"), second.get(5, TimeUnit.SECONDS).problems());
        assertEquals(ConfigChanges.Effect.NOW, third.get(5, TimeUnit.SECONDS).effect());
        assertEquals(List.of(), third.get(5, TimeUnit.SECONDS).problems(), "the model's problem is not the language file's");
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
        Files.writeString(mine.resolve("pack.mcmeta"), "{}");
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
    void aSaveIntoADisabledPackOfTheirsSaysTheGameDoesNotUseIt() throws Exception {
        Path mine = Files.createDirectories(this.directory.resolve("resourcepacks/MyPack"));
        Files.writeString(mine.resolve("pack.mcmeta"), "{}");
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
        Files.writeString(mine.resolve("pack.mcmeta"), "{}");
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
        Files.writeString(mine.resolve("pack.mcmeta"), "{}");
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
        edits.gameConnected(message -> {
            if (message instanceof ReloadMessage reload) sent.add(reload.payload());
            return true;
        });
        CompletableFuture<ResourceEdits.Saved> saved = edits.save(LANG, bytes("{}"));
        awaitSent(sent, 1);
        assertEquals("", sent.getFirst().managedPack(), "the game reloads without enabling or moving any pack");
        edits.answered(new ReloadResultPayload(sent.getFirst().requestId(), 10, List.of(), ""));
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
        return new ResourceEdits(this.directory, record, new ResourceOriginals(this.directory.resolve("total-debug/originals")),
                Runnable::run, () -> false, state);
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
