package com.github.minecraft_ta.totaldebug.protocol.scnet.mod;

import com.github.minecraft_ta.totaldebug.protocol.message.RunScriptPayload;
import com.github.tth05.scnet.message.AbstractMessageIncoming;
import com.github.tth05.scnet.util.ByteBufferInputStream;
import com.github.tth05.scnet.util.ByteBufferOutputStream;

public final class RunScriptMessage extends AbstractMessageIncoming {
    private RunScriptPayload payload;
    public RunScriptMessage() { }
    public RunScriptMessage(int scriptId, String scriptText, boolean serverSide, String executionEnvironment) {
        this.payload = new RunScriptPayload(scriptId, scriptText, serverSide, executionEnvironment);
    }
    @Override public void read(ByteBufferInputStream input) { this.payload = RunScriptPayload.read(input); }
    @Override public void write(ByteBufferOutputStream output) { this.payload.write(output); }
    public int scriptId() { return this.payload.scriptId(); }
    public String scriptText() { return this.payload.scriptText(); }
    public boolean serverSide() { return this.payload.serverSide(); }
    public String executionEnvironment() { return this.payload.executionEnvironment(); }
}
