package com.github.minecraft_ta.totalDebugCompanion.ui.views;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;

class MainWindowFocusTest {
    @Test
    void becomingVisibleDoesNotAutomaticallyRequestFocus() {
        assertFalse(MainWindow.INSTANCE.isAutoRequestFocus());
    }
}
