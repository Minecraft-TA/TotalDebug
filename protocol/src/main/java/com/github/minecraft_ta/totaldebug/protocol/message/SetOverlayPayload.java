package com.github.minecraft_ta.totaldebug.protocol.message;

import com.github.tth05.scnet.util.ByteBufferInputStream;
import com.github.tth05.scnet.util.ByteBufferOutputStream;

import java.util.Arrays;
import java.util.Objects;

/**
 * Protocol-24 payload putting one resource into the game's in-memory pack, or removing it when {@code content} is
 * null. {@code path} is the resource's path in a pack, such as {@code assets/ns/lang/en_us.json}. The game uses it
 * after the next reload, which Companion asks for separately.
 */
public record SetOverlayPayload(String path, byte[] content) {
    public static final int MAX_CONTENT_BYTES = 8 * 1024 * 1024;

    public SetOverlayPayload {
        Objects.requireNonNull(path, "path");
        if (!(path.startsWith("assets/") || path.startsWith("data/")) || path.contains("..") || path.contains("\\")) {
            throw new IllegalArgumentException("Not a resource path: " + path);
        }
        if (content != null && content.length > MAX_CONTENT_BYTES) {
            throw new IllegalArgumentException(path + " has " + content.length + " bytes; the limit is " + MAX_CONTENT_BYTES);
        }
    }

    public static SetOverlayPayload read(ByteBufferInputStream input) {
        String path = input.readString();
        int length = input.readInt();
        if (length < -1 || length > MAX_CONTENT_BYTES) throw new IllegalArgumentException("Invalid content length: " + length);
        return new SetOverlayPayload(path, length < 0 ? null : input.readByteArray(length));
    }

    public void write(ByteBufferOutputStream output) {
        output.writeString(this.path);
        if (this.content == null) {
            output.writeInt(-1);
        } else {
            output.writeInt(this.content.length);
            output.writeByteArray(this.content);
        }
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof SetOverlayPayload payload && payload.path.equals(this.path)
                && Arrays.equals(payload.content, this.content);
    }

    @Override
    public int hashCode() {
        return 31 * this.path.hashCode() + Arrays.hashCode(this.content);
    }

    @Override
    public String toString() {
        return "SetOverlayPayload[" + this.path + ", " + (this.content == null ? "removed" : this.content.length + " bytes") + "]";
    }
}
