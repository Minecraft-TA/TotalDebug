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
import com.github.minecraft_ta.totaldebug.protocol.message.ChangePayload;
import com.github.minecraft_ta.totaldebug.protocol.message.ChangeResultPayload;
import com.github.minecraft_ta.totaldebug.protocol.message.ClientPacksPayload;
import com.github.minecraft_ta.totaldebug.protocol.message.PackStackPayload;
import com.github.minecraft_ta.totaldebug.protocol.message.PlayingPayload;
import com.github.minecraft_ta.totaldebug.protocol.message.ReloadPayload;
import com.github.minecraft_ta.totaldebug.protocol.message.ReloadResultPayload;
import com.github.minecraft_ta.totaldebug.protocol.message.SetPacksPayload;
import com.github.minecraft_ta.totaldebug.protocol.scnet.ChangeMessage;
import com.github.minecraft_ta.totaldebug.protocol.scnet.DatapacksRequestMessage;
import com.github.minecraft_ta.totaldebug.protocol.scnet.ReloadMessage;
import com.github.minecraft_ta.totaldebug.protocol.scnet.SetPacksMessage;
import com.github.tth05.scnet.message.AbstractMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
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
    void theConnectedGameSelectsTheResourcePacksAsAChangeThenReloadsThem() throws Exception {
        ChangeRecord record = ChangeRecord.inMemory();
        ResourceEdits edits = edits(record, true);
        List<AbstractMessage> sent = connected(edits);
        PackSelections selections = selections(record, edits);

        CompletableFuture<PackSelections.Applied> applied = selections.set(SetPacksPayload.Side.RESOURCES, null,
                List.of("vanilla", "mod_resources", "programmer_art"));
        ChangePayload change = ((ChangeMessage) sent.getFirst()).payload();
        assertEquals(List.of(new ChangePayload.Edit("resourcePacks", "resourcePacks", null,
                "[\"vanilla\",\"mod_resources\",\"programmer_art\"]")), change.edits(), "the view's selection replaces the game's");
        edits.pipeline().answered(new ChangeResultPayload(change.requestId(), List.of(new ChangeResultPayload.Applied(
                "[\"vanilla\",\"mod_resources\"]", "[\"vanilla\",\"mod_resources\",\"programmer_art\"]")), ""));
        assertEquals("[\"vanilla\",\"mod_resources\"]", record.changes().getFirst().original(), "recorded as the game answered");
        assertFalse(applied.isDone(), "the game uses the selection once its resources reloaded");

        ReloadPayload reload = ((ReloadMessage) sent.get(1)).payload();
        assertEquals(Set.of(ReloadPayload.Kind.RESOURCES), reload.kinds());
        edits.pipeline().reloads().answered(new ReloadResultPayload(reload.requestId(), 900, List.of(), ""));
        assertEquals(ConfigChanges.Effect.NOW, applied.get(5, TimeUnit.SECONDS).effect());
        assertFalse(Files.exists(this.directory.resolve("options.txt")), "the game saves options.txt itself");
    }

    @Test
    void aRevertTheGameMadeMeanwhileTakesNoReloadAndAFailedReloadIsNamed() throws Exception {
        ChangeRecord record = ChangeRecord.inMemory();
        ResourceEdits edits = edits(record, true);
        List<AbstractMessage> sent = connected(edits);
        PackSelections selections = selections(record, edits);
        String original = "[\"vanilla\"]";
        String changed = "[\"vanilla\",\"file/Faithful\"]";
        record.changed(new ChangeRecord.PackSelection(SetPacksPayload.Side.RESOURCES, this.directory.resolve("options.txt")), original, changed);

        CompletableFuture<PackSelections.Applied> reverted = selections.revert(record.changes().getFirst());
        ChangePayload change = ((ChangeMessage) sent.getFirst()).payload();
        assertEquals(changed, change.edits().getFirst().expected(), "a revert expects what Companion last enabled");
        assertEquals(original, change.edits().getFirst().value());
        edits.pipeline().answered(new ChangeResultPayload(change.requestId(), List.of(new ChangeResultPayload.Applied(original, original)), ""));
        assertEquals(ConfigChanges.Effect.NOW, reverted.get(5, TimeUnit.SECONDS).effect());
        assertEquals(1, sent.size(), "the game already used the original");
        assertEquals(0, record.size(), "back to the original, the change ends");

        CompletableFuture<PackSelections.Applied> applied = selections.set(SetPacksPayload.Side.RESOURCES, null, List.of("vanilla", "file/Broken"));
        change = ((ChangeMessage) sent.get(1)).payload();
        edits.pipeline().answered(new ChangeResultPayload(change.requestId(), List.of(new ChangeResultPayload.Applied(original,
                "[\"vanilla\",\"file/Broken\"]")), ""));
        ReloadPayload reload = ((ReloadMessage) sent.get(2)).payload();
        edits.pipeline().reloads().answered(new ReloadResultPayload(reload.requestId(), 900, List.of(),
                "The game could not load the resources and turned off every resource pack; its log names the cause"));
        ExecutionException failed = assertThrows(ExecutionException.class, () -> applied.get(5, TimeUnit.SECONDS));
        assertTrue(failed.getCause().getMessage().startsWith("The game could not load the resources"), failed.getCause().getMessage());
        assertEquals(1, record.size(), "the game selected the packs, so the change stays recorded and can be reverted");
    }

    /** Connects the game of {@code edits}, keeping what Companion sends it. */
    private static List<AbstractMessage> connected(ResourceEdits edits) {
        List<AbstractMessage> sent = new CopyOnWriteArrayList<>();
        edits.location().connected(message -> {
            sent.add(message);
            return true;
        });
        return sent;
    }

    @Test
    void aDatapackSelectionNamesTheWorldTheGamePlays() throws Exception {
        Path world = this.directory.resolve("saves/Test");
        LevelDatFixture.write(world, LevelDatFixture.world("Test"));
        ChangeRecord record = ChangeRecord.inMemory();
        ResourceEdits edits = edits(record, true);
        List<SetPacksPayload> sent = new CopyOnWriteArrayList<>();
        List<String> worlds = new CopyOnWriteArrayList<>();
        edits.location().connected(message -> {
            SentMessage out = SentMessage.of(message);
            if (out.message() instanceof SetPacksMessage packs) {
                sent.add(packs.payload());
                worlds.add(out.world());
            }
            return true;
        });
        PlayingPayload playing = new PlayingPayload.Singleplayer(world.toString());
        edits.location().playing(playing);
        edits.packs().datapacks(playing.identity(), new PackStackPayload(48, List.of(new PackStackPayload.Pack("vanilla", "Minecraft", ""))));

        selections(record, edits).set(SetPacksPayload.Side.DATA, world, List.of("vanilla", "file/Tweaks"));

        assertEquals(new PlayingPayload.Singleplayer(world.toString()).identity(), worlds.getFirst(),
                "the relay refuses it if the game plays another world by the time it arrives");
        assertEquals(SetPacksPayload.Side.DATA, sent.getFirst().side(), "the world's server selects its datapacks");
    }

    @Test
    void anotherWorldPlayedDropsTheDatapacksItsServerNamedBefore() {
        ResourceEdits edits = edits(ChangeRecord.inMemory(), true);
        List<String> asked = new CopyOnWriteArrayList<>();
        edits.location().connected(message -> {
            SentMessage out = SentMessage.of(message);
            if (out.message() instanceof DatapacksRequestMessage) asked.add(out.world());
            return true;
        });
        PackStackPayload resourcePacks = new PackStackPayload(34, List.of(new PackStackPayload.Pack("vanilla", "Minecraft", "")));
        edits.packs().named(new ClientPacksPayload(resourcePacks, 48));
        PlayingPayload played = new PlayingPayload.Singleplayer(this.directory.resolve("saves/World").toString());
        edits.location().playing(played);
        edits.packs().datapacks(played.identity(), new PackStackPayload(48, List.of(new PackStackPayload.Pack("vanilla", "Minecraft", ""))));
        asked.clear();

        PlayingPayload other = new PlayingPayload.Singleplayer(this.directory.resolve("saves/Other").toString());
        edits.location().playing(other);

        assertNull(edits.packs().datapacks(), "the datapacks named were the previous world's server's");
        assertEquals(resourcePacks, edits.packs().resourcePacks(), "the resource packs are the game client's, whatever it plays");
        assertEquals(List.of(other.identity()), asked, "the new world's server is asked for its datapacks");
    }

    @Test
    void aReportOfTheWorldTheGameLeftIsDropped() {
        ResourceEdits edits = edits(ChangeRecord.inMemory(), true);
        edits.location().connected(message -> true);
        PlayingPayload left = new PlayingPayload.Singleplayer(this.directory.resolve("saves/World").toString());
        edits.location().playing(left);
        PackStackPayload report = new PackStackPayload(48, List.of(new PackStackPayload.Pack("vanilla", "Minecraft", "")));

        edits.location().playing(new PlayingPayload.Menu());
        edits.packs().datapacks(left.identity(), report);
        assertNull(edits.packs().datapacks(), "its server sent it before the game left, so it arrived after PLAYING(Menu)");

        PlayingPayload other = new PlayingPayload.Singleplayer(this.directory.resolve("saves/Other").toString());
        edits.location().playing(other);
        edits.packs().datapacks(left.identity(), report);
        assertNull(edits.packs().datapacks(), "nor does it stand for the datapacks of the world played next");

        edits.packs().datapacks(other.identity(), report);
        assertEquals(report, edits.packs().datapacks());
    }

    private ResourceEdits edits(ChangeRecord record, boolean gameRunning) {
        return ResourceEditsFixture.edits(GameLocations.of(this.directory, gameRunning), record,
                new ResourceOriginals(this.directory.resolve("total-debug/originals")), Runnable::run, InstanceState.inMemory());
    }

    private PackSelections selections(ChangeRecord record, ResourceEdits edits) {
        return new PackSelections(record, edits, Runnable::run);
    }
}
