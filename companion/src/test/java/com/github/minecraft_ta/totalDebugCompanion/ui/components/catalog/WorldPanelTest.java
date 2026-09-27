package com.github.minecraft_ta.totalDebugCompanion.ui.components.catalog;

import com.github.minecraft_ta.totalDebugCompanion.catalog.CatalogFixtures;
import com.github.minecraft_ta.totalDebugCompanion.catalog.CatalogIndex;
import com.github.minecraft_ta.totalDebugCompanion.catalog.CurrentWorld;
import com.github.minecraft_ta.totalDebugCompanion.catalog.ListedPack;
import com.github.minecraft_ta.totalDebugCompanion.catalog.LevelDatFixture;
import com.github.minecraft_ta.totaldebug.protocol.execution.Fact;
import com.github.minecraft_ta.totaldebug.protocol.execution.FactSection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

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

        List<FactSection> sections = WorldPanel.sections(CurrentWorld.read(world));
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

    private static List<String> row(String id, CatalogIndex index) {
        PacksPanel.Row row = PacksPanel.row(new ListedPack(id, ListedPack.State.ENABLED, null), index, PacksPanel.Side.DATA);
        return List.of(row.name(), row.from(), row.modId());
    }

    private static String value(FactSection section, String label) {
        return section.facts().stream().filter(fact -> fact.label().equals(label)).map(Fact::value).findFirst().orElseThrow();
    }
}
