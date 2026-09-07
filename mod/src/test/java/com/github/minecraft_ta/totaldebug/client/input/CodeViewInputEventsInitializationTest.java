package com.github.minecraft_ta.totaldebug.client.input;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class CodeViewInputEventsInitializationTest {
    @Test
    void ignoresInputEventsBeforeTheClientRuntimeIsInitialized() {
        assertDoesNotThrow(() -> CodeViewInputEvents.onClientTick(null));
        assertDoesNotThrow(() -> CodeViewInputEvents.onScreenKeyPressed(null));
        assertDoesNotThrow(() -> CodeViewInputEvents.onScreenKeyReleased(null));
    }
}
