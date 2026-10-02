package com.github.minecraft_ta.totalDebugCompanion.pack;

import com.github.minecraft_ta.totalDebugCompanion.game.GameLocation;
import com.github.minecraft_ta.totalDebugCompanion.storage.ChangeRecord;
import com.github.minecraft_ta.totaldebug.protocol.message.ClientPacksPayload;
import com.github.minecraft_ta.totaldebug.protocol.message.PackStackPayload;
import com.github.minecraft_ta.totaldebug.protocol.message.PlayingPayload;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GamePacksTest {
    @TempDir Path directory;

    private final AtomicInteger resources = new AtomicInteger();
    private final AtomicInteger data = new AtomicInteger();

    @Test
    void eachSideTellsItsOwnListenersOnlyWhenItsPacksDiffer() {
        GameLocation location = new GameLocation(this.directory);
        GamePacks packs = follow(new GamePacks(location));
        location.connected(message -> true);

        packs.named(new ClientPacksPayload(stack("vanilla"), 48));
        assertEquals(List.of(1, 1), told(), "the resource packs, and the version's datapack format");
        packs.named(new ClientPacksPayload(stack("vanilla"), 48));
        assertEquals(List.of(2, 1), told(), "the game names them after each reload, which may change their files");
        packs.named(new ClientPacksPayload(stack("vanilla", "file/Faithful"), 48));
        assertEquals(List.of(3, 1), told(), "another resource pack leaves the datapacks alone");

        PlayingPayload world = new PlayingPayload.Singleplayer(this.directory.resolve("saves/Test").toString());
        location.playing(world);
        assertEquals(List.of(3, 2), told(), "another world has other datapacks; the resource packs stay");
        packs.datapacks(world.identity(), stack("vanilla", "mod_data"), "");
        packs.datapacks(world.identity(), stack("vanilla", "mod_data"), "");
        assertEquals(List.of(3, 4), told(), "the server names them after each data load, which may change their files");

        location.disconnected();
        assertEquals(List.of(4, 5), told(), "without the game, the files stand for both");
    }

    @Test
    void aServersDatapacksAreCountedWhenNamedAndForgottenWithItsWorld() {
        GameLocation location = new GameLocation(this.directory);
        GamePacks packs = new GamePacks(location);
        location.connected(message -> true);
        location.playing(new PlayingPayload.Multiplayer("play.example.invalid", false, true));
        assertEquals(0, packs.serverDatapackCount(), "nothing named yet");

        packs.datapacks("", new PackStackPayload(48, List.of(new PackStackPayload.Pack("vanilla", "", "", 0),
                new PackStackPayload.Pack("mod/testmod", "", "", PackStackPayload.HIDDEN)),
                List.of(new PackStackPayload.Pack("bundle", "", "", 0))), "");
        assertEquals(2, packs.serverDatapackCount(), "the ones the World page lists, without the hidden one");

        location.playing(new PlayingPayload.Multiplayer("other.example.invalid", false, true));
        assertEquals(0, packs.serverDatapackCount(), "another server's world has its own");
        packs.datapacks("", stack("vanilla"), "");
        location.disconnected();
        assertEquals(0, packs.serverDatapackCount(), "without the game, none");
    }

    @Test
    void aSelectionWrittenToItsFileTellsItsSide() {
        GamePacks packs = follow(new GamePacks(new GameLocation(this.directory)));
        packs.written(ChangeRecord.PackSide.DATA);
        assertEquals(List.of(0, 1), told());
        packs.written(ChangeRecord.PackSide.RESOURCES);
        assertEquals(List.of(1, 1), told());
    }

    @Test
    void aFileIsSuppliedByTheSideOfItsFolder() {
        assertEquals(ChangeRecord.PackSide.RESOURCES, GamePacks.side("assets/minecraft/textures/block/sand.png"));
        assertEquals(ChangeRecord.PackSide.DATA, GamePacks.side("data/minecraft/recipe/sand.json"));
    }

    private GamePacks follow(GamePacks packs) {
        packs.changed(ChangeRecord.PackSide.RESOURCES).subscribe(this.resources::incrementAndGet);
        packs.changed(ChangeRecord.PackSide.DATA).subscribe(this.data::incrementAndGet);
        return packs;
    }

    private List<Integer> told() {
        return List.of(this.resources.get(), this.data.get());
    }

    private static PackStackPayload stack(String... enabled) {
        return new PackStackPayload(34, Arrays.stream(enabled).map(id -> new PackStackPayload.Pack(id, id, "", 0)).toList(), List.of());
    }
}
