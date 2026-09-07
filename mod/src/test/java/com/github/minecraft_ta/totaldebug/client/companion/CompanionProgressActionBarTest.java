package com.github.minecraft_ta.totaldebug.client.companion;

import net.minecraft.network.chat.contents.TranslatableContents;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CompanionProgressActionBarTest {
    @Test
    void downloadUsesTheDeterminateProgressMessageWhenLengthIsKnown() {
        var message = CompanionProgressActionBar.messageFor(
                CompanionStartupProgress.downloading("2.0.0", 25L, 100L)
        );

        var contents = (TranslatableContents) message.getContents();
        assertEquals("companion_app.download_progress", contents.getKey());
        assertEquals(25, contents.getArgs()[1]);
    }

    @Test
    void failureMessageDescribesTheWholeStartupProcess() {
        var message = CompanionProgressActionBar.messageFor(
                CompanionStartupProgress.failed("index failed")
        );

        var contents = (TranslatableContents) message.getContents();
        assertEquals("companion_app.startup_fail", contents.getKey());
    }
}
