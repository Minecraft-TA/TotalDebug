package com.github.minecraft_ta.totaldebug.protocol.message;

import com.github.tth05.scnet.util.ByteBufferInputStream;
import com.github.tth05.scnet.util.ByteBufferOutputStream;

/** Protocol-11 payload. Field order and encoding are shared by both endpoints. */
public record ClientHelloPayload(int protocolVersion, String token, String profileId, String dataDirectory, String workspaceDirectory) {
    public static ClientHelloPayload read(ByteBufferInputStream input) { return new ClientHelloPayload(input.readInt(), input.readString(), input.readString(), input.readString(), input.readString()); }
    public void write(ByteBufferOutputStream output) {
        output.writeInt(this.protocolVersion);
        output.writeString(this.token);
        output.writeString(this.profileId);
        output.writeString(this.dataDirectory);
        output.writeString(this.workspaceDirectory);
    }
}
