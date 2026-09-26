package com.github.minecraft_ta.totaldebug.client.companion;

import com.github.minecraft_ta.totaldebug.TotalDebug;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.RejectedExecutionException;

/**
 * Hands the foreground to Companion along with a request. When Windows refuses to let the game hand it over, the
 * request is still sent and the game keeps its input: Companion shows the request without coming to the front.
 */
final class CompanionForegroundHandoff {
    private final CompanionForegroundPermission permission;

    CompanionForegroundHandoff(CompanionForegroundPermission permission) {
        this.permission = Objects.requireNonNull(permission, "permission");
    }

    void transfer(
            long companionProcessId,
            Runnable beforeTransfer,
            Runnable sendRequest
    ) throws IOException {
        Objects.requireNonNull(beforeTransfer, "beforeTransfer");
        Objects.requireNonNull(sendRequest, "sendRequest");
        boolean granted;
        try {
            this.permission.grantTo(companionProcessId);
            granted = true;
        } catch (IOException refused) {
            TotalDebug.LOGGER.info("Companion stays in the background: {}", refused.getMessage());
            granted = false;
        }
        if (granted) beforeTransfer.run();
        try {
            sendRequest.run();
        } catch (RejectedExecutionException rejected) {
            throw new IOException("Companion connection could not accept the request: " + rejected.getMessage(), rejected);
        }
    }
}
