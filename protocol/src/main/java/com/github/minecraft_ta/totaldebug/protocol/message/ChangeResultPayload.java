package com.github.minecraft_ta.totaldebug.protocol.message;

import com.github.tth05.scnet.util.ByteBufferInputStream;
import com.github.tth05.scnet.util.ByteBufferOutputStream;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Protocol-39 payload answering a {@link ChangePayload}: for each edit, in its order, the value before and the value now
 * in effect; or, with no values, why the game changed nothing in {@code error}, which is empty on success. With both, the
 * game made the change, but what makes it take effect failed, such as the reload after a pack selection; the values are
 * then the ones the game holds after that.
 */
public record ChangeResultPayload(int requestId, List<Applied> applied, String error) {
    /** An edit the game made: the target's value before it and the value it has now. */
    public record Applied(String before, String now) {
        public Applied {
            Objects.requireNonNull(before, "before");
            Objects.requireNonNull(now, "now");
        }
    }

    public ChangeResultPayload {
        applied = List.copyOf(applied);
        Objects.requireNonNull(error, "error");
        if (applied.size() > ChangePayload.MAX_EDITS) throw new IllegalArgumentException("Invalid edit count: " + applied.size());
        if (error.isEmpty() && applied.isEmpty()) throw new IllegalArgumentException("A result holds the values, an error, or both");
    }

    public static ChangeResultPayload refused(int requestId, String error) {
        return new ChangeResultPayload(requestId, List.of(), error);
    }

    public static ChangeResultPayload read(ByteBufferInputStream input) {
        int requestId = input.readInt();
        int count = input.readInt();
        if (count < 0 || count > ChangePayload.MAX_EDITS) throw new IllegalArgumentException("Invalid edit count: " + count);
        List<Applied> applied = new ArrayList<>(count);
        for (int index = 0; index < count; index++) applied.add(new Applied(input.readString(), input.readString()));
        return new ChangeResultPayload(requestId, applied, input.readString());
    }

    public void write(ByteBufferOutputStream output) {
        output.writeInt(this.requestId);
        output.writeInt(this.applied.size());
        for (Applied edit : this.applied) {
            output.writeString(edit.before());
            output.writeString(edit.now());
        }
        output.writeString(this.error);
    }
}
