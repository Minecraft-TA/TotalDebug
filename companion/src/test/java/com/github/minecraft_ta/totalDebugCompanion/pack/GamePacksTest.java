package com.github.minecraft_ta.totalDebugCompanion.pack;

import com.github.minecraft_ta.totalDebugCompanion.game.GameLocation;
import com.github.minecraft_ta.totalDebugCompanion.storage.ChangeRecord;
import com.github.minecraft_ta.totaldebug.protocol.message.ClientPacksPayload;
import com.github.minecraft_ta.totaldebug.protocol.message.PackStackPayload;
import com.github.minecraft_ta.totaldebug.protocol.message.PlayingPayload;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
    void whatTheGameNamedIsPublishedAsOneValue() {
        GameLocation location = new GameLocation(this.directory);
        GamePacks packs = new GamePacks(location);
        location.connected(message -> true);
        PlayingPayload server = new PlayingPayload.Multiplayer("play.example.invalid", false, true);
        location.playing(server);
        packs.named(new ClientPacksPayload(stack("vanilla"), 48));
        packs.datapacks("", stack("vanilla", "mod_data"), "");
        GamePacks.Named before = packs.named();
        assertEquals(new GamePacks.Named(stack("vanilla"), 48, stack("vanilla", "mod_data"), server, "", 2), before);

        // A refusal replaces the datapacks, the refusal and the count together.
        List<GamePacks.Named> seen = new ArrayList<>();
        packs.changed(ChangeRecord.PackSide.DATA).subscribe(() -> seen.add(packs.named()));
        packs.datapacks("", stack("vanilla"), "Not an operator");
        assertEquals(new GamePacks.Named(stack("vanilla"), 48, null, server, "Not an operator", 0), packs.named());
        assertEquals(List.of(packs.named()), seen, "a follower reads the new value in its signal");
        assertEquals(stack("vanilla", "mod_data"), before.datapacks(), "a value taken before stays as it was");

        // A disconnect clears what the game named at once, and keeps the world the datapacks were named for.
        location.disconnected();
        assertEquals(new GamePacks.Named(null, 0, null, server, "", 0), packs.named());
        assertEquals(0, packs.format(false));
    }

    @Test
    void theDatapacksOfTheWorldTheGamePlaysStayWhenItIsAnnouncedAgain() {
        GameLocation location = new GameLocation(this.directory);
        GamePacks packs = new GamePacks(location);
        PlayingPayload world = new PlayingPayload.Singleplayer(this.directory.resolve("saves/Test").toString());
        location.connected(message -> true);
        location.playing(world);
        packs.datapacks(world.identity(), stack("vanilla", "mod_data"), "");

        // A reconnect is told the same world again.
        location.disconnected();
        location.connected(message -> true);
        location.playing(world);
        packs.datapacks(world.identity(), stack("vanilla", "mod_data"), "");
        location.playing(new PlayingPayload.Menu());
        location.playing(world);
        assertEquals(null, packs.datapacks(), "another world in between asks the world's server again");

        packs.datapacks(world.identity(), stack("vanilla"), "");
        location.connected(message -> true);
        assertEquals(stack("vanilla"), packs.datapacks(), "the connection announcing the same world keeps what its server named");
    }

    @Test
    void aResourcesMetadataComesFromTheHighestPackThatSuppliesIt() throws Exception {
        String texture = "assets/demo/textures/block/stone.png";
        Path folder = Files.createDirectories(this.directory.resolve("resourcepacks/Base"));
        Files.createDirectories(folder.resolve(texture).getParent());
        Files.writeString(folder.resolve(texture + ".mcmeta"), "folder");
        Path above = zip("Above.zip", Map.of(texture + ".mcmeta", "zip"));
        Path directoryOnly = zip("Directory.zip", Map.of(texture + ".mcmeta/", ""));
        GameLocation location = new GameLocation(this.directory);
        GamePacks packs = new GamePacks(location);
        location.connected(message -> true);

        packs.named(new ClientPacksPayload(new PackStackPayload(34, List.of(
                new PackStackPayload.Pack("file/Base", "Base", folder.toString(), 0),
                new PackStackPayload.Pack("file/Above.zip", "Above", above.toString(), 0)), List.of()), 48));
        assertEquals("zip", new String(packs.metadata(texture, folder, 1024).orElseThrow(), StandardCharsets.UTF_8),
                "the game takes a resource's metadata from the highest pack that supplies it");
        assertThrows(IOException.class, () -> packs.metadata(texture, folder, 2), "a file over the limit is refused");

        packs.named(new ClientPacksPayload(new PackStackPayload(34, List.of(
                new PackStackPayload.Pack("file/Base", "Base", folder.toString(), 0),
                new PackStackPayload.Pack("file/Directory.zip", "Directory", directoryOnly.toString(), 0)), List.of()), 48));
        assertEquals("folder", new String(packs.metadata(texture, folder, 1024).orElseThrow(), StandardCharsets.UTF_8),
                "a folder of that name in a pack above is no file");
    }

    /** A zip pack in {@code resourcepacks} holding {@code entries}; a name ending in a slash is a folder. */
    private Path zip(String name, Map<String, String> entries) throws IOException {
        Path zip = Files.createDirectories(this.directory.resolve("resourcepacks")).resolve(name);
        try (var output = new ZipOutputStream(Files.newOutputStream(zip))) {
            for (var entry : entries.entrySet()) {
                output.putNextEntry(new ZipEntry(entry.getKey()));
                output.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                output.closeEntry();
            }
        }
        return zip;
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
