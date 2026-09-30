package com.github.minecraft_ta.totalDebugCompanion.pack;

import com.github.minecraft_ta.totalDebugCompanion.change.ChangePipeline;
import com.github.minecraft_ta.totalDebugCompanion.catalog.ConfigChanges;
import com.github.minecraft_ta.totalDebugCompanion.catalog.CurrentWorld;
import com.github.minecraft_ta.totalDebugCompanion.catalog.LevelDatFixture;
import com.github.minecraft_ta.totalDebugCompanion.catalog.ListedPack;
import com.github.minecraft_ta.totalDebugCompanion.change.Effect;
import com.github.minecraft_ta.totalDebugCompanion.game.GameLocations;
import com.github.minecraft_ta.totalDebugCompanion.game.GameState;
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
import com.github.minecraft_ta.totaldebug.protocol.scnet.ChangeMessage;
import com.github.minecraft_ta.totaldebug.protocol.scnet.DatapacksRequestMessage;
import com.github.minecraft_ta.totaldebug.protocol.scnet.ReloadMessage;
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
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
        ResourceEdits edits = edits(record, false);
        PackSelections selections = selections(record, edits);
        AtomicInteger told = new AtomicInteger();
        edits.packs().changed(ChangeRecord.PackSide.RESOURCES).subscribe(told::incrementAndGet);

        PackSelections.Applied applied = selections.set(ChangeRecord.PackSide.RESOURCES, null,
                List.of("vanilla", "file/New", "mod_resources")).get(5, TimeUnit.SECONDS);
        assertEquals(Effect.GAME_STARTS, applied.effect());
        assertEquals(1, told.get(), "the views of the resource packs hear of the file written");
        assertEquals("version:3955\nresourcePacks:[\"vanilla\",\"file/New\",\"mod_resources\"]\n"
                + "incompatibleResourcePacks:[\"file/New\"]\nlang:en_us\n", Files.readString(options),
                "a pack enabled here is kept even if it was made for another version; one no longer enabled leaves that list");
        ChangeRecord.Change change = record.changes().getFirst();
        assertEquals("[\"vanilla\",\"mod_resources\",\"file/Old\"]", change.original());
        assertTrue(selections.holds(change));

        selections.revert(change).get(5, TimeUnit.SECONDS);
        assertEquals(List.of("vanilla", "mod_resources", "file/Old"), PackResources.enabledInOptions(options));
        assertEquals(0, record.size(), "back to the original, the change ends");
        assertEquals(2, told.get());

        selections.set(ChangeRecord.PackSide.RESOURCES, null, List.of("vanilla", "mod_resources", "file/Old")).get(5, TimeUnit.SECONDS);
        assertEquals(2, told.get(), "a selection the file already holds writes nothing and tells nobody");
    }

    @Test
    void aSelectionChangedOutsideCompanionIsNotReverted() throws Exception {
        Path options = this.directory.resolve("options.txt");
        Files.writeString(options, "resourcePacks:[\"vanilla\",\"mod_resources\"]\n");
        ChangeRecord record = ChangeRecord.inMemory();
        PackSelections selections = selections(record, edits(record, false));
        selections.set(ChangeRecord.PackSide.RESOURCES, null, List.of("vanilla", "mod_resources", "file/A")).get(5, TimeUnit.SECONDS);
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
        ExecutionException refused = assertThrows(ExecutionException.class, () -> selections.set(ChangeRecord.PackSide.RESOURCES,
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

        PackSelections.Applied applied = selections.set(ChangeRecord.PackSide.DATA, world,
                List.of("vanilla", "file/Fresh", "mod_data")).get(5, TimeUnit.SECONDS);
        assertEquals(Effect.WORLD_OPENS, applied.effect());
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
    void theConnectedGameSelectsTheResourcePacksAsAChangeAnsweredOnceItReloaded() throws Exception {
        ChangeRecord record = ChangeRecord.inMemory();
        ResourceEdits edits = edits(record, true);
        List<AbstractMessage> sent = connected(edits);
        PackSelections selections = selections(record, edits);

        CompletableFuture<PackSelections.Applied> applied = selections.set(ChangeRecord.PackSide.RESOURCES, null,
                List.of("vanilla", "mod_resources", "programmer_art"));
        ChangePayload change = ((ChangeMessage) sent.getFirst()).payload();
        assertEquals(List.of(new ChangePayload.Edit("resourcePacks", "resourcePacks", null,
                "[\"vanilla\",\"mod_resources\",\"programmer_art\"]")), change.edits(), "the view's selection replaces the game's");
        assertFalse(applied.isDone(), "the game answers once its resources reloaded");

        edits.pipeline().answered(new ChangeResultPayload(change.requestId(), List.of(new ChangeResultPayload.Applied(
                "[\"vanilla\",\"mod_resources\"]", "[\"vanilla\",\"mod_resources\",\"programmer_art\"]")), ""));
        assertEquals(Effect.NOW, applied.get(5, TimeUnit.SECONDS).effect());
        assertEquals("[\"vanilla\",\"mod_resources\"]", record.changes().getFirst().original(), "recorded as the game answered");
        assertEquals(1, sent.size(), "the game reloads what the selection needs itself");
        assertFalse(Files.exists(this.directory.resolve("options.txt")), "the game saves options.txt itself");
    }

    @Test
    void aRevertTheGameMadeMeanwhileEndsTheChangeAndAFailedReloadIsRecordedAsTheGameLeftIt() throws Exception {
        ChangeRecord record = ChangeRecord.inMemory();
        ResourceEdits edits = edits(record, true);
        List<AbstractMessage> sent = connected(edits);
        PackSelections selections = selections(record, edits);
        String original = "[\"vanilla\",\"mod_resources\"]";
        String changed = "[\"vanilla\",\"mod_resources\",\"file/Faithful\"]";
        record.changed(new ChangeRecord.PackSelection(ChangeRecord.PackSide.RESOURCES, this.directory.resolve("options.txt")), original, changed);

        CompletableFuture<PackSelections.Applied> reverted = selections.revert(record.changes().getFirst());
        ChangePayload change = ((ChangeMessage) sent.getFirst()).payload();
        assertEquals(changed, change.edits().getFirst().expected(), "a revert expects what Companion last enabled");
        assertEquals(original, change.edits().getFirst().value());
        edits.pipeline().answered(new ChangeResultPayload(change.requestId(), List.of(new ChangeResultPayload.Applied(original, original)), ""));
        assertEquals(Effect.NOW, reverted.get(5, TimeUnit.SECONDS).effect());
        assertEquals(0, record.size(), "back to the original, the change ends");

        CompletableFuture<PackSelections.Applied> applied = selections.set(ChangeRecord.PackSide.RESOURCES, null,
                List.of("vanilla", "mod_resources", "file/Broken"));
        change = ((ChangeMessage) sent.get(1)).payload();
        edits.pipeline().answered(new ChangeResultPayload(change.requestId(), List.of(new ChangeResultPayload.Applied(original, original)),
                "The game could not load the resources and turned off every resource pack; its log names the cause"));
        ExecutionException failed = assertThrows(ExecutionException.class, () -> applied.get(5, TimeUnit.SECONDS));
        assertTrue(failed.getCause().getMessage().startsWith("The game could not load the resources"), failed.getCause().getMessage());
        assertEquals(0, record.size(), "the game turned the packs off again, so nothing it holds is Companion's change");
    }

    @Test
    void aModsOwnResourcePackIsEnabledLikeAnyOther() throws Exception {
        Path options = this.directory.resolve("options.txt");
        Files.writeString(options, "resourcePacks:[\"vanilla\",\"mod_resources\"]\n");
        ChangeRecord record = ChangeRecord.inMemory();
        PackSelections selections = selections(record, edits(record, false));

        selections.set(ChangeRecord.PackSide.RESOURCES, null, List.of("vanilla", "mod_resources", "mod/testmod")).get(5, TimeUnit.SECONDS);
        assertEquals(List.of("vanilla", "mod_resources", "mod/testmod"), PackResources.enabledInOptions(options),
                "a mod that shows its resources as a pack of its own, as the pack screen lists it");
        assertTrue(Files.readString(options).contains("incompatibleResourcePacks:[\"mod/testmod\"]"),
                "kept even when made for another format, as a folder pack is");
        assertTrue(selections.holds(record.changes().getFirst()));
        Files.writeString(options, "resourcePacks:[\"vanilla\",\"mod_resources\"]\n");
        assertFalse(selections.holds(record.changes().getFirst()), "the game turned it off since");
        Files.writeString(options, "resourcePacks:[\"vanilla\",\"mod_resources\",\"mod/testmod\"]\n");
        selections.revert(record.changes().getFirst()).get(5, TimeUnit.SECONDS);
        assertEquals(0, record.size());
    }

    @Test
    void optionsLeavingOutTheRequiredPacksAreReadAsTheGameReadsThem() throws Exception {
        Path options = this.directory.resolve("options.txt");
        Files.writeString(options, "resourcePacks:[\"vanilla\",\"file/A\"]\n");
        ChangeRecord record = ChangeRecord.inMemory();
        PackSelections selections = selections(record, edits(record, false));

        selections.set(ChangeRecord.PackSide.RESOURCES, null, List.of("vanilla", "mod_resources")).get(5, TimeUnit.SECONDS);
        ChangeRecord.Change change = record.changes().getFirst();
        assertEquals("[\"vanilla\",\"file/A\",\"mod_resources\"]", change.original(),
                "the game adds the mods' resources on top, and names them so once it runs");
        assertTrue(selections.holds(change));
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
    void theWorldsServerSelectsItsDatapacksAsAChangeThroughTheRelay() throws Exception {
        Path world = this.directory.resolve("saves/Test");
        LevelDatFixture.write(world, LevelDatFixture.world("Test"));
        ChangeRecord record = ChangeRecord.inMemory();
        ResourceEdits edits = edits(record, true);
        List<SentMessage> sent = new CopyOnWriteArrayList<>();
        edits.location().connected(message -> {
            SentMessage out = SentMessage.of(message);
            if (out.message() instanceof ChangeMessage) sent.add(out);
            return true;
        });
        PlayingPayload playing = new PlayingPayload.Singleplayer(world.toString());
        edits.location().playing(playing);
        edits.packs().datapacks(playing.identity(), new PackStackPayload(48, List.of(
                new PackStackPayload.Pack("vanilla", "Minecraft", ""), new PackStackPayload.Pack("mod_data", "Mod Data", ""),
                new PackStackPayload.Pack("mod/testmod", "Test Mod", "", PackStackPayload.HIDDEN))), "");
        PackSelections selections = selections(record, edits);

        CompletableFuture<PackSelections.Applied> applied = selections.set(ChangeRecord.PackSide.DATA, world,
                List.of("vanilla", "mod_data", "mod/shown", "mod/testmod:data/testmod/datapacks/extra", "file/Tweaks"));
        assertEquals(playing.identity(), sent.getFirst().world(), "the relay refuses it if the game plays another world by the time it arrives");
        ChangePayload change = ((ChangeMessage) sent.getFirst().message()).payload();
        assertEquals(List.of(new ChangePayload.Edit("datapacks", "datapacks", null,
                "[\"vanilla\",\"mod_data\",\"mod/shown\",\"mod/testmod:data/testmod/datapacks/extra\",\"file/Tweaks\"]")), change.edits(),
                "a mod's own datapack and one a mod adds are packs like any other");
        assertFalse(applied.isDone(), "the server answers once its data reloaded");

        String before = "[\"vanilla\",\"mod_data\"]";
        String now = "[\"vanilla\",\"mod_data\",\"mod/shown\",\"mod/testmod:data/testmod/datapacks/extra\",\"file/Tweaks\"]";
        edits.pipeline().answered(new ChangeResultPayload(change.requestId(), List.of(new ChangeResultPayload.Applied(before, now)), ""));
        assertEquals(Effect.NOW, applied.get(5, TimeUnit.SECONDS).effect());
        ChangeRecord.Change recorded = record.changes().getFirst();
        assertEquals(before, recorded.original());
        assertFalse(selections.holds(recorded), "the server has not named its new datapacks yet");
        edits.packs().datapacks(playing.identity(), new PackStackPayload(48, List.of(
                new PackStackPayload.Pack("vanilla", "Minecraft", ""), new PackStackPayload.Pack("mod_data", "Mod Data", ""),
                new PackStackPayload.Pack("mod/testmod", "Test Mod", "", PackStackPayload.HIDDEN),
                new PackStackPayload.Pack("mod/shown", "Shown", ""),
                new PackStackPayload.Pack("mod/testmod:data/testmod/datapacks/extra", "Extra", ""),
                new PackStackPayload.Pack("file/Tweaks", "Tweaks", ""))), "");
        assertTrue(selections.holds(recorded), "the hidden parts of the mods' pack are left out, as the server keeps them");

        CompletableFuture<PackSelections.Applied> reverted = selections.revert(recorded);
        change = ((ChangeMessage) sent.get(1).message()).payload();
        assertEquals(now, change.edits().getFirst().expected(), "a revert expects what Companion last enabled");
        edits.pipeline().relayFailed(change.requestId(), "The game went to another world before the server could answer");
        ExecutionException failed = assertThrows(ExecutionException.class, () -> reverted.get(5, TimeUnit.SECONDS));
        assertEquals("The game went to another world before the server could answer", failed.getCause().getMessage());
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
        edits.packs().datapacks(played.identity(), new PackStackPayload(48, List.of(new PackStackPayload.Pack("vanilla", "Minecraft", ""))), "");
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
        edits.packs().datapacks(left.identity(), report, "");
        assertNull(edits.packs().datapacks(), "its server sent it before the game left, so it arrived after PLAYING(Menu)");
        edits.packs().datapacks("", report, "");
        assertNull(edits.packs().datapacks(), "nor does a server's report, which names no world, count in the menu");

        PlayingPayload other = new PlayingPayload.Singleplayer(this.directory.resolve("saves/Other").toString());
        edits.location().playing(other);
        edits.packs().datapacks(left.identity(), report, "");
        assertNull(edits.packs().datapacks(), "nor does it stand for the datapacks of the world played next");

        edits.packs().datapacks(other.identity(), report, "");
        assertEquals(report, edits.packs().datapacks());
    }

    @Test
    void aServersWorldIsChangedLiveThroughTheRelayByTheAddressTheGameJoined() throws Exception {
        ChangeRecord record = ChangeRecord.inMemory();
        ResourceEdits edits = edits(record, true);
        List<SentMessage> sent = new CopyOnWriteArrayList<>();
        edits.location().connected(message -> {
            sent.add(SentMessage.of(message));
            return true;
        });
        PlayingPayload.Multiplayer server = new PlayingPayload.Multiplayer("play.example.net:25565", false, true);
        edits.location().playing(server);
        assertTrue(sent.getFirst().message() instanceof DatapacksRequestMessage, "a server with TotalDebug is asked for its datapacks");
        assertEquals(server.identity(), sent.getFirst().world());
        edits.packs().datapacks("", new PackStackPayload(48, List.of(new PackStackPayload.Pack("vanilla", "Minecraft", ""))), "");
        assertNotNull(edits.packs().datapacks(), "the server names its world by no folder, as the game client names it by its address");
        Path world = edits.location().read().serverWorld(server);
        PackSelections selections = selections(record, edits);

        CompletableFuture<PackSelections.Applied> applied = selections.set(ChangeRecord.PackSide.DATA, world, List.of("vanilla", "file/Tweaks"));
        SentMessage change = sent.get(1);
        assertEquals(server.identity(), change.world(), "the relay refuses it once the game plays elsewhere");
        ChangePayload payload = ((ChangeMessage) change.message()).payload();
        edits.pipeline().answered(new ChangeResultPayload(payload.requestId(), List.of(new ChangeResultPayload.Applied(
                "[\"vanilla\"]", "[\"vanilla\",\"file/Tweaks\"]")), ""));
        assertEquals(Effect.NOW, applied.get(5, TimeUnit.SECONDS).effect());
        assertEquals(world, ((ChangeRecord.PackSelection) record.changes().getFirst().target()).location(), "recorded for the server's world, and revertible there");
    }

    @Test
    void aServersWorldIsRefusedWithWhatItNeeds() throws Exception {
        ChangeRecord record = ChangeRecord.inMemory();
        ResourceEdits edits = edits(record, true);
        List<AbstractMessage> sent = connected(edits);
        PackSelections selections = selections(record, edits);
        PlayingPayload.Multiplayer vanilla = new PlayingPayload.Multiplayer("vanilla.example.net", false, false);
        edits.location().playing(vanilla);
        Path vanillaWorld = edits.location().read().serverWorld(vanilla);
        assertEquals("The server vanilla.example.net does not have TotalDebug, which Companion needs to change its datapacks",
                refusal(selections.set(ChangeRecord.PackSide.DATA, vanillaWorld, List.of("vanilla"))));

        PlayingPayload.Multiplayer modded = new PlayingPayload.Multiplayer("modded.example.net:25565", false, true);
        edits.location().playing(modded);
        edits.packs().datapacks("", new PackStackPayload(48, List.of()), "You need operator permission on this server to change its world");
        assertEquals("You need operator permission on this server to change its world", edits.packs().worldRefusal(),
                "shown, while the server decides each change itself, as the player may be made an operator meanwhile");
        Path moddedWorld = edits.location().read().serverWorld(modded);
        assertEquals("modded.example.net:25565", GameState.worldName(moddedWorld));
        assertNotEquals(moddedWorld, edits.location().read().serverWorld(new PlayingPayload.Multiplayer("modded.example.net_25565", false, true)),
                "no two addresses share the change record's place");
        assertEquals("The world of vanilla.example.net is on its server; join it in the game to change its datapacks",
                refusal(selections.set(ChangeRecord.PackSide.DATA, vanillaWorld, List.of("vanilla"))), "never files: the world is not here");
        assertFalse(sent.stream().anyMatch(message -> SentMessage.of(message).message() instanceof ChangeMessage));
        assertEquals(0, record.size());
    }

    private static String refusal(CompletableFuture<?> change) {
        ExecutionException refused = assertThrows(ExecutionException.class, () -> change.get(5, TimeUnit.SECONDS));
        return refused.getCause().getMessage();
    }

    private ResourceEdits edits(ChangeRecord record, boolean gameRunning) {
        return ResourceEditsFixture.edits(GameLocations.of(this.directory, gameRunning), record,
                new ResourceOriginals(this.directory.resolve("total-debug/originals")), Runnable::run, InstanceState.inMemory());
    }

    private PackSelections selections(ChangeRecord record, ResourceEdits edits) {
        return new PackSelections(edits);
    }
}
