package com.github.minecraft_ta.totaldebug.client.companion;

import java.io.IOException;
import java.util.Objects;

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
        this.permission.grantTo(companionProcessId);
        beforeTransfer.run();
        try {
            sendRequest.run();
        } catch (java.util.concurrent.RejectedExecutionException rejected) {
            throw new IOException("Companion connection could not accept the request: " + rejected.getMessage(), rejected);
        }
    }
}
