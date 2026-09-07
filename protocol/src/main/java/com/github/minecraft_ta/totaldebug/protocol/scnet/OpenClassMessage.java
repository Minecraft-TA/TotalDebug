package com.github.minecraft_ta.totaldebug.protocol.scnet;

import com.github.minecraft_ta.totaldebug.protocol.message.OpenClassPayload;
import com.github.tth05.scnet.message.AbstractMessage;
import com.github.tth05.scnet.util.ByteBufferInputStream;
import com.github.tth05.scnet.util.ByteBufferOutputStream;
import java.util.Objects;

public final class OpenClassMessage extends AbstractMessage {
    private OpenClassPayload payload;
    public OpenClassMessage() { }
    public OpenClassMessage(String binaryName, int targetType, String targetIdentifier) {
        Objects.requireNonNull(binaryName, "binaryName");
        targetIdentifier = Objects.requireNonNullElse(targetIdentifier, "");
        this.payload = new OpenClassPayload(binaryName, targetType, targetIdentifier);
    }
    @Override public void read(ByteBufferInputStream input) { this.payload = OpenClassPayload.read(input); }
    @Override public void write(ByteBufferOutputStream output) { this.payload.write(output); }
    public String binaryName() { return this.payload.binaryName(); }
    public int targetType() { return this.payload.targetType(); }
    public String targetIdentifier() { return this.payload.targetIdentifier(); }
}
