package com.github.minecraft_ta.totalDebugCompanion.pack;

import com.github.minecraft_ta.totalDebugCompanion.catalog.CatalogIndex;
import com.github.minecraft_ta.totalDebugCompanion.change.ChangeLabels;
import com.github.minecraft_ta.totalDebugCompanion.game.GameState;
import com.github.minecraft_ta.totalDebugCompanion.navigation.NavigationTarget;
import com.github.minecraft_ta.totalDebugCompanion.navigation.ResourcesTab;
import com.github.minecraft_ta.totalDebugCompanion.navigation.WorldTab;
import com.github.minecraft_ta.totalDebugCompanion.storage.ChangeRecord;

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
        for (ChangeRecord.Change change : changes) {
            ChangeRecord.PackSelection target = (ChangeRecord.PackSelection) change.target();
            boolean resources = target.side() == ChangeRecord.PackSide.RESOURCES;
            rows.add(new Row(change, name(change), "", highestFirst(PackSelections.parse(change.current())),
                    highestFirst(PackSelections.parse(change.original())),
                    this.selections.holds(change) ? "" : "Changed outside Companion since",
                    resources ? new NavigationTarget.PackResources(ResourcesTab.PACKS, "") : new NavigationTarget.World(WorldTab.DATAPACKS)));
        }
        return new Rows(rows, List.of());
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
