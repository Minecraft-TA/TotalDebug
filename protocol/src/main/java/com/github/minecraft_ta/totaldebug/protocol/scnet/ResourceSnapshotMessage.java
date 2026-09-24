package com.github.minecraft_ta.totaldebug.protocol.scnet;

import com.github.tth05.scnet.message.AbstractMessage;
import com.github.tth05.scnet.util.ByteBufferInputStream;
import com.github.tth05.scnet.util.ByteBufferOutputStream;
import java.util.Objects;

/**
 * Names an immutable local archive of the client's winning models, textures and atlases. Resources in
 * {@code layers/<n>/assets/...} apply in layer order. Companion reads it from the shared filesystem.
 */
public final class ResourceSnapshotMessage extends AbstractMessage {
    public static final int MAX_LAYERS = 1_024;

    private String archive;
    private int layers;

    public ResourceSnapshotMessage() {
    }

    public ResourceSnapshotMessage(String archive, int layers) {
        this.archive = Objects.requireNonNull(archive, "archive");
        this.layers = layers;
        validate();
    }

    @Override
    public void read(ByteBufferInputStream input) {
        this.archive = input.readString();
        this.layers = input.readInt();
        validate();
    }

    @Override
    public void write(ByteBufferOutputStream output) {
        output.writeString(this.archive);
        output.writeInt(this.layers);
    }

    public String archive() {
        return this.archive;
    }

    public int layers() {
        return this.layers;
    }

    private void validate() {
        if (this.archive.isBlank() || this.layers < 1 || this.layers > MAX_LAYERS) {
            throw new IllegalArgumentException("Invalid resource snapshot");
        }
    }
}
