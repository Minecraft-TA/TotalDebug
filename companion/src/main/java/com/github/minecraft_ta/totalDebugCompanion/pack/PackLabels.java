package com.github.minecraft_ta.totalDebugCompanion.pack;

import com.github.minecraft_ta.totalDebugCompanion.catalog.CatalogIndex;
import com.github.minecraft_ta.totalDebugCompanion.change.ChangeLabels;
import com.github.minecraft_ta.totalDebugCompanion.game.GameState;
import com.github.minecraft_ta.totalDebugCompanion.navigation.NavigationTarget;
import com.github.minecraft_ta.totalDebugCompanion.navigation.ResourcesTab;
import com.github.minecraft_ta.totalDebugCompanion.navigation.WorldTab;
import com.github.minecraft_ta.totalDebugCompanion.storage.ChangeRecord;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Pack selections on the Changes page: the resource packs, or a world's datapacks, with the packs enabled now and before,
 * the highest first as the pack screen lists them; one changed outside Companion since says so.
 */
public final class PackLabels implements ChangeLabels {
    private final PackSelections selections;

    public PackLabels(PackSelections selections) {
        this.selections = Objects.requireNonNull(selections, "selections");
    }

    @Override
    public String tab() {
        return "Packs";
    }

    @Override
    public boolean covers(ChangeRecord.Target target) {
        return target instanceof ChangeRecord.PackSelection;
    }

    @Override
    public Rows rows(List<ChangeRecord.Change> changes, CatalogIndex index) {
        List<Row> rows = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        GameState game = this.selections.location().read();
        for (ChangeRecord.Change change : changes) {
            ChangeRecord.PackSelection target = (ChangeRecord.PackSelection) change.target();
            boolean resources = target.side() == ChangeRecord.PackSide.RESOURCES;
            // What the game or the file enables now, which is Companion's selection unless it changed outside it since.
            List<String> now;
            try {
                now = this.selections.current(game, target);
            } catch (IOException | RuntimeException unreadable) {
                problems.add(name(change) + " could not be read: " + unreadable.getMessage());
                now = PackSelections.parse(change.current());
            }
            this.selections.record().observed(target, PackSelections.json(now), String::equals);
            if (PackSelections.json(now).equals(change.original())) continue;
            // The World page shows the world the game plays, a server's before a world played last, so only that one opens.
            NavigationTarget opens = resources ? new NavigationTarget.PackResources(ResourcesTab.PACKS, "")
                    : game.plays(target.location()) ? new NavigationTarget.World(WorldTab.DATAPACKS) : null;
            rows.add(new Row(change, name(change), "", highestFirst(now), highestFirst(PackSelections.parse(change.original())),
                    PackSelections.json(now).equals(change.current()) ? "" : "Changed outside Companion since", opens));
        }
        return new Rows(rows, problems);
    }

    @Override
    public CompletableFuture<String> revert(List<ChangeRecord.Change> changes, CatalogIndex index) {
        return ChangeLabels.each(changes, change -> this.selections.revert(change).thenApply(applied -> ""), PackLabels::name);
    }

    private static String name(ChangeRecord.Change change) {
        ChangeRecord.PackSelection target = (ChangeRecord.PackSelection) change.target();
        return target.side() == ChangeRecord.PackSide.RESOURCES ? "Resource packs" : "Datapacks of " + GameState.worldName(target.location());
    }

    /** Pack ids, lowest first, as the pack screen lists them: the highest first. */
    private static String highestFirst(List<String> ids) {
        return ids.isEmpty() ? "None" : String.join(", ", ids.reversed());
    }
}
