package com.github.minecraft_ta.totalDebugCompanion.change;

import com.github.minecraft_ta.totalDebugCompanion.catalog.CatalogIndex;
import com.github.minecraft_ta.totalDebugCompanion.navigation.NavigationTarget;
import com.github.minecraft_ta.totalDebugCompanion.storage.ChangeRecord;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Function;

/**
 * How the Changes page lists and reverts one category's recorded changes without knowing the category (see
 * {@code docs/CHANGE_PIPELINE.md}): the category names its tab and its rows as the user reads them, and reverts its
 * changes through the pipeline.
 */
public interface ChangeLabels {
    /**
     * A recorded change as the page lists it: its name and where it is, its value now and before in the category's
     * words, a {@code notice} such as that it changed outside Companion since, or empty, and what opening it shows, or
     * null.
     */
    record Row(ChangeRecord.Change change, String name, String where, String now, String before, String notice,
               NavigationTarget opens) {
        public Row {
            Objects.requireNonNull(change, "change");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(where, "where");
            Objects.requireNonNull(now, "now");
            Objects.requireNonNull(before, "before");
            Objects.requireNonNull(notice, "notice");
        }

        public Instant changed() {
            return this.change.lastChanged();
        }
    }

    /** The rows of a category, with what could not be read of its changes. */
    record Rows(List<Row> rows, List<String> problems) {
        public Rows {
            rows = List.copyOf(rows);
            problems = List.copyOf(problems);
        }
    }

    /** The tab the category's changes are listed in, such as "Key bindings". */
    String tab();

    /** Whether a recorded change is one of this category's. */
    boolean covers(ChangeRecord.Target target);

    /**
     * The rows of the category's recorded {@code changes}, reading what their targets hold now; a target that holds its
     * original value again leaves the record and the list. {@code index} names mods and settings, or is null before the
     * catalog is captured. Blocking.
     */
    Rows rows(List<ChangeRecord.Change> changes, CatalogIndex index);

    /**
     * Puts back what each of {@code changes} changed, through the pipeline; one changed outside Companion since is left
     * alone. Completes with why some were not reverted, or empty.
     */
    CompletableFuture<String> revert(List<ChangeRecord.Change> changes, CatalogIndex index);

    /**
     * Reverts {@code changes} one after another with {@code revert}, each completing with why it was not, or empty;
     * completes with those reasons after "Not reverted: ", named by {@code name}, or empty.
     */
    static CompletableFuture<String> each(List<ChangeRecord.Change> changes, Function<ChangeRecord.Change, CompletableFuture<String>> revert,
                                          Function<ChangeRecord.Change, String> name) {
        List<String> failed = new ArrayList<>();
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (ChangeRecord.Change change : changes) {
            chain = chain.thenCompose(ignored -> revert.apply(change).handle((reason, failure) -> {
                String why = failure != null ? message(failure) : reason;
                if (!why.isEmpty()) {
                    synchronized (failed) {
                        failed.add(name.apply(change) + ": " + why);
                    }
                }
                return null;
            }));
        }
        return chain.thenApply(ignored -> failed.isEmpty() ? "" : "Not reverted: " + String.join("; ", failed));
    }

    private static String message(Throwable failure) {
        Throwable cause = failure;
        while (cause.getCause() != null && (cause.getMessage() == null || cause instanceof CompletionException)) {
            cause = cause.getCause();
        }
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }
}
