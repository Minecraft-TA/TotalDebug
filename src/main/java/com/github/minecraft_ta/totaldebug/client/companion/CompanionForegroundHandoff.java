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
        sendRequest.run();
    }
}
