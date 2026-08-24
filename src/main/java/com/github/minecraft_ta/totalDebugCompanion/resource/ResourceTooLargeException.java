package com.github.minecraft_ta.totalDebugCompanion.resource;

import java.io.IOException;

public final class ResourceTooLargeException extends IOException {

    public ResourceTooLargeException(String displayName, int maximumBytes) {
        super(displayName + " is larger than the " + formatBytes(maximumBytes) + " viewer limit");
    }

    private static String formatBytes(long bytes) {
        if (bytes >= 1024 * 1024) {
            return (bytes / (1024 * 1024)) + " MiB";
        }
        return (bytes / 1024) + " KiB";
    }
}
