package com.github.minecraft_ta.totaldebug.client.companion;

import com.github.minecraft_ta.totaldebug.protocol.message.ChangePayload;
import com.github.minecraft_ta.totaldebug.protocol.message.ChangeResultPayload;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;

/**
 * The values Companion changes in the running game, one handler for each category (see {@code docs/CHANGE_PIPELINE.md}).
 * A change is made whole or not at all: before any value is set, every edit's target must still hold the value the edit
 * expects, or already the new one, unless the edit replaces whatever it holds, and every new value must be one its
 * category can set. The answer waits for what makes the change take effect, such as the reload after a pack selection.
 * Client thread only.
 */
public final class ClientChanges {
    /** How the game reads and sets the values of one category, in the text form Companion writes them. */
    public interface Category {
        /** The value {@code target} has now; fails with {@link IllegalArgumentException} for a target the game lacks. */
        String read(String target);

        /** Fails with {@link IllegalArgumentException}, saying why, when the game cannot set {@code value}. */
        void check(String target, String value);

        /** Sets a value {@link #check} accepted. */
        void set(String target, String value);

        /**
         * Runs once after a change set values of this category, such as to save them, and completes once the change takes
         * effect, such as after the reload a pack selection needs; fails when that failed.
         */
        CompletableFuture<Void> finish();

        /** {@code target} as the user names it, in a refusal. */
        default String name(String target) {
            return target;
        }
    }

    private final Map<String, Category> categories;
    private final Executor client;

    /** {@code client} runs work on the client thread, where a change reads its values once it took effect. */
    public ClientChanges(Map<String, Category> categories, Executor client) {
        this.categories = Map.copyOf(categories);
        this.client = Objects.requireNonNull(client, "client");
    }

    /**
     * Makes the change and answers, once it took effect, with each edit's value before and now; or why nothing was
     * changed; or both the values and why the change did not take effect.
     */
    public CompletableFuture<ChangeResultPayload> apply(ChangePayload change) {
        CompletableFuture<ChangeResultPayload> answer;
        try {
            answer = make(change);
        } catch (RuntimeException failure) {
            answer = CompletableFuture.failedFuture(failure);
        }
        // Whatever fails in the game, Companion hears of it rather than waiting.
        return answer.exceptionally(failure -> ChangeResultPayload.refused(change.requestId(),
                "The game failed while making the change: " + message(failure)));
    }

    private CompletableFuture<ChangeResultPayload> make(ChangePayload change) {
        List<Category> handlers = new ArrayList<>();
        List<String> before = new ArrayList<>();
        Set<String> targets = new HashSet<>();
        try {
            for (ChangePayload.Edit edit : change.edits()) {
                Category category = this.categories.get(edit.category());
                if (category == null) throw new IllegalArgumentException("The game cannot change " + edit.category());
                if (!targets.add(edit.category() + "\n" + edit.target())) {
                    throw new IllegalArgumentException(edit.target() + " is changed twice in one change");
                }
                String current = category.read(edit.target());
                // A target already holding the new value, such as one reverted in the game meanwhile, is only answered.
                if (edit.expected() != null && !current.equals(edit.expected()) && !current.equals(edit.value())) {
                    throw new IllegalArgumentException(category.name(edit.target()) + " changed in the game since Companion read it");
                }
                category.check(edit.target(), edit.value());
                handlers.add(category);
                before.add(current);
            }
        } catch (IllegalArgumentException refused) {
            return CompletableFuture.completedFuture(ChangeResultPayload.refused(change.requestId(), refused.getMessage()));
        }
        for (int index = 0; index < handlers.size(); index++) {
            ChangePayload.Edit edit = change.edits().get(index);
            handlers.get(index).set(edit.target(), edit.value());
        }
        CompletableFuture<?>[] finished = new LinkedHashSet<>(handlers).stream().map(ClientChanges::finish).toArray(CompletableFuture[]::new);
        return CompletableFuture.allOf(finished).handleAsync((ignored, failure) -> {
            List<ChangeResultPayload.Applied> applied = new ArrayList<>();
            for (int index = 0; index < handlers.size(); index++) {
                applied.add(new ChangeResultPayload.Applied(before.get(index), handlers.get(index).read(change.edits().get(index).target())));
            }
            return new ChangeResultPayload(change.requestId(), applied, failure == null ? "" : message(failure));
        }, this.client);
    }

    private static CompletableFuture<Void> finish(Category category) {
        try {
            return category.finish();
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    private static String message(Throwable failure) {
        Throwable cause = failure;
        while (cause.getCause() != null && (cause.getMessage() == null || cause instanceof CompletionException)) {
            cause = cause.getCause();
        }
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }
}
