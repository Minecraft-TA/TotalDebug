package com.github.minecraft_ta.totaldebug.protocol.scnet;

import com.github.minecraft_ta.totaldebug.protocol.execution.ScriptExecutionEnvironment;
import com.github.minecraft_ta.totaldebug.protocol.execution.ScriptBytecode;
import com.github.minecraft_ta.totaldebug.protocol.message.RunScriptPayload;
import com.github.tth05.scnet.message.AbstractMessage;
import com.github.tth05.scnet.util.ByteBufferInputStream;
import com.github.tth05.scnet.util.ByteBufferOutputStream;

public final class RunScriptMessage extends AbstractMessage {
    private RunScriptPayload payload;

    public RunScriptMessage() {
    }

    public RunScriptMessage(int scriptId, ScriptBytecode bytecode, String inventoryId, boolean serverSide, ScriptExecutionEnvironment executionEnvironment) {
        this(scriptId, bytecode, inventoryId, serverSide, executionEnvironment.name());
    }

    public RunScriptMessage(int scriptId, ScriptBytecode bytecode, String inventoryId, boolean serverSide, String executionEnvironment) {
        this(scriptId, bytecode, inventoryId, serverSide, executionEnvironment, "");
    }

    public RunScriptMessage(int scriptId, ScriptBytecode bytecode, String inventoryId, boolean serverSide, String executionEnvironment, String serverSessionId) {
        this.payload = new RunScriptPayload(scriptId, bytecode, inventoryId, serverSide, executionEnvironment, serverSessionId);
    }

    @Override
    public void read(ByteBufferInputStream input) {
        this.payload = RunScriptPayload.read(input);
    }

    @Override
    public void write(ByteBufferOutputStream output) {
        this.payload.write(output);
    }

    public String serverSessionId() { return this.payload.serverSessionId(); }

    public int scriptId() {
        return this.payload.scriptId();
    }

    public ScriptBytecode bytecode() {
        return this.payload.bytecode();
    }

    public String inventoryId() {
        return this.payload.inventoryId();
    }

    public boolean serverSide() {
        return this.payload.serverSide();
    }

    public String executionEnvironment() {
        return this.payload.executionEnvironment();
    }
}
