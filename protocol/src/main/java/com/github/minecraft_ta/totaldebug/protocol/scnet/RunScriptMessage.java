package com.github.minecraft_ta.totaldebug.protocol.scnet;

import com.github.minecraft_ta.totaldebug.protocol.execution.ScriptBytecode;
import com.github.minecraft_ta.totaldebug.protocol.message.RunScriptPayload;
import com.github.tth05.scnet.message.AbstractMessage;
import com.github.tth05.scnet.util.ByteBufferInputStream;
import com.github.tth05.scnet.util.ByteBufferOutputStream;

public final class RunScriptMessage extends AbstractMessage {
    private RunScriptPayload payload;

    public RunScriptMessage() {
    }

    public RunScriptMessage(int scriptId, ScriptBytecode bytecode, String inventoryId, boolean serverSide, String executionEnvironment, String serverSessionId) {
        this(scriptId, bytecode, inventoryId, serverSide, executionEnvironment, serverSessionId, "", "", "");
    }

    public RunScriptMessage(int scriptId, ScriptBytecode bytecode, String inventoryId, boolean serverSide, String executionEnvironment, String serverSessionId, String subject, String subjectSessionId, String subjectExpectedId) {
        this.payload = new RunScriptPayload(scriptId, bytecode, inventoryId, serverSide, executionEnvironment, serverSessionId, subject, subjectSessionId, subjectExpectedId);
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

    /** The target's subject text, or empty when the run has no target. */
    public String subject() {
        return this.payload.subject();
    }

    public String subjectSessionId() {
        return this.payload.subjectSessionId();
    }

    /** The registry id the target must still have, or empty to accept whatever occupies the subject. */
    public String subjectExpectedId() {
        return this.payload.subjectExpectedId();
    }
}
