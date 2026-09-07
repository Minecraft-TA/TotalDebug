package com.github.minecraft_ta.totaldebug.network;

import com.github.minecraft_ta.totaldebug.TotalDebug;
import com.github.minecraft_ta.totaldebug.script.ExecutionResult;
import com.github.minecraft_ta.totaldebug.script.ExecutionStatus;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ForwardedExecutionResultTest {
    @Test
    void roundTripsTheCanonicalResult() {
        ForwardedExecutionResult original = new ForwardedExecutionResult(
                -1,
                ExecutionResult.completed("server output", null)
        );
        ForwardedExecutionResultAssembler assembler = new ForwardedExecutionResultAssembler();

        ForwardedExecutionResult decoded = original.toPayloads().stream()
                .map(ForwardedExecutionResult::decodeChunk)
                .map(assembler::accept)
                .flatMap(java.util.Optional::stream)
                .findFirst()
                .orElseThrow();

        assertEquals(original, decoded);
    }

    @Test
    void rejectsAnotherForwardedMessageId() {
        ForwardedCompanionPayload payload = new ForwardedCompanionPayload(
                ResourceLocation.fromNamespaceAndPath(TotalDebug.MOD_ID, "something_else"),
                new byte[0]
        );

        assertThrows(IllegalArgumentException.class, () -> ForwardedExecutionResult.decodeChunk(payload));
    }

    @Test
    void rejectsTrailingBytes() {
        ForwardedCompanionPayload valid = new ForwardedExecutionResult(
                7,
                ExecutionResult.progress(ExecutionStatus.COMPILATION_COMPLETED)
        ).toPayloads().getFirst();
        byte[] body = Arrays.copyOf(valid.body(), valid.body().length + 1);

        assertThrows(
                IllegalArgumentException.class,
                () -> ForwardedExecutionResult.decodeChunk(new ForwardedCompanionPayload(valid.messageId(), body))
        );
    }

    @Test
    void chunksLargeResultsWithoutTheFormerCharacterCutoff() {
        String logs = "x".repeat(1_500_000);
        ForwardedExecutionResult original = new ForwardedExecutionResult(
                7,
                ExecutionResult.completed(logs, null)
        );
        java.util.List<ForwardedCompanionPayload> payloads = original.toPayloads();
        assertTrue(payloads.size() > 1);
        assertTrue(payloads.stream().allMatch(payload -> payload.body().length <= ForwardedCompanionPayload.MAX_BODY_BYTES));

        ForwardedExecutionResultAssembler assembler = new ForwardedExecutionResultAssembler();
        ForwardedExecutionResult decoded = payloads.stream()
                .map(ForwardedExecutionResult::decodeChunk)
                .map(assembler::accept)
                .flatMap(java.util.Optional::stream)
                .findFirst()
                .orElseThrow();

        assertEquals(logs, decoded.result().logs().text());
        assertEquals(logs.length(), decoded.result().logs().totalCharacters());
        assertTrue(!decoded.result().logs().truncated());
    }

    @Test
    void rejectsInvalidUtf8BeforeParsingTheEnvelope() {
        ForwardedExecutionResult.Chunk chunk = new ForwardedExecutionResult.Chunk(
                UUID.randomUUID(),
                7,
                0,
                1,
                1,
                new byte[]{(byte) 0xFF}
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> new ForwardedExecutionResultAssembler().accept(chunk)
        );
    }
}
