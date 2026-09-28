package com.github.minecraft_ta.totalDebugCompanion.ui.components.catalog;

import com.github.minecraft_ta.totalDebugCompanion.catalog.Mixins;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MixinsPanelTest {
    private static final String LEVEL = "net.minecraft.world.level.Level";

    @Test
    void aMemberSeveralModsChangeIsSharedAndAnOverwriteAmongThemStandsOut() {
        List<MixinsPanel.Row> rows = MixinsPanel.rows(List.of(
                new Mixins.Mixin("gears", "gears.mixins.json", "com.gears.mixin.LevelMixin", List.of(LEVEL), Mixins.Side.BOTH, 1000,
                        List.of(new Mixins.Change("Inject", "tick"), new Mixins.Change("Inject", "explode"))),
                new Mixins.Mixin("speed", "speed.mixins.json", "com.speed.mixin.LevelMixin", List.of(LEVEL), Mixins.Side.BOTH, 1000,
                        List.of(new Mixins.Change("Overwrite", "tick"))),
                new Mixins.Mixin("speed", "speed.mixins.json", "com.speed.mixin.LevelAccess", List.of(LEVEL), Mixins.Side.BOTH, 1000,
                        List.of(new Mixins.Change("Adds", "")))));

        assertEquals(List.of("", "explode", "tick"), rows.stream().map(MixinsPanel.Row::member).toList(), "by member, the class first");
        MixinsPanel.Row tick = rows.get(2);
        assertTrue(tick.shared());
        assertTrue(tick.overwritten(), "one mod replaces what another injects into");
        assertEquals("Inject, Overwrite", tick.kinds());
        assertFalse(rows.get(1).shared());
    }

    @Test
    void aSelectorNamingItsOwnerChangesOnlyThatTarget() {
        List<MixinsPanel.Row> rows = MixinsPanel.rows(List.of(new Mixins.Mixin("gears", "gears.mixins.json", "com.gears.mixin.Both",
                List.of(LEVEL, "net.minecraft.server.level.ServerLevel"), Mixins.Side.BOTH, 1000,
                List.of(new Mixins.Change("Inject", "tick", LEVEL), new Mixins.Change("Inject", "save")))));
        assertEquals(List.of("Level#save", "Level#tick", "ServerLevel#save"), rows.stream()
                .map(row -> row.target().substring(row.target().lastIndexOf('.') + 1) + "#" + row.member()).toList());
    }
}
