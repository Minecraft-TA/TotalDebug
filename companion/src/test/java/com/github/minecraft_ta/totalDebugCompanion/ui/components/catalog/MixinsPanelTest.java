package com.github.minecraft_ta.totalDebugCompanion.ui.components.catalog;

import com.github.minecraft_ta.totalDebugCompanion.catalog.MixinSelector;
import com.github.minecraft_ta.totalDebugCompanion.catalog.MixinTarget;
import com.github.minecraft_ta.totalDebugCompanion.catalog.Mixins;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MixinsPanelTest {
    private static final String LEVEL = "net.minecraft.world.level.Level";
    private static final String SERVER_LEVEL = "net.minecraft.server.level.ServerLevel";
    private static final MixinTarget LEVEL_CLASS = new MixinTarget("net/minecraft/world/level/Level", List.of("value"), List.of(
            new MixinTarget.Method("tick", "()V", false),
            new MixinTarget.Method("tick", "(I)V", false),
            new MixinTarget.Method("explode", "(DDD)V", false),
            new MixinTarget.Method("renderSky", "()V", false),
            new MixinTarget.Method("renderSkyBox", "(I)V", false),
            new MixinTarget.Method("value", "()I", false),
            new MixinTarget.Method("create", "()V", true)));
    private static final MixinTarget SERVER_LEVEL_CLASS = new MixinTarget("net/minecraft/server/level/ServerLevel", List.of(), List.of(
            new MixinTarget.Method("tick", "()V", false),
            new MixinTarget.Method("save", "()V", false)));

    @Test
    void aMemberSeveralModsChangeIsSharedAndAnOverwriteAmongThemStandsOut() {
        List<MixinsPanel.Row> rows = rows(
                mixin("gears", Mixins.Side.BOTH, change("Inject", method("tick", "", 1)), change("Inject", method("explode", "", 1))),
                mixin("speed", Mixins.Side.BOTH, change("Overwrite", method("tick", "()V", 1))),
                mixin("speed", Mixins.Side.BOTH, change("Adds", new MixinSelector.Whole())));

        assertEquals(List.of("The class", "explode(DDD)V", "tick()V"), shown(rows),
                "the class first; tick named alone selects its first overload, which the overwrite names");
        MixinsPanel.Row tick = rows.get(2);
        assertTrue(tick.shared());
        assertTrue(tick.overwritten(), "one mod replaces what another injects into");
        assertEquals("Inject, Overwrite", tick.kinds());
        assertFalse(rows.get(1).shared());
    }

    @Test
    void aQuantifierDecidesHowManyOverloadsASelectorSelects() {
        List<MixinsPanel.Row> rows = rows(
                mixin("gears", Mixins.Side.BOTH, change("Inject", method("tick", "", Integer.MAX_VALUE))),
                mixin("speed", Mixins.Side.BOTH, change("Overwrite", method("tick", "(I)V", 1))),
                mixin("glow", Mixins.Side.BOTH, change("Inject", method("tick", "", 1))));
        assertEquals(List.of("tick()V", "tick(I)V"), shown(rows));
        assertEquals(List.of(List.of("gears", "glow"), List.of("gears", "speed")), rows.stream().map(row -> List.copyOf(row.mods())).toList(),
                "tick* selects both overloads, tick without a quantifier only the first");
        assertTrue(rows.get(1).overwritten());
        assertFalse(rows.get(0).overwritten(), "the overwrite is of the other overload");
    }

    @Test
    void aFieldAndAMethodOfOneNameStayApart() {
        List<MixinsPanel.Row> rows = rows(
                mixin("gears", Mixins.Side.BOTH, change("Accessor", new MixinSelector.Field("value"))),
                mixin("speed", Mixins.Side.BOTH, change("Overwrite", method("value", "()I", 1))),
                mixin("glow", Mixins.Side.BOTH, change("Inject", method("value", "", 1))));
        assertEquals(List.of("value", "value()I"), shown(rows), "the field first");
        assertFalse(rows.getFirst().shared(), "the field's accessor meets no method change");
        assertTrue(rows.get(1).overwritten(), "the injection naming the method alone meets the overwrite");
    }

    @Test
    void patternsAndEveryMethodMeetOnTheMethodsTheySelect() {
        List<MixinsPanel.Row> rows = rows(
                mixin("gears", Mixins.Side.BOTH, change("Inject", new MixinSelector.Matching("", "^render", ""))),
                mixin("speed", Mixins.Side.BOTH, change("Overwrite", method("renderSky", "()V", 1))),
                mixin("dusk", Mixins.Side.BOTH, change("Inject", method("", "", Integer.MAX_VALUE))),
                mixin("glow", Mixins.Side.BOTH, change("Inject", new MixinSelector.Matching("", "", "\\(I\\)V"))),
                mixin("dawn", Mixins.Side.BOTH, change("Inject", new MixinSelector.Matching("ServerLevel$", "", ""))));
        MixinsPanel.Row sky = row(rows, "renderSky()V");
        assertEquals(List.of("gears", "speed", "dusk"), List.copyOf(sky.mods()), "the pattern, the overwrite and every method");
        assertTrue(sky.overwritten());
        assertEquals(List.of("gears", "dusk", "glow"), List.copyOf(row(rows, "renderSkyBox(I)V").mods()), "a descriptor pattern too");
        assertTrue(row(rows, "tick()V").mods().contains("dusk"));
        assertFalse(row(rows, "tick()V").mods().contains("dawn"), "its owner pattern names another class");
        MixinsPanel.Row unmatched = row(rows, "owner=/ServerLevel$/");
        assertEquals(MixinsPanel.Kind.NOT_SELECTED, unmatched.kind());
        assertFalse(rows.stream().anyMatch(row -> row.member().startsWith("create")),
                "every method passes over a static one for a handler that is not static");
    }

    @Test
    void aSelectorThatSelectsNothingIsARowOfItsOwnThatMeetsNoOther() {
        MixinSelector overwrite = new MixinSelector.First(List.of(method("m_1234_", "()V", 1), method("tick", "()V", 1)));
        List<MixinsPanel.Row> rows = rows(
                mixin("gears", Mixins.Side.BOTH, change("Overwrite", overwrite), change("Inject", method("missing", "", 1))),
                mixin("speed", Mixins.Side.BOTH, change("Inject", method("missing", "", 1)), change("Inject", new MixinSelector.Dynamic("@Foo(tick)"))),
                new Mixins.Mixin("glow", "glow.mixins.json", "com.glow.mixin.M", List.of("com.other.Gone"), Mixins.Side.BOTH, 1000,
                        List.of(change("Inject", method("tick", "", 1)))));
        assertEquals(List.of("Gone#tick", "Level#tick()V", "Level#missing", "Level#@Foo(tick)"),
                rows.stream().map(row -> row.target().substring(row.target().lastIndexOf('.') + 1) + "#" + row.member()).toList(),
                "an overwrite takes its first alias the class has");
        assertEquals(MixinsPanel.Kind.NO_CLASS, rows.getFirst().kind());
        MixinsPanel.Row missing = rows.get(2);
        assertEquals(MixinsPanel.Kind.NOT_SELECTED, missing.kind());
        assertEquals(2, missing.mods().size());
        assertFalse(missing.shared(), "nothing is changed where nothing is selected");
        assertEquals(MixinsPanel.Kind.DYNAMIC, rows.get(3).kind());
    }

    @Test
    void changesOnSidesThatNeverMeetAreNotShared() {
        List<MixinsPanel.Row> rows = rows(
                mixin("gears", Mixins.Side.CLIENT, change("Overwrite", method("tick", "()V", 1))),
                mixin("speed", Mixins.Side.SERVER, change("Inject", method("tick", "", 1))));
        assertFalse(rows.getFirst().shared(), "a client-only and a server-only change never apply together");
        assertFalse(rows.getFirst().overwritten());
        assertEquals(LEVEL + "#tick()V", rows.getFirst().reference());
    }

    @Test
    void aSelectorNamingItsOwnerChangesOnlyThatTarget() {
        List<MixinsPanel.Row> rows = rows(new Mixins.Mixin("gears", "gears.mixins.json", "com.gears.mixin.Both",
                List.of(LEVEL, SERVER_LEVEL), Mixins.Side.BOTH, 1000,
                List.of(new Mixins.Change("Inject", method("tick", "", 1), LEVEL), change("Inject", method("save", "", 1)))));
        assertEquals(List.of("Level#tick()V", "Level#save", "ServerLevel#save()V"), rows.stream()
                .map(row -> row.target().substring(row.target().lastIndexOf('.') + 1) + "#" + row.member()).toList(),
                "Level has no save, so its selector selects nothing there");
    }

    @Test
    void aTargetThatCannotBeReadIsNamedAndLeftOut() {
        List<String> problems = new ArrayList<>();
        List<MixinsPanel.Row> rows = MixinsPanel.rows(List.of(mixin("gears", Mixins.Side.BOTH, change("Inject", method("tick", "", 1)))),
                binaryName -> {
                    throw new IOException("the index is closed");
                }, problems);
        assertTrue(rows.isEmpty());
        assertEquals(List.of(LEVEL + ": the index is closed"), problems);
    }

    private static List<MixinsPanel.Row> rows(Mixins.Mixin... mixins) {
        Map<String, MixinTarget> targets = Map.of(LEVEL, LEVEL_CLASS, SERVER_LEVEL, SERVER_LEVEL_CLASS);
        List<String> problems = new ArrayList<>();
        List<MixinsPanel.Row> rows = MixinsPanel.rows(List.of(mixins), binaryName -> Optional.ofNullable(targets.get(binaryName)), problems);
        assertEquals(List.of(), problems);
        return rows;
    }

    private static MixinsPanel.Row row(List<MixinsPanel.Row> rows, String member) {
        return rows.stream().filter(row -> row.member().equals(member)).findFirst().orElseThrow(() -> new AssertionError(member + " in " + shown(rows)));
    }

    private static Mixins.Mixin mixin(String mod, Mixins.Side side, Mixins.Change... changes) {
        return new Mixins.Mixin(mod, mod + ".mixins.json", "com." + mod + ".mixin.M", List.of(LEVEL), side, 1000, List.of(changes));
    }

    private static Mixins.Change change(String kind, MixinSelector selector) {
        return new Mixins.Change(kind, selector);
    }

    private static MixinSelector method(String name, String descriptor, int limit) {
        return new MixinSelector.Method(name, descriptor, limit);
    }

    private static List<String> shown(List<MixinsPanel.Row> rows) {
        return rows.stream().map(MixinsPanel.Row::member).toList();
    }
}
