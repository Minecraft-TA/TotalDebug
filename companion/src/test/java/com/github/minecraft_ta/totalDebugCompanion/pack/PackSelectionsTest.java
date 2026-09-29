package com.github.minecraft_ta.totalDebugCompanion.pack;

import com.github.minecraft_ta.totalDebugCompanion.change.ChangePipeline;
import com.github.minecraft_ta.totalDebugCompanion.catalog.ConfigChanges;
import com.github.minecraft_ta.totalDebugCompanion.catalog.CurrentWorld;
import com.github.minecraft_ta.totalDebugCompanion.catalog.LevelDatFixture;
import com.github.minecraft_ta.totalDebugCompanion.catalog.ListedPack;
import com.github.minecraft_ta.totalDebugCompanion.game.GameLocations;
import com.github.minecraft_ta.totalDebugCompanion.storage.ChangeRecord;
import com.github.minecraft_ta.totalDebugCompanion.storage.InstanceState;
import com.github.minecraft_ta.totalDebugCompanion.storage.ResourceOriginals;
import com.github.minecraft_ta.totaldebug.protocol.message.PackStackPayload;
import com.github.minecraft_ta.totaldebug.protocol.message.PlayingPayload;
import com.github.minecraft_ta.totaldebug.protocol.message.ReloadResultPayload;
import com.github.minecraft_ta.totaldebug.protocol.message.SetPacksPayload;
import com.github.minecraft_ta.totaldebug.protocol.scnet.SetPacksMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PackSelectionsTest {
    @TempDir Path directory;

    @Test
    void aClosedGamesResourcePacksAreWrittenToOptionsAndRevertedFromThere() throws Exception {
        Path options = this.directory.resolve("options.txt");
        Files.writeString(options, "version:3955\nresourcePacks:[\"vanilla\",\"mod_resources\",\"file/Old\"]\n"
                + "incompatibleResourcePacks:[\"file/Old\"]\nlang:en_us\n");
        ChangeRecord record = ChangeRecord.inMemory();
        PackSelections selections = selections(record, edits(record, false));

        PackSelections.Applied applied = selections.set(SetPacksPayload.Side.RESOURCES, null,
                List.of("vanilla", "file/New", "mod_resources")).get(5, TimeUnit.SECONDS);
        assertEquals(ConfigChanges.Effect.GAME_STARTS, applied.effect());
        assertEquals("version:3955\nresourcePacks:[\"vanilla\",\"file/New\",\"mod_resources\"]\n"
                + "incompatibleResourcePacks:[\"file/New\"]\nlang:en_us\n", Files.readString(options),
                "a pack enabled here is kept even if it was made for another version; one no longer enabled leaves that list");
        ChangeRecord.Change change = record.changes().getFirst();
        assertEquals("[\"vanilla\",\"mod_resources\",\"file/Old\"]", change.original());
        assertTrue(selections.holds(change));

        selections.revert(change).get(5, TimeUnit.SECONDS);
        assertEquals(List.of("vanilla", "mod_resources", "file/Old"), PackResources.enabledInOptions(options));
        assertEquals(0, record.size(), "back to the original, the change ends");
    }

    @Test
    void aSelectionChangedOutsideCompanionIsNotReverted() throws Exception {
        Path options = this.directory.resolve("options.txt");
        Files.writeString(options, "resourcePacks:[\"vanilla\",\"mod_resources\"]\n");
        ChangeRecord record = ChangeRecord.inMemory();
        PackSelections selections = selections(record, edits(record, false));
        selections.set(SetPacksPayload.Side.RESOURCES, null, List.of("vanilla", "mod_resources", "file/A")).get(5, TimeUnit.SECONDS);
        Files.writeString(options, "resourcePacks:[\"vanilla\",\"mod_resources\",\"file/B\"]\n");

        ChangeRecord.Change change = record.changes().getFirst();
        assertFalse(selections.holds(change));
        ExecutionException refused = assertThrows(ExecutionException.class, () -> selections.revert(change).get(5, TimeUnit.SECONDS));
        assertTrue(refused.getCause().getMessage().contains("changed outside Companion"), refused.getCause().getMessage());
        assertEquals(List.of("vanilla", "mod_resources", "file/B"), PackResources.enabledInOptions(options));
    }

    @Test
    void aRunningGameWithoutAConnectionKeepsItsResourcePacks() throws Exception {
        ChangeRecord record = ChangeRecord.inMemory();
        PackSelections selections = selections(record, edits(record, true));
        ExecutionException refused = assertThrows(ExecutionException.class, () -> selections.set(SetPacksPayload.Side.RESOURCES,
                null, List.of("vanilla")).get(5, TimeUnit.SECONDS));
        assertTrue(refused.getCause().getMessage().startsWith("The game is running but not connected"), refused.getCause().getMessage());
        assertFalse(Files.exists(this.directory.resolve("options.txt")));
    }

    @Test
    void aClosedWorldsDatapacksAreWrittenIntoItsLevelDat() throws Exception {
        Path world = this.directory.resolve("saves/Test");
        LevelDatFixture.write(world, LevelDatFixture.world("Test"));
        LevelDatFixture.datapack(world, "Tweaks");
        LevelDatFixture.datapack(world, "Fresh");
        ChangeRecord record = ChangeRecord.inMemory();
        PackSelections selections = selections(record, edits(record, false));

        PackSelections.Applied applied = selections.set(SetPacksPayload.Side.DATA, world,
                List.of("vanilla", "file/Fresh", "mod_data")).get(5, TimeUnit.SECONDS);
        assertEquals(ConfigChanges.Effect.WORLD_OPENS, applied.effect());
        CurrentWorld.Saved saved = CurrentWorld.read(GameLocations.of(this.directory, false).read(), world);
        assertEquals(List.of("+mod_data", "+file/Fresh", "+vanilla", "-file/Tweaks", "-bundle",
                        "-mod/testmod:data/testmod/datapacks/extra"),
                saved.datapacks().stream().map(pack -> (pack.state() == ListedPack.State.ENABLED ? "+" : "-") + pack.id()).toList(),
                "every other pack of the world is disabled, so the game does not enable it again as a new one");
        assertEquals("Test", saved.name(), "the rest of level.dat is written back as read");
        assertEquals(8_757_790_292_842_126_093L, saved.seed());
        assertTrue(Files.isRegularFile(world.resolve("level.dat_old")), "the last level.dat is kept, as the game keeps it");

        selections.revert(record.changes().getFirst()).get(5, TimeUnit.SECONDS);
        assertEquals(List.of("file/Tweaks", "mod_data", "vanilla"), CurrentWorld.read(GameLocations.of(this.directory, false).read(), world).datapacks().stream()
                .filter(pack -> pack.state() == ListedPack.State.ENABLED).map(ListedPack::id).toList());
    }

    @Test
    void theConnectedGameSelectsThePacksAndTheChangeIsRecordedOnceItDid() throws Exception {
        ChangeRecord record = ChangeRecord.inMemory();
        ResourceEdits edits = edits(record, true);
        edits.packs().packStack(new PackStackPayload(34, 48, List.of(
                new PackStackPayload.Pack("vanilla", "Minecraft", "", PackStackPayload.REQUIRED),
                new PackStackPayload.Pack("mod_resources", "Mod Resources", "", PackStackPayload.REQUIRED),
                new PackStackPayload.Pack("mod/testmod", "Test Mod", "", PackStackPayload.HIDDEN)), List.of()));
        List<SetPacksPayload> sent = new CopyOnWriteArrayList<>();
        edits.location().connected(message -> {
            if (message instanceof SetPacksMessage packs) sent.add(packs.payload());
            return true;
        });
        PackSelections selections = selections(record, edits);

        CompletableFuture<PackSelections.Applied> applied = selections.set(SetPacksPayload.Side.RESOURCES, null,
                List.of("vanilla", "mod_resources", "programmer_art"));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (sent.isEmpty() && System.nanoTime() < deadline) Thread.sleep(10);
        assertEquals(List.of("vanilla", "mod_resources", "programmer_art"), sent.getFirst().enabled());
        assertEquals(0, record.size(), "recorded once the game did it");

        edits.pipeline().reloads().answered(new ReloadResultPayload(sent.getFirst().requestId(), 900, List.of(), ""));
        assertEquals(ConfigChanges.Effect.NOW, applied.get(5, TimeUnit.SECONDS).effect());
        assertEquals("[\"vanilla\",\"mod_resources\"]", record.changes().getFirst().original(),
                "as options.txt keeps it: without the parts of the mods' pack");
        assertFalse(Files.exists(this.directory.resolve("options.txt")), "the game saves options.txt itself");
    }

    @Test
    void aDatapackSelectionNamesTheWorldTheGamePlays() throws Exception {
        Path world = this.directory.resolve("saves/Test");
        LevelDatFixture.write(world, LevelDatFixture.world("Test"));
        ChangeRecord record = ChangeRecord.inMemory();
        ResourceEdits edits = edits(record, true);
        List<SetPacksPayload> sent = new CopyOnWriteArrayList<>();
        edits.location().connected(message -> {
            if (message instanceof SetPacksMessage packs) sent.add(packs.payload());
            return true;
        });
        edits.location().playing(new PlayingPayload.Singleplayer(world.toString()));
        edits.packs().packStack(new PackStackPayload(34, 48, List.of(), List.of(new PackStackPayload.Pack("vanilla", "Minecraft", ""))));

        selections(record, edits).set(SetPacksPayload.Side.DATA, world, List.of("vanilla", "file/Tweaks"));

        assertEquals(world.toAbsolutePath().normalize().toString(), sent.getFirst().world(),
                "the game refuses it if it plays another world by the time it arrives");
    }

    @Test
    void anotherWorldPlayedDropsThePacksTheGameNamedBefore() {
        ResourceEdits edits = edits(ChangeRecord.inMemory(), true);
        edits.location().connected(message -> true);
        edits.packs().packStack(new PackStackPayload(34, 48, List.of(), List.of(new PackStackPayload.Pack("vanilla", "Minecraft", ""))));

        edits.location().playing(new PlayingPayload.Singleplayer(this.directory.resolve("saves/Other").toString()));

        assertNull(edits.packs().packStack(), "the packs named were the previous world's; the game names them again");
    }

    private ResourceEdits edits(ChangeRecord record, boolean gameRunning) {
        return ResourceEditsFixture.edits(GameLocations.of(this.directory, gameRunning), record,
                new ResourceOriginals(this.directory.resolve("total-debug/originals")), Runnable::run, InstanceState.inMemory());
    }

    private PackSelections selections(ChangeRecord record, ResourceEdits edits) {
        return new PackSelections(record, edits, Runnable::run);
    }
}
