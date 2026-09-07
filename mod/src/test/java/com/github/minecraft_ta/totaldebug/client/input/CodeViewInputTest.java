package com.github.minecraft_ta.totaldebug.client.input;

import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodeViewInputTest {
    @Test
    void emptyScreenResolverFallsBackToTheContainerSlot() {
        AtomicBoolean containerLookupCalled = new AtomicBoolean();

        CodeViewInput.resolveHoveredItem(
                (screen, mouseX, mouseY) -> Optional.empty(),
                null,
                10,
                20,
                () -> {
                    containerLookupCalled.set(true);
                    return Optional.empty();
                }
        );

        assertTrue(containerLookupCalled.get());
    }

    @Test
    void screenResolverLifecycleRequiresTheSameInstalledInstance() {
        var input = new CodeViewInput(target -> {
        });
        ScreenItemStackResolver installed = (screen, mouseX, mouseY) -> Optional.empty();
        ScreenItemStackResolver other = (screen, mouseX, mouseY) -> Optional.empty();

        input.installScreenItemResolver(installed);

        assertThrows(IllegalStateException.class, () -> input.installScreenItemResolver(other));
        assertThrows(IllegalStateException.class, () -> input.removeScreenItemResolver(other));

        input.removeScreenItemResolver(installed);
    }
}
