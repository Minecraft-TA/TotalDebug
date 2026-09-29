package com.github.minecraft_ta.totaldebug.client.companion;

import com.github.minecraft_ta.totaldebug.protocol.message.ChangePayload;
import com.github.minecraft_ta.totaldebug.protocol.message.ChangeResultPayload;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The values Companion changes in the running game, one handler for each category (see {@code docs/CHANGE_PIPELINE.md}).
 * A change is made whole or not at all: before any value is set, every edit's target must still hold the value the edit
 * expects, and every new value must be one its category can set. Client thread only.
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

        /** Runs once after a change set values of this category, such as to save them. */
        void finish();
    }

    private final Map<String, Category> categories;

    public ClientChanges(Map<String, Category> categories) {
        this.categories = Map.copyOf(categories);
    }

    /** Makes the change and answers with each edit's value before and now, or why nothing was changed. */
    public ChangeResultPayload apply(ChangePayload change) {
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
                if (!current.equals(edit.expected())) {
                    throw new IllegalArgumentException(edit.target() + " changed in the game since Companion read it");
                }
                category.check(edit.target(), edit.value());
                handlers.add(category);
                before.add(current);
            }
        } catch (IllegalArgumentException refused) {
            return ChangeResultPayload.refused(change.requestId(), refused.getMessage());
        }
        for (int index = 0; index < handlers.size(); index++) {
            ChangePayload.Edit edit = change.edits().get(index);
            handlers.get(index).set(edit.target(), edit.value());
        }
        new LinkedHashSet<>(handlers).forEach(Category::finish);
        List<ChangeResultPayload.Applied> applied = new ArrayList<>();
        for (int index = 0; index < handlers.size(); index++) {
            applied.add(new ChangeResultPayload.Applied(before.get(index), handlers.get(index).read(change.edits().get(index).target())));
        }
        return new ChangeResultPayload(change.requestId(), applied, "");
    }
}
