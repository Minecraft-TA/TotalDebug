package com.github.minecraft_ta.totaldebug.client;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OpenCodeOperationTest {
    @Test
    void opensTheResolvedClassWithoutFocusingCompanion() {
        RecordingActions actions = new RecordingActions();

        new OpenCodeOperation(actions).openOrFocus(Optional.of(String.class));

        assertEquals(List.of(String.class), actions.openedClasses);
        assertEquals(0, actions.focusCount);
    }

    @Test
    void focusesCompanionWhenThereIsNoTarget() {
        RecordingActions actions = new RecordingActions();

        new OpenCodeOperation(actions).openOrFocus(Optional.empty());

        assertEquals(List.of(), actions.openedClasses);
        assertEquals(List.of("focus Companion"), actions.focusEvents);
        assertEquals(1, actions.focusCount);
    }

    private static final class RecordingActions implements OpenCodeOperation.Actions {
        private final List<Class<?>> openedClasses = new ArrayList<>();
        private final List<String> focusEvents = new ArrayList<>();
        private int focusCount;

        @Override
        public void openClass(Class<?> targetClass) {
            this.openedClasses.add(targetClass);
        }

        @Override
        public void focusCompanion() {
            this.focusEvents.add("focus Companion");
            this.focusCount++;
        }
    }
}
