package com.github.minecraft_ta.totaldebug.client;

import com.github.minecraft_ta.totaldebug.client.input.Selection;
import com.github.minecraft_ta.totaldebug.protocol.inspection.SubjectIdentity;
import com.github.minecraft_ta.totaldebug.protocol.inspection.SubjectRef;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CodeViewOperationTest {
    @Test
    void inspectsTheResolvedWorldSubjectWithoutFocusingCompanion() {
        RecordingActions actions = new RecordingActions();
        Selection subject = new Selection(
                new SubjectRef.Block("minecraft:overworld", 1, 64, 2),
                new SubjectIdentity(SubjectIdentity.Kind.BLOCK, "minecraft:furnace", "Furnace", "Minecraft",
                        List.of(), "minecraft:furnace"),
                Optional.empty());

        new CodeViewOperation(actions).inspectOrFocus(Optional.of(subject));

        assertEquals(List.of("inspect block minecraft:overworld 1 64 2"), actions.events);
    }

    @Test
    void focusesCompanionWhenThereIsNoTarget() {
        RecordingActions actions = new RecordingActions();

        new CodeViewOperation(actions).inspectOrFocus(Optional.empty());

        assertEquals(List.of("focus Companion"), actions.events);
    }

    private static final class RecordingActions implements CodeViewOperation.Actions {
        private final List<String> events = new ArrayList<>();

        @Override
        public void inspect(Selection subject) {
            this.events.add("inspect " + subject.subject().format());
        }

        @Override
        public void focusCompanion() {
            this.events.add("focus Companion");
        }
    }
}
