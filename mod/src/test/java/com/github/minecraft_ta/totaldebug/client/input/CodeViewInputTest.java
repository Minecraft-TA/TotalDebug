package com.github.minecraft_ta.totaldebug.client.input;

import com.github.minecraft_ta.totaldebug.client.inspection.KeptStacks;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CodeViewInputTest {
    @Test
    void theSlotUnderTheMouseComesBeforeTheRecipeViewer() {
        Optional<String> resolved = CodeViewInput.slotFirst(() -> Optional.of("slot"), () -> {
            throw new AssertionError("a recipe viewer must not claim a slot that holds a stack");
        });

        assertEquals(Optional.of("slot"), resolved);
    }

    @Test
    void withoutASlotTheRecipeViewerIsAsked() {
        assertEquals(Optional.of("viewer"), CodeViewInput.slotFirst(Optional::empty, () -> Optional.of("viewer")));
    }

    @Test
    void screenResolverLifecycleRequiresTheSameInstalledInstance() {
        var input = new CodeViewInput(subject -> {
        }, new KeptStacks());
        ScreenItemStackResolver installed = (screen, mouseX, mouseY) -> Optional.empty();
        ScreenItemStackResolver other = (screen, mouseX, mouseY) -> Optional.empty();

        input.installScreenItemResolver(installed);

        assertThrows(IllegalStateException.class, () -> input.installScreenItemResolver(other));
        assertThrows(IllegalStateException.class, () -> input.removeScreenItemResolver(other));

        input.removeScreenItemResolver(installed);
    }
}
