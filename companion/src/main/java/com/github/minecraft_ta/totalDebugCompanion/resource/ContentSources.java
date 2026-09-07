package com.github.minecraft_ta.totalDebugCompanion.resource;

import java.io.IOException;
import java.io.InputStream;

final class ContentSources {

    private ContentSources() {
    }

    static byte[] readBounded(InputStream stream, int maximumBytes, String displayName) throws IOException {
        byte[] bytes = stream.readNBytes(maximumBytes + 1);
        if (bytes.length > maximumBytes) {
            throw new ResourceTooLargeException(displayName, maximumBytes);
        }
        return bytes;
    }
}
