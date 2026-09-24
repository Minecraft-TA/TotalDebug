package com.github.minecraft_ta.totaldebug.client;

import com.github.minecraft_ta.totaldebug.client.input.WorldSubject;
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
        WorldSubject subject = new WorldSubject(
                new SubjectRef.Block("minecraft:overworld", 1, 64, 2), "Furnace", "minecraft:furnace", "Minecraft",
                List.of());

        new CodeViewOperation(actions).inspectOrFocus(Optional.of(subject));

        assertEquals(List.of("inspect block minecraft:overworld 1 64 2"), actions.events);
    }

    @Test
    void opensTheResolvedClassWithoutFocusingCompanion() {
        RecordingActions actions = new RecordingActions();

        new CodeViewOperation(actions).openOrFocus(Optional.of(String.class));

        assertEquals(List.of("open java.lang.String"), actions.events);
    }

    @Test
    void focusesCompanionWhenThereIsNoTarget() {
        RecordingActions actions = new RecordingActions();

        new CodeViewOperation(actions).inspectOrFocus(Optional.empty());
        new CodeViewOperation(actions).openOrFocus(Optional.empty());

        assertEquals(List.of("focus Companion", "focus Companion"), actions.events);
    }

    private static final class RecordingActions implements CodeViewOperation.Actions {
        private final List<String> events = new ArrayList<>();

        @Override
        public void inspect(WorldSubject subject) {
            this.events.add("inspect " + subject.subject().format());
        }

        @Override
        public void openClass(Class<?> targetClass) {
            this.events.add("open " + targetClass.getName());
        }

        @Override
        public void focusCompanion() {
            this.events.add("focus Companion");
        }
    }
}
