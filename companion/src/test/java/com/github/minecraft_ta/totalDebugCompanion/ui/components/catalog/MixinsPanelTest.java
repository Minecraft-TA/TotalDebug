package com.github.minecraft_ta.totalDebugCompanion.ui.components.catalog;

import com.github.minecraft_ta.totalDebugCompanion.catalog.MixinMember;
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
                mixin("gears", Mixins.Side.BOTH, change("Inject", method("tick", "")), change("Inject", method("explode", ""))),
                mixin("speed", Mixins.Side.BOTH, change("Overwrite", method("tick", "()V"))),
                mixin("speed", Mixins.Side.BOTH, new Mixins.Change("Adds", new MixinMember.Whole()))));

        assertEquals(List.of("The class", "explode", "tick()V"), shown(rows),
                "the class first; tick named alone joins the overload the overwrite names");
        MixinsPanel.Row tick = rows.get(2);
        assertTrue(tick.shared());
        assertTrue(tick.overwritten(), "one mod replaces what another injects into");
        assertEquals("Inject, Overwrite", tick.kinds());
        assertFalse(rows.get(1).shared());
    }

    @Test
    void overloadsPickedByDescriptorAreRowsOfTheirOwn() {
        List<MixinsPanel.Row> rows = MixinsPanel.rows(List.of(
                mixin("gears", Mixins.Side.BOTH, change("Inject", method("tick", "()V"))),
                mixin("speed", Mixins.Side.BOTH, change("Overwrite", method("tick", "(I)V"))),
                mixin("glow", Mixins.Side.BOTH, change("Inject", method("tick", "")))));
        assertEquals(List.of("tick()V", "tick(I)V"), shown(rows));
        assertEquals(List.of(2, 2), rows.stream().map(row -> row.mods().size()).toList(),
                "a change naming the member alone reaches every overload; the two picked ones do not meet");
        assertTrue(rows.get(1).overwritten());
        assertFalse(rows.get(0).overwritten(), "the overwrite is of the other overload");
    }

    @Test
    void aFieldAndAMethodOfOneNameStayApart() {
        List<MixinsPanel.Row> rows = MixinsPanel.rows(List.of(
                mixin("gears", Mixins.Side.BOTH, change("Accessor", new MixinMember.Field("value"))),
                mixin("speed", Mixins.Side.BOTH, change("Overwrite", method("value", "()I"))),
                mixin("glow", Mixins.Side.BOTH, change("Inject", method("value", "")))));
        assertEquals(List.of("value", "value()I"), shown(rows), "the field first");
        assertFalse(rows.getFirst().shared(), "the field's accessor meets no method change");
        assertTrue(rows.get(1).overwritten(), "the injection naming the method alone meets the overwrite");
    }

    @Test
    void aWildcardJoinsTheMethodsItMatchesOrStandsAlone() {
        List<MixinsPanel.Row> rows = MixinsPanel.rows(List.of(
                mixin("gears", Mixins.Side.BOTH, change("Inject", method("render*", ""))),
                mixin("speed", Mixins.Side.BOTH, change("Overwrite", method("renderSky", "()V"))),
                mixin("glow", Mixins.Side.BOTH, change("Inject", method("tick*", "")))));
        assertEquals(List.of("renderSky()V", "tick*"), shown(rows), "render* joins renderSky; tick* matches nothing named");
        assertTrue(rows.getFirst().overwritten());
        assertTrue(method("*Sky*", "").reaches(method("renderSky", "()V")));
        assertTrue(method("*", "").reaches(method("tick", "()V")), "every method");
        assertFalse(method("render*Sky", "").reaches(method("renderSkyBox", "()V")));
        assertFalse(method("renderSky*", "()V").reaches(method("renderSkyBox", "(I)V")), "another overload");
    }

    @Test
    void changesOnSidesThatNeverMeetAreNotShared() {
        List<MixinsPanel.Row> rows = MixinsPanel.rows(List.of(
                mixin("gears", Mixins.Side.CLIENT, change("Overwrite", method("tick", ""))),
                mixin("speed", Mixins.Side.SERVER, change("Inject", method("tick", "")))));
        assertFalse(rows.getFirst().shared(), "a client-only and a server-only change never apply together");
        assertFalse(rows.getFirst().overwritten());
        assertEquals(LEVEL + "#tick", rows.getFirst().reference());
    }

    @Test
    void aSelectorNamingItsOwnerChangesOnlyThatTarget() {
        List<MixinsPanel.Row> rows = MixinsPanel.rows(List.of(new Mixins.Mixin("gears", "gears.mixins.json", "com.gears.mixin.Both",
                List.of(LEVEL, "net.minecraft.server.level.ServerLevel"), Mixins.Side.BOTH, 1000,
                List.of(new Mixins.Change("Inject", method("tick", ""), LEVEL), change("Inject", method("save", ""))))));
        assertEquals(List.of("Level#save", "Level#tick", "ServerLevel#save"), rows.stream()
                .map(row -> row.target().substring(row.target().lastIndexOf('.') + 1) + "#" + row.member().shown()).toList());
    }

    private static Mixins.Mixin mixin(String mod, Mixins.Side side, Mixins.Change... changes) {
        return new Mixins.Mixin(mod, mod + ".mixins.json", "com." + mod + ".mixin.M", List.of(LEVEL), side, 1000, List.of(changes));
    }

    private static Mixins.Change change(String kind, MixinMember member) {
        return new Mixins.Change(kind, member);
    }

    private static MixinMember method(String name, String descriptor) {
        return new MixinMember.Method(name, descriptor);
    }

    private static List<String> shown(List<MixinsPanel.Row> rows) {
        return rows.stream().map(row -> row.member().shown()).toList();
    }
}
