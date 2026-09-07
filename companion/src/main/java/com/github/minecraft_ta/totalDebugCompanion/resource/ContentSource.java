package com.github.minecraft_ta.totalDebugCompanion.resource;

import java.io.IOException;

/** A local file or archive entry that can be opened in a resource tab. */
public interface ContentSource {

    String identity();

    String displayName();

    String tooltip();

    byte[] read(int maximumBytes) throws IOException;
}
