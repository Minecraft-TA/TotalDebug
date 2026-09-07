package com.github.minecraft_ta.totaldebug.protocol.scnet.companion;

import com.github.minecraft_ta.totaldebug.protocol.message.RunScriptPayload;
import com.github.minecraft_ta.totaldebug.protocol.execution.ScriptExecutionEnvironment;
import com.github.tth05.scnet.message.AbstractMessageOutgoing;
import com.github.tth05.scnet.util.ByteBufferOutputStream;

public final class RunScriptMessage extends AbstractMessageOutgoing {
    private final RunScriptPayload payload;
    public RunScriptMessage(int scriptId, String scriptText, boolean serverSide, ScriptExecutionEnvironment executionEnvironment) {
        this.payload = new RunScriptPayload(scriptId, scriptText, serverSide, executionEnvironment.name());
    }
    @Override public void write(ByteBufferOutputStream output) { this.payload.write(output); }
    public int scriptId() { return this.payload.scriptId(); }
    public String scriptText() { return this.payload.scriptText(); }
    public boolean serverSide() { return this.payload.serverSide(); }
    public String executionEnvironment() { return this.payload.executionEnvironment(); }
}
