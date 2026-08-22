package com.github.minecraft_ta.totaldebug.network;

import com.github.minecraft_ta.totaldebug.TotalDebug;
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
                ScriptStatusType.RUN_COMPLETED,
                "server output"
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
                ScriptStatusType.COMPILATION_COMPLETED,
                ""
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
                ScriptStatusType.RUN_COMPLETED,
                "x".repeat(ForwardedScriptStatus.MAX_MESSAGE_CHARACTERS + 1)
        ).toPayload());

        assertEquals(ForwardedScriptStatus.MAX_MESSAGE_CHARACTERS, decoded.message().length());
        assertTrue(decoded.message().endsWith("[TotalDebug truncated the server script output]"));
    }
}
