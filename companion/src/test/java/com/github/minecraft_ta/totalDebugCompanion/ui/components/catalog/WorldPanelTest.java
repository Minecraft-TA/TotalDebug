package com.github.minecraft_ta.totalDebugCompanion.ui.components.catalog;

import com.github.minecraft_ta.totalDebugCompanion.catalog.CatalogFixtures;
import com.github.minecraft_ta.totalDebugCompanion.catalog.CatalogIndex;
import com.github.minecraft_ta.totalDebugCompanion.catalog.CurrentWorld;
import com.github.minecraft_ta.totalDebugCompanion.catalog.ListedPack;
import com.github.minecraft_ta.totalDebugCompanion.catalog.LevelDatFixture;
import com.github.minecraft_ta.totalDebugCompanion.game.GameLocation;
import com.github.minecraft_ta.totalDebugCompanion.game.GameLocations;
import com.github.minecraft_ta.totalDebugCompanion.game.GameState;
import com.github.minecraft_ta.totaldebug.protocol.execution.Fact;
import com.github.minecraft_ta.totaldebug.protocol.execution.FactSection;
import com.github.minecraft_ta.totaldebug.protocol.message.PackStackPayload;
import com.github.minecraft_ta.totaldebug.protocol.message.PlayingPayload;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class WorldPanelTest {
    @TempDir Path directory;

    @Test
    void aPackIsNamedByWhereItComesFrom() throws Exception {
        CatalogIndex index = new CatalogIndex(CatalogFixtures.catalog(CatalogFixtures.modJar(this.directory)));
        String mod = index.mod("testmod").orElseThrow().title();

        assertEquals(List.of("Tweaks", "World folder", ""), row("file/Tweaks", index));
        assertEquals(List.of("vanilla", "Minecraft", ""), row("vanilla", index));
        assertEquals(List.of("mod_data", "Every mod", ""), row("mod_data", index));
        assertEquals(List.of("extra", mod, "testmod"), row("mod/testmod:data/testmod/datapacks/extra", index),
                "a mod's optional pack opens the mod");
        assertEquals(List.of("mod/testmod,other", mod, "testmod"), row("mod/testmod,other", index),
                "a jar of several mods belongs to its first");
        assertEquals(List.of("testmod:generated", mod, "testmod"), row("testmod:generated", index), "a pack a mod adds in code");
        assertEquals(List.of("gone:generated", "gone", ""), row("gone:generated", index), "a namespace without a mod");
        assertEquals(List.of("extra", "testmod", ""), row("mod/testmod:data/testmod/datapacks/extra", null),
                "before the catalog is captured the mod is named by its id");
    }

    @Test
    void theOverviewSaysWhenARuleHoldsTheClockOrWeather() throws Exception {
        Map<String, Object> data = LevelDatFixture.world("Test");
        Path world = this.directory.resolve("world");
        LevelDatFixture.write(world, data);

        List<FactSection> sections = WorldPanel.sections(CurrentWorld.read(GameLocations.of(this.directory, false).read(), world));
        assertEquals(List.of("World", "Time and weather"), sections.stream().map(FactSection::title).toList());
        assertEquals("21, 77, -28", value(sections.getFirst(), "Spawn"));
        assertEquals("Allowed", value(sections.getFirst(), "Commands"));
        assertEquals("12:00, stopped", value(sections.getLast(), "Time"), "doDaylightCycle is false");
        assertEquals("Rain", value(sections.getLast(), "Weather"));
    }

    @Test
    void aResourcePackIsNamedByWhereItComesFromToo() {
        PacksPanel.Row faithful = PacksPanel.row(new ListedPack("file/Faithful.zip", ListedPack.State.DISABLED,
                this.directory.resolve("resourcepacks/Faithful.zip")), null, PacksPanel.Side.RESOURCES);
        assertEquals(List.of("Faithful.zip", "Resource packs folder"), List.of(faithful.name(), faithful.from()));
        assertEquals("Minecraft", PacksPanel.row(new ListedPack("programmer_art", ListedPack.State.ENABLED, null), null,
                PacksPanel.Side.RESOURCES).from());
        assertEquals("Every mod", PacksPanel.row(new ListedPack("mod_resources", ListedPack.State.ENABLED, null), null,
                PacksPanel.Side.RESOURCES).from());
        PacksPanel.Row programmerArt = PacksPanel.row(new ListedPack("programmer_art", ListedPack.State.ENABLED, null,
                "Programmer Art"), null, PacksPanel.Side.RESOURCES);
        assertEquals(List.of("Programmer Art", "Minecraft"), List.of(programmerArt.name(), programmerArt.from()),
                "the running game's title names it");
    }

    @Test
    void onAServerThePageShowsTheDatapacksItNamesOrWhyItCannot() {
        GameLocation location = GameLocations.of(this.directory, true);
        location.connected(message -> true);
        PlayingPayload.Multiplayer server = new PlayingPayload.Multiplayer("play.example.net", false, true);
        location.playing(server);
        GameState game = location.read();
        PackStackPayload named = new PackStackPayload(48, List.of(new PackStackPayload.Pack("vanilla", "Default", ""),
                new PackStackPayload.Pack("mod/testmod", "Test Mod", "", PackStackPayload.HIDDEN),
                new PackStackPayload.Pack("file/Arena", "Arena", "")), List.of(new PackStackPayload.Pack("file/Events", "Events", "")));

        WorldPanel.Loaded loaded = WorldPanel.readServer(game, server, named, "");
        assertEquals(new WorldPanel.ServerWorld("play.example.net", game.serverWorld(server)), loaded.server(),
                "changes go to the place the change record keeps the server's world");
        assertEquals(List.of("+file/Arena", "+vanilla", "-file/Events"), loaded.datapacks().stream()
                .map(pack -> (pack.state() == ListedPack.State.ENABLED ? "+" : "-") + pack.id()).toList(),
                "as the pack screen lists them, without the parts of the mods' pack; the files are the server's");
        assertNull(loaded.datapacks().getFirst().file());

        assertEquals("Waiting for the server play.example.net to name its world's datapacks.", WorldPanel.readServer(game, server, null, "").problem());
        assertEquals("You need operator permission on this server to change its world.",
                WorldPanel.readServer(game, server, null, "You need operator permission on this server to change its world").problem());
        assertEquals("The server vanilla.example.net does not have TotalDebug, which Companion needs to show its world.",
                WorldPanel.readServer(game, new PlayingPayload.Multiplayer("vanilla.example.net", false, false), null, "").problem());
    }

    private static List<String> row(String id, CatalogIndex index) {
        PacksPanel.Row row = PacksPanel.row(new ListedPack(id, ListedPack.State.ENABLED, null), index, PacksPanel.Side.DATA);
        return List.of(row.name(), row.from(), row.modId());
    }

    private static String value(FactSection section, String label) {
        return section.facts().stream().filter(fact -> fact.label().equals(label)).map(Fact::value).findFirst().orElseThrow();
    }
}
