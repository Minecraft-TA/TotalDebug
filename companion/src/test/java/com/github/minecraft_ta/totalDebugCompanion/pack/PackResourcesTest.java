package com.github.minecraft_ta.totalDebugCompanion.pack;

import com.github.minecraft_ta.totalDebugCompanion.catalog.CatalogFixtures;
import com.github.minecraft_ta.totalDebugCompanion.catalog.CatalogIndex;
import com.github.minecraft_ta.totalDebugCompanion.catalog.LevelDatFixture;
import com.github.minecraft_ta.totalDebugCompanion.catalog.ListedPack;
import com.github.minecraft_ta.totalDebugCompanion.catalog.ModResources;
import com.github.minecraft_ta.totaldebug.protocol.message.PackStackPayload;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PackResourcesTest {
    private static final String TEXTURE = "assets/testmod/textures/item/widget.png";
    private static final String RECIPE = "data/testmod/recipe/widget.json";

    @TempDir Path directory;

    @Test
    void withoutAGameAssetsFollowOptionsAndDataTheMods() throws Exception {
        CatalogIndex index = new CatalogIndex(CatalogFixtures.catalog(CatalogFixtures.modJar(this.directory)));
        String mod = index.mod("testmod").orElseThrow().title();
        Path faithful = pack(this.directory.resolve("resourcepacks/Faithful"), TEXTURE, RECIPE);
        Files.writeString(this.directory.resolve("options.txt"), "resourcePacks:[\"file/Faithful\"]\n");
        List<String> unlisted = PackResources.assets(null, index, this.directory).stream().map(PackResources.Source::id).toList();
        assertEquals(List.of("vanilla", "file/Faithful", "mod/testmod"), unlisted,
                "both are required: the game adds vanilla at the bottom and the mods' resources at the top; NeoForge has no file here");

        Files.writeString(this.directory.resolve("options.txt"), "resourcePacks:[\"vanilla\",\"mod_resources\",\"file/Faithful\"]\n");
        List<PackResources.Source> assets = PackResources.assets(null, index, this.directory);
        assertEquals(List.of("vanilla", "mod/testmod", "file/Faithful"), assets.stream().map(PackResources.Source::id).toList());
        assertEquals(List.of(faithful), assets.getLast().files());

        PackResources.Joined joined = PackResources.join(assets, PackResources.data(null, index, this.directory));
        assertEquals("Faithful", joined.from().get(TEXTURE), "the highest pack wins");
        assertEquals(List.of(mod), joined.hidden().get(TEXTURE));
        assertEquals(mod, joined.from().get(RECIPE), "a resource pack's data is not read by the game");
        assertFalse(joined.hidden().containsKey(RECIPE));
        assertEquals(1, joined.resources().stream().filter(resource -> resource.path().equals(TEXTURE)).count());
        ModResources.Resource texture = joined.resources().stream().filter(resource -> resource.path().equals(TEXTURE)).findFirst().orElseThrow();
        assertEquals(faithful, texture.file(), "opening the file opens the copy the game uses");
    }

    @Test
    void aGameWithoutAWorldListsNoDataAndAnUnreadablePackAddsNothing() throws Exception {
        CatalogIndex index = new CatalogIndex(CatalogFixtures.catalog(CatalogFixtures.modJar(this.directory)));
        PackStackPayload menu = new PackStackPayload(34, 48, List.of(new PackStackPayload.Pack("mod/testmod", "Mod resources", "")),
                List.of());
        assertEquals(List.of(), PackResources.data(menu, index, this.directory), "at the menu or on a server the game has no data of its own");

        PackResources.Joined joined = PackResources.join(List.of(
                new PackResources.Source("file/Gone", "Gone", List.of(this.directory.resolve("resourcepacks/Gone.zip"))),
                new PackResources.Source("mod/testmod", "Test Mod", index.resourceFiles("testmod"))), List.of());
        assertEquals("Test Mod", joined.from().get(TEXTURE), "a pack that cannot be read is skipped, not the whole list");
    }

    @Test
    void withAGameBothFollowTheStacksItNamed() throws Exception {
        CatalogIndex index = new CatalogIndex(CatalogFixtures.catalog(CatalogFixtures.modJar(this.directory)));
        Path faithful = pack(this.directory.resolve("resourcepacks/Faithful"), TEXTURE);
        Path datapack = pack(this.directory.resolve("saves/World/datapacks/TotalDebug"), RECIPE);
        PackStackPayload stack = new PackStackPayload(34, 48,
                List.of(new PackStackPayload.Pack("file/Faithful", "Faithful", faithful.toString()),
                        new PackStackPayload.Pack("mod/testmod", "Mod resources", "")),
                List.of(new PackStackPayload.Pack("mod/testmod", "Mod resources", ""),
                        new PackStackPayload.Pack("file/TotalDebug", "TotalDebug", datapack.toString())));

        PackResources.Joined joined = PackResources.join(PackResources.assets(stack, index, this.directory),
                PackResources.data(stack, index, this.directory));
        String mod = index.mod("testmod").orElseThrow().title();
        assertEquals(mod, joined.from().get(TEXTURE), "the game put the mod above Faithful");
        assertEquals(List.of("Faithful"), joined.hidden().get(TEXTURE));
        assertEquals("TotalDebug", joined.from().get(RECIPE), "the world's datapack wins over the mod's data");
    }

    @Test
    void twoPacksWithTheSameTitleStillHideOneAnother() throws Exception {
        Path older = pack(this.directory.resolve("resourcepacks/Faithful 1"), TEXTURE);
        Path newer = pack(this.directory.resolve("resourcepacks/Faithful 2"), TEXTURE);

        PackResources.Joined joined = PackResources.join(List.of(
                new PackResources.Source("file/Faithful 1", "Faithful", List.of(older)),
                new PackResources.Source("file/Faithful 2", "Faithful", List.of(newer))), List.of());
        assertEquals(List.of("Faithful"), joined.hidden().get(TEXTURE), "packs are told apart by id, not by the title they show");
    }

    @Test
    void resourcePacksFollowThePackScreenWithTheModsAsOnePack() throws Exception {
        pack(this.directory.resolve("resourcepacks/Faithful"), TEXTURE);
        pack(this.directory.resolve("resourcepacks/Unused"), TEXTURE);
        Files.writeString(this.directory.resolve("options.txt"), "resourcePacks:[\"vanilla\",\"mod_resources\",\"file/Faithful\"]\n");
        assertEquals(List.of("file/Faithful", "mod_resources", "vanilla", "file/Unused"),
                PackResources.resourcePacks(null, this.directory).stream().map(ListedPack::id).toList(),
                "enabled ones with the highest first, then those in resourcepacks/ that are not");

        PackStackPayload stack = new PackStackPayload(34, 48, List.of(new PackStackPayload.Pack("vanilla", "Minecraft", ""),
                new PackStackPayload.Pack("mod/testmod", "Test Mod", ""), new PackStackPayload.Pack("mod/other,more", "Other", ""),
                new PackStackPayload.Pack("file/Unused", "Unused", "")), List.of());
        List<ListedPack> running = PackResources.resourcePacks(stack, this.directory);
        assertEquals(List.of("file/Unused", "mod_resources", "vanilla", "file/Faithful"), running.stream().map(ListedPack::id).toList(),
                "the game names a pack for each mod file, which its pack screen shows as one");
        assertEquals(ListedPack.State.DISABLED, running.getLast().state());
        assertEquals(this.directory.resolve("resourcepacks/Unused"), running.getFirst().file());
        assertEquals(List.of("Unused", "", "Minecraft", ""), running.stream().map(ListedPack::title).toList(),
                "the running game's titles are kept, except for the mods it shows as one");
    }

    @Test
    void withoutAGameAPackOfTheFolderThatIsGoneIsNotListed() throws Exception {
        Files.writeString(this.directory.resolve("options.txt"), "resourcePacks:[\"vanilla\",\"programmer_art\",\"file/Gone\"]\n");
        assertEquals(List.of("mod_resources", "programmer_art", "vanilla"),
                PackResources.resourcePacks(null, this.directory).stream().map(ListedPack::id).toList(),
                "the game drops it when it starts; a built-in pack has no file of its own and stays");

        Files.writeString(this.directory.resolve("options.txt"), "resourcePacks:[\"programmer_art\"]\n");
        assertEquals(List.of("mod_resources", "programmer_art", "vanilla"),
                PackResources.resourcePacks(null, this.directory).stream().map(ListedPack::id).toList(),
                "the game adds the required packs options.txt leaves out: Minecraft's at the bottom, the mods' at the top");
    }

    @Test
    void withoutAGameAnUnreadableLevelDatFailsRatherThanLeavingTheWorldsPacksOut() throws Exception {
        CatalogIndex index = new CatalogIndex(CatalogFixtures.catalog(CatalogFixtures.modJar(this.directory)));
        Path world = Files.createDirectories(this.directory.resolve("saves/World"));
        Files.writeString(world.resolve("level.dat"), "not nbt");

        assertThrows(IOException.class, () -> PackResources.data(null, index, this.directory));
    }

    @Test
    void withoutAGameTheRequiredDataPacksStayWhenTheWorldListsNone() throws Exception {
        CatalogIndex index = new CatalogIndex(CatalogFixtures.catalog(CatalogFixtures.modJar(this.directory)));
        Path world = this.directory.resolve("saves/World");
        Map<String, Object> data = LevelDatFixture.world("World");
        data.remove("DataPacks");
        LevelDatFixture.write(world, data);
        pack(world.resolve("datapacks/Added"), RECIPE);

        assertEquals(List.of("vanilla", "file/Added", "mod/testmod"),
                PackResources.data(null, index, this.directory).stream().map(PackResources.Source::id).toList(),
                "the game adds Minecraft's data at the bottom and the mods' at the top");
    }

    @Test
    void withoutAGameDataFollowsTheCurrentWorldsDatapacks() throws Exception {
        CatalogIndex index = new CatalogIndex(CatalogFixtures.catalog(CatalogFixtures.modJar(this.directory)));
        Path world = this.directory.resolve("saves/World");
        Map<String, Object> data = LevelDatFixture.world("World");
        data.put("DataPacks", Map.of("Enabled", List.of("vanilla", "mod_data", "file/Tweaks", "create:generated"), "Disabled", List.of()));
        LevelDatFixture.write(world, data);
        Path tweaks = pack(world.resolve("datapacks/Tweaks"), RECIPE);
        Path added = pack(world.resolve("datapacks/Added"), "data/testmod/recipe/cog.json");

        List<PackResources.Source> sources = PackResources.data(null, index, this.directory);
        assertEquals(List.of("file/Tweaks", "file/Added"), sources.subList(sources.size() - 2, sources.size()).stream()
                .map(PackResources.Source::id).toList(),
                "the world's own pack is above the mods, and a new one in its folder above that, as the game enables it on load");
        assertEquals(List.of(tweaks), sources.get(sources.size() - 2).files());
        assertEquals(List.of(added), sources.getLast().files());
        assertEquals("Tweaks", PackResources.join(List.of(), sources).from().get(RECIPE));
    }

    @Test
    void optionsWithoutResourcePacksMeanTheGamesDefaults() throws Exception {
        assertEquals(List.of("vanilla", "mod_resources"), PackResources.enabledInOptions(this.directory.resolve("options.txt")));
        assertEquals("Faithful.zip", PackResources.title("file/Faithful.zip"));
    }

    private static Path pack(Path folder, String... paths) throws IOException {
        Files.createDirectories(folder);
        Files.writeString(folder.resolve("pack.mcmeta"), "{}");
        for (String path : paths) {
            Path file = folder.resolve(path);
            Files.createDirectories(file.getParent());
            Files.writeString(file, path);
        }
        return folder;
    }
}
