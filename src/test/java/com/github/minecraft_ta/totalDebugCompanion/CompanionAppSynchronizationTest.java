package com.github.minecraft_ta.totalDebugCompanion;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;

import static org.junit.jupiter.api.Assertions.assertFalse;

class CompanionAppSynchronizationTest {

    @Test
    void debuggerControllerLookupDoesNotUseTheApplicationClassMonitor() throws Exception {
        int modifiers = CompanionApp.class
                .getDeclaredMethod("getDebuggerController")
                .getModifiers();

        assertFalse(
                Modifier.isSynchronized(modifiers),
                "MainWindow initialization must not contend with runtime installation on CompanionApp.class"
        );
    }
}
