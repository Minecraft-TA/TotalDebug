package com.github.minecraft_ta.totaldebug.client.companion.message;

import com.github.tth05.scnet.message.AbstractMessageIncoming;
import com.github.tth05.scnet.util.ByteBufferInputStream;
import com.github.tth05.scnet.util.ByteBufferOutputStream;

public final class RunScriptMessage extends AbstractMessageIncoming {
    private int scriptId;
    private String scriptText;
    private boolean serverSide;
    private String executionEnvironment;

    public RunScriptMessage() {
    }

    @Override
    public void read(ByteBufferInputStream messageStream) {
        this.scriptId = messageStream.readInt();
        this.scriptText = messageStream.readString();
        this.serverSide = messageStream.readBoolean();
        this.executionEnvironment = messageStream.readString();
    }

    @Override
    public void write(ByteBufferOutputStream messageStream) {
        messageStream.writeInt(this.scriptId);
        messageStream.writeString(this.scriptText);
        messageStream.writeBoolean(this.serverSide);
        messageStream.writeString(this.executionEnvironment);
    }

    public int scriptId() {
        return this.scriptId;
    }

    public String scriptText() {
        return this.scriptText;
    }

    public boolean serverSide() {
        return this.serverSide;
    }

    public String executionEnvironment() {
        return this.executionEnvironment;
    }
}
