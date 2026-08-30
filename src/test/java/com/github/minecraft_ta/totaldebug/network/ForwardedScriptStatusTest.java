package com.github.minecraft_ta.totaldebug.network;

import com.github.minecraft_ta.totaldebug.TotalDebug;
import com.github.minecraft_ta.totaldebug.script.ScriptStatus;
import com.github.minecraft_ta.totaldebug.script.ScriptStatusType;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ForwardedScriptStatusTest {
    @Test
    void roundTripsTheWhitelistedStatusFormat() {
        ForwardedScriptStatus original = new ForwardedScriptStatus(
                7,
                new ScriptStatus(
                        ScriptStatusType.RUN_COMPLETED,
                        "server output",
                        "{\"answer\":42}",
                        ""
                )
        );

        assertEquals(original, ForwardedScriptStatus.fromPayload(original.toPayload()));
    }

    @Test
    void rejectsAnotherForwardedMessageId() {
        ForwardedCompanionPayload payload = new ForwardedCompanionPayload(
                ResourceLocation.fromNamespaceAndPath(TotalDebug.MOD_ID, "something_else"),
                new byte[0]
        );

        assertThrows(IllegalArgumentException.class, () -> ForwardedScriptStatus.fromPayload(payload));
    }

    @Test
    void rejectsTrailingBytes() {
        ForwardedCompanionPayload valid = new ForwardedScriptStatus(
                7,
                ScriptStatus.progress(ScriptStatusType.COMPILATION_COMPLETED)
        ).toPayload();
        byte[] body = Arrays.copyOf(valid.body(), valid.body().length + 1);

        assertThrows(
                IllegalArgumentException.class,
                () -> ForwardedScriptStatus.fromPayload(new ForwardedCompanionPayload(valid.messageId(), body))
        );
    }

    @Test
    void boundsLargeServerOutputBeforeCreatingTheMinecraftPayload() {
        ForwardedScriptStatus decoded = ForwardedScriptStatus.fromPayload(new ForwardedScriptStatus(
                7,
                ScriptStatus.completed(
                        "x".repeat(ForwardedScriptStatus.MAX_MESSAGE_CHARACTERS + 1),
                        null
                )
        ).toPayload());

        assertEquals(ForwardedScriptStatus.MAX_MESSAGE_CHARACTERS, decoded.status().output().length());
        assertTrue(decoded.status().output().endsWith("[TotalDebug truncated the server script output]"));
    }
}
