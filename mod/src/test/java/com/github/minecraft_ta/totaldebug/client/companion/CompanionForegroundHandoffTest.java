package com.github.minecraft_ta.totaldebug.client.companion;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CompanionForegroundHandoffTest {
    @Test
    void queueRejectionUsesTheExistingIoFailureContract() {
        CompanionForegroundHandoff handoff = new CompanionForegroundHandoff(processId -> { });
        var rejected = new java.util.concurrent.RejectedExecutionException("receiver is not keeping up");
        IOException failure = assertThrows(IOException.class,
                () -> handoff.transfer(42L, () -> { }, () -> { throw rejected; }));
        assertEquals(rejected, failure.getCause());
    }

    @Test
    void grantsPermissionBeforeReleasingInputAndSendingTheRequest() throws Exception {
        List<String> events = new ArrayList<>();
        CompanionForegroundHandoff handoff = new CompanionForegroundHandoff(
                processId -> events.add("grant " + processId)
        );

        handoff.transfer(42L, () -> events.add("release input"), () -> events.add("send request"));

        assertEquals(List.of("grant 42", "release input", "send request"), events);
    }

    @Test
    void aDeniedGrantDoesNotReleaseInputOrSendTheRequest() {
        List<String> events = new ArrayList<>();
        CompanionForegroundHandoff handoff = new CompanionForegroundHandoff(processId -> {
            events.add("grant " + processId);
            throw new IOException("denied");
        });

        IOException failure = assertThrows(
                IOException.class,
                () -> handoff.transfer(
                        42L,
                        () -> events.add("release input"),
                        () -> events.add("send request")
                )
        );

        assertEquals("denied", failure.getMessage());
        assertEquals(List.of("grant 42"), events);
    }
}
