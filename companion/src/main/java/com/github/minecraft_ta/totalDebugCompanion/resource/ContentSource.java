package com.github.minecraft_ta.totalDebugCompanion.resource;

import java.io.IOException;
import java.util.Optional;

/** A local file or archive entry that can be opened in a resource tab. */
public interface ContentSource {

    String identity();

    String displayName();

    String tooltip();

    byte[] read(int maximumBytes) throws IOException;

    /** Reads the file named like this one plus {@code suffix}, such as a texture's {@code .mcmeta}; empty when absent. */
    Optional<byte[]> readAdjacent(String suffix, int maximumBytes) throws IOException;
}
