package com.github.minecraft_ta.totaldebug.protocol.message;

import com.github.minecraft_ta.totaldebug.protocol.execution.ScriptBytecode;
import com.github.minecraft_ta.totaldebug.protocol.inspection.SubjectRef;
import com.github.tth05.scnet.util.ByteBufferInputStream;
import com.github.tth05.scnet.util.ByteBufferOutputStream;
import java.util.Objects;

/**
 * Field order and encoding are shared by both endpoints. {@code subject} is an optional
 * {@link SubjectRef} text bound to the run's target, valid
 * only while the client is still in {@code subjectSessionId}'s world. Both are empty for a run without a target.
 * {@code subjectExpectedId}, when not empty, is the registry id the target must still have; a run against a subject
 * that changed since then fails instead of running.
 */
public record RunScriptPayload(int scriptId, ScriptBytecode bytecode, String inventoryId, boolean serverSide, String executionEnvironment, String serverSessionId, String subject, String subjectSessionId, String subjectExpectedId) {
    public RunScriptPayload {
        subject = Objects.requireNonNullElse(subject, "");
        subjectSessionId = Objects.requireNonNullElse(subjectSessionId, "");
        subjectExpectedId = Objects.requireNonNullElse(subjectExpectedId, "");
        if (subject.isEmpty() != subjectSessionId.isEmpty()) {
            throw new IllegalArgumentException("A script subject requires its game session and vice versa");
        }
        if (subject.isEmpty() && !subjectExpectedId.isEmpty()) {
            throw new IllegalArgumentException("An expected subject id requires a subject");
        }
    }

    public static RunScriptPayload read(ByteBufferInputStream input) {
        return new RunScriptPayload(input.readInt(), ScriptBytecode.read(input), input.readString(), input.readBoolean(), input.readString(), input.readString(), input.readString(), input.readString(), input.readString());
    }
    public void write(ByteBufferOutputStream output) {
        output.writeInt(this.scriptId);
        this.bytecode.write(output);
        output.writeString(this.inventoryId);
        output.writeBoolean(this.serverSide);
        output.writeString(this.executionEnvironment);
        output.writeString(this.serverSessionId);
        output.writeString(this.subject);
        output.writeString(this.subjectSessionId);
        output.writeString(this.subjectExpectedId);
    }
}
