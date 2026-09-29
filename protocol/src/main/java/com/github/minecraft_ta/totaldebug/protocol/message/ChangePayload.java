package com.github.minecraft_ta.totaldebug.protocol.message;

import com.github.tth05.scnet.util.ByteBufferInputStream;
import com.github.tth05.scnet.util.ByteBufferOutputStream;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Protocol-39 payload asking the game to change values it keeps, as one change (see {@code docs/CHANGE_PIPELINE.md}).
 * The game checks that every edit's target still has the value the edit expects before it sets any, and answers with a
 * {@link ChangeResultPayload} of the same request id.
 */
public record ChangePayload(int requestId, List<Edit> edits) {
    public static final int MAX_EDITS = 1_024;

    /**
     * One value of a change: {@code category} names the game's handler, such as {@code keyBinding}, {@code target} what
     * it changes in the category's words, {@code expected} the value the edit was made against, or null where it replaces
     * whatever the target holds, and {@code value} the new one, both as the category writes values.
     */
    public record Edit(String category, String target, String expected, String value) {
        public Edit {
            Objects.requireNonNull(category, "category");
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(value, "value");
        }
    }

    public ChangePayload {
        edits = List.copyOf(edits);
        if (edits.isEmpty() || edits.size() > MAX_EDITS) throw new IllegalArgumentException("Invalid edit count: " + edits.size());
    }

    public static ChangePayload read(ByteBufferInputStream input) {
        int requestId = input.readInt();
        int count = input.readInt();
        if (count < 1 || count > MAX_EDITS) throw new IllegalArgumentException("Invalid edit count: " + count);
        List<Edit> edits = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            String category = input.readString();
            String target = input.readString();
            String expected = input.readBoolean() ? input.readString() : null;
            edits.add(new Edit(category, target, expected, input.readString()));
        }
        return new ChangePayload(requestId, edits);
    }

    public void write(ByteBufferOutputStream output) {
        output.writeInt(this.requestId);
        output.writeInt(this.edits.size());
        for (Edit edit : this.edits) {
            output.writeString(edit.category());
            output.writeString(edit.target());
            output.writeBoolean(edit.expected() != null);
            if (edit.expected() != null) output.writeString(edit.expected());
            output.writeString(edit.value());
        }
    }
}
