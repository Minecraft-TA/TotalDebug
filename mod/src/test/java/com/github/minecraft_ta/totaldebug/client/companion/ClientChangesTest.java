package com.github.minecraft_ta.totaldebug.client.companion;

import com.github.minecraft_ta.totaldebug.protocol.message.ChangePayload;
import com.github.minecraft_ta.totaldebug.protocol.message.ChangeResultPayload;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ClientChangesTest {
    /** Keys by name; a value starting with {@code bad} cannot be set. */
    private static final class Keys implements ClientChanges.Category {
        final Map<String, String> values = new HashMap<>(Map.of("key.jump", "space", "key.sneak", "shift"));
        int saves;

        @Override public String read(String target) {
            String value = this.values.get(target);
            if (value == null) throw new IllegalArgumentException(target + " is not a key binding of this game");
            return value;
        }

        @Override public void check(String target, String value) {
            if (value.startsWith("bad")) throw new IllegalArgumentException(value + " is not a key of this game");
        }

        @Override public void set(String target, String value) {
            this.values.put(target, value);
        }

        @Override public void finish() {
            this.saves++;
        }

        @Override public String name(String target) {
            return target.equals("key.sneak") ? "Sneak" : target;
        }
    }

    private final Keys keys = new Keys();
    private final ClientChanges changes = new ClientChanges(Map.of("keyBinding", this.keys));

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
                this.changes.apply(new ChangePayload(1, List.of(new ChangePayload.Edit("gameRule", "doDaylightCycle", "true", "false")))));

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

    private void assertRefused(String reason, ChangeResultPayload result) {
        assertEquals(ChangeResultPayload.refused(1, reason), result);
    }

    private ChangeResultPayload apply(ChangePayload.Edit... edits) {
        return this.changes.apply(new ChangePayload(1, List.of(edits)));
    }

    private static ChangePayload.Edit edit(String target, String expected, String value) {
        return new ChangePayload.Edit("keyBinding", target, expected, value);
    }
}
