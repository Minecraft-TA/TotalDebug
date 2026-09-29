package com.github.minecraft_ta.totaldebug.client.companion;

import com.github.minecraft_ta.totaldebug.protocol.message.ChangePayload;
import com.github.minecraft_ta.totaldebug.protocol.message.ChangeResultPayload;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ClientChangesTest {
    /** Keys by name; a value starting with {@code bad} cannot be set. */
    private static final class Keys implements ClientChanges.Category {
        final Map<String, String> values = new HashMap<>(Map.of("key.jump", "space", "key.sneak", "shift"));
        int saves;
        /** What makes a change take effect, as a pack selection's reload. */
        CompletableFuture<Void> taking = CompletableFuture.completedFuture(null);

        @Override public String read(String target) {
            String value = this.values.get(target);
            if (value == null) throw new IllegalArgumentException(target + " is not a key binding of this game");
            if (value.equals("gone")) throw new IllegalStateException("gone cannot be read back");
            return value;
        }

        @Override public void check(String target, String value) {
            if (value.startsWith("bad")) throw new IllegalArgumentException(value + " is not a key of this game");
        }

        @Override public void set(String target, String value) {
            if (value.equals("crash")) throw new IllegalStateException("the key table is locked");
            this.values.put(target, value);
        }

        @Override public CompletableFuture<Void> finish() {
            this.saves++;
            return this.taking;
        }

        @Override public String name(String target) {
            return target.equals("key.sneak") ? "Sneak" : target;
        }
    }

    private final Keys keys = new Keys();
    private final ClientChanges changes = new ClientChanges(Map.of("keyBinding", this.keys), Runnable::run);

    @Test
    void aChangeSetsEveryValueAndSavesOnce() {
        ChangeResultPayload result = apply(edit("key.jump", "space", "g"), edit("key.sneak", "shift", "space"));

        assertEquals(new ChangeResultPayload(1, List.of(new ChangeResultPayload.Applied("space", "g"),
                new ChangeResultPayload.Applied("shift", "space")), ""), result);
        assertEquals(Map.of("key.jump", "g", "key.sneak", "space"), this.keys.values);
        assertEquals(1, this.keys.saves);
    }

    @Test
    void oneEditTheGameRefusesKeepsTheWholeChangeOut() {
        assertRefused("key.jump changed in the game since Companion read it",
                apply(edit("key.sneak", "shift", "g"), edit("key.jump", "g", "h")));
        assertRefused("bad.key is not a key of this game", apply(edit("key.jump", "space", "g"), edit("key.sneak", "shift", "bad.key")));
        assertRefused("key.nothing is not a key binding of this game", apply(edit("key.jump", "space", "g"), edit("key.nothing", "", "g")));
        assertRefused("key.jump is changed twice in one change", apply(edit("key.jump", "space", "g"), edit("key.jump", "space", "h")));
        assertRefused("The game cannot change gameRule",
                this.changes.apply(new ChangePayload(1, List.of(new ChangePayload.Edit("gameRule", "doDaylightCycle", "true", "false")))).join());

        assertEquals(Map.of("key.jump", "space", "key.sneak", "shift"), this.keys.values, "nothing was set");
        assertEquals(0, this.keys.saves);
    }

    @Test
    void aTargetAlreadyHoldingItsNewValueIsAnsweredAsItIs() {
        ChangeResultPayload result = apply(edit("key.jump", "g", "space"));

        assertEquals(new ChangeResultPayload(1, List.of(new ChangeResultPayload.Applied("space", "space")), ""), result,
                "such as a revert of a value the player put back in the game");
        assertRefused("Sneak changed in the game since Companion read it", apply(edit("key.sneak", "g", "h")));
        assertEquals(new ChangeResultPayload(1, List.of(new ChangeResultPayload.Applied("shift", "h")), ""), apply(edit("key.sneak", null, "h")),
                "an edit made against no value replaces whatever the target holds");
    }

    @Test
    void theAnswerWaitsForTheChangeToTakeEffectAndNamesWhyItDidNot() {
        this.keys.taking = new CompletableFuture<>();
        CompletableFuture<ChangeResultPayload> answer = this.changes.apply(new ChangePayload(1, List.of(edit("key.jump", "space", "g"))));
        assertFalse(answer.isDone(), "a pack selection is answered once the game reloaded");

        this.keys.values.put("key.jump", "space");
        this.keys.taking.completeExceptionally(new IllegalStateException("The game could not load the resources"));
        assertEquals(new ChangeResultPayload(1, List.of(new ChangeResultPayload.Applied("space", "space")),
                "The game could not load the resources"), answer.join(), "the values it holds after the failure, and why");
    }

    @Test
    void whateverFailsInTheGameIsAnswered() {
        assertRefused("The game failed while making the change: the key table is locked", apply(edit("key.jump", "space", "crash")));

        assertRefused("The game failed while making the change: gone cannot be read back",
                apply(edit("key.jump", "space", "gone")));
    }

    private void assertRefused(String reason, ChangeResultPayload result) {
        assertEquals(ChangeResultPayload.refused(1, reason), result);
    }

    private ChangeResultPayload apply(ChangePayload.Edit... edits) {
        return this.changes.apply(new ChangePayload(1, List.of(edits))).join();
    }

    private static ChangePayload.Edit edit(String target, String expected, String value) {
        return new ChangePayload.Edit("keyBinding", target, expected, value);
    }
}
