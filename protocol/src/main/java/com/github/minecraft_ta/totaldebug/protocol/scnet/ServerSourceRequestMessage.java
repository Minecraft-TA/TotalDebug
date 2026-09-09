package com.github.minecraft_ta.totaldebug.protocol.scnet;

import com.github.tth05.scnet.message.AbstractMessage;
import com.github.tth05.scnet.util.ByteBufferInputStream;
import com.github.tth05.scnet.util.ByteBufferOutputStream;

/** Requests one source by its position in the current ordered server baseline. */
public final class ServerSourceRequestMessage extends AbstractMessage {
    private String sessionId;
    private String requestId;
    private int source;

    public ServerSourceRequestMessage() {}

    public ServerSourceRequestMessage(String sessionId, String requestId, int source) {
        if (sessionId.isBlank() || sessionId.length() > 64 || requestId.isBlank() || requestId.length() > 64
                || source < 0 || source >= 4096) throw new IllegalArgumentException("Invalid server source request");
        this.sessionId = sessionId;
        this.requestId = requestId;
        this.source = source;
    }

    @Override
    public void read(ByteBufferInputStream input) {
        var checked = new ServerSourceRequestMessage(input.readString(), input.readString(), input.readInt());
        this.sessionId = checked.sessionId;
        this.requestId = checked.requestId;
        this.source = checked.source;
    }

    @Override
    public void write(ByteBufferOutputStream output) {
        output.writeString(sessionId);
        output.writeString(requestId);
        output.writeInt(source);
    }

    public String sessionId() { return sessionId; }
    public String requestId() { return requestId; }
    public int source() { return source; }
}
