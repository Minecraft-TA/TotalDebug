package com.github.minecraft_ta.totalDebugCompanion.pack;

import com.github.minecraft_ta.totalDebugCompanion.catalog.CatalogIndex;
import com.github.minecraft_ta.totalDebugCompanion.catalog.PackFolders;
import com.github.minecraft_ta.totalDebugCompanion.change.ChangeLabels;
import com.github.minecraft_ta.totalDebugCompanion.navigation.NavigationTarget;
import com.github.minecraft_ta.totalDebugCompanion.storage.ChangeRecord;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Resources on the Changes page: each file by its path, the pack it was saved into, and whether the pack held a copy
 * before; one changed outside Companion since says so.
 */
public final class ResourceLabels implements ChangeLabels {
    private final ResourceEdits edits;

    public ResourceLabels(ResourceEdits edits) {
        this.edits = Objects.requireNonNull(edits, "edits");
    }

    @Override
    public String tab() {
        return "Resources";
    }

    @Override
    public boolean covers(ChangeRecord.Target target) {
        return target instanceof ChangeRecord.Resource;
    }

    @Override
    public Rows rows(List<ChangeRecord.Change> changes, CatalogIndex index) {
        List<Row> rows = new ArrayList<>();
        for (ChangeRecord.Change change : changes) {
            ChangeRecord.Resource target = (ChangeRecord.Resource) change.target();
            boolean held = this.edits.holds(change);
            if (this.edits.pipeline().record().change(target) == null) continue;
            rows.add(new Row(change, target.path().substring(target.path().indexOf('/') + 1), PackFolders.label(target.location()),
                    "Edited", change.original().isEmpty() ? "Not in the pack" : "The pack's earlier copy",
                    held ? "" : "Changed outside Companion since", new NavigationTarget.LocalFile(target.location().resolve(target.path()))));
        }
        rows.sort(Comparator.comparing(Row::name));
        return new Rows(rows, List.of());
    }

    @Override
    public CompletableFuture<String> revert(List<ChangeRecord.Change> changes, CatalogIndex index) {
        List<CompletableFuture<ResourceEdits.Saved>> requests = this.edits.revert(changes);
        return CompletableFuture.allOf(requests.toArray(CompletableFuture[]::new)).handle((ignored, failure) -> {
            List<String> failed = new ArrayList<>();
            for (int position = 0; position < requests.size(); position++) {
                CompletableFuture<ResourceEdits.Saved> request = requests.get(position);
                ChangeRecord.Resource target = (ChangeRecord.Resource) changes.get(position).target();
                String name = target.path().substring(target.path().indexOf('/') + 1);
                if (request.isCompletedExceptionally()) {
                    Throwable cause = request.exceptionNow();
                    failed.add(name + ": " + (cause.getCause() == null ? cause.getMessage() : cause.getCause().getMessage()));
                } else if (!request.join().reloadFailure().isEmpty()) {
                    failed.add(name + ": " + request.join().reloadFailure());
                } else if (!request.join().unused().isEmpty()) {
                    failed.add(name + ": " + request.join().unused());
                }
            }
            return failed.isEmpty() ? "" : "Not reverted or not reloaded: " + String.join("; ", failed);
        });
    }
}
