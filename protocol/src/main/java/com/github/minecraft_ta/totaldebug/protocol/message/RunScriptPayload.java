package com.github.minecraft_ta.totaldebug.protocol.message;

import com.github.minecraft_ta.totaldebug.protocol.Side;
import com.github.minecraft_ta.totaldebug.protocol.execution.ScriptBytecode;
import com.github.minecraft_ta.totaldebug.protocol.inspection.SubjectRef;
import com.github.tth05.scnet.util.ByteBufferInputStream;
import com.github.tth05.scnet.util.ByteBufferOutputStream;
import java.util.Objects;

/**
 * Field order and encoding are shared by both endpoints. {@code subject} is an optional
 * {@link SubjectRef} text bound to the run's target. {@code world} is the world the run is meant for, as
 * {@link PlayingPayload#identity()} names it: every server run and every run with a target has one, and the game refuses
 * the run once it plays another.
 * {@code subjectExpectedId}, when not empty, is the registry id the target must still have; a run against a subject
 * that changed since then fails instead of running.
 */
public record RunScriptPayload(int scriptId, ScriptBytecode bytecode, String inventoryId, Side side, String executionEnvironment, String subject, String world, String subjectExpectedId) {
    public RunScriptPayload {
        Objects.requireNonNull(side, "side");
        subject = Objects.requireNonNullElse(subject, "");
        world = Objects.requireNonNullElse(world, "");
        subjectExpectedId = Objects.requireNonNullElse(subjectExpectedId, "");
        if (!subject.isEmpty() && world.isEmpty()) {
            throw new IllegalArgumentException("A script subject requires its world");
        }
        if (subject.isEmpty() && !subjectExpectedId.isEmpty()) {
            throw new IllegalArgumentException("An expected subject id requires a subject");
        }
    }

    public static RunScriptPayload read(ByteBufferInputStream input) {
        return new RunScriptPayload(input.readInt(), ScriptBytecode.read(input), input.readString(), input.readBoolean() ? Side.SERVER : Side.CLIENT, input.readString(), input.readString(), input.readString(), input.readString());
    }
    public void write(ByteBufferOutputStream output) {
        output.writeInt(this.scriptId);
        this.bytecode.write(output);
        output.writeString(this.inventoryId);
        output.writeBoolean(this.side == Side.SERVER);
        output.writeString(this.executionEnvironment);
        output.writeString(this.subject);
        output.writeString(this.world);
        output.writeString(this.subjectExpectedId);
    }
}
